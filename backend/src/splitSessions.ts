import { EventEmitter } from 'node:events';
import { Router, type Response } from 'express';
import { Types } from 'mongoose';
import { ExpenseModel, GroupModel, expenseJson } from './groupStore';
import { requireSession, type AuthenticatedLocals } from './session';
import { SplitInputError } from './splitting';
import type { SessionStore, UserStore } from './userStore';
import { SplitSessionModel, type SplitSessionRecord } from './splitSessionStore';

const OPERATION_KEY = /^[A-Za-z0-9_-]{16,80}$/;
const UNIT_ID = /^[A-Za-z0-9_.:-]{1,80}$/;
const MAX_AMOUNT_CENTS = 100_000_000;
const MAX_UNITS = 200;
// While open, each accepted claim/release advances version once. Stop releases
// after this many mutations, leaving at most MAX_UNITS claims and finalization.
export const MAX_REASSIGNMENT_MUTATIONS = 2_000;

export type SplitSessionOperation = {
  operationKey: string;
  sessionId: string;
  groupId: string;
  actorId: string;
  type: 'claim' | 'unclaim' | 'finalize';
  payload: Record<string, unknown>;
  state: 'pending' | 'complete';
  version: number;
  sequence: number;
  expenseId?: string;
  createdAt: string;
};

export type SplitSessionUpdate = {
  groupId: string;
  sequence: number;
  event: { groupId: string; sessionId: string; sequence: number; type: 'created' | 'claim' | 'unclaim' | 'finalized'; operationKey?: string; actorId: string };
  session: ReturnType<typeof splitSessionJson>;
};

/** Post-commit feed for the Socket.IO adapter. Listeners receive persisted state only. */
export const splitSessionEvents = new EventEmitter();

export function publishSplitSessionUpdate(session: SplitSessionRecord, type: SplitSessionUpdate['event']['type'],
  operationKey?: string, actorId?: string): void {
  const persisted = session.events.find(event => event.sequence === session.sequence);
  try {
    splitSessionEvents.emit('onGroupUpdated', {
      groupId: session.groupId.toString(), sequence: session.sequence,
      event: {
        groupId: session.groupId.toString(), sessionId: session._id.toString(), sequence: session.sequence,
        type, ...(operationKey ? { operationKey } : persisted?.operationKey ? { operationKey: persisted.operationKey } : {}),
        actorId: (actorId ? new Types.ObjectId(actorId) : persisted?.actorId)?.toString() ?? session.createdBy.toString(),
      },
      session: splitSessionJson(session),
    } satisfies SplitSessionUpdate);
    splitSessionEvents.emit('updated', { groupId: session.groupId.toString(), sequence: session.sequence, session: splitSessionJson(session) });
  } catch {
    // Delivery is best-effort; the committed snapshot and operation lookup remain authoritative.
  }
}

export function splitSessionJson(session: SplitSessionRecord) {
  return {
    id: session._id.toString(), groupId: session.groupId.toString(), description: session.description,
    amountCents: session.amountCents, currency: session.currency, paidBy: session.paidBy.toString(),
    units: session.units.map(unit => ({ unitId: unit.unitId, description: unit.description, amountCents: unit.amountCents,
      ownerId: unit.ownerId?.toString() ?? null })),
    status: session.status, version: session.version, sequence: session.sequence,
    finalizedExpenseId: session.finalizedExpenseId?.toString() ?? null,
    createdAt: session.createdAt.toISOString(), updatedAt: session.updatedAt.toISOString(),
  };
}

function operationJson(session: SplitSessionRecord, operation: SplitSessionRecord['operations'][number]): SplitSessionOperation {
  return {
    operationKey: operation.operationKey, sessionId: session._id.toString(), groupId: session.groupId.toString(),
    actorId: operation.actorId.toString(), type: operation.type, payload: operation.payload,
    state: operation.state, version: operation.version, sequence: operation.sequence,
    ...(operation.expenseId ? { expenseId: operation.expenseId.toString() } : {}), createdAt: operation.createdAt.toISOString(),
  };
}

function parseCreateInput(body: unknown, group: { memberIds: Types.ObjectId[] }) {
  const fields = body && typeof body === 'object' ? body as Record<string, unknown> : {};
  const description = typeof fields.description === 'string' ? fields.description.trim() : '';
  if (!description || description.length > 100) throw new SplitInputError('Enter a description of 1-100 characters.');
  if (fields.reviewed !== true) throw new SplitInputError('Review and confirm the itemized receipt before starting a split session.');
  const amountCents = fields.amountCents;
  if (typeof amountCents !== 'number' || !Number.isSafeInteger(amountCents) || amountCents < 1 || amountCents > MAX_AMOUNT_CENTS) {
    throw new SplitInputError('Enter a valid total in integer cents.');
  }
  if (typeof fields.paidBy !== 'string' || !group.memberIds.some(id => id.toString() === fields.paidBy)) {
    throw new SplitInputError('Choose a payer who belongs to the group.');
  }
  if (!Array.isArray(fields.units) || fields.units.length < 1 || fields.units.length > MAX_UNITS) {
    throw new SplitInputError('Enter between 1 and 200 receipt item units.');
  }
  const seen = new Set<string>();
  const units = fields.units.map((entry: unknown) => {
    const unit = entry && typeof entry === 'object' ? entry as Record<string, unknown> : {};
    const unitId = unit.unitId;
    const unitDescription = typeof unit.description === 'string' ? unit.description.trim() : '';
    const cents = unit.amountCents;
    if (typeof unitId !== 'string' || !UNIT_ID.test(unitId) || seen.has(unitId)) {
      throw new SplitInputError('Every receipt item needs a unique valid unit ID.');
    }
    if (!unitDescription || unitDescription.length > 100) throw new SplitInputError('Each receipt item needs a description of 1-100 characters.');
    if (typeof cents !== 'number' || !Number.isSafeInteger(cents) || cents < 1 || cents > MAX_AMOUNT_CENTS) {
      throw new SplitInputError('Each receipt item must have a positive integer-cent amount.');
    }
    seen.add(unitId);
    return { unitId, description: unitDescription, amountCents: cents, ownerId: null };
  });
  if (units.reduce((sum, unit) => sum + unit.amountCents, 0) !== amountCents) {
    throw new SplitInputError('Receipt item amounts must add up exactly to the reviewed total.');
  }
  return { description, amountCents, paidBy: fields.paidBy, units };
}

function parseVersion(value: unknown): number {
  if (typeof value !== 'number' || !Number.isSafeInteger(value) || value < 0) {
    throw new SplitInputError('Refresh the split session to load its current version.');
  }
  return value;
}

function parseOperationKey(value: unknown): string {
  if (typeof value !== 'string' || !OPERATION_KEY.test(value)) throw new SplitInputError('A valid operation key is required.');
  return value;
}

function asRecord(session: unknown): SplitSessionRecord {
  return session as SplitSessionRecord;
}

function sameOperationPayload(existing: SplitSessionRecord['operations'][number], type: 'claim' | 'unclaim' | 'finalize', payload: Record<string, unknown>) {
  return existing.type === type && JSON.stringify(existing.payload) === JSON.stringify(payload);
}

function currentOperation(session: SplitSessionRecord, operationKey: string, actorId: string) {
  return session.operations.find(operation => operation.operationKey === operationKey && operation.actorId.toString() === actorId);
}

function authorizedSessionId(value: unknown) {
  return typeof value === 'string' && Types.ObjectId.isValid(value) ? value : null;
}

async function memberGroup(groupId: unknown, userId: string) {
  return typeof groupId === 'string' && Types.ObjectId.isValid(groupId)
    ? GroupModel.findOne({ _id: groupId, memberIds: userId, deletedAt: null }).lean() : null;
}

async function authorizedSplitSession(sessionId: unknown, userId: string) {
  const id = authorizedSessionId(sessionId);
  if (!id) return null;
  const session = await SplitSessionModel.findById(id).lean<SplitSessionRecord>();
  if (!session) return null;
  const group = await GroupModel.findOne({ _id: session.groupId, memberIds: userId, deletedAt: null }).lean();
  return group ? { session, group } : null;
}

function claimError(res: Response, status: number, message: string, session?: SplitSessionRecord) {
  // Kept as a tiny helper so conflicts always return an authoritative recovery snapshot.
  res.status(status).json({ error: message, ...(session ? { session: splitSessionJson(session) } : {}) });
}

function sharesFor(session: SplitSessionRecord) {
  const totals = new Map<string, number>();
  for (const unit of session.units) {
    if (!unit.ownerId) throw new SplitInputError('Assign every receipt item before finalizing the split.');
    const userId = unit.ownerId.toString();
    totals.set(userId, (totals.get(userId) ?? 0) + unit.amountCents);
  }
  const shares = [...totals.entries()].sort(([a], [b]) => a.localeCompare(b))
    .map(([userId, amountCents]) => ({ userId: new Types.ObjectId(userId), amountCents }));
  if (shares.reduce((sum, share) => sum + share.amountCents, 0) !== session.amountCents) {
    throw new SplitInputError('Assigned item amounts do not reconcile with the expense total.');
  }
  return shares;
}

function samePostedExpense(expense: NonNullable<Awaited<ReturnType<typeof ExpenseModel.findOne>>>, session: SplitSessionRecord,
  shares: ReturnType<typeof sharesFor>) {
  const existingShares = expense.shares.map(share => `${share.userId.toString()}:${share.amountCents}`).sort();
  const expectedShares = shares.map(share => `${share.userId.toString()}:${share.amountCents}`).sort();
  return expense.groupId.toString() === session.groupId.toString() && expense.createdBy.toString() === session.createdBy.toString() &&
    expense.description === session.description && expense.amountCents === session.amountCents && expense.currency === session.currency &&
    expense.paidBy.toString() === session.paidBy.toString() && expense.splitMode === 'custom' &&
    expense.splitSessionId?.toString() === session._id.toString() && JSON.stringify(existingShares) === JSON.stringify(expectedShares);
}

class FinalizationRecoveryError extends Error {
  constructor(readonly status: number, message: string) { super(message); }
}

/** Complete a persisted finalization intent after a lost response or server/client restart. */
async function finishFinalization(session: SplitSessionRecord) {
  const operationKey = session.finalizeOperationKey;
  const actorId = session.finalizeActorId?.toString();
  if (session.status !== 'finalizing' || !operationKey || !actorId || !currentOperation(session, operationKey, actorId)) {
    throw new FinalizationRecoveryError(503, 'The split finalization state is unavailable. Retry shortly.');
  }
  const shares = sharesFor(session);
  const requestId = `split-session-${session._id.toString()}`;
  let expense;
  try {
    expense = await ExpenseModel.create({ groupId: session.groupId, description: session.description,
      amountCents: session.amountCents, currency: session.currency, paidBy: session.paidBy,
      createdBy: session.createdBy, splitMode: 'custom', shares, requestId, splitSessionId: session._id });
  } catch (error) {
    if ((error as { code?: number }).code !== 11000) throw error;
    expense = await ExpenseModel.findOne({ groupId: session.groupId, createdBy: session.createdBy, requestId });
    if (!expense || !samePostedExpense(expense, session, shares)) {
      throw new FinalizationRecoveryError(409, 'A conflicting expense already uses this split session.');
    }
  }
  const nextVersion = session.version + 1;
  const nextSequence = session.sequence + 1;
  const finalized = await SplitSessionModel.findOneAndUpdate({
    _id: session._id, groupId: session.groupId, status: 'finalizing', finalizeOperationKey: operationKey,
    finalizeActorId: actorId, version: session.version,
  }, {
    $set: { status: 'finalized', finalizedExpenseId: expense._id,
      'operations.$[operation].state': 'complete', 'operations.$[operation].version': nextVersion,
      'operations.$[operation].sequence': nextSequence, 'operations.$[operation].expenseId': expense._id },
    $inc: { version: 1, sequence: 1 },
    $push: { events: { sequence: nextSequence, type: 'finalized', operationKey, actorId, occurredAt: new Date() } },
  }, { returnDocument: 'after', runValidators: true, arrayFilters: [{ 'operation.operationKey': operationKey }] }).lean<SplitSessionRecord>();
  if (!finalized) {
    const latest = await SplitSessionModel.findById(session._id).lean<SplitSessionRecord>();
    const operation = latest && currentOperation(latest, operationKey, actorId);
    if (latest?.status === 'finalized' && latest.finalizedExpenseId?.toString() === expense._id.toString() && operation) {
      return { session: latest, operation, expense: expenseJson(expense) };
    }
    throw new FinalizationRecoveryError(503, 'The split was posted and is finishing. Refresh the group to recover its result.');
  }
  publishSplitSessionUpdate(finalized, 'finalized', operationKey, actorId);
  return { session: finalized, operation: currentOperation(finalized, operationKey, actorId)!, expense: expenseJson(expense) };
}

/** REST endpoints for the UC-04/UC-05 split-session state machine. */
export function createSplitSessionsRouter(users: UserStore, sessions: SessionStore): Router {
  const router = Router();
  router.use(requireSession(users, sessions));
  router.use((_req, res, next) => { res.setHeader('Cache-Control', 'no-store'); next(); });

  router.post('/groups/:groupId/split-sessions', async (req, res) => {
    const { user } = res.locals as AuthenticatedLocals;
    const group = await memberGroup(req.params.groupId, user.id);
    if (!group) { res.status(404).json({ error: 'Group not found.' }); return; }
    if (group.ownerId.toString() !== user.id) { res.status(403).json({ error: 'Only the group owner can start a split session.' }); return; }
    try {
      const input = parseCreateInput(req.body, group);
      const createRequestId = req.body?.requestId;
      if (typeof createRequestId !== 'string' || !OPERATION_KEY.test(createRequestId)) {
        throw new SplitInputError('A valid session submission ID is required.');
      }
      const matchesInput = (session: SplitSessionRecord) => session.description === input.description &&
        session.amountCents === input.amountCents && session.paidBy.toString() === input.paidBy &&
        JSON.stringify(session.units.map(unit => [unit.unitId, unit.description, unit.amountCents])) ===
          JSON.stringify(input.units.map(unit => [unit.unitId, unit.description, unit.amountCents]));
      const existing = await SplitSessionModel.findOne({ groupId: group._id, createdBy: user.id, createRequestId }).lean<SplitSessionRecord>();
      if (existing) {
        if (!matchesInput(existing)) { res.status(409).json({ error: 'This session submission ID was already used with different details.' }); return; }
        res.json({ session: splitSessionJson(existing) }); return;
      }
      const now = new Date();
      const created = await SplitSessionModel.create({
        groupId: group._id, createdBy: user.id, createRequestId, description: input.description, amountCents: input.amountCents,
        currency: group.currency, paidBy: input.paidBy, units: input.units, status: 'open', version: 0, sequence: 1,
        operations: [], events: [{ sequence: 1, type: 'created', actorId: user.id, occurredAt: now }],
      });
      const persisted = asRecord(created.toObject());
      publishSplitSessionUpdate(persisted, 'created', undefined, user.id);
      res.status(201).json({ session: splitSessionJson(persisted) });
    } catch (error) {
      if ((error as { code?: number }).code === 11000 && typeof req.body?.requestId === 'string') {
        const existing = await SplitSessionModel.findOne({ groupId: group._id, createdBy: user.id, createRequestId: req.body.requestId }).lean<SplitSessionRecord>();
        if (existing) {
          const input = parseCreateInput(req.body, group);
          const matches = existing.description === input.description && existing.amountCents === input.amountCents &&
            existing.paidBy.toString() === input.paidBy && JSON.stringify(existing.units.map(unit => [unit.unitId, unit.description, unit.amountCents])) ===
            JSON.stringify(input.units.map(unit => [unit.unitId, unit.description, unit.amountCents]));
          if (!matches) { res.status(409).json({ error: 'This session submission ID was already used with different details.' }); return; }
          res.json({ session: splitSessionJson(existing) }); return;
        }
        throw error;
      }
      if (!(error instanceof SplitInputError)) throw error;
      res.status(400).json({ error: error.message });
    }
  });

  router.get('/groups/:groupId/snapshot', async (req, res) => {
    const { user } = res.locals as AuthenticatedLocals;
    const group = await memberGroup(req.params.groupId, user.id);
    if (!group) { res.status(404).json({ error: 'Group not found.' }); return; }
    // A persisted owner-authorized intent can be completed even if the initiating device
    // lost its pending key. No new operation is invented and claims remain fenced.
    const pending = await SplitSessionModel.find({ groupId: group._id, status: 'finalizing' }).lean<SplitSessionRecord[]>();
    try {
      for (const session of pending) await finishFinalization(session);
    } catch (error) {
      if (!(error instanceof FinalizationRecoveryError)) throw error;
      res.status(error.status).json({ error: error.message }); return;
    }
    const [expenses, splitSessions] = await Promise.all([
      ExpenseModel.find({ groupId: group._id }).sort({ createdAt: -1, _id: -1 }).lean(),
      SplitSessionModel.find({ groupId: group._id }).sort({ createdAt: -1, _id: -1 }).lean<SplitSessionRecord[]>(),
    ]);
    res.json({
      groupId: group._id.toString(),
      expenses: expenses.map(expense => expenseJson(expense)),
      splitSessions: splitSessions.map(splitSessionJson),
    });
  });

  router.post('/split-sessions/:sessionId/claims', async (req, res) => {
    const { user } = res.locals as AuthenticatedLocals;
    const authorized = await authorizedSplitSession(req.params.sessionId, user.id);
    if (!authorized) { res.status(404).json({ error: 'Split session not found.' }); return; }
    const { session } = authorized;
    try {
      const unitId = req.body?.unitId;
      const action = req.body?.action;
      const version = parseVersion(req.body?.version);
      const operationKey = parseOperationKey(req.body?.operationKey);
      if (typeof unitId !== 'string' || !UNIT_ID.test(unitId) || !['claim', 'unclaim'].includes(action)) {
        throw new SplitInputError('Choose a valid receipt item and claim or unclaim action.');
      }
      const type = action as 'claim' | 'unclaim';
      const payload = { unitId, action: type, version };
      const prior = currentOperation(session, operationKey, user.id);
      if (prior) {
        if (!sameOperationPayload(prior, type, payload)) { claimError(res, 409, 'This operation key was already used for different details.', session); return; }
        res.json({ session: splitSessionJson(session), operation: operationJson(session, prior) });
        return;
      }
      const elsewhere = await SplitSessionModel.findOne({ 'operations.operationKey': operationKey }).lean<SplitSessionRecord>();
      if (elsewhere) { claimError(res, 409, 'This operation key has already been used.', session); return; }
      if (session.status !== 'open') { claimError(res, 409, 'This split session is no longer open for claims.', session); return; }
      if (version !== session.version) { claimError(res, 409, 'The split session has changed. Refresh before claiming.', session); return; }
      const unit = session.units.find(item => item.unitId === unitId);
      if (!unit) { res.status(404).json({ error: 'Receipt item not found.' }); return; }
      const owner = unit.ownerId?.toString() ?? null;
      if (type === 'unclaim' && owner && owner !== user.id) {
        claimError(res, 403, 'You can only unclaim an item you own.', session); return;
      }
      if ((type === 'claim' && owner !== null) || (type === 'unclaim' && owner === null)) {
        claimError(res, 409, type === 'claim' ? 'This receipt item is already claimed.' : 'This receipt item is not claimed.', session); return;
      }
      if (type === 'unclaim' && session.version >= MAX_REASSIGNMENT_MUTATIONS) {
        claimError(res, 409, 'This split session has reached its reassignment limit. Existing claims can no longer be released; claim the remaining items and finalize the split.', session);
        return;
      }
      const nextVersion = version + 1;
      const nextSequence = session.sequence + 1;
      const now = new Date();
      const updated = await SplitSessionModel.findOneAndUpdate({
        _id: session._id, groupId: session.groupId, status: 'open', version,
        units: { $elemMatch: { unitId, ownerId: type === 'claim' ? null : user.id } },
        'operations.operationKey': { $ne: operationKey },
      }, {
        $set: { 'units.$.ownerId': type === 'claim' ? user.id : null },
        $inc: { version: 1, sequence: 1 },
        $push: {
          operations: { operationKey, actorId: user.id, type, payload, state: 'complete', version: nextVersion,
            sequence: nextSequence, createdAt: now },
          events: { sequence: nextSequence, type, operationKey, actorId: user.id, occurredAt: now },
        },
      }, { returnDocument: 'after', runValidators: true }).lean<SplitSessionRecord>();
      if (!updated) {
        const latest = await SplitSessionModel.findById(session._id).lean<SplitSessionRecord>();
        const replay = latest && currentOperation(latest, operationKey, user.id);
        if (latest && replay && sameOperationPayload(replay, type, payload)) {
          res.json({ session: splitSessionJson(latest), operation: operationJson(latest, replay) }); return;
        }
        claimError(res, 409, 'The split session changed. Refresh before claiming.', latest ?? session);
        return;
      }
      publishSplitSessionUpdate(updated, type, operationKey, user.id);
      const operation = currentOperation(updated, operationKey, user.id)!;
      res.json({ session: splitSessionJson(updated), operation: operationJson(updated, operation) });
    } catch (error) {
      if ((error as { code?: number }).code === 11000) {
        const latest = await SplitSessionModel.findById(session._id).lean<SplitSessionRecord>();
        claimError(res, 409, 'This operation key has already been used.', latest ?? session);
        return;
      }
      if (!(error instanceof SplitInputError)) throw error;
      res.status(400).json({ error: error.message });
    }
  });

  router.post('/split-sessions/:sessionId/finalize', async (req, res) => {
    const { user } = res.locals as AuthenticatedLocals;
    const authorized = await authorizedSplitSession(req.params.sessionId, user.id);
    if (!authorized) { res.status(404).json({ error: 'Split session not found.' }); return; }
    const { group } = authorized;
    if (group.ownerId.toString() !== user.id) { res.status(403).json({ error: 'Only the group owner can finalize a split session.' }); return; }
    try {
      const version = parseVersion(req.body?.version);
      const operationKey = parseOperationKey(req.body?.operationKey);
      let session = authorized.session;
      const payload = { version };
      const prior = currentOperation(session, operationKey, user.id);
      if (prior) {
        if (!sameOperationPayload(prior, 'finalize', payload)) { claimError(res, 409, 'This operation key was already used for different details.', session); return; }
        if (prior.state === 'complete' && prior.expenseId) {
          const expense = await ExpenseModel.findById(prior.expenseId).lean();
          if (!expense) { res.status(503).json({ error: 'The finalized split expense is unavailable. Retry shortly.' }); return; }
          res.json({ session: splitSessionJson(session), operation: operationJson(session, prior), expense: expenseJson(expense) });
          return;
        }
      } else {
        const elsewhere = await SplitSessionModel.findOne({ 'operations.operationKey': operationKey }).lean<SplitSessionRecord>();
        if (elsewhere) { claimError(res, 409, 'This operation key has already been used.', session); return; }
      }
      if (session.status === 'finalized') {
        const expense = session.finalizedExpenseId ? await ExpenseModel.findById(session.finalizedExpenseId).lean() : null;
        if (!expense) { res.status(503).json({ error: 'The finalized split expense is unavailable. Retry shortly.' }); return; }
        if (session.finalizeOperationKey !== operationKey) { claimError(res, 409, 'This split session has already been finalized.', session); return; }
        const operation = currentOperation(session, operationKey, user.id);
        res.json({ session: splitSessionJson(session), ...(operation ? { operation: operationJson(session, operation) } : {}), expense: expenseJson(expense) });
        return;
      }
      if (session.status === 'open') {
        if (session.version !== version) { claimError(res, 409, 'The split session has changed. Refresh before finalizing.', session); return; }
        const shares = sharesFor(session);
        const memberIds = new Set(group.memberIds.map(id => id.toString()));
        if (!memberIds.has(session.paidBy.toString()) || shares.some(share => !memberIds.has(share.userId.toString()))) {
          throw new SplitInputError('The payer and every item owner must still belong to the group.');
        }
        const now = new Date();
        const lock = await SplitSessionModel.findOneAndUpdate({ _id: session._id, status: 'open', version }, {
          $set: { status: 'finalizing', finalizeOperationKey: operationKey, finalizeActorId: user.id },
          $push: { operations: { operationKey, actorId: user.id, type: 'finalize', payload, state: 'pending',
            version, sequence: session.sequence, createdAt: now } },
        }, { returnDocument: 'after', runValidators: true }).lean<SplitSessionRecord>();
        if (!lock) {
          const latest = await SplitSessionModel.findById(session._id).lean<SplitSessionRecord>();
          const replay = latest && currentOperation(latest, operationKey, user.id);
          if (latest?.status === 'finalizing' && latest.finalizeOperationKey === operationKey && replay &&
              sameOperationPayload(replay, 'finalize', payload)) {
            session = latest;
          } else if (latest?.status === 'finalized' && latest.finalizeOperationKey === operationKey && replay?.expenseId) {
            const expense = await ExpenseModel.findById(replay.expenseId).lean();
            if (!expense) { res.status(503).json({ error: 'The finalized split expense is unavailable. Retry shortly.' }); return; }
            res.json({ session: splitSessionJson(latest), operation: operationJson(latest, replay), expense: expenseJson(expense) });
            return;
          } else {
            claimError(res, 409, 'The split session changed. Refresh before finalizing.', latest ?? session);
            return;
          }
        }
        if (lock) session = lock;
      } else if (session.status === 'finalizing') {
        if (session.finalizeOperationKey !== operationKey || session.finalizeActorId?.toString() !== user.id) {
          claimError(res, 409, 'This split session is already being finalized.', session); return;
        }
        if (session.version !== version) { claimError(res, 409, 'The split session changed. Refresh before finalizing.', session); return; }
      }

      // Claims are fenced by the persisted finalizing state. A deterministic expense request ID
      // makes retry/recovery safe if the process stops between posting and closing the session.
      const result = await finishFinalization(session);
      res.json({ session: splitSessionJson(result.session), operation: operationJson(result.session, result.operation), expense: result.expense });
    } catch (error) {
      if ((error as { code?: number }).code === 11000) {
        res.status(409).json({ error: 'This operation key has already been used. Refresh the split session.' });
        return;
      }
      if (error instanceof FinalizationRecoveryError) { res.status(error.status).json({ error: error.message }); return; }
      if (!(error instanceof SplitInputError)) throw error;
      res.status(400).json({ error: error.message });
    }
  });

  router.get('/operations/:operationKey', async (req, res) => {
    const { user } = res.locals as AuthenticatedLocals;
    const operationKey = req.params.operationKey;
    if (typeof operationKey !== 'string' || !OPERATION_KEY.test(operationKey)) { res.status(404).json({ error: 'Operation not found.' }); return; }
    // Authorization is part of the query: a member cannot use a guessed key to probe another member's outcome.
    const session = await SplitSessionModel.findOne({ operations: { $elemMatch: { operationKey, actorId: user.id } } }).lean<SplitSessionRecord>();
    if (!session) { res.status(404).json({ error: 'Operation not found.' }); return; }
    const operation = currentOperation(session, operationKey, user.id);
    if (!operation) { res.status(404).json({ error: 'Operation not found.' }); return; }
    const group = await GroupModel.findOne({ _id: session.groupId, memberIds: user.id, deletedAt: null }).lean();
    if (!group) { res.status(404).json({ error: 'Operation not found.' }); return; }
    const expenseId = operation.expenseId ?? (operation.state === 'pending' ? session.finalizedExpenseId : undefined);
    const expense = expenseId ? await ExpenseModel.findById(expenseId).lean() : null;
    res.json({ operation: operationJson(session, operation), ...(expense ? { expense: expenseJson(expense) } : {}) });
  });
  return router;
}
