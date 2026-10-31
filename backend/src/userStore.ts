import os from 'node:os';
import mongoose, { Schema, Types, model } from 'mongoose';
import { initGroupStores } from './groupStore';
import { CURRENCIES, type Currency } from './currencies';
import { initSplitSessionStore } from './splitSessionStore';
import { RegistrationVerificationModel } from './registrationVerification';

export type UserRecord = {
  id: string;
  email: string;
  displayName: string;
  passwordHash?: string;
  googleId?: string;
  phoneNumber?: string;
  timeZone?: string;
  defaultCurrency?: Currency;
  avatarDataUrl?: string;
  sessionVersion?: number;
  retainedSessionHash?: string;
  createdAt: Date;
};
export type NewUser = Omit<UserRecord, 'id' | 'createdAt'>;
export type AccountUpdate = Partial<Pick<UserRecord, 'email' | 'displayName' | 'passwordHash' | 'phoneNumber' | 'timeZone' | 'defaultCurrency' | 'avatarDataUrl'>>;
export type SessionRecord = { userId: string; expiresAt: Date; sessionVersion?: number };

/** Thrown when an email or Google account is already registered. */
export class DuplicateAccountError extends Error {
  constructor() { super('Account already exists'); }
}

export interface UserStore {
  findById(id: string): Promise<UserRecord | null>;
  findByEmail(email: string): Promise<UserRecord | null>;
  findByGoogleId(googleId: string): Promise<UserRecord | null>;
  create(user: NewUser): Promise<UserRecord>;
  // Credential changes advance the session version atomically, keeping only this token valid.
  update(id: string, changes: AccountUpdate, retainedSessionHash?: string): Promise<UserRecord | null>;
}

/** Sessions are looked up by a SHA-256 hash; raw tokens are never stored. */
export interface SessionStore {
  create(tokenHash: string, session: SessionRecord): Promise<void>;
  find(tokenHash: string): Promise<SessionRecord | null>;
  delete(tokenHash: string): Promise<void>;
}

/** Legacy sessions use version zero; a credential change invalidates them on every lookup. */
export function sessionMatchesAccount(session: SessionRecord, user: Pick<UserRecord, 'sessionVersion' | 'retainedSessionHash'> | null, tokenHash: string): boolean {
  return user !== null && ((session.sessionVersion ?? 0) === (user.sessionVersion ?? 0) || user.retainedSessionHash === tokenHash);
}

const userSchema = new Schema({
  email: { type: String, required: true, unique: true, lowercase: true, trim: true, maxlength: 254 },
  displayName: { type: String, required: true, trim: true, maxlength: 60 },
  passwordHash: { type: String },
  phoneNumber: { type: String, maxlength: 32, default: '' },
  timeZone: { type: String, maxlength: 100, default: 'UTC' },
  defaultCurrency: { type: String, enum: CURRENCIES, default: 'CAD' },
  avatarDataUrl: { type: String, maxlength: 180000, default: '' },
  sessionVersion: { type: Number, default: 0 },
  retainedSessionHash: { type: String },
  // Sparse: accounts created with a password have no Google ID.
  googleId: { type: String, unique: true, sparse: true },
}, { timestamps: true });

const sessionSchema = new Schema({
  tokenHash: { type: String, required: true, unique: true },
  userId: { type: Schema.Types.ObjectId, ref: 'User', required: true, index: true },
  // TTL index: MongoDB removes sessions once they expire.
  expiresAt: { type: Date, required: true, expires: 0 },
  sessionVersion: { type: Number, default: 0 },
});

export const UserModel = model('User', userSchema);
export const SessionModel = model('Session', sessionSchema);

type UserDocument = {
  _id: Types.ObjectId;
  email: string;
  displayName: string;
  passwordHash?: string | null;
  googleId?: string | null;
  phoneNumber?: string;
  timeZone?: string;
  defaultCurrency?: Currency;
  avatarDataUrl?: string;
  sessionVersion?: number;
  retainedSessionHash?: string;
  createdAt: Date;
};

function toUser(doc: UserDocument | null): UserRecord | null {
  if (!doc) return null;
  return {
    id: doc._id.toString(),
    email: doc.email,
    displayName: doc.displayName,
    createdAt: doc.createdAt,
    phoneNumber: doc.phoneNumber ?? '',
    timeZone: doc.timeZone ?? 'UTC',
    defaultCurrency: doc.defaultCurrency ?? 'CAD',
    avatarDataUrl: doc.avatarDataUrl ?? '',
    sessionVersion: doc.sessionVersion ?? 0,
    ...(doc.retainedSessionHash ? { retainedSessionHash: doc.retainedSessionHash } : {}),
    ...(doc.passwordHash ? { passwordHash: doc.passwordHash } : {}),
    ...(doc.googleId ? { googleId: doc.googleId } : {}),
  };
}

const DUPLICATE_KEY = 11000;

export const mongoUserStore: UserStore = {
  async update(id, changes, retainedSessionHash) {
    if (!mongoose.isValidObjectId(id)) return null;
    try {
      const update = retainedSessionHash
        ? { $set: { ...changes, retainedSessionHash }, $inc: { sessionVersion: 1 } }
        : { $set: changes };
      return toUser(await UserModel.findByIdAndUpdate(id, update, { returnDocument: 'after', runValidators: true }).lean<UserDocument>());
    } catch (error) {
      if ((error as { code?: unknown })?.code === DUPLICATE_KEY) throw new DuplicateAccountError();
      throw error;
    }
  },
  async findById(id) {
    if (!mongoose.isValidObjectId(id)) return null;
    return toUser(await UserModel.findById(id).lean<UserDocument>());
  },
  async findByEmail(email) {
    return toUser(await UserModel.findOne({ email }).lean<UserDocument>());
  },
  async findByGoogleId(googleId) {
    return toUser(await UserModel.findOne({ googleId }).lean<UserDocument>());
  },
  async create(user) {
    try {
      const created = await UserModel.create(user);
      return toUser(created.toObject<UserDocument>()) as UserRecord;
    } catch (error) {
      if ((error as { code?: unknown })?.code === DUPLICATE_KEY) throw new DuplicateAccountError();
      throw error;
    }
  },
};

export const mongoSessionStore: SessionStore = {
  async create(tokenHash, session) {
    await SessionModel.create({ tokenHash, ...session });
  },
  async find(tokenHash) {
    const doc = await SessionModel.findOne({ tokenHash }).lean<{ userId: Types.ObjectId; expiresAt: Date; sessionVersion?: number }>();
    if (!doc) return null;
    const session = { userId: doc.userId.toString(), expiresAt: doc.expiresAt, sessionVersion: doc.sessionVersion ?? 0 };
    const user = await UserModel.findById(doc.userId).select('sessionVersion retainedSessionHash')
      .lean<Pick<UserRecord, 'sessionVersion' | 'retainedSessionHash'>>();
    if (!user) { await SessionModel.deleteOne({ tokenHash }); return null; }
    return sessionMatchesAccount(session, user, tokenHash) ? session : null;
  },
  async delete(tokenHash) {
    await SessionModel.deleteOne({ tokenHash });
  },
};

export async function connectDatabase(uri: string): Promise<void> {
  // Passing os avoids the driver's dynamic import('os'), which fails inside Jest's VM.
  await mongoose.connect(uri, { serverSelectionTimeoutMS: 10000, runtimeAdapters: { os } });
  // Build the unique indexes before accepting requests so duplicate sign-ups are rejected.
  await Promise.all([UserModel.init(), SessionModel.init(), RegistrationVerificationModel.init()]);
  await Promise.all([initGroupStores(), initSplitSessionStore()]);
}

export async function disconnectDatabase(): Promise<void> {
  await mongoose.disconnect();
}
