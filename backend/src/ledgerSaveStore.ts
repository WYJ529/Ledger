import mongoose, { Schema, model, type Types } from 'mongoose';
import { CURRENCIES, type Currency } from './currencies';

export const LEDGER_SAVE_SLOTS = [1, 2, 3] as const;

export type LedgerSaveRecord = {
  id: string;
  input: { kind: string; amount: string; currency: string; date: string; merchant: string; category: string; notes: string };
  amountMinor: number;
  fx: { baseCurrency: string; rate: string; rateDate: string; source: string; convertedMinor: number };
  source: string;
  sourceFileName: string;
  revision: number;
  createdAt: string;
  updatedAt: string;
};

/** A complete snapshot of one device ledger, like a game save. */
export type LedgerSaveContent = { name?: string; deviceName: string; baseCurrency: Currency; records: LedgerSaveRecord[] };
export type LedgerSaveSummary = { name?: string; slot: number; revision: number; savedAt: Date; deviceName: string; baseCurrency: Currency; recordCount: number };
export type LedgerSave = LedgerSaveSummary & { records: LedgerSaveRecord[] };

/** Thrown when a slot no longer holds the revision the device last listed. */
export class LedgerSaveConflictError extends Error {
  constructor() { super('Save slot changed'); }
}

export interface LedgerSaveStore {
  list(userId: string): Promise<LedgerSaveSummary[]>;
  find(userId: string, slot: number): Promise<LedgerSave | null>;
  /** Writes a slot only if it still holds [expectedRevision]; null means the slot must be empty. */
  put(userId: string, slot: number, content: LedgerSaveContent, expectedRevision: number | null): Promise<LedgerSaveSummary>;
  /** Removes the slot only if it still holds [expectedRevision]; an already empty slot is not an error. */
  delete(userId: string, slot: number, expectedRevision: number): Promise<void>;
  rename?(userId: string, slot: number, name: string, expectedRevision: number): Promise<LedgerSaveSummary>;
}

const ledgerSaveSchema = new Schema({
  userId: { type: Schema.Types.ObjectId, ref: 'User', required: true },
  slot: { type: Number, required: true, min: 1, max: LEDGER_SAVE_SLOTS.length },
  revision: { type: Number, required: true, min: 1 },
  savedAt: { type: Date, required: true },
  name: { type: String, maxlength: 40, default: '' },
  deviceName: { type: String, required: true, maxlength: 60 },
  baseCurrency: { type: String, required: true, enum: CURRENCIES },
  recordCount: { type: Number, required: true, min: 0 },
  // Validated by the router before storage; kept as plain data so a restore returns it unchanged.
  records: { type: [Schema.Types.Mixed], required: true },
});
ledgerSaveSchema.index({ userId: 1, slot: 1 }, { unique: true });

export const LedgerSaveModel = model('LedgerSave', ledgerSaveSchema);

type LedgerSaveDocument = Omit<LedgerSave, 'records'> & { _id: Types.ObjectId; userId: Types.ObjectId; records?: LedgerSaveRecord[] };

const SUMMARY_FIELDS = 'name slot revision savedAt deviceName baseCurrency recordCount';
const DUPLICATE_KEY = 11000;

function summary(doc: LedgerSaveDocument): LedgerSaveSummary {
  return { name: doc.name ?? '', slot: doc.slot, revision: doc.revision, savedAt: doc.savedAt, deviceName: doc.deviceName,
    baseCurrency: doc.baseCurrency, recordCount: doc.recordCount };
}

export const mongoLedgerSaveStore: LedgerSaveStore = {
  async list(userId) {
    if (!mongoose.isValidObjectId(userId)) return [];
    const docs = await LedgerSaveModel.find({ userId }).select(SUMMARY_FIELDS).sort({ slot: 1 }).lean<LedgerSaveDocument[]>();
    return docs.map(summary);
  },
  async find(userId, slot) {
    if (!mongoose.isValidObjectId(userId)) return null;
    const doc = await LedgerSaveModel.findOne({ userId, slot }).lean<LedgerSaveDocument>();
    return doc ? { ...summary(doc), records: doc.records ?? [] } : null;
  },
  async put(userId, slot, content, expectedRevision) {
    const fields = { ...content, name: content.name ?? '', recordCount: content.records.length, savedAt: new Date() };
    if (expectedRevision === null) {
      try {
        const created = await LedgerSaveModel.create({ userId, slot, revision: 1, ...fields });
        return summary(created.toObject<LedgerSaveDocument>());
      } catch (error) {
        if ((error as { code?: unknown })?.code === DUPLICATE_KEY) throw new LedgerSaveConflictError();
        throw error;
      }
    }
    const updated = await LedgerSaveModel.findOneAndUpdate({ userId, slot, revision: expectedRevision },
      { $set: fields, $inc: { revision: 1 } }, { returnDocument: 'after', runValidators: true })
      .select(SUMMARY_FIELDS).lean<LedgerSaveDocument>();
    if (!updated) throw new LedgerSaveConflictError();
    return summary(updated);
  },
  async rename(userId, slot, name, expectedRevision) {
    const updated = await LedgerSaveModel.findOneAndUpdate({ userId, slot, revision: expectedRevision },
      { $set: { name }, $inc: { revision: 1 } }, { returnDocument: 'after', runValidators: true })
      .select(SUMMARY_FIELDS).lean<LedgerSaveDocument>();
    if (!updated) throw new LedgerSaveConflictError();
    return summary(updated);
  },
  async delete(userId, slot, expectedRevision) {
    const result = await LedgerSaveModel.deleteOne({ userId, slot, revision: expectedRevision });
    if (result.deletedCount === 0 && await LedgerSaveModel.exists({ userId, slot })) throw new LedgerSaveConflictError();
  },
};

export async function initLedgerSaveStore(): Promise<void> {
  await LedgerSaveModel.init();
}
