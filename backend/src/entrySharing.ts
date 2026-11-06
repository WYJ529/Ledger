import { publishGroupChange } from './groupMembershipEvents';
import { createHash } from 'node:crypto';
import { Router } from 'express';
import { ExpenseModel, expenseJson } from './groupStore';
import type { AuthenticatedLocals } from './session';
import { formatMinor, validateInput, LedgerValidationError, type TransactionInput } from './ledger/domain';
import { splitBill } from './splitting';
import { minorUnitDigits } from './currencies';
import { Types } from 'mongoose';

type Group = { _id: Types.ObjectId; memberIds: Types.ObjectId[]; currency: string; name: string };

/** Mounted after the group's authenticated membership guard. */
export function entrySharingRouter(): Router {
  const router = Router({ mergeParams: true });
  router.post('/:groupId/shared-entries', async (req, res) => {
    const { user } = res.locals as AuthenticatedLocals;
    const group = res.locals.group as Group;
    try {
      const id: unknown = req.body?.sourceId;
      if (typeof id !== 'string' || !/^[a-zA-Z0-9_-]{16,80}$/.test(id)) {
        res.status(400).json({ error: 'A valid personal entry identifier is required.' }); return;
      }
      const { amountMinor, ...input } = validateInput(req.body?.input);
      if (input.kind !== 'expense' || input.currency !== group.currency || amountMinor > 100_000_000) {
        res.status(400).json({ error: 'Share an expense in the group currency within the group amount limit.' }); return;
      }
      const requestId = `personal-entry-${createHash('sha256').update(id).digest('hex')}`;
      const personalEntry = { id, input };
      const existing = await ExpenseModel.findOne().setOptions({ sanitizeFilter: true })
        .where('groupId').equals(new Types.ObjectId(group._id.toHexString()))
        .where('createdBy').equals(new Types.ObjectId(user.id))
        .where('requestId').equals(requestId).lean();
      function replay(saved: NonNullable<typeof existing>) {
        if (JSON.stringify(saved.personalEntry) !== JSON.stringify(personalEntry)) {
          res.status(409).json({ error: 'This entry was already shared with different details. Edit the saved group expense instead.' }); return;
        }
        res.json({ expense: expenseJson(saved), alreadyShared: true });
      }
      if (existing) { replay(existing); return; }
      const memberIds = group.memberIds.map(member => member.toString());
      const values = {
        groupId: group._id, createdBy: user.id, paidBy: user.id, requestId, personalEntry,
        description: input.merchant.slice(0, 100) || input.category, currency: group.currency,
        amountCents: amountMinor, subtotalCents: amountMinor, taxCents: 0, tipCents: 0,
        splitMode: 'equal' as const, shares: splitBill(amountMinor, 'equal', memberIds.map(userId => ({ userId })), memberIds, minorUnitDigits(group.currency)),
      };
      try {
        const saved = await ExpenseModel.create(values);
        publishGroupChange(group._id.toString(), 'expense-added');
        res.status(201).json({ expense: expenseJson(saved), alreadyShared: false });
      } catch (error) {
        if ((error as { code?: number }).code !== 11000) throw error;
        const saved = await ExpenseModel.findOne().setOptions({ sanitizeFilter: true })
          .where('groupId').equals(new Types.ObjectId(group._id.toHexString()))
          .where('createdBy').equals(new Types.ObjectId(user.id))
          .where('requestId').equals(requestId).lean();
        if (!saved) throw error;
        replay(saved);
      }
    } catch (error) {
      if (!(error instanceof LedgerValidationError)) throw error;
      res.status(400).json({ error: error.message });
    }
  });

  router.get('/:groupId/expenses/:expenseId/personal-entry', async (req, res) => {
    const { user } = res.locals as AuthenticatedLocals;
    const group = res.locals.group as Group;
    const id = req.params.expenseId;
    const expense = typeof id === 'string' && /^[a-f\d]{24}$/i.test(id)
      ? await ExpenseModel.findOne().setOptions({ sanitizeFilter: true })
        .where('_id').equals(new Types.ObjectId(id))
        .where('groupId').equals(new Types.ObjectId(group._id.toHexString())).lean() : null;
    if (!expense) { res.status(404).json({ error: 'Expense not found.' }); return; }
    const amount = expense.shares.find(share => share.userId.toString() === user.id)?.amountCents ?? 0;
    if (amount <= 0) { res.status(400).json({ error: 'This expense has no positive share allocated to you.' }); return; }
    const original = expense.personalEntry as { id: string; input: TransactionInput } | undefined;
    const input: TransactionInput = {
      kind: 'expense', amount: formatMinor(amount, expense.currency), currency: expense.currency,
      date: original?.input.date ?? expense.createdAt.toISOString().slice(0, 10),
      merchant: original?.input.merchant.slice(0, 100) === expense.description ? original.input.merchant : expense.description,
      category: original?.input.category ?? 'Other', notes: original?.input.notes ?? '',
    };
    res.json({ input, groupName: group.name, expenseId: expense._id.toString(),
      sourcePersonalId: expense.createdBy.toString() === user.id ? original?.id ?? null : null });
  });
  return router;
}
