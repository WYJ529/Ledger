import { randomBytes, randomInt } from 'node:crypto';
import { model, Schema } from 'mongoose';
import { hashPassword, verifyPassword } from './password';

export const CODE_TTL_MS = 10 * 60 * 1000;
export const RESEND_DELAY_MS = 60 * 1000;
export const MAX_CODE_ATTEMPTS = 5;

export type VerificationRecord = {
  email: string; verificationId: string; codeHash: string;
  expiresAt: Date; resendAt: Date; attempts: number; ready: boolean;
};

export interface RegistrationVerificationStore {
  reserve(record: VerificationRecord, now: Date): Promise<boolean>;
  activate(verificationId: string): Promise<void>;
  invalidate(verificationId: string): Promise<void>;
  takeAttempt(email: string, verificationId: string, now: Date): Promise<VerificationRecord | null>;
  consume(verificationId: string, codeHash: string, now: Date): Promise<boolean>;
}

const schema = new Schema({
  email: { type: String, required: true, unique: true },
  verificationId: { type: String, required: true, unique: true },
  codeHash: { type: String, required: true },
  expiresAt: { type: Date, required: true, expires: 0 },
  resendAt: { type: Date, required: true },
  attempts: { type: Number, required: true },
  ready: { type: Boolean, required: true },
});
export const RegistrationVerificationModel = model('RegistrationVerification', schema);

export const mongoRegistrationVerifications: RegistrationVerificationStore = {
  async reserve(record, now) {
    try {
      // The unique email index makes concurrent requests compete for one slot.
      await RegistrationVerificationModel.findOneAndUpdate(
        { email: record.email, resendAt: { $lte: now } }, { $set: record }, { upsert: true });
      return true;
    } catch (error) {
      if ((error as { code?: unknown })?.code === 11000) return false;
      throw error;
    }
  },
  async activate(verificationId) {
    await RegistrationVerificationModel.updateOne({ verificationId }, { $set: { ready: true } });
  },
  async invalidate(verificationId) {
    // Keep the resend cooldown even when delivery fails.
    await RegistrationVerificationModel.updateOne({ verificationId }, { $set: { ready: false, attempts: MAX_CODE_ATTEMPTS } });
  },
  async takeAttempt(email, verificationId, now) {
    return RegistrationVerificationModel.findOneAndUpdate(
      { email, verificationId, ready: true, expiresAt: { $gt: now }, attempts: { $lt: MAX_CODE_ATTEMPTS } },
      { $inc: { attempts: 1 } }, { returnDocument: 'after' }).lean<VerificationRecord>();
  },
  async consume(verificationId, codeHash, now) {
    const result = await RegistrationVerificationModel.deleteOne({
      verificationId, codeHash, ready: true, expiresAt: { $gt: now }, attempts: { $lte: MAX_CODE_ATTEMPTS },
    });
    return result.deletedCount === 1;
  },
};

export async function createVerification(email: string, now = Date.now()) {
  const code = randomInt(0, 1000000).toString().padStart(6, '0');
  const record: VerificationRecord = {
    email, verificationId: randomBytes(32).toString('hex'), codeHash: await hashPassword(code),
    expiresAt: new Date(now + CODE_TTL_MS), resendAt: new Date(now + RESEND_DELAY_MS), attempts: 0, ready: false,
  };
  return { code, record };
}

export async function consumeVerification(store: RegistrationVerificationStore, email: string, id: string, code: string) {
  const record = await store.takeAttempt(email, id, new Date());
  if (!record || !await verifyPassword(code, record.codeHash)) return false;
  // Only one concurrent verification can consume the challenge, and a resend invalidates it.
  return store.consume(id, record.codeHash, new Date());
}
