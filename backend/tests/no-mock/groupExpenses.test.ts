import request from 'supertest';
import { MongoMemoryServer } from 'mongodb-memory-server';
import { createApp } from '../../src/app';
import { ExpenseModel, GroupModel } from '../../src/groupStore';
import { connectDatabase, disconnectDatabase, SessionModel, UserModel } from '../../src/userStore';

let mongo: MongoMemoryServer;
const app = createApp();
let auth: string;
let memberAuth: string;
let outsiderAuth: string;
let groupId: string;
let ids: string[];

beforeAll(async () => {
  mongo = await MongoMemoryServer.create();
  await connectDatabase(mongo.getUri('group-expenses-test'));
}, 120000);
beforeEach(async () => {
  const accounts = [];
  for (const name of ['owner', 'member', 'third', 'outsider']) {
    const res = await request(app).post('/api/auth/register').send({ email: `${name}@example.com`, displayName: name, password: 'correct horse battery' });
    expect(res.status).toBe(201);
    accounts.push(res.body);
  }
  ids = accounts.slice(0, 3).map(account => account.user.id as string);
  auth = `Bearer ${accounts[0].token}`;
  memberAuth = `Bearer ${accounts[1].token}`;
  outsiderAuth = `Bearer ${accounts[3].token}`;
  const group = await GroupModel.create({ name: 'Roommates', currency: 'CAD', ownerId: ids[0]!, memberIds: ids });
  groupId = group.id;
});
afterEach(async () => {
  await Promise.all([ExpenseModel.deleteMany({}), GroupModel.deleteMany({}), UserModel.deleteMany({}), SessionModel.deleteMany({})]);
});
afterAll(async () => { await disconnectDatabase(); await mongo?.stop(); }, 30000);

function bill(overrides: Record<string, unknown> = {}) {
  return { description: 'Dinner', amount: '10.00', paidBy: ids[0], splitMode: 'equal',
    shares: ids.map(userId => ({ userId })), requestId: 'submission-00000001', ...overrides };
}
function post(body: object, token = auth) {
  return request(app).post(`/api/groups/${groupId}/expenses`).set('Authorization', token).send(body);
}
function put(expenseId: string, body: object, token = auth, targetGroupId = groupId) {
  return request(app).put(`/api/groups/${targetGroupId}/expenses/${expenseId}`).set('Authorization', token).send(body);
}

test('meal calculations persist individual pretax amounts and rates and reject tampering', async () => {
  const calculation = { tipRate: '15', members: [
    { userId: ids[0], subtotal: '20.00', extraTaxRate: '0' },
    { userId: ids[1], subtotal: '30.00', extraTaxRate: '10' },
  ] };
  const payload = bill({ splitMode: 'custom', amount: '63.00', subtotal: '50.00', tax: '5.50', tip: '7.50', customCalculation: calculation,
    shares: [{ userId: ids[0], amount: '24.00' }, { userId: ids[1], amount: '39.00' }] });
  const saved = await post(payload);
  expect(saved.status).toBe(201);
  const summary = await request(createApp()).get(`/api/groups/${groupId}/summary`).set('Authorization', memberAuth);
  expect(summary.body.expenses[0].customCalculation.tipRate).toBe('15.00');
  expect(summary.body.expenses[0].customCalculation.members).toHaveLength(2);
  expect((await post(payload)).status).toBe(200);
  const members = calculation.members.map(m => ({ ...m, extraTaxRate: '0' }));
  expect((await post({ ...payload, customCalculation: { ...calculation, members } })).status).toBe(400);
  expect((await post({ ...payload, customCalculation: { ...calculation, tipRate: '101' } })).status).toBe(400);
  const edited = await put(saved.body.expense.id, { ...payload, description: 'Changed', revision: 0 });
  expect(edited.status).toBe(200);
  expect((await put(saved.body.expense.id, { ...payload, description: 'Stale edit', revision: 0 })).status).toBe(409);
});

test('persists tax and tip breakdown, splits final total, and protects retries and edits', async () => {
  const input = bill({ subtotal: '10.00', tax: '1.23', tip: '1.50', amount: '12.73' });
  const saved = await post(input);
  expect(saved.status).toBe(201);
  expect(saved.body.expense).toMatchObject({ subtotalCents: 1000, taxCents: 123, tipCents: 150, amountCents: 1273 });
  expect(saved.body.expense.shares.map((s: { amountCents: number }) => s.amountCents)).toEqual([425, 424, 424]);
  expect((await post(input)).status).toBe(200);
  expect((await post({ ...input, tax: '1.22', tip: '1.51' })).status).toBe(409);
  const changed = await put(saved.body.expense.id, { ...input, revision: 0, tax: '2.00', amount: '13.50' });
  expect(changed.status).toBe(200);
  const summary = await request(createApp()).get(`/api/groups/${groupId}/summary`).set('Authorization', auth);
  expect(summary.body.expenses[0]).toMatchObject({ subtotalCents: 1000, taxCents: 200, tipCents: 150, amountCents: 1350 });
  expect(summary.body.totalCents).toBe(1350);
});

test('rejects inconsistent, negative, overprecision and excessive tax/tip totals', async () => {
  for (const fields of [
    { subtotal: '9.00' }, { subtotal: '10', tax: '-1' }, { subtotal: '10', tip: '0.001' },
    { subtotal: '1000000.00', tax: '0.01', amount: '1000000.01' },
  ]) expect((await post(bill(fields))).status).toBe(400);
  expect(await ExpenseModel.countDocuments()).toBe(0);
});

test('persists both split modes, allows members to add bills and aggregates shares', async () => {
  const first = await post(bill());
  expect(first.status).toBe(201);
  expect(first.body.expense.shares.map((s: { amountCents: number }) => s.amountCents)).toEqual([334, 333, 333]);
  const second = await post(bill({ amount: '5.00', splitMode: 'custom', requestId: 'submission-00000002', shares: [
    { userId: ids[0], amount: '1.25' }, { userId: ids[1], amount: '3.75' },
  ] }), memberAuth);
  expect(second.status).toBe(201);
  // A new app instance reads the persisted data rather than process-local state.
  const summary = await request(createApp()).get(`/api/groups/${groupId}/summary`).set('Authorization', memberAuth);
  expect(summary.status).toBe(200);
  expect(summary.body).toMatchObject({ totalCents: 1500, expenseCount: 2, group: { name: 'Roommates', currency: 'CAD' } });
  expect(summary.body.memberShares.reduce((sum: number, share: { amountCents: number }) => sum + share.amountCents, 0)).toBe(1500);
  expect(summary.body.memberShares.find((s: { userId: string }) => s.userId === ids[1]).amountCents).toBe(708);
  expect(summary.body.expenses).toHaveLength(2);
  expect(await ExpenseModel.countDocuments({ groupId })).toBe(2);
});

test('rejects missing/revoked sessions and hides groups from nonmembers', async () => {
  expect((await request(app).get('/api/groups')).status).toBe(401);
  const groups = await request(app).get('/api/groups').set('Authorization', outsiderAuth);
  expect(groups.body.groups).toEqual([]);
  expect((await post(bill(), outsiderAuth)).status).toBe(404);
  expect((await request(app).get(`/api/groups/${groupId}/summary`).set('Authorization', outsiderAuth)).status).toBe(404);
  expect((await request(app).get('/api/groups/invalid/summary').set('Authorization', auth)).status).toBe(404);
  await request(app).post('/api/auth/logout').set('Authorization', memberAuth);
  expect((await post(bill(), memberAuth)).status).toBe(401);
});

test('returns an empty summary and zero totals for an unused group', async () => {
  const res = await request(app).get(`/api/groups/${groupId}/summary`).set('Authorization', auth);
  expect(res.status).toBe(200);
  expect(res.body).toMatchObject({ totalCents: 0, expenseCount: 0, expenses: [], memberShares: [] });
});

test('rejects invalid amounts, nonmembers, duplicates and mismatched custom shares without saving', async () => {
  for (const overrides of [
    { amount: '0' }, { amount: '1.001' }, { amount: 10 }, { description: ' ' },
    { shares: [] }, { shares: [{ userId: ids[0] }, { userId: ids[0] }] },
    { shares: [{ userId: 'outsider' }] }, { paidBy: 'outsider' }, { requestId: '' },
    { splitMode: 'custom', shares: [{ userId: ids[0], amount: '9.99' }] },
  ]) expect((await post(bill(overrides))).status).toBe(400);
  expect(await ExpenseModel.countDocuments()).toBe(0);
});

test('concurrent retries save a single expense; conflicting reuse is rejected', async () => {
  const results = await Promise.all([post(bill()), post(bill())]);
  expect(results.map(res => res.status).sort()).toEqual([200, 201]);
  expect(results[0]!.body.expense.id).toBe(results[1]!.body.expense.id);
  expect(await ExpenseModel.countDocuments()).toBe(1);
  expect((await post(bill({ description: 'Changed' }))).status).toBe(409);
});

test('manual expense creation reserves the split-session request ID prefix', async () => {
  const response = await post(bill({ requestId: 'split-session-000000000000000000000001' }));
  expect(response.status).toBe(400);
  expect(response.body.error).toMatch(/reserved for finalized split sessions/);
  expect(await ExpenseModel.countDocuments({ groupId })).toBe(0);
});

test('totals include expenses beyond the recent 50 shown in the UI', async () => {
  await ExpenseModel.insertMany(Array.from({ length: 51 }, (_, i) => ({
    groupId, description: `Bill ${i}`, amountCents: 100, currency: 'CAD', paidBy: ids[0], createdBy: ids[0],
    splitMode: 'equal', shares: [{ userId: ids[0], amountCents: 100 }], requestId: `bulk-submission-${i}`,
  })));
  const res = await request(app).get(`/api/groups/${groupId}/summary`).set('Authorization', auth);
  expect(res.status).toBe(200);
  expect(res.body.expenseCount).toBe(51);
  expect(res.body.totalCents).toBe(5100);
  expect(res.body.expenses).toHaveLength(50);
});

test('edits the original expense and recalculates totals without changing its creator or creation time', async () => {
  const original = (await post(bill())).body.expense;
  const updated = await put(original.id, bill({ description: 'Corrected dinner', amount: '12.00', paidBy: ids[1],
    splitMode: 'custom', shares: [{ userId: ids[0], amount: '3.00' }, { userId: ids[1], amount: '9.00' }],
    revision: 0, currency: 'USD', createdBy: ids[2], groupId: '000000000000000000000000',
  }));
  expect(updated.status).toBe(200);
  expect(updated.body.expense).toMatchObject({ id: original.id, description: 'Corrected dinner', amountCents: 1200,
    currency: 'CAD', createdBy: ids[0], createdAt: original.createdAt, paidBy: ids[1], revision: 1, splitMode: 'custom' });
  const summary = await request(createApp()).get(`/api/groups/${groupId}/summary`).set('Authorization', auth);
  expect(summary.body).toMatchObject({ totalCents: 1200, expenseCount: 1 });
  expect(summary.body.expenses[0]).toMatchObject({ id: original.id, canEdit: true, revision: 1 });
  expect(summary.body.memberShares.find((s: { userId: string }) => s.userId === ids[1]).amountCents).toBe(900);
  expect(await ExpenseModel.countDocuments()).toBe(1);
  expect((await ExpenseModel.findById(original.id))?.updatedBy?.toString()).toBe(ids[0]);
  // Retrying the old create request must not add a second bill after an edit.
  expect((await post(bill())).status).toBe(409);
});

test('only the creator or group owner can edit, and summary exposes the same permission', async () => {
  const ownerBill = (await post(bill())).body.expense;
  const memberSummary = await request(app).get(`/api/groups/${groupId}/summary`).set('Authorization', memberAuth);
  expect(memberSummary.body.expenses[0].canEdit).toBe(false);
  expect((await put(ownerBill.id, bill({ amount: '12.00', revision: 0 }), memberAuth)).status).toBe(403);
  const memberBill = (await post(bill(), memberAuth)).body.expense;
  expect((await put(memberBill.id, bill({ amount: '12.00', revision: 0 }), memberAuth)).status).toBe(200);
  expect((await put(memberBill.id, bill({ amount: '14.00', revision: 1 }), auth)).status).toBe(200);
  const stored = await ExpenseModel.findById(memberBill.id);
  expect(stored?.createdBy.toString()).toBe(ids[1]);
  expect(stored?.updatedBy?.toString()).toBe(ids[0]);
});

test('editing requires a session, membership and an expense belonging to the route group', async () => {
  const original = (await post(bill())).body.expense;
  const input = bill({ amount: '12.00', revision: 0 });
  expect((await request(app).put(`/api/groups/${groupId}/expenses/${original.id}`).send(input)).status).toBe(401);
  expect((await put(original.id, input, outsiderAuth)).status).toBe(404);
  expect((await put('not-an-id', input)).status).toBe(404);
  expect((await put('000000000000000000000000', input)).status).toBe(404);
  const other = await GroupModel.create({ name: 'Another group', currency: 'CAD', ownerId: ids[0]!, memberIds: ids });
  expect((await put(original.id, input, auth, other.id)).status).toBe(404);
  expect((await ExpenseModel.findById(original.id))?.amountCents).toBe(1000);
});

test('invalid edits leave the original amount, version and shares intact', async () => {
  const original = (await post(bill())).body.expense;
  for (const overrides of [
    { revision: undefined }, { revision: -1 }, { revision: '0' }, { revision: 0.5 },
    { amount: '0' }, { amount: '1.001' }, { description: ' ' }, { paidBy: 'outsider' },
    { shares: [] }, { shares: [{ userId: ids[0] }, { userId: ids[0] }] },
    { splitMode: 'custom', shares: [{ userId: ids[0], amount: '9.99' }] },
  ]) expect((await put(original.id, bill({ revision: 0, ...overrides }))).status).toBe(400);
  const stored = await ExpenseModel.findById(original.id);
  expect(stored?.amountCents).toBe(1000);
  expect(stored?.__v).toBe(0);
  expect(stored?.shares.reduce((sum, share) => sum + share.amountCents, 0)).toBe(1000);
});

test('concurrent edits cannot overwrite one another; an identical retry performs no extra write', async () => {
  const original = (await post(bill())).body.expense;
  const inputs = [bill({ amount: '12.00', revision: 0 }), bill({ amount: '14.00', revision: 0 })];
  const results = await Promise.all(inputs.map(input => put(original.id, input)));
  expect(results.map(res => res.status).sort()).toEqual([200, 409]);
  const winner = results.findIndex(res => res.status === 200);
  const retry = await put(original.id, inputs[winner]!);
  expect(retry.status).toBe(200);
  expect(retry.body.expense).toMatchObject({ id: original.id, revision: 1, amountCents: winner === 0 ? 1200 : 1400 });
  expect(await ExpenseModel.countDocuments()).toBe(1);
  expect((await ExpenseModel.findById(original.id))?.__v).toBe(1);
});

test('existing records without a version field can still be edited', async () => {
  const original = (await post(bill())).body.expense;
  const stored = await ExpenseModel.findById(original.id);
  await ExpenseModel.collection.updateOne({ _id: stored!._id }, { $unset: { __v: '' } });
  const summary = await request(app).get(`/api/groups/${groupId}/summary`).set('Authorization', auth);
  expect(summary.body.expenses[0].revision).toBe(0);
  const updated = await put(original.id, bill({ amount: '11.00', revision: 0 }));
  expect(updated.status).toBe(200);
  expect(updated.body.expense).toMatchObject({ id: original.id, amountCents: 1100, revision: 1 });
});

function percentageBill(overrides: Record<string, unknown> = {}) {
  return bill({ splitMode: 'percentage', shares: [
    { userId: ids[0], percentage: '33.33' }, { userId: ids[1], percentage: '33.33' }, { userId: ids[2], percentage: '33.34' },
  ], ...overrides });
}

describe('Unmocked: POST /api/groups/:groupId/expenses (percentage)', () => {
  // Input: valid 33.33/33.33/33.34 split plus untrusted client amounts.
  // Expected: 201, canonical integer cents and original percentages persist in real MongoDB.
  test('calculates and persists percentages independently of caller-supplied amounts', async () => {
    const shares = [
      { userId: ids[0], percentage: '33.33', amount: '999' },
      { userId: ids[1], percentage: '33.33', percentageBasisPoints: 10000 },
      { userId: ids[2], percentage: '33.34', amountCents: 0 },
    ];
    const response = await post(percentageBill({ shares }));
    expect(response.status).toBe(201);
    expect(response.body.expense).toMatchObject({ splitMode: 'percentage', amountCents: 1000 });
    expect(response.body.expense.shares).toEqual([
      { userId: ids[0], percentageBasisPoints: 3333, amountCents: 333 },
      { userId: ids[1], percentageBasisPoints: 3333, amountCents: 333 },
      { userId: ids[2], percentageBasisPoints: 3334, amountCents: 334 },
    ]);
    const summary = await request(createApp()).get(`/api/groups/${groupId}/summary`).set('Authorization', memberAuth);
    expect(summary.status).toBe(200);
    expect(summary.body).toMatchObject({ totalCents: 1000, expenseCount: 1 });
    expect(summary.body.expenses[0].shares).toEqual(response.body.expense.shares);
    const saved = await ExpenseModel.findById(response.body.expense.id);
    expect(saved?.shares.map(share => share.percentageBasisPoints)).toEqual([3333, 3333, 3334]);
  });

  // Input: same request and reordered shares. Expected: 200 and the same saved ID, not a duplicate.
  test('replays identical percentages even when member order changes', async () => {
    const input = percentageBill();
    const first = await post(input);
    const retry = await post({ ...input, shares: [...input.shares].reverse() });
    expect(first.status).toBe(201);
    expect(retry.status).toBe(200);
    expect(retry.body.expense.id).toBe(first.body.expense.id);
    expect(await ExpenseModel.countDocuments()).toBe(1);
  });

  // Input: 1-cent bill, 50/50 then 60/40 with the same request ID; rounded cents are identical.
  // Expected: 409 because the original percentages are part of the saved request's content.
  test('rejects changed percentages even when they round to the same cents', async () => {
    const first = await post(percentageBill({ amount: '0.01', shares: [
      { userId: ids[0], percentage: '50' }, { userId: ids[1], percentage: '50' },
    ] }));
    expect(first.status).toBe(201);
    const retry = await post(percentageBill({ amount: '0.01', shares: [
      { userId: ids[0], percentage: '60' }, { userId: ids[1], percentage: '40' },
    ] }));
    expect(retry.status).toBe(409);
    expect((await ExpenseModel.findById(first.body.expense.id))?.shares.map(share => share.percentageBasisPoints)).toEqual([5000, 5000]);
    expect(await ExpenseModel.countDocuments()).toBe(1);
  });

  // Input: invalid percentage or non-100% total. Expected: 400, no expense inserted.
  test.each(['99.99', '100.01', '-1', '1.001', '', 100, null])('rejects invalid single-participant percentages %s', async percentage => {
    const response = await post(percentageBill({ shares: [{ userId: ids[0], percentage }] }));
    expect(response.status).toBe(400);
    expect(await ExpenseModel.countDocuments()).toBe(0);
  });

  // Input: selected member with zero percent. Expected: 201; zero share is retained without any rounding cent.
  test('retains a zero-percent participant on a tiny bill', async () => {
    const response = await post(percentageBill({ amount: '0.01', shares: [
      { userId: ids[0], percentage: '0' }, { userId: ids[1], percentage: '100' },
    ] }));
    expect(response.status).toBe(201);
    expect(response.body.expense.shares).toEqual([
      { userId: ids[0], percentageBasisPoints: 0, amountCents: 0 },
      { userId: ids[1], percentageBasisPoints: 10000, amountCents: 1 },
    ]);
  });
});

describe('Unmocked: PUT /api/groups/:groupId/expenses/:expenseId (percentage)', () => {
  // Input: existing equal bill changed to percentage; later total change with the same percentages.
  // Expected: 200 each, same ID, updated revision/allocations and preserved original percentages.
  test('switches existing split modes and recalculates percentages when the total changes', async () => {
    const original = (await post(bill())).body.expense;
    const changed = await put(original.id, percentageBill({ revision: 0 }));
    expect(changed.status).toBe(200);
    expect(changed.body.expense).toMatchObject({ id: original.id, splitMode: 'percentage', revision: 1 });
    const resized = await put(original.id, percentageBill({ revision: 1, amount: '12.00' }));
    expect(resized.status).toBe(200);
    expect(resized.body.expense.shares.map((share: { amountCents: number }) => share.amountCents)).toEqual([400, 400, 400]);
    expect(resized.body.expense.shares.map((share: { percentageBasisPoints: number }) => share.percentageBasisPoints)).toEqual([3333, 3333, 3334]);
    const custom = await put(original.id, bill({ revision: 2, splitMode: 'custom', shares: [{ userId: ids[0], amount: '10.00' }] }));
    expect(custom.status).toBe(200);
    expect(custom.body.expense.shares).toEqual([{ userId: ids[0], amountCents: 1000 }]);
    expect((await ExpenseModel.findById(original.id))?.shares[0]?.percentageBasisPoints).toBeUndefined();
    expect(await ExpenseModel.countDocuments()).toBe(1);
  });

  // Input: percentage-only edit that leaves rounded cents unchanged, then an identical retry.
  // Expected: first edit advances revision; retry does not advance it again.
  test('persists percentage-only changes and replays identical edits', async () => {
    const oldInput = percentageBill({ amount: '0.01', shares: [
      { userId: ids[0], percentage: '50' }, { userId: ids[1], percentage: '50' },
    ] });
    const original = (await post(oldInput)).body.expense;
    const edit = percentageBill({ revision: 0, amount: '0.01', shares: [
      { userId: ids[0], percentage: '60' }, { userId: ids[1], percentage: '40' },
    ] });
    const changed = await put(original.id, edit);
    expect(changed.status).toBe(200);
    expect(changed.body.expense.revision).toBe(1);
    expect(changed.body.expense.shares.map((share: { percentageBasisPoints: number }) => share.percentageBasisPoints)).toEqual([6000, 4000]);
    const retry = await put(original.id, edit);
    expect(retry.status).toBe(200);
    expect(retry.body.expense.revision).toBe(1);
  });

  // Input: invalid total percentage on edit. Expected: 400; no amount, shares or revision changes.
  test('preserves the saved expense when percentages do not total 100%', async () => {
    const original = (await post(percentageBill())).body.expense;
    const before = await ExpenseModel.findById(original.id).lean();
    const response = await put(original.id, percentageBill({ revision: 0, shares: [{ userId: ids[0], percentage: '90' }] }));
    expect(response.status).toBe(400);
    expect(await ExpenseModel.findById(original.id).lean()).toEqual(before);
  });
});
