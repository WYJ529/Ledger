import request from 'supertest';
import { MongoMemoryServer } from 'mongodb-memory-server';
import { createApp } from '../../src/app';
import { MAX_REASSIGNMENT_MUTATIONS, splitSessionEvents } from '../../src/splitSessions';
import { GroupModel, ExpenseModel } from '../../src/groupStore';
import { initSplitSessionStore, SplitSessionModel } from '../../src/splitSessionStore';
import { connectDatabase, disconnectDatabase, mongoSessionStore, mongoUserStore, SessionModel, UserModel } from '../../src/userStore';
import { startSession } from '../../src/session';

let mongo: MongoMemoryServer;
const app = createApp();

let ownerId = '';
let memberId = '';
let outsiderId = '';
let ownerAuth = '';
let memberAuth = '';
let outsiderAuth = '';
let groupId = '';

beforeAll(async () => {
  mongo = await MongoMemoryServer.create();
  await connectDatabase(mongo.getUri('split-session-test'));
  await initSplitSessionStore();
}, 120000);

beforeEach(async () => {
  const accounts = await Promise.all(['owner', 'member', 'outsider'].map(async name => {
    const account = await UserModel.create({ email: `${name}@example.com`, displayName: name });
    const user = await mongoUserStore.findById(account.id);
    if (!user) throw new Error('Test account did not persist.');
    const session = await startSession(mongoSessionStore, user);
    return { id: account.id, auth: `Bearer ${session.token}` };
  }));
  ownerId = accounts[0]!.id;
  memberId = accounts[1]!.id;
  outsiderId = accounts[2]!.id;
  ownerAuth = accounts[0]!.auth;
  memberAuth = accounts[1]!.auth;
  outsiderAuth = accounts[2]!.auth;
  const group = await GroupModel.create({ name: 'Roommates', currency: 'CAD', ownerId, memberIds: [ownerId, memberId] });
  groupId = group.id;
});

afterEach(async () => {
  splitSessionEvents.removeAllListeners();
  await Promise.all([SplitSessionModel.deleteMany({}), ExpenseModel.deleteMany({}), GroupModel.deleteMany({}),
    SessionModel.deleteMany({}), UserModel.deleteMany({})]);
});
afterAll(async () => { await disconnectDatabase(); await mongo?.stop(); }, 30000);

const items = [
  { unitId: 'burger-1', description: 'Burger', amountCents: 1200 },
  { unitId: 'fries-1', description: 'Fries', amountCents: 600 },
];

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

test('only the owner can create a reviewed, reconciled itemized session and retries do not duplicate it', async () => {
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

test('claims are exclusive, versioned, retry-safe and publish only the persisted state', async () => {
  const created = await create();
  const sessionId = created.body.session.id as string;
  const updates: unknown[] = [];
  splitSessionEvents.on('updated', update => updates.push(update));
  const results = await Promise.all([
    claim(sessionId, 'burger-1', memberAuth, { operationKey: 'claim-first-000001' }),
    claim(sessionId, 'burger-1', ownerAuth, { operationKey: 'claim-other-000001' }),
  ]);
  expect(results.map(result => result.status).sort()).toEqual([200, 409]);
  const accepted = results.find(result => result.status === 200)!;
  const memberWon = results[0]!.status === 200;
  const acceptedKey = memberWon ? 'claim-first-000001' : 'claim-other-000001';
  const acceptedAuth = memberWon ? memberAuth : ownerAuth;
  const otherAuth = memberWon ? ownerAuth : memberAuth;
  expect(accepted.body.session.units.find((unit: { unitId: string }) => unit.unitId === 'burger-1').ownerId).toBe(memberWon ? memberId : ownerId);
  expect(updates).toHaveLength(1);
  expect(updates[0]).toMatchObject({ groupId, sequence: 2, session: { id: sessionId, version: 1 } });
  const replay = await claim(sessionId, 'burger-1', acceptedAuth, { version: 0, operationKey: acceptedKey });
  expect(replay.status).toBe(200);
  expect(replay.body.operation.sequence).toBe(2);
  expect((await claim(sessionId, 'fries-1', acceptedAuth, { operationKey: acceptedKey })).status).toBe(409);
  expect((await request(app).get(`/api/operations/${acceptedKey}`).set('Authorization', otherAuth)).status).toBe(404);
  const lookup = await request(app).get(`/api/operations/${acceptedKey}`).set('Authorization', acceptedAuth);
  expect(lookup.status).toBe(200);
  expect(lookup.body.operation).toMatchObject({ type: 'claim', state: 'complete', sequence: 2 });
  expect((await claim(sessionId, 'burger-1', memberAuth, { operationKey: 'claim-stale-000001' })).status).toBe(409);
});

test.each([
  { $ne: null }, { $gt: '' }, { $eq: 'split-request-0001' }, { $regex: '.*' },
  { nested: { $ne: null } }, {}, ['split-request-0001'], null,
])('rejects operator-shaped keys and item identifiers: %j', async malicious => {
  const created = await create();
  const sessionId = created.body.session.id as string;
  expect((await create({ requestId: malicious })).status).toBe(400);
  expect((await create({ paidBy: malicious })).status).toBe(400);
  expect((await claim(sessionId, 'burger-1', memberAuth, { operationKey: malicious })).status).toBe(400);
  expect((await claim(sessionId, 'burger-1', memberAuth, { unitId: malicious })).status).toBe(400);
  expect((await claim(sessionId, 'burger-1', memberAuth, { version: malicious })).status).toBe(400);
  const finalize = await request(app).post(`/api/split-sessions/${sessionId}/finalize`).set('Authorization', ownerAuth)
    .send({ version: 0, operationKey: malicious });
  expect(finalize.status).toBe(400);
  const stored = await SplitSessionModel.findById(sessionId).lean();
  expect(stored?.version).toBe(0);
  expect(stored?.operations).toHaveLength(0);
  expect(await SplitSessionModel.countDocuments({ groupId })).toBe(1);
  expect(await ExpenseModel.countDocuments({ groupId })).toBe(0);
});

test.each(['a'.repeat(16), `Ab9_-${'z'.repeat(75)}`])('preserves exact valid submission and operation keys: %s', async key => {
  const created = await create({ requestId: key });
  expect(created.status).toBe(201);
  const sessionId = created.body.session.id as string;
  const replay = await create({ requestId: key });
  expect(replay.status).toBe(200);
  expect(replay.body.session.id).toBe(sessionId);
  const accepted = await claim(sessionId, 'burger-1', memberAuth, { operationKey: key });
  expect(accepted.status).toBe(200);
  expect(accepted.body.operation.operationKey).toBe(key);
  const lookup = await request(app).get(`/api/operations/${key}`).set('Authorization', memberAuth);
  expect(lookup.status).toBe(200);
  expect(lookup.body.operation).toEqual(accepted.body.operation);
  expect((await request(app).get(`/api/operations/${key}`).set('Authorization', outsiderAuth)).status).toBe(404);
});

test.each(['not-an-id', '{"$ne":null}', '000000000000000000000000'])('invalid or unknown URL IDs reveal no data: %s', async id => {
  await create();
  const encoded = encodeURIComponent(id);
  expect((await request(app).get(`/api/groups/${encoded}/snapshot`).set('Authorization', ownerAuth)).status).toBe(404);
  expect((await claim(encoded, 'burger-1')).status).toBe(404);
  expect((await request(app).get(`/api/operations/${encoded}`).set('Authorization', ownerAuth)).status).toBe(404);
});

test('concurrent creates replay the same validated request and keep one session', async () => {
  const results = await Promise.all([create(), create()]);
  expect(results.map(result => result.status).sort()).toEqual([200, 201]);
  expect(results[0]!.body.session.id).toBe(results[1]!.body.session.id);
  expect(await SplitSessionModel.countDocuments({ groupId })).toBe(1);
});

test('unclaiming another member’s unit is forbidden and membership is checked on snapshots', async () => {
  const created = await create();
  const sessionId = created.body.session.id as string;
  expect((await claim(sessionId, 'burger-1', memberAuth, { operationKey: 'claim-member-00001' })).status).toBe(200);
  const unclaim = await request(app).post(`/api/split-sessions/${sessionId}/claims`).set('Authorization', ownerAuth)
    .send({ unitId: 'burger-1', action: 'unclaim', version: 1, operationKey: 'unclaim-owner-00001' });
  expect(unclaim.status).toBe(403);
  expect((await request(app).get(`/api/groups/${groupId}/snapshot`).set('Authorization', outsiderAuth)).status).toBe(404);
  const snapshot = await request(app).get(`/api/groups/${groupId}/snapshot`).set('Authorization', memberAuth);
  expect(snapshot.status).toBe(200);
  expect(snapshot.body.splitSessions[0]).toMatchObject({ id: sessionId, sequence: 2, version: 1 });
});

test('owner can finalize fully claimed items once and retries return the same posted expense', async () => {
  const created = await create();
  const sessionId = created.body.session.id as string;
  expect((await claim(sessionId, 'burger-1', memberAuth, { operationKey: 'claim-burger-00001' })).status).toBe(200);
  expect((await claim(sessionId, 'fries-1', ownerAuth, { version: 1, operationKey: 'claim-fries-000001' })).status).toBe(200);
  // An unassigned item cannot finalize; reclaiming it leaves all units assigned.
  expect((await request(app).post(`/api/split-sessions/${sessionId}/claims`).set('Authorization', ownerAuth)
    .send({ unitId: 'fries-1', action: 'unclaim', version: 2, operationKey: 'unclaim-fries-00001' })).status).toBe(200);
  expect((await request(app).post(`/api/split-sessions/${sessionId}/finalize`).set('Authorization', ownerAuth)
    .send({ version: 3, operationKey: 'finalize-empty-0001' })).status).toBe(400);
  expect((await request(app).post(`/api/split-sessions/${sessionId}/claims`).set('Authorization', memberAuth)
    .send({ unitId: 'fries-1', action: 'claim', version: 3, operationKey: 'member-fries-000001' })).status).toBe(200);
  const final = await request(app).post(`/api/split-sessions/${sessionId}/finalize`).set('Authorization', ownerAuth)
    .send({ version: 4, operationKey: 'finalize-op-000002' });
  expect(final.status).toBe(200);
  expect(final.body.session.status).toBe('finalized');
  expect(final.body.expense.shares).toEqual([{ userId: memberId, amountCents: 1800 }]);
  expect(await ExpenseModel.countDocuments({ groupId })).toBe(1);
  const retry = await request(app).post(`/api/split-sessions/${sessionId}/finalize`).set('Authorization', ownerAuth)
    .send({ version: 4, operationKey: 'finalize-op-000002' });
  expect(retry.status).toBe(200);
  expect(retry.body.expense.id).toBe(final.body.expense.id);
  expect(await ExpenseModel.countDocuments({ groupId })).toBe(1);
  const summary = await request(app).get(`/api/groups/${groupId}/summary`).set('Authorization', ownerAuth);
  expect(summary.body.expenses[0].canEdit).toBe(false);
  const edit = await request(app).put(`/api/groups/${groupId}/expenses/${final.body.expense.id}`).set('Authorization', ownerAuth)
    .send({ description: 'Changed', amount: '1.00', paidBy: ownerId, splitMode: 'equal', shares: [{ userId: ownerId }], revision: 0 });
  expect(edit.status).toBe(409);
  expect((await request(app).post(`/api/split-sessions/${sessionId}/claims`).set('Authorization', ownerAuth)
    .send({ unitId: 'fries-1', action: 'unclaim', version: 5, operationKey: 'post-finalize-00001' })).status).toBe(409);
});

test('concurrent finalization retries complete once and operation lookup remains private after removal', async () => {
  const created = await create({ amountCents: 1200, units: items.slice(0, 1) });
  const sessionId = created.body.session.id as string;
  expect((await claim(sessionId, 'burger-1', memberAuth, { operationKey: 'single-claim-000001' })).status).toBe(200);
  const finalRequest = () => request(app).post(`/api/split-sessions/${sessionId}/finalize`).set('Authorization', ownerAuth)
    .send({ version: 1, operationKey: 'single-final-000001' });
  const results = await Promise.all([finalRequest(), finalRequest()]);
  expect(results.map(result => result.status)).toEqual([200, 200]);
  expect(results[0]!.body.expense.id).toBe(results[1]!.body.expense.id);
  expect(await ExpenseModel.countDocuments({ groupId })).toBe(1);
  const stored = await SplitSessionModel.findById(sessionId).lean();
  expect(stored?.operations.filter(operation => operation.operationKey === 'single-final-000001')).toHaveLength(1);
  expect(stored?.events.filter(event => event.type === 'finalized')).toHaveLength(1);
  await GroupModel.updateOne({ _id: groupId }, { $pull: { memberIds: memberId } });
  expect((await request(app).get('/api/operations/single-claim-000001').set('Authorization', memberAuth)).status).toBe(404);
  expect((await request(app).get(`/api/groups/${groupId}/snapshot`).set('Authorization', memberAuth)).status).toBe(404);
});

test.each(['missing', 'different'])('finalization never adopts an expense with a %s session link', async marker => {
  const created = await create({ amountCents: 1200, units: items.slice(0, 1) });
  const sessionId = created.body.session.id as string;
  expect((await claim(sessionId, 'burger-1', memberAuth, { operationKey: 'collision-claim-001' })).status).toBe(200);
  // Matching financial fields and request ID alone must not turn a manual or
  // unrelated expense into this session's immutable finalization result.
  const collision = await ExpenseModel.create({ groupId, description: 'Dinner', amountCents: 1200, currency: 'CAD',
    paidBy: ownerId, createdBy: ownerId, splitMode: 'custom', shares: [{ userId: memberId, amountCents: 1200 }],
    requestId: `split-session-${sessionId}`,
    ...(marker === 'different' ? { splitSessionId: '000000000000000000000001' } : {}) });
  const updates: unknown[] = [];
  splitSessionEvents.on('updated', update => updates.push(update));
  const finalize = await request(app).post(`/api/split-sessions/${sessionId}/finalize`).set('Authorization', ownerAuth)
    .send({ version: 1, operationKey: 'collision-final-001' });
  expect(finalize.status).toBe(409);
  expect(finalize.body.error).toMatch(/conflicting expense/);
  expect((await request(app).get(`/api/groups/${groupId}/snapshot`).set('Authorization', ownerAuth)).status).toBe(409);
  const stored = await SplitSessionModel.findById(sessionId).lean();
  expect(stored).toMatchObject({ status: 'finalizing', version: 1, sequence: 2 });
  expect(stored?.finalizedExpenseId).toBeUndefined();
  expect(stored?.operations.filter(operation => operation.type === 'finalize')).toHaveLength(1);
  expect(stored?.operations.find(operation => operation.type === 'finalize')?.state).toBe('pending');
  expect(stored?.events.some(event => event.type === 'finalized')).toBe(false);
  expect(updates).toHaveLength(0);
  expect(await ExpenseModel.countDocuments({ groupId })).toBe(1);
  expect((await ExpenseModel.findById(collision.id))?.splitSessionId?.toString()).toBe(
    marker === 'different' ? '000000000000000000000001' : undefined);
});

test('reassignment history is bounded while old retries, remaining claims and finalization still complete', async () => {
  const units = Array.from({ length: 200 }, (_, index) => ({ unitId: `item-${index}`, description: `Item ${index}`, amountCents: 1 }));
  const created = await create({ amountCents: units.length, units });
  expect(created.status).toBe(201);
  const sessionId = created.body.session.id as string;
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
      ...operations.map(operation => ({ sequence: operation.sequence, type: operation.type,
        operationKey: operation.operationKey, actorId: memberId, occurredAt: now }))],
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
  expect(atLimit?.operations.some(operation => operation.operationKey === 'boundary-denied-0001')).toBe(false);

  for (let index = 1; index < units.length; index++) {
    expect((await claim(sessionId, `item-${index}`, memberAuth, {
      version: MAX_REASSIGNMENT_MUTATIONS + index, operationKey: `remaining-claim-${String(index).padStart(3, '0')}`,
    })).status).toBe(200);
  }
  const final = await request(app).post(`/api/split-sessions/${sessionId}/finalize`).set('Authorization', ownerAuth)
    .send({ version: MAX_REASSIGNMENT_MUTATIONS + units.length, operationKey: 'boundary-finalize-001' });
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

test.each([false, true])('snapshot completes a persisted finalize intent after process loss (expense posted: %s)', async posted => {
  const created = await create({ amountCents: 1200, units: items.slice(0, 1) });
  const sessionId = created.body.session.id as string;
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
  const updates: unknown[] = [];
  splitSessionEvents.on('updated', update => updates.push(update));
  const snapshot = await request(app).get(`/api/groups/${groupId}/snapshot`).set('Authorization', memberAuth);
  expect(snapshot.status).toBe(200);
  expect(snapshot.body.splitSessions[0]).toMatchObject({ status: 'finalized', version: 2, sequence: 3 });
  expect(snapshot.body.expenses).toHaveLength(1);
  expect(await ExpenseModel.countDocuments({ groupId })).toBe(1);
  expect(updates).toHaveLength(1);
  const lookup = await request(app).get('/api/operations/crash-final-000001').set('Authorization', ownerAuth);
  expect(lookup.body.operation).toMatchObject({ state: 'complete', version: 2, sequence: 3 });
  const repeat = await request(app).get(`/api/groups/${groupId}/snapshot`).set('Authorization', ownerAuth);
  expect(repeat.status).toBe(200);
  expect(repeat.body.expenses).toHaveLength(1);
  expect(updates).toHaveLength(1);
});
