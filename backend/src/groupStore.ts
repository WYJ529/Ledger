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
  percentageBasisPoints: { type: Number, min: 0, max: 10000, validate: Number.isSafeInteger },
  // Adjusted split only: the signed amount added to (or deducted from) this member's equal share.
  adjustmentCents: { type: Number, min: -100_000_000, max: 100_000_000, validate: Number.isSafeInteger },
}, { _id: false });

const expenseSchema = new Schema({
  groupId: { type: Schema.Types.ObjectId, ref: 'Group', required: true },
  description: { type: String, required: true, maxlength: 100 },
  amountCents: { type: Number, required: true, min: 1, max: 100_000_000, validate: Number.isSafeInteger },
  currency: { type: String, required: true },
  paidBy: { type: Schema.Types.ObjectId, ref: 'User', required: true },
  createdBy: { type: Schema.Types.ObjectId, ref: 'User', required: true },
  updatedBy: { type: Schema.Types.ObjectId, ref: 'User' },
  splitMode: { type: String, required: true, enum: ['equal', 'custom', 'percentage', 'adjusted'] },
  shares: { type: [shareSchema], required: true },
  // A retried submission cannot create the same expense twice.
  requestId: { type: String, required: true, maxlength: 80 },
}, { timestamps: true });
expenseSchema.index({ groupId: 1, createdBy: 1, requestId: 1 }, { unique: true });
expenseSchema.index({ groupId: 1, createdAt: -1, _id: -1 });

export const GroupModel = model('Group', groupSchema);
export const GroupInvitationModel = model('GroupInvitation', invitationSchema);
export const ExpenseModel = model('Expense', expenseSchema);

export function expenseJson(expense: {
  _id: Types.ObjectId; description: string; amountCents: number; currency: string;
  paidBy: Types.ObjectId; createdBy: Types.ObjectId; splitMode: string; createdAt: Date;
  shares: { userId: Types.ObjectId; amountCents: number; percentageBasisPoints?: number | null; adjustmentCents?: number | null }[];
  __v?: number;
}) {
  return {
    id: expense._id.toString(), description: expense.description, amountCents: expense.amountCents,
    currency: expense.currency, paidBy: expense.paidBy.toString(), createdBy: expense.createdBy.toString(),
    splitMode: expense.splitMode, createdAt: expense.createdAt.toISOString(),
    revision: expense.__v ?? 0,
    shares: expense.shares.map(share => ({ userId: share.userId.toString(), amountCents: share.amountCents,
      ...(share.percentageBasisPoints != null ? { percentageBasisPoints: share.percentageBasisPoints } : {}),
      ...(share.adjustmentCents != null ? { adjustmentCents: share.adjustmentCents } : {}),
    })),
  };
}

export async function initGroupStores(): Promise<void> {
  await Promise.all([GroupModel.init(), ExpenseModel.init(), GroupInvitationModel.init()]);
}
