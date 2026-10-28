import { Schema, Types, model } from 'mongoose';

const splitUnitSchema = new Schema({
  unitId: { type: String, required: true, minlength: 1, maxlength: 80 },
  description: { type: String, required: true, maxlength: 100 },
  amountCents: { type: Number, required: true, min: 1, max: 100_000_000, validate: Number.isSafeInteger },
  ownerId: { type: Schema.Types.ObjectId, ref: 'User', default: null },
}, { _id: false });

const operationSchema = new Schema({
  operationKey: { type: String, required: true, maxlength: 80 },
  actorId: { type: Schema.Types.ObjectId, ref: 'User', required: true },
  type: { type: String, required: true, enum: ['claim', 'unclaim', 'finalize'] },
  payload: { type: Schema.Types.Mixed, required: true },
  state: { type: String, required: true, enum: ['pending', 'complete'] },
  version: { type: Number, required: true, min: 0 },
  sequence: { type: Number, required: true, min: 0 },
  expenseId: { type: Schema.Types.ObjectId, ref: 'Expense' },
  createdAt: { type: Date, required: true },
}, { _id: false });

const eventSchema = new Schema({
  sequence: { type: Number, required: true, min: 1 },
  type: { type: String, required: true, enum: ['created', 'claim', 'unclaim', 'finalized'] },
  operationKey: { type: String, maxlength: 80 },
  actorId: { type: Schema.Types.ObjectId, ref: 'User', required: true },
  occurredAt: { type: Date, required: true },
}, { _id: false });

const splitSessionSchema = new Schema({
  groupId: { type: Schema.Types.ObjectId, ref: 'Group', required: true, index: true },
  createdBy: { type: Schema.Types.ObjectId, ref: 'User', required: true },
  createRequestId: { type: String, required: true, maxlength: 80 },
  description: { type: String, required: true, trim: true, maxlength: 100 },
  amountCents: { type: Number, required: true, min: 1, max: 100_000_000, validate: Number.isSafeInteger },
  currency: { type: String, required: true },
  paidBy: { type: Schema.Types.ObjectId, ref: 'User', required: true },
  units: { type: [splitUnitSchema], required: true, validate: (units: unknown[]) => units.length >= 1 && units.length <= 200 },
  status: { type: String, required: true, enum: ['open', 'finalizing', 'finalized'], default: 'open' },
  version: { type: Number, required: true, min: 0, default: 0 },
  sequence: { type: Number, required: true, min: 0, default: 0 },
  finalizedExpenseId: { type: Schema.Types.ObjectId, ref: 'Expense' },
  finalizeOperationKey: { type: String, maxlength: 80 },
  finalizeActorId: { type: Schema.Types.ObjectId, ref: 'User' },
  operations: { type: [operationSchema], required: true, default: [] },
  events: { type: [eventSchema], required: true, default: [] },
}, { timestamps: true });

splitSessionSchema.index({ groupId: 1, createdAt: -1 });
splitSessionSchema.index({ groupId: 1, createdBy: 1, createRequestId: 1 }, { unique: true });
// Operation keys are globally unique so a reconnect can resolve one key without
// exposing another member's result. Outcome and event are embedded in the same
// session document as the claim mutation, so a single-document CAS commits all.
splitSessionSchema.index({ 'operations.operationKey': 1 }, { unique: true, sparse: true });

export const SplitSessionModel = model('SplitSession', splitSessionSchema);

export async function initSplitSessionStore(): Promise<void> {
  await SplitSessionModel.init();
}

export type SplitSessionUnitRecord = {
  unitId: string;
  description: string;
  amountCents: number;
  ownerId: Types.ObjectId | null;
};

export type SplitSessionRecord = {
  _id: Types.ObjectId;
  groupId: Types.ObjectId;
  createdBy: Types.ObjectId;
  createRequestId: string;
  description: string;
  amountCents: number;
  currency: string;
  paidBy: Types.ObjectId;
  units: SplitSessionUnitRecord[];
  status: 'open' | 'finalizing' | 'finalized';
  version: number;
  sequence: number;
  finalizedExpenseId?: Types.ObjectId;
  finalizeOperationKey?: string;
  finalizeActorId?: Types.ObjectId;
  operations: {
    operationKey: string;
    actorId: Types.ObjectId;
    type: 'claim' | 'unclaim' | 'finalize';
    payload: Record<string, unknown>;
    state: 'pending' | 'complete';
    version: number;
    sequence: number;
    expenseId?: Types.ObjectId;
    createdAt: Date;
  }[];
  events: { sequence: number; type: string; operationKey?: string; actorId: Types.ObjectId; occurredAt: Date }[];
  createdAt: Date;
  updatedAt: Date;
};
