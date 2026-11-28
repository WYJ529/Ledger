import { required, requiredString } from '../helpers/required';
import request from 'supertest';
import { createApp } from '../../src/app';
import { ExpenseModel, GroupModel } from '../../src/groupStore';
import { mongoUserStore, mongoSessionStore, SessionModel, UserModel } from '../../src/userStore';
import { useIsolatedMongo } from '../helpers/isolatedMongo';
import { startSession } from '../../src/session';
import { groupMembershipEvents } from '../../src/groupMembershipEvents';
import { MAX_AMOUNT_MINOR } from '../../src/money';
import { testsRejectInvalidSessions } from '../helpers/sessionRejection';
import { expectEachRejected, operatorShapedIds } from '../helpers/operatorInput';

// Real HTTP routing, session verification and MongoDB. No mocked components.
const app = createApp();
let auth: string;
let memberAuth: string;
let outsiderAuth: string;
let groupId: string;
let ids: string[];
let sharedExpenseId = '';

useIsolatedMongo('group-expenses-test', [ExpenseModel, GroupModel, UserModel, SessionModel]);
beforeEach(async () => {
  const accounts = [];
  for (const name of ['owner', 'member', 'third', 'outsider']) {
    const user = await mongoUserStore.create({ email: `${name}@example.com`, displayName: name });
    accounts.push(await startSession(mongoSessionStore, user));
  }
  ids = accounts.slice(0, 3).map(account => account.user.id);
  auth = `Bearer ${required(accounts[0]).token}`;
  memberAuth = `Bearer ${required(accounts[1]).token}`;
  outsiderAuth = `Bearer ${required(accounts[3]).token}`;
  const group = await GroupModel.create({ name: 'Roommates', currency: 'CAD', ownerId: required(ids[0]), memberIds: ids });
  groupId = group.id;
});

function bill(overrides: Record<string, unknown> = {}) {
  return { description: 'Dinner', amount: '10.00', paidBy: ids[0], splitMode: 'equal',
    shares: ids.map(userId => ({ userId })), requestId: 'submission-00000001', ...overrides };
}
function post(body: object, token = auth): request.Test {
  return request(app).post(`/api/groups/${groupId}/expenses`).set('Authorization', token).send(body);
}
function put(expenseId: unknown, body: object, token = auth, targetGroupId: unknown = groupId): request.Test {
  return request(app).put(`/api/groups/${requiredString(targetGroupId)}/expenses/${requiredString(expenseId)}`).set('Authorization', token).send(body);
}
function summary(token = auth): request.Test {
  return request(createApp()).get(`/api/groups/${groupId}/summary`).set('Authorization', token);
}
function percentageBill(overrides: Record<string, unknown> = {}) {
  return bill({ splitMode: 'percentage', shares: [
    { userId: ids[0], percentage: '33.33' }, { userId: ids[1], percentage: '33.33' }, { userId: ids[2], percentage: '33.34' },
  ], ...overrides });
}
function adjustedBill(overrides: Record<string, unknown> = {}) {
  return bill({ amount: '40.00', splitMode: 'adjusted', shares: [
    { userId: ids[0] }, { userId: ids[1], adjustment: '8.00' }, { userId: ids[2], adjustment: '-2.00' },
  ], ...overrides });
}
// A custom meal bill: CAD 20 and 30 before tax, 10% extra tax for the second member, 15% tip.
function mealBill() {
  const calculation = { tipRate: '15', members: [
    { userId: ids[0], subtotal: '20.00', extraTaxRate: '0' }, { userId: ids[1], subtotal: '30.00', extraTaxRate: '10' },
  ] };
  const payload = bill({ splitMode: 'custom', amount: '63.00', subtotal: '50.00', tax: '5.50', tip: '7.50', customCalculation: calculation,
    shares: [{ userId: ids[0], amount: '24.00' }, { userId: ids[1], amount: '39.00' }] });
  return { calculation, payload };
}
const groupState = () => Promise.all([GroupModel.find().lean(), ExpenseModel.find().lean()]);
async function prepareSharedExpense() {
  const response = await post(bill({ requestId: 'session-check-bill-01' }));
  expect(response.status).toBe(201);
  sharedExpenseId = requiredString(response.body.expense.id);
}

// Interface GET /api/groups
describe('Unmocked: GET /api/groups', () => {
  testsRejectInvalidSessions((): request.Test => request(app).get('/api/groups'), () => auth, groupState);

  // Input: list from a member and from an authenticated nonmember
  // Expected status code: 200
  // Expected behavior: only groups that contain the caller are listed
  // Expected output: { groups: [Roommates] } for the member and { groups: [] } for the nonmember
  test('Lists only the groups the caller belongs to', async () => {
    const mine = await request(app).get('/api/groups').set('Authorization', memberAuth);
    expect(mine.status).toBe(200);
    expect(mine.body.groups).toEqual([expect.objectContaining({ id: groupId, name: 'Roommates', memberCount: 3 })]);
    expect((await request(app).get('/api/groups').set('Authorization', outsiderAuth)).body.groups).toEqual([]);
  });
});

// Interface GET /api/groups/:groupId/summary
describe('Unmocked: GET /api/groups/:groupId/summary', () => {

  // Input: a legacy bill whose participant account and imported-by metadata are absent.
  // Expected status code: 200.
  // Expected behavior: retain the bill and represent the missing member in the roster.
  // Expected output: former-member label and an unimported expense.
  test('keeps historical shares when an account has been removed', async () => {
    const created = await post(bill());
    await UserModel.deleteOne({ _id: ids[1] });
    await ExpenseModel.updateOne({ _id: created.body.expense.id }, { $unset: { importedBy: '' } });
    const response = await summary();
    expect(response.status).toBe(200);
    expect(response.body.group.members).toContainEqual({ id: ids[1], displayName: 'Former member', role: 'member' });
    expect(response.body.expenses[0].importedByMe).toBe(false);
  });

  testsRejectInvalidSessions((): request.Test => request(app).get(`/api/groups/${groupId}/summary`), () => auth, groupState);

  // Input: summary of a group without expenses
  // Expected status code: 200
  // Expected behavior: totals are zero
  // Expected output: { totalCents: 0, expenseCount: 0, expenses: [], memberShares: [] }
  test('Returns an empty summary for an unused group', async () => {
    const res = await request(app).get(`/api/groups/${groupId}/summary`).set('Authorization', auth);
    expect(res.status).toBe(200);
    expect(res.body).toMatchObject({ totalCents: 0, expenseCount: 0, expenses: [], memberShares: [] });
  });

  // Input: an equal bill from the owner and a custom bill from a member, read through a new app instance
  // Expected status code: 200
  // Expected behavior: totals and member shares are aggregated from MongoDB, not process state
  // Expected output: { totalCents: 1500, expenseCount: 2, memberShares } summing to 1500
  test('Aggregates persisted bills and member shares', async () => {
    expect((await post(bill())).status).toBe(201);
    expect((await post(bill({ amount: '5.00', splitMode: 'custom', requestId: 'submission-00000002', shares: [
      { userId: ids[0], amount: '1.25' }, { userId: ids[1], amount: '3.75' },
    ] }), memberAuth)).status).toBe(201);
    const res = await summary(memberAuth);
    expect(res.status).toBe(200);
    expect(res.body).toMatchObject({ totalCents: 1500, expenseCount: 2, group: { name: 'Roommates', currency: 'CAD' } });
    expect(res.body.memberShares.reduce((sum: number, share: { amountCents: number }) => sum + share.amountCents, 0)).toBe(1500);
    expect(res.body.memberShares.find((s: { userId: string }) => s.userId === ids[1]).amountCents).toBe(708);
    expect(res.body.expenses).toHaveLength(2);
  });

  // Input: group with 51 bills
  // Expected status code: 200
  // Expected behavior: totals include every bill while the list shows the latest 50
  // Expected output: { expenseCount: 51, totalCents: 5100, expenses: 50 items }
  test('Totals include expenses beyond the recent 50 shown in the UI', async () => {
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

  // Input: summary requested by a nonmember, with an invalid ID, and with an operator-shaped ID
  // Expected status code: 404
  // Expected behavior: no group data is disclosed
  // Expected output: { error: "Group not found." }
  test('Hides the group from nonmembers and invalid identifiers', async () => {
    expect((await request(app).get(`/api/groups/${groupId}/summary`).set('Authorization', outsiderAuth)).status).toBe(404);
    expect((await request(app).get('/api/groups/invalid/summary').set('Authorization', auth)).status).toBe(404);
    const operator = encodeURIComponent('{"$ne":null}');
    expect((await request(app).get(`/api/groups/${operator}/summary`).set('Authorization', auth)).status).toBe(404);
  });
});

// Interface POST /api/groups/:groupId/expenses
describe('Unmocked: POST /api/groups/:groupId/expenses', () => {

  // Input: an authenticated expense submission without a JSON body.
  // Expected status code: 400.
  // Expected behavior: reject the absent expense before storage.
  // Expected output: the required-description diagnostic.
  test('requires a body to create an expense', async () => {
    const response = await request(app).post(`/api/groups/${groupId}/expenses`).set('Authorization', auth);
    expect(response.status).toBe(400);
    expect(response.body.error).toContain('description');
    expect(await ExpenseModel.countDocuments()).toBe(0);
  });


  // Input: invalid splits and adjusted/meal calculations on a valid bill.
  // Expected status code: 400.
  // Expected behavior: reject the explanation or allocation before writing an expense.
  // Expected output: a field-specific error and no expense.
  test('rejects invalid share and meal calculations', async () => {
    for (const [change, error] of [
      [{ description: null }, 'description'], [{ splitMode: 'other' }, 'splitting'],
      [{ shares: [null] }, 'member ID'], [{ shares: [{ userId: 4 }] }, 'member ID'],
      ...[42, 'plus'].map(adjustment => [{ splitMode: 'adjusted', shares: [{ userId: ids[0], adjustment }] }, 'valid adjustment']),
      [{ splitMode: 'adjusted', shares: [{ userId: ids[0], adjustment: '11' }] }, 'larger than'],
      ...[[], undefined, Array(101).fill({})].map(members => [{ customCalculation: { tipRate: '0', members } }, '1 and 100']),
      ...[[null], [{ userId: 'other' }], [0, 0].map(() => ({ userId: ids[0], subtotal: '1', extraTaxRate: '0' }))].map(
        members => [{ customCalculation: { tipRate: '0', members } }, 'unique group members']),
      [{ customCalculation: { tipRate: '0', members: [{ userId: ids[0], subtotal: '0', extraTaxRate: '0' }] } }, 'Meal total'],
    ] as [Record<string, unknown>, string][]) {
      const response = await post(bill(change));
      expect(response.status).toBe(400);
      expect(response.body.error).toContain(error);
    }
    expect(await ExpenseModel.countDocuments()).toBe(0);
  });

  // Input: an adjusted bill with blank adjustments and a legacy retry without explicit subtotal, tax or tip.
  // Expected status code: 201, then 200.
  // Expected behavior: treat blank adjustments and absent legacy extras as zero.
  // Expected output: the original expense and an unchanged expense count.
  test('replays older expense records with omitted extras', async () => {
    const payload = bill({ splitMode: 'adjusted', shares: ids.map(userId => ({ userId, adjustment: '  ' })) });
    const created = await post(payload);
    expect(created.status).toBe(201);
    await ExpenseModel.updateOne({ _id: created.body.expense.id }, { $unset: { subtotalCents: '', taxCents: '', tipCents: '' } });
    const replay = await post(payload);
    expect(replay.status).toBe(200);
    expect(replay.body.expense.id).toBe(created.body.expense.id);
    expect(await ExpenseModel.countDocuments()).toBe(1);
  });

  testsRejectInvalidSessions((): request.Test => request(app).post(`/api/groups/${groupId}/expenses`).send(bill()), () => auth, groupState);

  // Input: the largest allowed amount in CAD or JPY with a percentage split, then one minor unit more
  // Expected status code: 201, then 400
  // Expected behavior: the ceiling persists exactly; larger amounts are rejected
  // Expected output: { expense } with amountCents MAX_AMOUNT_MINOR
  test.each(['CAD', 'JPY'])('Persists the monetary ceiling and rejects the next minor unit for %s', async currency => {
    await GroupModel.updateOne({ _id: groupId }, { currency });
    const amount = currency === 'JPY' ? '1000000000000' : '10000000000.00';
    const tooLarge = currency === 'JPY' ? '1000000000001' : '10000000000.01';
    const saved = await post(bill({ amount: '  ' + amount + '\n', splitMode: 'percentage',
      shares: [{ userId: ids[0], percentage: ' 33.33 ' }, { userId: ids[1], percentage: '66.67' }] }));
    expect(saved.status).toBe(201);
    expect(saved.body.expense).toMatchObject({ amountCents: MAX_AMOUNT_MINOR, currency });
    expect(saved.body.expense.shares.map((share: { amountCents: number }) => share.amountCents)).toEqual([333300000000, 666700000000]);
    expect((await ExpenseModel.findById(requiredString(saved.body.expense.id)))?.amountCents).toBe(MAX_AMOUNT_MINOR);
    expect((await post(bill({ amount: tooLarge, requestId: 'submission-too-large' }))).status).toBe(400);
    expect((await summary()).body.totalCents).toBe(MAX_AMOUNT_MINOR);
    expect(await ExpenseModel.countDocuments()).toBe(1);
  });

  // Input: equal CAD 10 bill from the owner and a custom bill from a member
  // Expected status code: 201
  // Expected behavior: any member may add bills; equal shares are split to the cent
  // Expected output: { expense } with shares 334, 333, 333
  test('Lets members add equal and custom bills', async () => {
    const first = await post(bill());
    expect(first.status).toBe(201);
    expect(first.body.expense.shares.map((s: { amountCents: number }) => s.amountCents)).toEqual([334, 333, 333]);
    const second = await post(bill({ amount: '5.00', splitMode: 'custom', requestId: 'submission-00000002', shares: [
      { userId: ids[0], amount: '1.25' }, { userId: ids[1], amount: '3.75' },
    ] }), memberAuth);
    expect(second.status).toBe(201);
    expect(await ExpenseModel.countDocuments({ groupId })).toBe(2);
  });

  // Input: subtotal 10.00, tax 1.23, tip 1.50, total 12.73; retried, then retried with different tax/tip
  // Expected status code: 201, 200, then 409
  // Expected behavior: the breakdown persists; the final total is split; a changed retry is refused
  // Expected output: { expense } with subtotalCents 1000, taxCents 123, tipCents 150
  test('Persists the tax and tip breakdown and protects retries', async () => {
    const input = bill({ subtotal: '10.00', tax: '1.23', tip: '1.50', amount: '12.73' });
    const saved = await post(input);
    expect(saved.status).toBe(201);
    expect(saved.body.expense).toMatchObject({ subtotalCents: 1000, taxCents: 123, tipCents: 150, amountCents: 1273 });
    expect(saved.body.expense.shares.map((s: { amountCents: number }) => s.amountCents)).toEqual([425, 424, 424]);
    expect((await post(input)).status).toBe(200);
    expect((await post({ ...input, tax: '1.22', tip: '1.51' })).status).toBe(409);
  });

  // Input: subtotal that does not reconcile, negative tax, sub-cent tip, or a total over the ceiling
  // Expected status code: 400
  // Expected behavior: nothing is saved
  // Expected output: error message
  test('Rejects inconsistent or invalid tax and tip totals', async () => {
    for (const fields of [
      { subtotal: '9.00' }, { subtotal: '10', tax: '-1' }, { subtotal: '10', tip: '0.001' },
      { subtotal: '10000000000.00', tax: '0.01', amount: '10000000000.01' },
    ]) expect((await post(bill(fields))).status).toBe(400);
    expect(await ExpenseModel.countDocuments()).toBe(0);
  });

  // Input: custom meal calculation with per-member subtotals, tax rates and a tip rate; then tampered calculations
  // Expected status code: 201, replay 200, tampering 400
  // Expected behavior: the calculation is stored for editing and must match the submitted shares
  // Expected output: summary shows tipRate "15.00" and two calculation members
  test('Persists a meal calculation and rejects tampering', async () => {
    const { calculation, payload } = mealBill();
    expect((await post(payload)).status).toBe(201);
    const res = await summary(memberAuth);
    expect(res.body.expenses[0].customCalculation.tipRate).toBe('15.00');
    expect(res.body.expenses[0].customCalculation.members).toHaveLength(2);
    expect((await post(payload)).status).toBe(200);
    const members = calculation.members.map(m => ({ ...m, extraTaxRate: '0' }));
    expect((await post({ ...payload, customCalculation: { ...calculation, members } })).status).toBe(400);
    expect((await post({ ...payload, customCalculation: { ...calculation, tipRate: '101' } })).status).toBe(400);
  });

  // Input: zero, sub-cent or numeric amount, blank description, empty/duplicate/foreign shares, empty requestId, unbalanced custom split
  // Expected status code: 400
  // Expected behavior: nothing is saved
  // Expected output: error message
  test('Rejects invalid bills without saving', async () => {
    for (const overrides of [
      { amount: '0' }, { amount: '1.001' }, { amount: 10 }, { description: ' ' },
      { shares: [] }, { shares: [{ userId: ids[0] }, { userId: ids[0] }] },
      { shares: [{ userId: 'outsider' }] }, { paidBy: 'outsider' }, { requestId: '' },
      { splitMode: 'custom', shares: [{ userId: ids[0], amount: '9.99' }] },
    ]) expect((await post(bill(overrides))).status).toBe(400);
    expect(await ExpenseModel.countDocuments()).toBe(0);
  });

  // Input: requestId given as a query operator object or array
  // Expected status code: 400
  // Expected behavior: operators are never used in a MongoDB filter; nothing is saved
  // Expected output: error message
  test('Rejects query operators as submission IDs', async () => {
    await expectEachRejected(operatorShapedIds('submission-00000001'), (requestId): request.Test => post(bill({ requestId })));
    expect(await ExpenseModel.countDocuments()).toBe(0);
  });

  // Input: the same bill submitted twice at once, then the same requestId with another description
  // Expected status code: 201 and 200, then 409
  // Expected behavior: one expense is saved
  // Expected output: the same expense ID for both retries
  test('Concurrent retries save a single expense', async () => {
    const results = await Promise.all([post(bill()), post(bill())]);
    expect(results.map(({ status }: { status: number }) => status).sort()).toEqual([200, 201]);
    expect(required(results[0]).body.expense.id).toBe(required(results[1]).body.expense.id);
    expect(await ExpenseModel.countDocuments()).toBe(1);
    expect((await post(bill({ description: 'Changed' }))).status).toBe(409);
  });

  // Input: requestId with the reserved "split-session-" prefix
  // Expected status code: 400
  // Expected behavior: manual bills cannot impersonate finalized Let's Go Dutch results
  // Expected output: error mentioning finalized split sessions
  test('Reserves the split-session request ID prefix', async () => {
    const response = await post(bill({ requestId: 'split-session-000000000000000000000001' }));
    expect(response.status).toBe(400);
    expect(response.body.error).toMatch(/reserved for finalized split sessions/);
    expect(await ExpenseModel.countDocuments({ groupId })).toBe(0);
  });

  // Input: bill from a nonmember, and from a member whose session was logged out
  // Expected status code: 404, then 401
  // Expected behavior: nothing is saved
  // Expected output: error message
  test('Rejects nonmembers and revoked sessions', async () => {
    expect((await post(bill(), outsiderAuth)).status).toBe(404);
    await request(app).post('/api/auth/logout').set('Authorization', memberAuth);
    expect((await post(bill(), memberAuth)).status).toBe(401);
    expect(await ExpenseModel.countDocuments()).toBe(0);
  });

  // Input: 33.33/33.33/33.34 percentage split with untrusted client amounts
  // Expected status code: 201
  // Expected behavior: integer cents are calculated on the server; the percentages persist
  // Expected output: { expense } with shares 333, 333, 334 and basis points 3333, 3333, 3334
  test('Calculates percentages independently of caller-supplied amounts', async () => {
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
    const res = await summary(memberAuth);
    expect(res.body).toMatchObject({ totalCents: 1000, expenseCount: 1 });
    expect(res.body.expenses[0].shares).toEqual(response.body.expense.shares);
    const saved = await ExpenseModel.findById(requiredString(response.body.expense.id));
    expect(saved?.shares.map(share => share.percentageBasisPoints)).toEqual([3333, 3333, 3334]);
  });

  // Input: the same percentage bill with the members in reverse order
  // Expected status code: 201, then 200
  // Expected behavior: the retry is recognised as identical
  // Expected output: the same expense ID
  test('Replays identical percentages even when member order changes', async () => {
    const input = percentageBill();
    const first = await post(input);
    const retry = await post({ ...input, shares: [...input.shares].reverse() });
    expect(first.status).toBe(201);
    expect(retry.status).toBe(200);
    expect(retry.body.expense.id).toBe(first.body.expense.id);
    expect(await ExpenseModel.countDocuments()).toBe(1);
  });

  // Input: 1-cent bill at 50/50, then 60/40 with the same requestId (same rounded cents)
  // Expected status code: 201, then 409
  // Expected behavior: the original percentages are part of the saved request
  // Expected output: stored basis points stay 5000/5000
  test('Rejects changed percentages even when they round to the same cents', async () => {
    const first = await post(percentageBill({ amount: '0.01', shares: [
      { userId: ids[0], percentage: '50' }, { userId: ids[1], percentage: '50' },
    ] }));
    expect(first.status).toBe(201);
    const retry = await post(percentageBill({ amount: '0.01', shares: [
      { userId: ids[0], percentage: '60' }, { userId: ids[1], percentage: '40' },
    ] }));
    expect(retry.status).toBe(409);
    expect((await ExpenseModel.findById(requiredString(first.body.expense.id)))?.shares.map(share => share.percentageBasisPoints)).toEqual([5000, 5000]);
    expect(await ExpenseModel.countDocuments()).toBe(1);
  });

  // Input: single participant with an invalid percentage or a total other than 100%
  // Expected status code: 400
  // Expected behavior: nothing is saved
  // Expected output: error message
  test.each(['99.99', '100.01', '-1', '1.001', '', 100, null])('Rejects invalid single-participant percentage %s', async percentage => {
    const response = await post(percentageBill({ shares: [{ userId: ids[0], percentage }] }));
    expect(response.status).toBe(400);
    expect(await ExpenseModel.countDocuments()).toBe(0);
  });

  // Input: 1-cent bill at 0% / 100%
  // Expected status code: 201
  // Expected behavior: the zero-percent member is kept with no rounding cent
  // Expected output: shares 0 and 1 cent
  test('Retains a zero-percent participant on a tiny bill', async () => {
    const response = await post(percentageBill({ amount: '0.01', shares: [
      { userId: ids[0], percentage: '0' }, { userId: ids[1], percentage: '100' },
    ] }));
    expect(response.status).toBe(201);
    expect(response.body.expense.shares).toEqual([
      { userId: ids[0], percentageBasisPoints: 0, amountCents: 0 },
      { userId: ids[1], percentageBasisPoints: 10000, amountCents: 1 },
    ]);
  });

  // Input: CAD 40 with +8.00 for one member and -2.00 for another, plus untrusted client amounts
  // Expected status code: 201
  // Expected behavior: the remaining 34.00 is split evenly; adjustments persist
  // Expected output: shares 1134, 1933, 933 with adjustmentCents 0, 800, -200
  test('Calculates shares from adjustments', async () => {
    const shares = [
      { userId: ids[0], amount: '999' }, { userId: ids[1], adjustment: '8.00', amountCents: 0 }, { userId: ids[2], adjustment: '-2.00' },
    ];
    const response = await post(adjustedBill({ shares }));
    expect(response.status).toBe(201);
    expect(response.body.expense).toMatchObject({ splitMode: 'adjusted', amountCents: 4000 });
    expect(response.body.expense.shares).toEqual([
      { userId: ids[0], adjustmentCents: 0, amountCents: 1134 },
      { userId: ids[1], adjustmentCents: 800, amountCents: 1933 },
      { userId: ids[2], adjustmentCents: -200, amountCents: 933 },
    ]);
    const res = await summary(memberAuth);
    expect(res.body).toMatchObject({ totalCents: 4000, expenseCount: 1 });
    expect(res.body.expenses[0].shares).toEqual(response.body.expense.shares);
    expect((await ExpenseModel.findById(requiredString(response.body.expense.id)))?.shares.map(share => share.adjustmentCents)).toEqual([0, 800, -200]);
  });

  // Input: adjusted bill retried with reordered members, then with a different adjustment
  // Expected status code: 200, then 409
  // Expected behavior: identical adjustments replay; changed ones are refused
  // Expected output: the same expense ID for the replay
  test('Replays identical adjustments and rejects changed ones', async () => {
    const input = adjustedBill();
    const first = await post(input);
    const retry = await post({ ...input, shares: [...input.shares].reverse() });
    expect(retry.status).toBe(200);
    expect(retry.body.expense.id).toBe(first.body.expense.id);
    const changed = await post(adjustedBill({ shares: [{ userId: ids[0] }, { userId: ids[1], adjustment: '9.00' }, { userId: ids[2] }] }));
    expect(changed.status).toBe(409);
    expect(await ExpenseModel.countDocuments()).toBe(1);
  });

  // Input: a deduction larger than the member's equal share
  // Expected status code: 400
  // Expected behavior: nothing is saved
  // Expected output: error mentioning "at least 0"
  test('Rejects adjustments that would make a share negative', async () => {
    const response = await post(adjustedBill({ shares: [{ userId: ids[0], adjustment: '-50.00' }, { userId: ids[1] }] }));
    expect(response.status).toBe(400);
    expect(response.body.error).toMatch(/at least 0/);
    expect(await ExpenseModel.countDocuments()).toBe(0);
  });

  // Input: KRW group with a whole-won bill, then a fractional won amount
  // Expected status code: 201, then 400
  // Expected behavior: zero-decimal currencies store whole units only
  // Expected output: shares 3334, 3333, 3333; error mentioning "whole amount"
  test('Stores whole won and rejects fractional amounts', async () => {
    const wonGroup = await GroupModel.create({ name: 'Seoul trip', currency: 'KRW', ownerId: required(ids[0]), memberIds: ids });
    const postWon = (body: object): request.Test => request(app).post(`/api/groups/${wonGroup.id}/expenses`).set('Authorization', auth).send(body);
    const created = await postWon(bill({ amount: '10000' }));
    expect(created.status).toBe(201);
    expect(created.body.expense).toMatchObject({ currency: 'KRW', amountCents: 10000 });
    expect(created.body.expense.shares.map((s: { amountCents: number }) => s.amountCents)).toEqual([3334, 3333, 3333]);
    const fractional = await postWon(bill({ amount: '100.50', requestId: 'submission-won-00002' }));
    expect(fractional.status).toBe(400);
    expect(fractional.body.error).toMatch(/whole amount/);
    expect(await ExpenseModel.countDocuments({ groupId: wonGroup._id })).toBe(1);
  });
});

// Interface PUT /api/groups/:groupId/expenses/:expenseId
describe('Unmocked: PUT /api/groups/:groupId/expenses/:expenseId', () => {

  // Input: an expense edit without any body.
  // Expected status code: 400.
  // Expected behavior: refuse to edit the saved expense.
  // Expected output: validation error and unchanged revision.
  test('rejects an empty expense edit', async () => {
    const id = (await post(bill())).body.expense.id;
    const response = await request(app).put(`/api/groups/${groupId}/expenses/${id}`).set('Authorization', auth);
    expect(response.status).toBe(400);
    expect((await ExpenseModel.findById(id).lean())?.__v).toBe(0);
  });

  testsRejectInvalidSessions((): request.Test => request(app).put(`/api/groups/${groupId}/expenses/${sharedExpenseId}`).send({ ...bill(), revision: 0 }),
    () => auth, groupState, prepareSharedExpense);

  // Input: edit of a bill at revision 0 that also tries to change currency, creator and group
  // Expected status code: 200; retrying the original create afterwards gives 409
  // Expected behavior: the same expense is updated and totals recalculated; creator, creation time and currency are kept
  // Expected output: { expense } with revision 1 and the new amount
  test('Edits the original expense and recalculates totals', async () => {
    const original = (await post(bill())).body.expense;
    const updated = await put(original.id, bill({ description: 'Corrected dinner', amount: '12.00', paidBy: ids[1],
      splitMode: 'custom', shares: [{ userId: ids[0], amount: '3.00' }, { userId: ids[1], amount: '9.00' }],
      revision: 0, currency: 'USD', createdBy: ids[2], groupId: '000000000000000000000000',
    }));
    expect(updated.status).toBe(200);
    expect(updated.body.expense).toMatchObject({ id: original.id, description: 'Corrected dinner', amountCents: 1200,
      currency: 'CAD', createdBy: ids[0], createdAt: original.createdAt, paidBy: ids[1], revision: 1, splitMode: 'custom' });
    const res = await summary();
    expect(res.body).toMatchObject({ totalCents: 1200, expenseCount: 1 });
    expect(res.body.expenses[0]).toMatchObject({ id: original.id, canEdit: true, revision: 1 });
    expect(res.body.memberShares.find((s: { userId: string }) => s.userId === ids[1]).amountCents).toBe(900);
    expect(await ExpenseModel.countDocuments()).toBe(1);
    expect((await ExpenseModel.findById(original.id))?.updatedBy?.toString()).toBe(ids[0]);
    // Retrying the old create request must not add a second bill after an edit.
    expect((await post(bill())).status).toBe(409);
  });

  // Input: tax change on a bill with a tax/tip breakdown
  // Expected status code: 200
  // Expected behavior: the new breakdown and total are saved
  // Expected output: summary shows taxCents 200 and amountCents 1350
  test('Edits the tax and tip breakdown', async () => {
    const input = bill({ subtotal: '10.00', tax: '1.23', tip: '1.50', amount: '12.73' });
    const saved = await post(input);
    const changed = await put(saved.body.expense.id, { ...input, revision: 0, tax: '2.00', amount: '13.50' });
    expect(changed.status).toBe(200);
    const res = await summary();
    expect(res.body.expenses[0]).toMatchObject({ subtotalCents: 1000, taxCents: 200, tipCents: 150, amountCents: 1350 });
    expect(res.body.totalCents).toBe(1350);
  });

  // Input: edit of a meal-calculation bill, then a stale edit at the same revision
  // Expected status code: 200, then 409
  // Expected behavior: stale edits cannot overwrite the newer version
  // Expected output: conflict error for the stale edit
  test('Rejects a stale edit of a meal calculation', async () => {
    const { payload } = mealBill();
    const saved = await post(payload);
    expect((await put(saved.body.expense.id, { ...payload, description: 'Changed', revision: 0 })).status).toBe(200);
    expect((await put(saved.body.expense.id, { ...payload, description: 'Stale edit', revision: 0 })).status).toBe(409);
  });

  // Input: a member edits the owner's bill, then their own; the owner then edits the member's bill
  // Expected status code: 403, then 200, then 200
  // Expected behavior: only the creator or group owner may edit; summary exposes the same permission
  // Expected output: canEdit false for the member on the owner's bill
  test('Only the creator or group owner can edit', async () => {
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

  // Input: edit by a nonmember, with an invalid, unknown or operator-shaped expense ID, or through another group
  // Expected status code: 404
  // Expected behavior: the stored amount is unchanged
  // Expected output: error message
  test('Requires membership and an expense belonging to the route group', async () => {
    const original = (await post(bill())).body.expense;
    const input = bill({ amount: '12.00', revision: 0 });
    expect((await put(original.id, input, outsiderAuth)).status).toBe(404);
    expect((await put('not-an-id', input)).status).toBe(404);
    expect((await put('000000000000000000000000', input)).status).toBe(404);
    expect((await put(encodeURIComponent('{"$ne":null}'), {})).status).toBe(404);
    const other = await GroupModel.create({ name: 'Another group', currency: 'CAD', ownerId: required(ids[0]), memberIds: ids });
    expect((await put(original.id, input, auth, other.id)).status).toBe(404);
    expect((await ExpenseModel.findById(original.id))?.amountCents).toBe(1000);
  });

  // Input: missing, negative, string or fractional revision, or an invalid bill body
  // Expected status code: 400
  // Expected behavior: amount, version and shares are unchanged
  // Expected output: error message
  test('Invalid edits leave the original intact', async () => {
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

  // Input: two different edits at revision 0 at once, then a retry of the winner
  // Expected status code: one 200 and one 409, then 200
  // Expected behavior: edits cannot overwrite one another; the identical retry performs no extra write
  // Expected output: { expense } at revision 1 with the winning amount
  test('Concurrent edits cannot overwrite one another', async () => {
    const original = (await post(bill())).body.expense;
    const amounts = ['12.00', '14.00'];
    const attempts = await Promise.all(amounts.map(async amount => {
      const input = bill({ amount, revision: 0 });
      const response = await put(original.id, input);
      return { input, status: response.status, amount };
    }));
    expect(attempts).toContainEqual(expect.objectContaining({ status: 409 }));
    const winner = required(attempts.find(attempt => attempt.status === 200));
    const retry = await put(original.id, winner.input);
    expect(retry.status).toBe(200);
    expect(retry.body.expense).toMatchObject({ id: original.id, revision: 1, amountCents: Number(winner.amount) * 100 });
    expect(await ExpenseModel.countDocuments()).toBe(1);
    expect((await ExpenseModel.findById(original.id))?.__v).toBe(1);
  });

  // Input: edit of a stored record that has no version field
  // Expected status code: 200
  // Expected behavior: a missing version counts as revision 0
  // Expected output: { expense } at revision 1
  test('Existing records without a version field can still be edited', async () => {
    const original = (await post(bill())).body.expense;
    const stored = await ExpenseModel.findById(original.id);
    await ExpenseModel.collection.updateOne({ _id: required(stored)._id }, { $unset: { __v: '' } });
    expect((await summary()).body.expenses[0].revision).toBe(0);
    const updated = await put(original.id, bill({ amount: '11.00', revision: 0 }));
    expect(updated.status).toBe(200);
    expect(updated.body.expense).toMatchObject({ id: original.id, amountCents: 1100, revision: 1 });
  });

  // Input: equal bill changed to percentage, its total changed, then changed to custom
  // Expected status code: 200 for each
  // Expected behavior: percentages are recalculated for the new total and removed when no longer used
  // Expected output: shares 400, 400, 400 at 12.00; a single 1000 share for the custom split
  test('Switches split modes and recalculates percentages', async () => {
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

  // Input: percentage-only edit that leaves the rounded cents unchanged, then an identical retry
  // Expected status code: 200 for both
  // Expected behavior: the first edit advances the revision; the retry does not
  // Expected output: revision 1 both times with basis points 6000/4000
  test('Persists percentage-only changes and replays identical edits', async () => {
    const original = (await post(percentageBill({ amount: '0.01', shares: [
      { userId: ids[0], percentage: '50' }, { userId: ids[1], percentage: '50' },
    ] }))).body.expense;
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

  // Input: percentages that total 90%
  // Expected status code: 400
  // Expected behavior: amount, shares and revision are unchanged
  // Expected output: error message
  test('Preserves the saved expense when percentages do not total 100%', async () => {
    const original = (await post(percentageBill())).body.expense;
    const before = await ExpenseModel.findById(original.id).lean();
    const response = await put(original.id, percentageBill({ revision: 0, shares: [{ userId: ids[0], percentage: '90' }] }));
    expect(response.status).toBe(400);
    expect(await ExpenseModel.findById(original.id).lean()).toEqual(before);
  });

  // Input: equal bill changed to adjusted, its total changed, then back to equal
  // Expected status code: 200 for each
  // Expected behavior: adjustments are kept while the remainder is re-split, and removed for equal
  // Expected output: shares 1334, 2133, 1133 at 46.00
  test('Switches a bill to adjusted and re-splits the remainder', async () => {
    const original = (await post(bill())).body.expense;
    const changed = await put(original.id, adjustedBill({ revision: 0 }));
    expect(changed.status).toBe(200);
    expect(changed.body.expense).toMatchObject({ id: original.id, splitMode: 'adjusted', revision: 1 });
    const resized = await put(original.id, adjustedBill({ revision: 1, amount: '46.00' }));
    expect(resized.status).toBe(200);
    expect(resized.body.expense.shares.map((share: { amountCents: number }) => share.amountCents)).toEqual([1334, 2133, 1133]);
    expect((await put(original.id, bill({ revision: 2 }))).status).toBe(200);
    expect((await ExpenseModel.findById(original.id))?.shares[0]?.adjustmentCents).toBeUndefined();
  });

  // Input: description-only edit (twice) of a CAD 60 bill shared with a member who has since left,
  // then a stale description edit, then an amount change
  // Expected status code: 200, 200, 409, then 400
  // Expected behavior: renaming keeps the payer and the departed member's historical share; the retry
  // writes nothing; changing the amount would need the departed member in the split and is refused
  // Expected output: stored bill "Team dinner", 6000 cents, shares 3000/3000, revision advanced once
  test.each(['owner', 'departing member'])('Renames a bill paid by the %s without reallocating its shares', async payer => {
    const paidBy = payer === 'owner' ? ids[0] : ids[1];
    const created = await post(bill({ description: 'Dinner', amount: '60.00', paidBy, requestId: 'historical-dinner-01',
      shares: [{ userId: ids[0] }, { userId: ids[1] }] }));
    expect(created.status).toBe(201);
    const saved = created.body.expense;
    expect((await request(app).post(`/api/groups/${groupId}/leave`).set('Authorization', memberAuth)).status).toBe(200);
    const rename = { description: 'Team dinner', revision: saved.revision };
    expect((await put(saved.id, rename)).status).toBe(200);
    expect((await put(saved.id, rename)).status).toBe(200);
    expect((await put(saved.id, { ...rename, description: 'Stale edit' })).status).toBe(409);
    expect((await put(saved.id, { ...rename, revision: saved.revision + 1, amount: '90.00' })).status).toBe(400);
    const stored = await ExpenseModel.findById(saved.id).lean();
    expect(stored).toMatchObject({ description: 'Team dinner', amountCents: 6000, __v: saved.revision + 1 });
    expect(stored?.paidBy.toString()).toBe(paidBy);
    expect(stored?.shares.map(share => [share.userId.toString(), share.amountCents]))
      .toEqual(expect.arrayContaining([[ids[0], 3000], [ids[1], 3000]]));
    expect(stored?.shares).toHaveLength(2);
  });
});

// Interface DELETE /api/groups/:groupId/expenses/:expenseId
describe('Unmocked: DELETE /api/groups/:groupId/expenses/:expenseId', () => {

  // Input: a malformed expense ID and an immutable finalized allocation.
  // Expected status code: 404 and 409.
  // Expected behavior: preserve the original allocation.
  // Expected output: error and one persisted expense.
  test('protects finalized allocations from deletion', async () => {
    const created = await post(bill());
    const id = created.body.expense.id;
    await ExpenseModel.updateOne({ _id: id }, { splitSessionId: groupId });
    const remove = (expense: string): request.Test => request(app).delete(`/api/groups/${groupId}/expenses/${expense}?revision=0`).set('Authorization', auth);
    expect((await remove('invalid')).status).toBe(404);
    expect((await remove(requiredString(id))).status).toBe(409);
    expect(await ExpenseModel.exists({ _id: id })).toBeTruthy();
  });

  testsRejectInvalidSessions((): request.Test => request(app).delete(`/api/groups/${groupId}/expenses/${sharedExpenseId}?revision=0`),
    () => auth, groupState, prepareSharedExpense);

  // Input: add, edit, then delete as a member (forbidden), at a stale revision, and at the current revision
  // Expected status code: 403, 409, then 204
  // Expected behavior: committed writes publish only the group ID and change type; rejected deletes publish nothing
  // Expected output: empty body; the summary has no expenses afterwards
  test('Deletes at the current revision and notifies only committed changes', async () => {
    const changes: unknown[] = [];
    const listener = (change: unknown) => changes.push(change);
    groupMembershipEvents.on('groupChanged', listener);
    try {
      const saved = await post(bill());
      const id = saved.body.expense.id;
      expect(changes).toEqual([{ groupId, type: 'expense-added' }]);
      expect((await put(id, bill({ description: 'Lunch', revision: 0 }))).status).toBe(200);
      const remove = (revision: number, token = auth): request.Test => request(app).delete(`/api/groups/${groupId}/expenses/${id}?revision=${revision}`).set('Authorization', token);
      expect((await remove(1, memberAuth)).status).toBe(403);
      expect((await remove(0)).status).toBe(409);
      expect(changes).toHaveLength(2);
      expect((await remove(1)).status).toBe(204);
      expect(changes).toEqual(['expense-added', 'expense-updated', 'expense-deleted'].map(type => ({ groupId, type })));
      expect((await summary()).body).toMatchObject({ totalCents: 0, expenseCount: 0, expenses: [] });
    } finally { groupMembershipEvents.off('groupChanged', listener); }
  });

  // Input: delete without a revision, and of an unknown expense
  // Expected status code: 400, then 404
  // Expected behavior: nothing is deleted
  // Expected output: error message
  test('Requires a revision and an existing expense', async () => {
    const id = (await post(bill())).body.expense.id;
    expect((await request(app).delete(`/api/groups/${groupId}/expenses/${id}`).set('Authorization', auth)).status).toBe(400);
    expect((await request(app).delete(`/api/groups/${groupId}/expenses/000000000000000000000000?revision=0`).set('Authorization', auth)).status).toBe(404);
    expect(await ExpenseModel.countDocuments()).toBe(1);
  });
});
