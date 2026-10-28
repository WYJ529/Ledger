import { Router } from 'express';
import { Types } from 'mongoose';
import { ExpenseModel, GroupModel, expenseJson } from './groupStore';
import { requireSession, type AuthenticatedLocals } from './session';
import { parseMoney, splitBill, SplitInputError } from './splitting';
import type { UserStore, SessionStore } from './userStore';
import { groupManagementRouter, groupJson } from './groupManagement';

function expenseInput(body: unknown, group: { memberIds: Types.ObjectId[]; currency: string }) {
  const fields = body && typeof body === 'object' ? body as Record<string, unknown> : {};
  const description = typeof fields.description === 'string' ? fields.description.trim() : '';
  if (!description || description.length > 100) throw new SplitInputError('Enter a description of 1-100 characters.');
  const amountCents = parseMoney(fields.amount);
  const memberIds = group.memberIds.map(id => id.toString());
  if (typeof fields.paidBy !== 'string' || !memberIds.includes(fields.paidBy)) {
    throw new SplitInputError('Choose a payer who belongs to the group.');
  }
  const shares = splitBill(amountCents, fields.splitMode, fields.shares, memberIds);
  return { description, amountCents, currency: group.currency, paidBy: fields.paidBy,
    splitMode: fields.splitMode as 'equal' | 'custom' | 'percentage', shares };
}

function sameExpense(existing: Parameters<typeof expenseJson>[0], input: ReturnType<typeof expenseInput>) {
  return existing.description === input.description && existing.amountCents === input.amountCents &&
    existing.currency === input.currency && existing.paidBy.toString() === input.paidBy && existing.splitMode === input.splitMode &&
    existing.shares.length === input.shares.length && existing.shares.every((share, index) => {
      const proposed = input.shares[index];
      return proposed !== undefined && share.userId.toString() === proposed.userId &&
        share.amountCents === proposed.amountCents && (share.percentageBasisPoints ?? undefined) === proposed.percentageBasisPoints;
    });
}

export function groupExpensesRouter(users: UserStore, sessions: SessionStore): Router {
  const router = Router();
  router.use(requireSession(users, sessions));
  router.use((_req, res, next) => { res.setHeader('Cache-Control', 'no-store'); next(); });
  router.use(groupManagementRouter(users));

  router.get('/', async (_req, res) => {
    const { user } = res.locals as AuthenticatedLocals;
    const groups = await GroupModel.find({ memberIds: user.id, deletedAt: null }).sort({ createdAt: -1 }).lean();
    res.json({ groups: groups.map(groupJson) });
  });

  router.use('/:groupId', async (req, res, next) => {
    const { user } = res.locals as AuthenticatedLocals;
    const id = req.params.groupId;
    const group = typeof id === 'string' && /^[a-f\d]{24}$/i.test(id)
      ? await GroupModel.findOne({ _id: id, memberIds: user.id, deletedAt: null }).lean() : null;
    // Do not disclose another group's existence to a nonmember.
    if (!group) { res.status(404).json({ error: 'Group not found.' }); return; }
    res.locals.group = group;
    next();
  });

  router.get('/:groupId/summary', async (_req, res) => {
    const group = res.locals.group as NonNullable<Awaited<ReturnType<typeof GroupModel.findOne>>>;
    const { user } = res.locals as AuthenticatedLocals;
    const members = await Promise.all(group.memberIds.map(async id => {
      const user = await users.findById(id.toString());
      return { id: id.toString(), displayName: user?.displayName ?? 'Former member', role: id.toString() === group.ownerId.toString() ? 'owner' : 'member' };
    }));
    // One aggregation produces consistent totals, including expenses beyond the displayed page.
    const [summary] = await ExpenseModel.aggregate<{
      totals: { totalCents: number; expenseCount: number }[];
      allocations: { _id: Types.ObjectId; amountCents: number }[];
      recent: Parameters<typeof expenseJson>[0][];
    }>([
      { $match: { groupId: group._id } },
      { $facet: {
        totals: [{ $group: { _id: null, totalCents: { $sum: '$amountCents' }, expenseCount: { $sum: 1 } } }],
        allocations: [{ $unwind: '$shares' }, { $group: { _id: '$shares.userId', amountCents: { $sum: '$shares.amountCents' } } }],
        recent: [{ $sort: { createdAt: -1, _id: -1 } }, { $limit: 50 }],
      } },
    ]);
    const totals = summary?.totals[0] ?? { totalCents: 0, expenseCount: 0 };
    res.json({
      group: { ...groupJson(group), members },
      ...totals,
      memberShares: (summary?.allocations ?? []).map(item => ({ userId: item._id.toString(), amountCents: item.amountCents })),
      expenses: (summary?.recent ?? []).map(expense => ({ ...expenseJson(expense),
        canEdit: expense.createdBy.toString() === user.id || group.ownerId.toString() === user.id,
      })),
      recentLimit: 50,
    });
  });

  router.post('/:groupId/expenses', async (req, res) => {
    const group = res.locals.group as NonNullable<Awaited<ReturnType<typeof GroupModel.findOne>>>;
    const { user } = res.locals as AuthenticatedLocals;
    try {
      const body = req.body;
      const values = expenseInput(body, group);
      if (typeof body?.requestId !== 'string' || !/^[a-zA-Z0-9_-]{16,80}$/.test(body.requestId)) {
        throw new SplitInputError('A valid submission ID is required.');
      }
      const input = {
        groupId: group._id, createdBy: user.id, requestId: body.requestId,
        ...values,
      };
      // The unique index also protects against simultaneous duplicate requests.
      let created;
      try { created = await ExpenseModel.create(input); }
      catch (error) {
        if ((error as { code?: number }).code !== 11000) throw error;
        const existing = await ExpenseModel.findOne({ groupId: group._id, createdBy: user.id, requestId: body.requestId }).lean();
        if (!existing) throw error;
        if (!sameExpense(existing, values)) { res.status(409).json({ error: 'This submission was already saved with different details. Refresh the group before adding another bill.' }); return; }
        res.json({ expense: expenseJson(existing) });
        return;
      }
      res.status(201).json({ expense: expenseJson(created) });
    } catch (error) {
      if (!(error instanceof SplitInputError)) throw error;
      res.status(400).json({ error: error.message });
    }
  });

  router.put('/:groupId/expenses/:expenseId', async (req, res) => {
    const group = res.locals.group as NonNullable<Awaited<ReturnType<typeof GroupModel.findOne>>>;
    const { user } = res.locals as AuthenticatedLocals;
    const expenseId = req.params.expenseId;
    const existing = typeof expenseId === 'string' && /^[a-f\d]{24}$/i.test(expenseId)
      ? await ExpenseModel.findOne({ _id: expenseId, groupId: group._id }).lean() : null;
    if (!existing) { res.status(404).json({ error: 'Expense not found.' }); return; }
    if (existing.createdBy.toString() !== user.id && group.ownerId.toString() !== user.id) {
      res.status(403).json({ error: 'Only the expense creator or group owner can edit this expense.' }); return;
    }
    try {
      const revision: unknown = req.body?.revision;
      if (typeof revision !== 'number' || !Number.isSafeInteger(revision) || revision < 0) {
        throw new SplitInputError('Refresh the group to load the current expense version.');
      }
      const input = expenseInput(req.body, group);
      // An unchanged form or a retry after an uncertain response needs no further write.
      if (sameExpense(existing, input)) { res.json({ expense: expenseJson(existing) }); return; }
      const updated = await ExpenseModel.findOneAndUpdate({
        _id: existing._id, groupId: group._id,
        ...(revision === 0 ? { $or: [{ __v: 0 }, { __v: { $exists: false } }] } : { __v: revision }),
      }, { $set: { ...input, updatedBy: user.id }, $inc: { __v: 1 } }, { returnDocument: 'after', runValidators: true });
      if (!updated) {
        res.status(409).json({ error: 'This expense has changed since you opened it. Go back and refresh the group before editing again.' }); return;
      }
      res.json({ expense: expenseJson(updated) });
    } catch (error) {
      if (!(error instanceof SplitInputError)) throw error;
      res.status(400).json({ error: error.message });
    }
  });
  return router;
}
