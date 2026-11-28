import { required, requiredString } from '../helpers/required';
import request from 'supertest';
import { useIsolatedMongo } from '../helpers/isolatedMongo';
import { createApp } from '../../src/app';
import { MAX_REASSIGNMENT_MUTATIONS, publishSplitSessionUpdate, splitSessionEvents, type SplitSessionUpdate } from '../../src/splitSessions';
import { GroupModel, ExpenseModel } from '../../src/groupStore';
import { SplitSessionModel, type SplitSessionRecord } from '../../src/splitSessionStore';
import { mongoSessionStore, mongoUserStore, SessionModel, UserModel } from '../../src/userStore';
import { startSession } from '../../src/session';
import { MAX_AMOUNT_MINOR } from '../../src/money';
import { testsRejectInvalidSessions } from '../helpers/sessionRejection';

// Real HTTP routing, session verification and MongoDB. No mocked components.
const app = createApp();

let ownerId = '';
let memberId = '';
let ownerAuth = '';
let memberAuth = '';
let outsiderAuth = '';
let groupId = '';
let sharedSessionId = '';

useIsolatedMongo('split-session-test', [SplitSessionModel, ExpenseModel, GroupModel, SessionModel, UserModel]);

beforeEach(async () => {
  const accounts = await Promise.all(['owner', 'member', 'outsider'].map(async name => {
    const account = await UserModel.create({ email: `${name}@example.com`, displayName: name });
    const user = await mongoUserStore.findById(account.id);
    if (!user) throw new Error('Test account did not persist.');
    const session = await startSession(mongoSessionStore, user);
    return { id: account.id, auth: `Bearer ${session.token}` };
  }));
  ownerId = required(accounts[0]).id;
  memberId = required(accounts[1]).id;
  ownerAuth = required(accounts[0]).auth;
  memberAuth = required(accounts[1]).auth;
  outsiderAuth = required(accounts[2]).auth;
  const group = await GroupModel.create({ name: 'Roommates', currency: 'CAD', ownerId, memberIds: [ownerId, memberId] });
  groupId = group.id;
});

afterEach(() => {
  splitSessionEvents.removeAllListeners();
});

const items = [
  { unitId: 'burger-1', description: 'Burger', amountCents: 1200 },
  { unitId: 'fries-1', description: 'Fries', amountCents: 600 },
];
const malicious = [
  { $ne: null }, { $gt: '' }, { $eq: 'split-request-0001' }, { $regex: '.*' },
  { nested: { $ne: null } }, {}, ['split-request-0001'], null,
];
const unknownIds = ['not-an-id', '{"$ne":null}', '000000000000000000000000'];

function create(overrides: Record<string, unknown> = {}, token = ownerAuth) {
  return request(app).post(`/api/groups/${groupId}/split-sessions`).set('Authorization', token).send({
    reviewed: true, requestId: 'split-request-0001', description: 'Dinner', amountCents: 1800,
    paidBy: ownerId, units: items, ...overrides,
  });
}
function claim(sessionId: string, unitId: string, token = memberAuth, overrides: Record<string, unknown> = {}) {
  return request(app).post(`/api/split-sessions/${sessionId}/claims`).set('Authorization', token).send({
    unitId, action: 'claim', version: 0, operationKey: `claim-op-${unitId}-00001`, ...overrides,
  });
}
function finalize(sessionId: string, version: number, operationKey: unknown, token = ownerAuth) {
  return request(app).post(`/api/split-sessions/${sessionId}/finalize`).set('Authorization', token).send({ version, operationKey });
}
function snapshot(token = ownerAuth, id = groupId) {
  return request(app).get(`/api/groups/${id}/snapshot`).set('Authorization', token);
}
function operation(key: string, token = memberAuth) {
  return request(app).get(`/api/operations/${key}`).set('Authorization', token);
}
async function createdSession(overrides: Record<string, unknown> = {}) {
  return (await create(overrides)).body.session.id as string;
}
async function expectUnchanged(sessionId: string) {
  const stored = await SplitSessionModel.findById(sessionId).lean();
  expect(stored?.version).toBe(0);
  expect(stored?.operations).toHaveLength(0);
  expect(await SplitSessionModel.countDocuments({ groupId })).toBe(1);
  expect(await ExpenseModel.countDocuments({ groupId })).toBe(0);
}
const storedState = () => Promise.all([SplitSessionModel.find().lean(), ExpenseModel.find().lean()]);
async function prepareSharedSession() {
  sharedSessionId = await createdSession({ requestId: 'session-check-split-01' });
  expect(sharedSessionId).toBeTruthy();
}

// Interface POST /api/groups/:groupId/split-sessions
describe('Unmocked: POST /api/groups/:groupId/split-sessions', () => {
  // Input: republish a committed claim and a legacy snapshot lacking its event history.
  // Expected status code: 200 for the claim.
  // Expected behavior: use the persisted event identity when available, otherwise use the creator.
  // Expected output: claim key and member ID, then only the creator ID.
  test('recovers event metadata for subscribers from stored snapshots', async () => {
    const id = await createdSession();
    expect((await claim(id, 'burger-1')).status).toBe(200);
    const session = await SplitSessionModel.findById(id).lean<SplitSessionRecord>().orFail();
    const updates: SplitSessionUpdate[] = [];
    splitSessionEvents.on('onGroupUpdated', (update: SplitSessionUpdate) => updates.push(update));
    publishSplitSessionUpdate(session, 'claim');
    publishSplitSessionUpdate({ ...session, events: [] }, 'created');
    expect(updates[0]?.event).toMatchObject({ actorId: memberId, operationKey: 'claim-op-burger-1-00001' });
    expect(updates[1]?.event.actorId).toBe(ownerId);
    expect(updates[1]?.event).not.toHaveProperty('operationKey');
  });


  // Input: a missing group, an absent body, or invalid line items in a reviewed split.
  // Expected status code: 404 for the missing group and 400 for invalid input.
  // Expected behavior: validate the receipt before persisting a session.
  // Expected output: diagnostic for the rejected field and zero sessions.
  test('validates receipt item IDs, descriptions, amounts and bounds', async () => {
    expect((await create({}, outsiderAuth)).status).toBe(404);
    const absent = await request(app).post(`/api/groups/${groupId}/split-sessions`).set('Authorization', ownerAuth);
    expect(absent.status).toBe(400);
    const unit = required(items[0]);
    for (const [units, error] of [
      [[], 'between 1 and 200'], [Array(201).fill(unit), 'between 1 and 200'],
      [[null], 'unit ID'], [[unit, unit], 'unit ID'], [[{ ...unit, description: null }], 'description'],
      [[{ ...unit, amountCents: -1 }], 'positive integer-cent'],
    ] as const) {
      const response = await create({ units });
      expect(response.status).toBe(400);
      expect(response.body.error).toContain(error);
    }
    expect(await SplitSessionModel.countDocuments()).toBe(0);
  });

  testsRejectInvalidSessions(() => request(app).post(`/api/groups/${groupId}/split-sessions`).send({}), () => ownerAuth, storedState);

  // Input: start by a member, unreviewed items, items that do not reconcile, a valid start, its retry, and a changed retry
  // Expected status code: 403, 400, 400, 201, 200, 409
  // Expected behavior: only the owner starts a reviewed, reconciled session; retries do not duplicate it
  // Expected output: { session } with status "open", version 0, sequence 1
  test('Only the owner starts a reviewed, reconciled session once', async () => {
    expect((await create({}, memberAuth)).status).toBe(403);
    expect((await create({ reviewed: false })).status).toBe(400);
    expect((await create({ units: [...items, { unitId: 'tip', description: 'Tip', amountCents: 1 }] })).status).toBe(400);
    const res = await create();
    expect(res.status).toBe(201);
    expect(res.body.session).toMatchObject({ amountCents: 1800, currency: 'CAD', status: 'open', version: 0, sequence: 1 });
    const retry = await create();
    expect(retry.status).toBe(200);
    expect(retry.body.session.id).toBe(res.body.session.id);
    expect((await create({ description: 'Changed' })).status).toBe(409);
    expect(await SplitSessionModel.countDocuments({ groupId })).toBe(1);
  });

  // Input: the same start request twice at once
  // Expected status code: 201 and 200
  // Expected behavior: one session is stored
  // Expected output: the same session ID for both
  test('Concurrent starts keep one session', async () => {
    const results = await Promise.all([create(), create()]);
    expect(results.map(result => result.status).sort()).toEqual([200, 201]);
    expect(required(results[0]).body.session.id).toBe(required(results[1]).body.session.id);
    expect(await SplitSessionModel.countDocuments({ groupId })).toBe(1);
  });

  // Input: operator-shaped requestId or paidBy
  // Expected status code: 400
  // Expected behavior: operators are never used in a MongoDB filter; no extra session is stored
  // Expected output: error message
  test.each(malicious)('Rejects operator-shaped identifiers: %j', async value => {
    const sessionId = await createdSession();
    expect((await create({ requestId: value })).status).toBe(400);
    expect((await create({ paidBy: value })).status).toBe(400);
    await expectUnchanged(sessionId);
  });

  // Input: 16-character and 80-character valid request keys, each sent twice
  // Expected status code: 201, then 200
  // Expected behavior: the exact key is kept and replays the same session
  // Expected output: the same session ID
  test.each(['a'.repeat(16), `Ab9_-${'z'.repeat(75)}`])('Preserves an exact valid request key: %s', async key => {
    const created = await create({ requestId: key });
    expect(created.status).toBe(201);
    const replay = await create({ requestId: key });
    expect(replay.status).toBe(200);
    expect(replay.body.session.id).toBe(created.body.session.id);
  });

  // Input: tax and tip of 1.00 and 2.00 on 18.00 of items, then a retry with tax and tip swapped
  // Expected status code: 201, then 409
  // Expected behavior: tax and tip are stored with the session and are part of the request
  // Expected output: { session } with taxCents 100 and tipCents 200
  test('Stores tax and tip with the session', async () => {
    const created = await create({ amountCents: 2100, taxCents: 100, tipCents: 200 });
    expect(created.status).toBe(201);
    expect(created.body.session).toMatchObject({ amountCents: 2100, taxCents: 100, tipCents: 200 });
    expect((await create({ amountCents: 2100, taxCents: 200, tipCents: 100 })).status).toBe(409);
  });

  // Input: fractional tax, negative tax, and a tax that does not reconcile with the total
  // Expected status code: 400
  // Expected behavior: no session is stored
  // Expected output: error message
  test('Requires whole-cent tax and tip that reconcile with the total', async () => {
    expect((await create({ amountCents: 1900, taxCents: 100.5 })).status).toBe(400);
    expect((await create({ amountCents: 1800, taxCents: -100, tipCents: 100 })).status).toBe(400);
    expect((await create({ amountCents: 1800, taxCents: 100 })).status).toBe(400);
    expect(await SplitSessionModel.countDocuments()).toBe(0);
  });

  // Input: a total one minor unit above the monetary ceiling
  // Expected status code: 400
  // Expected behavior: no session is stored
  // Expected output: error message
  test('Rejects a total above the monetary ceiling', async () => {
    expect((await create({ requestId: 'split-over-limit-0001', amountCents: MAX_AMOUNT_MINOR + 1,
      units: [{ ...items[0], amountCents: MAX_AMOUNT_MINOR + 1 }] })).status).toBe(400);
    expect(await SplitSessionModel.countDocuments()).toBe(0);
  });
});

// Interface GET /api/groups/:groupId/snapshot
describe('Unmocked: GET /api/groups/:groupId/snapshot', () => {
  testsRejectInvalidSessions(() => request(app).get(`/api/groups/${groupId}/snapshot`), () => ownerAuth, storedState);

  // Input: snapshot by a member after one claim, and by a nonmember
  // Expected status code: 200, then 404
  // Expected behavior: membership is checked; the member sees the current session state
  // Expected output: { splitSessions: [{ id, sequence: 2, version: 1 }] }
  test('Returns current sessions to members only', async () => {
    const sessionId = await createdSession();
    expect((await claim(sessionId, 'burger-1', memberAuth, { operationKey: 'claim-member-00001' })).status).toBe(200);
    expect((await snapshot(outsiderAuth)).status).toBe(404);
    const res = await snapshot(memberAuth);
    expect(res.status).toBe(200);
    expect(res.body.splitSessions[0]).toMatchObject({ id: sessionId, sequence: 2, version: 1 });
  });

  // Input: snapshot with an invalid, operator-shaped or unknown group ID
  // Expected status code: 404
  // Expected behavior: no data is revealed
  // Expected output: error message
  test.each(unknownIds)('Reveals no data for group ID %s', async id => {
    await create();
    expect((await snapshot(ownerAuth, encodeURIComponent(id))).status).toBe(404);
  });

  // Input: snapshot after a crash left a persisted finalize intent (with and without the expense already posted)
  // Expected status code: 200
  // Expected behavior: the snapshot completes finalization exactly once and publishes one update
  // Expected output: { splitSessions: [{ status: "finalized", version: 2 }], expenses: [one expense] }
  test.each([false, true])('Completes a persisted finalize intent after process loss (expense posted: %s)', async posted => {
    const sessionId = await createdSession({ amountCents: 1200, units: items.slice(0, 1) });
    expect((await claim(sessionId, 'burger-1', memberAuth, { operationKey: 'crash-claim-000001' })).status).toBe(200);
    // Reproduce a process interruption after the owner intent is persisted, with the
    // original request key absent from the recovering client entirely.
    await SplitSessionModel.updateOne({ _id: sessionId }, {
      $set: { status: 'finalizing', finalizeOperationKey: 'crash-final-000001', finalizeActorId: ownerId },
      $push: { operations: { operationKey: 'crash-final-000001', actorId: ownerId, type: 'finalize', payload: { version: 1 },
        state: 'pending', version: 1, sequence: 2, createdAt: new Date() } },
    });
    if (posted) await ExpenseModel.create({ groupId, description: 'Dinner', amountCents: 1200, currency: 'CAD',
      paidBy: ownerId, createdBy: ownerId, splitMode: 'custom', shares: [{ userId: memberId, amountCents: 1200 }],
      requestId: `split-session-${sessionId}`, splitSessionId: sessionId });
    const updates: SplitSessionUpdate[] = [];
    splitSessionEvents.on('onGroupUpdated', (update: SplitSessionUpdate) => updates.push(update));
    const res = await snapshot(memberAuth);
    expect(res.status).toBe(200);
    expect(res.body.splitSessions[0]).toMatchObject({ status: 'finalized', version: 2, sequence: 3 });
    expect(res.body.expenses).toHaveLength(1);
    expect(await ExpenseModel.countDocuments({ groupId })).toBe(1);
    expect(updates).toHaveLength(1);
    const lookup = await operation('crash-final-000001', ownerAuth);
    expect(updates[0]).toMatchObject({ groupId, sequence: 3, session: { id: sessionId, status: 'finalized' },
      event: { groupId, sessionId, sequence: 3, type: 'finalized', operationKey: 'crash-final-000001', actorId: ownerId } });
    expect(lookup.body.operation).toMatchObject({ state: 'complete', version: 2, sequence: 3 });
    const repeat = await snapshot();
    expect(repeat.status).toBe(200);
    expect(repeat.body.expenses).toHaveLength(1);
    expect(updates).toHaveLength(1);
  });
});

// Interface POST /api/split-sessions/:sessionId/claims
describe('Unmocked: POST /api/split-sessions/:sessionId/claims', () => {

  // Input: an unknown item, an unowned release, a duplicate claim and a key reused in another session.
  // Expected status code: 404 for the unknown item and 409 for state conflicts.
  // Expected behavior: only the first valid claim advances the session.
  // Expected output: an authoritative conflict snapshot and one stored claim.
  test('rejects conflicting claims and releases at the current version', async () => {
    const id = await createdSession();
    expect((await claim(id, 'missing')).status).toBe(404);
    const empty = await claim(id, 'burger-1', memberAuth, { action: 'unclaim' });
    expect(empty.status).toBe(409);
    expect(empty.body.error).toContain('not claimed');
    expect((await claim(id, 'burger-1')).status).toBe(200);
    const again = await claim(id, 'burger-1', ownerAuth, { version: 1, operationKey: 'second-claim-same-unit' });
    expect(again.status).toBe(409);
    expect(again.body.error).toContain('already claimed');
    const second = await createdSession({ requestId: 'other-split-request' });
    expect((await claim(second, 'burger-1')).status).toBe(409);
    expect((await SplitSessionModel.findById(id).lean())?.operations).toHaveLength(1);
    expect((await SplitSessionModel.findById(second).lean())?.operations).toHaveLength(0);
  });

  testsRejectInvalidSessions(() => request(app).post(`/api/split-sessions/${sharedSessionId}/claims`)
    .send({ unitId: 'burger-1', action: 'claim', version: 0, operationKey: 'unauthorized-claim-01' }), () => memberAuth, storedState, prepareSharedSession);

  // Input: member and owner claim the same item at once; the winner replays; a reused key on another item; a stale claim
  // Expected status code: one 200 and one 409; replay 200; reuse 409; stale 409
  // Expected behavior: claims are exclusive and versioned; only the persisted state is published once
  // Expected output: { session, operation } with the winner as item owner and sequence 2
  test('Claims are exclusive, versioned and retry-safe', async () => {
    const sessionId = await createdSession();
    const updates: SplitSessionUpdate[] = [];
    splitSessionEvents.on('onGroupUpdated', (update: SplitSessionUpdate) => updates.push(update));
    const results = await Promise.all([
      claim(sessionId, 'burger-1', memberAuth, { operationKey: 'claim-first-000001' }),
      claim(sessionId, 'burger-1', ownerAuth, { operationKey: 'claim-other-000001' }),
    ]);
    expect(results.map(result => result.status).sort()).toEqual([200, 409]);
    const accepted = required(results.find(result => result.status === 200));
    const memberWon = required(results[0]).status === 200;
    const acceptedKey = memberWon ? 'claim-first-000001' : 'claim-other-000001';
    const acceptedAuth = memberWon ? memberAuth : ownerAuth;
    expect(accepted.body.session.units.find((unit: { unitId: string }) => unit.unitId === 'burger-1').ownerId).toBe(memberWon ? memberId : ownerId);
    expect(updates).toHaveLength(1);
    expect(updates[0]).toMatchObject({ groupId, sequence: 2, session: { id: sessionId, version: 1 },
      event: { groupId, sessionId, sequence: 2, type: 'claim', operationKey: acceptedKey, actorId: memberWon ? memberId : ownerId } });
    const replay = await claim(sessionId, 'burger-1', acceptedAuth, { version: 0, operationKey: acceptedKey });
    expect(replay.status).toBe(200);
    expect(replay.body.operation.sequence).toBe(2);
    expect((await claim(sessionId, 'fries-1', acceptedAuth, { operationKey: acceptedKey })).status).toBe(409);
    expect((await claim(sessionId, 'burger-1', memberAuth, { operationKey: 'claim-stale-000001' })).status).toBe(409);
  });

  // Input: the owner tries to release an item claimed by a member
  // Expected status code: 403
  // Expected behavior: only the claimer can release their item
  // Expected output: error message
  test('Forbids releasing another member\'s item', async () => {
    const sessionId = await createdSession();
    expect((await claim(sessionId, 'burger-1', memberAuth, { operationKey: 'claim-member-00001' })).status).toBe(200);
    const unclaim = await claim(sessionId, 'burger-1', ownerAuth, { action: 'unclaim', version: 1, operationKey: 'unclaim-owner-00001' });
    expect(unclaim.status).toBe(403);
  });

  // Input: operator-shaped operationKey, unitId or version
  // Expected status code: 400
  // Expected behavior: operators are never used in a MongoDB filter; the session is unchanged
  // Expected output: error message
  test.each(malicious)('Rejects operator-shaped claim fields: %j', async value => {
    const sessionId = await createdSession();
    expect((await claim(sessionId, 'burger-1', memberAuth, { operationKey: value })).status).toBe(400);
    expect((await claim(sessionId, 'burger-1', memberAuth, { unitId: value })).status).toBe(400);
    expect((await claim(sessionId, 'burger-1', memberAuth, { version: value })).status).toBe(400);
    await expectUnchanged(sessionId);
  });

  // Input: claim with an invalid, operator-shaped or unknown session ID
  // Expected status code: 404
  // Expected behavior: no data is revealed
  // Expected output: error message
  test.each(unknownIds)('Reveals no data for session ID %s', async id => {
    await create();
    expect((await claim(encodeURIComponent(id), 'burger-1')).status).toBe(404);
  });

  // Input: alternating claim history just below the reassignment limit on a 200-item session, then more changes
  // Expected status code: 200 up to the limit; 409 for a release past it; 200 for remaining claims and finalization
  // Expected behavior: history is bounded while old retries, remaining claims and finalization still complete
  // Expected output: denial mentions the reassignment limit; the final expense assigns all 200 cents to the member
  test('Bounds reassignment history while remaining claims still complete', async () => {
    const units = Array.from({ length: 200 }, (_, index) => ({ unitId: `item-${index}`, description: `Item ${index}`, amountCents: 1 }));
    const sessionId = await createdSession({ amountCents: units.length, units });
    const now = new Date();
    // Seed valid alternating history just below the boundary. Odd mutation count
    // leaves item-0 claimed; the final allowed release makes all 200 unassigned.
    const operations = Array.from({ length: MAX_REASSIGNMENT_MUTATIONS - 1 }, (_, index) => {
      const type = index % 2 === 0 ? 'claim' : 'unclaim';
      return { operationKey: `history-operation-${index}`, actorId: memberId, type,
        payload: { unitId: 'item-0', action: type, version: index }, state: 'complete',
        version: index + 1, sequence: index + 2, createdAt: now };
    });
    await SplitSessionModel.updateOne({ _id: sessionId }, { $set: {
      version: MAX_REASSIGNMENT_MUTATIONS - 1, sequence: MAX_REASSIGNMENT_MUTATIONS,
      'units.0.ownerId': memberId, operations,
      events: [{ sequence: 1, type: 'created', actorId: ownerId, occurredAt: now },
        ...operations.map(op => ({ sequence: op.sequence, type: op.type,
          operationKey: op.operationKey, actorId: memberId, occurredAt: now }))],
    } });
    const releasePayload = { action: 'unclaim', version: MAX_REASSIGNMENT_MUTATIONS - 1, operationKey: 'boundary-release-001' };
    const release = await claim(sessionId, 'item-0', memberAuth, releasePayload);
    expect(release.status).toBe(200);
    expect(release.body.session.version).toBe(MAX_REASSIGNMENT_MUTATIONS);
    const replay = await claim(sessionId, 'item-0', memberAuth, releasePayload);
    expect(replay.status).toBe(200);
    expect(replay.body.operation.sequence).toBe(MAX_REASSIGNMENT_MUTATIONS + 1);
    expect(replay.body.session.version).toBe(MAX_REASSIGNMENT_MUTATIONS);
    expect((await claim(sessionId, 'item-0', memberAuth, {
      version: MAX_REASSIGNMENT_MUTATIONS, operationKey: 'remaining-claim-000',
    })).status).toBe(200);
    const denied = await claim(sessionId, 'item-0', memberAuth, {
      action: 'unclaim', version: MAX_REASSIGNMENT_MUTATIONS + 1, operationKey: 'boundary-denied-0001',
    });
    expect(denied.status).toBe(409);
    expect(denied.body.error).toMatch(/reassignment limit.*no longer be released/);
    expect(denied.body.session.version).toBe(MAX_REASSIGNMENT_MUTATIONS + 1);
    expect(denied.body.session.units[0].ownerId).toBe(memberId);
    const atLimit = await SplitSessionModel.findById(sessionId).lean();
    expect(atLimit?.operations).toHaveLength(MAX_REASSIGNMENT_MUTATIONS + 1);
    expect(atLimit?.events).toHaveLength(MAX_REASSIGNMENT_MUTATIONS + 2);
    expect(atLimit?.operations.some(op => op.operationKey === 'boundary-denied-0001')).toBe(false);
    for (let index = 1; index < units.length; index++) {
      expect((await claim(sessionId, `item-${index}`, memberAuth, {
        version: MAX_REASSIGNMENT_MUTATIONS + index, operationKey: `remaining-claim-${String(index).padStart(3, '0')}`,
      })).status).toBe(200);
    }
    const final = await finalize(sessionId, MAX_REASSIGNMENT_MUTATIONS + units.length, 'boundary-finalize-001');
    expect(final.status).toBe(200);
    expect(final.body.session.status).toBe('finalized');
    expect(final.body.expense.shares).toEqual([{ userId: memberId, amountCents: units.length }]);
    const stored = await SplitSessionModel.findById(sessionId).lean();
    expect(stored?.operations).toHaveLength(2201);
    expect(stored?.events).toHaveLength(2202);
    expect(stored?.version).toBe(2201);
    expect(stored?.sequence).toBe(2202);
    expect(await ExpenseModel.countDocuments({ groupId })).toBe(1);
  }, 60000);
});

// Interface POST /api/split-sessions/:sessionId/finalize
describe('Unmocked: POST /api/split-sessions/:sessionId/finalize', () => {
  // Input: a persisted receipt whose units no longer add up to the recorded total.
  // Expected status code: 400.
  // Expected behavior: refuse to post or lock the session.
  // Expected output: reconciliation error and zero expenses.
  test('checks stored receipt totals before posting', async () => {
    const id = requiredString((await create({ units: [items[0]], amountCents: 1200 })).body.session.id);
    expect((await claim(id, 'burger-1')).status).toBe(200);
    await SplitSessionModel.updateOne({ _id: id }, { $inc: { amountCents: 1 } });
    const result = await finalize(id, 1, 'reconcile-final-0001');
    expect(result.status).toBe(400);
    expect(result.body.error).toContain('do not reconcile');
    expect(await ExpenseModel.countDocuments()).toBe(0);
  });

  // Input: replay a finalized receipt written without an operation entry, then without an expense link.
  // Expected status code: 200 followed by 503.
  // Expected behavior: replay the original expense when available and never create another.
  // Expected output: the saved expense without an operation, then a recovery diagnostic.
  test('replays an older finalization with incomplete metadata', async () => {
    const id = requiredString((await create({ units: [items[0]], amountCents: 1200 })).body.session.id);
    await claim(id, 'burger-1');
    const key = 'legacy-finalize-0001';
    const posted = await finalize(id, 1, key);
    expect(posted.status).toBe(200);
    await SplitSessionModel.updateOne({ _id: id }, { $pull: { operations: { operationKey: key } } });
    const replay = await finalize(id, 1, key);
    expect(replay.status).toBe(200);
    expect(replay.body.expense).toEqual(posted.body.expense);
    expect(replay.body).not.toHaveProperty('operation');
    await SplitSessionModel.updateOne({ _id: id }, { $unset: { finalizedExpenseId: '' } });
    expect((await finalize(id, 1, key)).status).toBe(503);
    expect(await ExpenseModel.countDocuments()).toBe(1);
  });

  // Input: a pending finalization with the matching owner and key but an outdated version.
  // Expected status code: 409.
  // Expected behavior: preserve the pending intent.
  // Expected output: current session version and no expense.
  test('checks the receipt version during recovery', async () => {
    const id = requiredString((await create()).body.session.id);
    const key = 'legacy-pending-00001';
    await SplitSessionModel.updateOne({ _id: id }, { $set: {
      status: 'finalizing', version: 2, finalizeOperationKey: key, finalizeActorId: ownerId,
    } });
    const response = await finalize(id, 1, key);
    expect(response.status).toBe(409);
    expect(response.body.session.version).toBe(2);
    expect(await ExpenseModel.countDocuments()).toBe(0);
  });


  // Input: an outsider, a non-owner, a stale version and a previously used claim key.
  // Expected status code: 404, 403, 409 and 409.
  // Expected behavior: refuse finalization before taking a lock or posting an expense.
  // Expected output: access or version diagnostic and zero expenses.
  test('enforces ownership, version and operation identity', async () => {
    const id = await createdSession();
    const key = 'owner-finalize-request';
    expect((await finalize(id, 0, key, outsiderAuth)).status).toBe(404);
    expect((await finalize(id, 0, key, memberAuth)).status).toBe(403);
    expect((await finalize(id, 1, key)).status).toBe(409);
    expect((await claim(id, 'burger-1', ownerAuth, { operationKey: key })).status).toBe(200);
    expect((await finalize(id, 1, key)).status).toBe(409);
    expect(await ExpenseModel.countDocuments()).toBe(0);
  });

  // Input: a legacy split without tax/tip, then a retry of an already finalized session.
  // Expected status code: 200 for the replay and finish, 409 for a new key and 503 for a missing posted expense.
  // Expected behavior: use zero extras for old records and retain the original finalization result.
  // Expected output: the original expense or a recovery diagnostic.
  test('recovers a legacy split and reports a missing finalized expense', async () => {
    const id = await createdSession();
    await SplitSessionModel.updateOne({ _id: id }, { $unset: { taxCents: '', tipCents: '' } });
    expect((await create()).body.session).toMatchObject({ id, taxCents: 0, tipCents: 0 });
    expect((await claim(id, 'burger-1')).status).toBe(200);
    expect((await claim(id, 'fries-1', ownerAuth, { version: 1 })).status).toBe(200);
    const key = 'legacy-finalize-request';
    const finished = await finalize(id, 2, key);
    expect(finished.status).toBe(200);
    expect(finished.body.expense).toMatchObject({ subtotalCents: 1800, taxCents: 0, tipCents: 0 });
    expect((await finalize(id, 2, 'another-finalize-key')).status).toBe(409);
    await ExpenseModel.deleteOne({ _id: finished.body.expense.id });
    expect((await finalize(id, 2, key)).status).toBe(503);
    expect((await finalize(id, 2, 'another-finalize-key')).status).toBe(503);
  });

  testsRejectInvalidSessions(() => request(app).post(`/api/split-sessions/${sharedSessionId}/finalize`)
    .send({ version: 0, operationKey: 'unauthorized-final-01' }), () => ownerAuth, storedState, prepareSharedSession);

  // Input: finalize with an unassigned item, then after every item is claimed, then a retry, then edits afterwards
  // Expected status code: 400, 200, 200, then 409 for the edit and for a later unclaim
  // Expected behavior: the owner posts one expense; retries return it; the posted bill is immutable
  // Expected output: { session: { status: "finalized" }, expense } with the member owing 1800
  test('Posts one expense once every item is claimed', async () => {
    const sessionId = await createdSession();
    expect((await claim(sessionId, 'burger-1', memberAuth, { operationKey: 'claim-burger-00001' })).status).toBe(200);
    expect((await claim(sessionId, 'fries-1', ownerAuth, { version: 1, operationKey: 'claim-fries-000001' })).status).toBe(200);
    // An unassigned item cannot finalize; reclaiming it leaves all units assigned.
    expect((await claim(sessionId, 'fries-1', ownerAuth, { action: 'unclaim', version: 2, operationKey: 'unclaim-fries-00001' })).status).toBe(200);
    expect((await finalize(sessionId, 3, 'finalize-empty-0001')).status).toBe(400);
    expect((await claim(sessionId, 'fries-1', memberAuth, { version: 3, operationKey: 'member-fries-000001' })).status).toBe(200);
    const final = await finalize(sessionId, 4, 'finalize-op-000002');
    expect(final.status).toBe(200);
    expect(final.body.session.status).toBe('finalized');
    expect(final.body.expense.shares).toEqual([{ userId: memberId, amountCents: 1800 }]);
    expect(await ExpenseModel.countDocuments({ groupId })).toBe(1);
    const retry = await finalize(sessionId, 4, 'finalize-op-000002');
    expect(retry.status).toBe(200);
    expect(retry.body.expense.id).toBe(final.body.expense.id);
    expect(await ExpenseModel.countDocuments({ groupId })).toBe(1);
    const summary = await request(app).get(`/api/groups/${groupId}/summary`).set('Authorization', ownerAuth);
    expect(summary.body.expenses[0].canEdit).toBe(false);
    const edit = await request(app).put(`/api/groups/${groupId}/expenses/${final.body.expense.id}`).set('Authorization', ownerAuth)
      .send({ description: 'Changed', amount: '1.00', paidBy: ownerId, splitMode: 'equal', shares: [{ userId: ownerId }], revision: 0 });
    expect(edit.status).toBe(409);
    expect((await claim(sessionId, 'fries-1', ownerAuth, { action: 'unclaim', version: 5, operationKey: 'post-finalize-00001' })).status).toBe(409);
  });

  // Input: the paying member claims an item and leaves the group; the owner then finalizes
  // Expected status code: 404 for the departed member's unclaim; 200 for finalize
  // Expected behavior: earlier claims stay finalizable but the departed member can act no further
  // Expected output: { expense } paid by and owed by the departed member
  test('Keeps earlier claims finalizable after a member leaves', async () => {
    const sessionId = await createdSession({ amountCents: 1200, units: items.slice(0, 1), paidBy: memberId });
    expect((await claim(sessionId, 'burger-1', memberAuth)).status).toBe(200);
    expect((await request(app).post(`/api/groups/${groupId}/leave`).set('Authorization', memberAuth)).status).toBe(200);
    expect((await claim(sessionId, 'burger-1', memberAuth, { action: 'unclaim', version: 1,
      operationKey: 'departed-unclaim-001' })).status).toBe(404);
    const finalized = await finalize(sessionId, 1, 'departed-final-00001');
    expect(finalized.status).toBe(200);
    expect(finalized.body.expense.paidBy).toBe(memberId);
    expect(finalized.body.expense.shares).toEqual([{ userId: memberId, amountCents: 1200 }]);
  });

  // Input: the same finalize request twice at once
  // Expected status code: 200 for both
  // Expected behavior: finalization completes once with one operation and one finalized event
  // Expected output: the same expense ID for both
  test('Concurrent finalization retries complete once', async () => {
    const sessionId = await createdSession({ amountCents: 1200, units: items.slice(0, 1) });
    expect((await claim(sessionId, 'burger-1', memberAuth, { operationKey: 'single-claim-000001' })).status).toBe(200);
    const results = await Promise.all([finalize(sessionId, 1, 'single-final-000001'), finalize(sessionId, 1, 'single-final-000001')]);
    expect(results.map(result => result.status)).toEqual([200, 200]);
    expect(required(results[0]).body.expense.id).toBe(required(results[1]).body.expense.id);
    expect(await ExpenseModel.countDocuments({ groupId })).toBe(1);
    const stored = await SplitSessionModel.findById(sessionId).lean();
    expect(stored?.operations.filter(op => op.operationKey === 'single-final-000001')).toHaveLength(1);
    expect(stored?.events.filter(event => event.type === 'finalized')).toHaveLength(1);
  });

  // Input: finalize when an expense with the session's request ID exists but its session link is missing or different
  // Expected status code: 409; a later snapshot also returns 409
  // Expected behavior: an unrelated expense is never adopted; the session stays "finalizing" and nothing is published
  // Expected output: error mentioning a conflicting expense
  test.each(['missing', 'different'])('Never adopts an expense with a %s session link', async marker => {
    const sessionId = await createdSession({ amountCents: 1200, units: items.slice(0, 1) });
    expect((await claim(sessionId, 'burger-1', memberAuth, { operationKey: 'collision-claim-001' })).status).toBe(200);
    // Matching financial fields and request ID alone must not turn a manual or
    // unrelated expense into this session's immutable finalization result.
    const collision = await ExpenseModel.create({ groupId, description: 'Dinner', amountCents: 1200, currency: 'CAD',
      paidBy: ownerId, createdBy: ownerId, splitMode: 'custom', shares: [{ userId: memberId, amountCents: 1200 }],
      requestId: `split-session-${sessionId}`,
      ...(marker === 'different' ? { splitSessionId: '000000000000000000000001' } : {}) });
    const updates: SplitSessionUpdate[] = [];
    splitSessionEvents.on('onGroupUpdated', (update: SplitSessionUpdate) => updates.push(update));
    const res = await finalize(sessionId, 1, 'collision-final-001');
    expect(res.status).toBe(409);
    expect(res.body.error).toMatch(/conflicting expense/);
    expect((await snapshot()).status).toBe(409);
    const stored = await SplitSessionModel.findById(sessionId).lean();
    expect(stored).toMatchObject({ status: 'finalizing', version: 1, sequence: 2 });
    expect(stored?.finalizedExpenseId).toBeUndefined();
    expect(stored?.operations.filter(op => op.type === 'finalize')).toHaveLength(1);
    expect(stored?.operations.find(op => op.type === 'finalize')?.state).toBe('pending');
    expect(stored?.events.some(event => event.type === 'finalized')).toBe(false);
    expect(updates).toHaveLength(0);
    expect(await ExpenseModel.countDocuments({ groupId })).toBe(1);
    expect((await ExpenseModel.findById(collision.id))?.splitSessionId?.toString()).toBe(
      marker === 'different' ? '000000000000000000000001' : undefined);
  });

  // Input: 12.00 and 6.00 of items with 1.00 tax and 2.00 tip, claimed by owner and member
  // Expected status code: 200
  // Expected behavior: tax and tip follow the claimed items proportionally
  // Expected output: { expense } with subtotalCents 1800 and shares 1400 (owner) and 700 (member)
  test('Splits tax and tip with the claimed items', async () => {
    const sessionId = await createdSession({ amountCents: 2100, taxCents: 100, tipCents: 200 });
    await claim(sessionId, 'burger-1', ownerAuth);
    await claim(sessionId, 'fries-1', memberAuth, { version: 1 });
    const final = await finalize(sessionId, 2, 'finalize-extras-0001');
    expect(final.status).toBe(200);
    expect(final.body.expense).toMatchObject({ amountCents: 2100, subtotalCents: 1800, taxCents: 100, tipCents: 200 });
    const shares = Object.fromEntries((final.body.expense.shares as { userId: string; amountCents: number }[])
      .map(share => [share.userId, share.amountCents]));
    expect(shares).toEqual({ [ownerId]: 1400, [memberId]: 700 });
  });

  // Input: a maximum-size split with large tax and tip claimed half each
  // Expected status code: 200
  // Expected behavior: proportional allocation keeps full precision at the ceiling
  // Expected output: { expense } with two shares of 500000000000
  test('Finalizes a maximum-size split without losing precision', async () => {
    const sessionId = await createdSession({ amountCents: MAX_AMOUNT_MINOR, taxCents: 166666666667, tipCents: 166666666667,
      units: items.map(item => ({ ...item, amountCents: 333333333333 })) });
    expect((await claim(sessionId, 'burger-1', ownerAuth)).status).toBe(200);
    expect((await claim(sessionId, 'fries-1', memberAuth, { version: 1 })).status).toBe(200);
    const finalized = await finalize(sessionId, 2, 'finalize-maximum-0001');
    expect(finalized.status).toBe(200);
    expect(finalized.body.expense).toMatchObject({ amountCents: MAX_AMOUNT_MINOR, subtotalCents: 666666666666 });
    expect(finalized.body.expense.shares.map((share: { amountCents: number }) => share.amountCents)).toEqual([500000000000, 500000000000]);
    expect((await ExpenseModel.findById(requiredString(finalized.body.expense.id)))?.amountCents).toBe(MAX_AMOUNT_MINOR);
  });

  // Input: operator-shaped finalize operationKey
  // Expected status code: 400
  // Expected behavior: the session is unchanged and no expense is posted
  // Expected output: error message
  test.each(malicious)('Rejects an operator-shaped operation key: %j', async value => {
    const sessionId = await createdSession();
    expect((await finalize(sessionId, 0, value)).status).toBe(400);
    await expectUnchanged(sessionId);
  });
});

// Interface GET /api/operations/:operationKey
describe('Unmocked: GET /api/operations/:operationKey', () => {
  testsRejectInvalidSessions(() => request(app).get('/api/operations/unknown-operation-01'), () => memberAuth, storedState);

  // Input: lookup of an accepted claim key by its actor and by another member
  // Expected status code: 200 for the actor, 404 for the other member
  // Expected behavior: operation results are private to their actor
  // Expected output: { operation: { type: "claim", state: "complete", sequence: 2 } }
  test('Returns an operation only to its actor', async () => {
    const sessionId = await createdSession();
    expect((await claim(sessionId, 'burger-1', memberAuth, { operationKey: 'lookup-claim-000001' })).status).toBe(200);
    expect((await operation('lookup-claim-000001', ownerAuth)).status).toBe(404);
    const lookup = await operation('lookup-claim-000001');
    expect(lookup.status).toBe(200);
    expect(lookup.body.operation).toMatchObject({ type: 'claim', state: 'complete', sequence: 2 });
  });

  // Input: 16-character and 80-character valid operation keys used for a claim, then looked up
  // Expected status code: 200 for the actor, 404 for a nonmember
  // Expected behavior: the exact key is kept
  // Expected output: the same operation returned by the claim
  test.each(['a'.repeat(16), `Ab9_-${'z'.repeat(75)}`])('Preserves an exact valid operation key: %s', async key => {
    const sessionId = await createdSession();
    const accepted = await claim(sessionId, 'burger-1', memberAuth, { operationKey: key });
    expect(accepted.status).toBe(200);
    expect(accepted.body.operation.operationKey).toBe(key);
    const lookup = await operation(key);
    expect(lookup.status).toBe(200);
    expect(lookup.body.operation).toEqual(accepted.body.operation);
    expect((await operation(key, outsiderAuth)).status).toBe(404);
  });

  // Input: lookup after the actor was removed from the group
  // Expected status code: 404
  // Expected behavior: former members cannot read operation results
  // Expected output: error message
  test('Stays private after the actor is removed', async () => {
    const sessionId = await createdSession({ amountCents: 1200, units: items.slice(0, 1) });
    expect((await claim(sessionId, 'burger-1', memberAuth, { operationKey: 'removed-claim-00001' })).status).toBe(200);
    await GroupModel.updateOne({ _id: groupId }, { $pull: { memberIds: memberId } });
    expect((await operation('removed-claim-00001')).status).toBe(404);
    expect((await snapshot(memberAuth)).status).toBe(404);
  });

  // Input: lookup with an invalid, operator-shaped or unknown key
  // Expected status code: 404
  // Expected behavior: no data is revealed
  // Expected output: error message
  test.each(unknownIds)('Reveals no data for key %s', async id => {
    await create();
    expect((await operation(encodeURIComponent(id), ownerAuth)).status).toBe(404);
  });
});
