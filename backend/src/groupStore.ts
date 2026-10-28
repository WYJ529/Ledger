import { Schema, model, Types } from 'mongoose';

const groupSchema = new Schema({
  name: { type: String, required: true, trim: true, maxlength: 60 },
  description: { type: String, trim: true, maxlength: 240, default: '' },
  ownerId: { type: Schema.Types.ObjectId, ref: 'User', required: true },
  memberIds: { type: [Schema.Types.ObjectId], required: true, index: true },
  currency: { type: String, required: true, enum: ['CAD', 'USD', 'EUR', 'CNY'], default: 'CAD' },
  createRequestId: { type: String, maxlength: 80 },
  deletedAt: { type: Date },
}, { timestamps: true });
groupSchema.index({ ownerId: 1, createRequestId: 1 }, { unique: true, partialFilterExpression: { createRequestId: { $type: 'string' } } });

const invitationSchema = new Schema({
  tokenHash: { type: String, required: true, unique: true },
  groupId: { type: Schema.Types.ObjectId, ref: 'Group', required: true, index: true },
  inviterId: { type: Schema.Types.ObjectId, ref: 'User', required: true },
  expiresAt: { type: Date, required: true, expires: 0 },
}, { timestamps: true });

const shareSchema = new Schema({
  userId: { type: Schema.Types.ObjectId, ref: 'User', required: true },
  amountCents: { type: Number, required: true, min: 0, max: 100_000_000, validate: Number.isSafeInteger },
}, { _id: false });

const expenseSchema = new Schema({
  groupId: { type: Schema.Types.ObjectId, ref: 'Group', required: true },
  description: { type: String, required: true, maxlength: 100 },
  amountCents: { type: Number, required: true, min: 1, max: 100_000_000, validate: Number.isSafeInteger },
  subtotalCents: { type: Number, min: 1, max: 100_000_000, validate: Number.isSafeInteger },
  taxCents: { type: Number, min: 0, max: 100_000_000, validate: Number.isSafeInteger },
  tipCents: { type: Number, min: 0, max: 100_000_000, validate: Number.isSafeInteger },
  customCalculation: { type: Schema.Types.Mixed },
  currency: { type: String, required: true },
  paidBy: { type: Schema.Types.ObjectId, ref: 'User', required: true },
  createdBy: { type: Schema.Types.ObjectId, ref: 'User', required: true },
  updatedBy: { type: Schema.Types.ObjectId, ref: 'User' },
  splitMode: { type: String, required: true, enum: ['equal', 'custom'] },
  shares: { type: [shareSchema], required: true },
  // A retried submission cannot create the same expense twice.
  requestId: { type: String, required: true, maxlength: 80 },
  // Finalized item allocations are an immutable receipt snapshot.
  splitSessionId: { type: Schema.Types.ObjectId, ref: 'SplitSession' },
}, { timestamps: true });
expenseSchema.index({ groupId: 1, createdBy: 1, requestId: 1 }, { unique: true });
expenseSchema.index({ groupId: 1, createdAt: -1, _id: -1 });

export const GroupModel = model('Group', groupSchema);
export const GroupInvitationModel = model('GroupInvitation', invitationSchema);
export const ExpenseModel = model('Expense', expenseSchema);

export function expenseJson(expense: {
  _id: Types.ObjectId; description: string; amountCents: number; currency: string;
  paidBy: Types.ObjectId; createdBy: Types.ObjectId; splitMode: string; createdAt: Date;
  shares: { userId: Types.ObjectId; amountCents: number }[];
  __v?: number;
  subtotalCents?: number | null | undefined; taxCents?: number | null | undefined; tipCents?: number | null | undefined;
  customCalculation?: unknown;
}) {
  return {
    id: expense._id.toString(), description: expense.description, amountCents: expense.amountCents,
    subtotalCents: expense.subtotalCents ?? expense.amountCents,
    taxCents: expense.taxCents ?? 0, tipCents: expense.tipCents ?? 0,
    customCalculation: expense.customCalculation ?? null,
    currency: expense.currency, paidBy: expense.paidBy.toString(), createdBy: expense.createdBy.toString(),
    splitMode: expense.splitMode, createdAt: expense.createdAt.toISOString(),
    revision: expense.__v ?? 0,
    shares: expense.shares.map(share => ({ userId: share.userId.toString(), amountCents: share.amountCents })),
  };
}

export async function initGroupStores(): Promise<void> {
  await Promise.all([GroupModel.init(), ExpenseModel.init(), GroupInvitationModel.init()]);
}
