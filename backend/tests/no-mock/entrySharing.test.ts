import { requiredString } from '../helpers/required';
import request from 'supertest';
import { ExpenseModel, GroupModel } from '../../src/groupStore';
import { useGroupApiFixture } from '../helpers/groupApiFixture';
import { testsRejectInvalidSessions } from '../helpers/sessionRejection';

// Real HTTP routing, session verification and MongoDB. No mocked components.
const f = useGroupApiFixture();
const input = { kind: 'expense', amount: '10.01', currency: 'CAD', date: '2026-01-15', merchant: 'Dinner', category: 'Food', notes: 'Shared receipt' };
const sourceId = 'personal-record-00000001';
function share(auth = f.memberAuth, groupId = f.groupId, body: object = { sourceId, input }): request.Test {
  return request(f.app).post(`/api/groups/${groupId}/shared-entries`).set('Authorization', auth).send(body);
}
function mine(expenseId = f.expenseId, auth = f.memberAuth, groupId = f.groupId): request.Test {
  return request(f.app).get(`/api/groups/${groupId}/expenses/${expenseId}/personal-entry`).set('Authorization', auth);
}
function markImported(expenseId = f.expenseId, auth = f.memberAuth, groupId = f.groupId): request.Test {
  return request(f.app).post(`/api/groups/${groupId}/expenses/${expenseId}/personal-entry/imported`).set('Authorization', auth).send({});
}
async function importedInSummary(auth: string) {
  const summary = await request(f.app).get(`/api/groups/${f.groupId}/summary`).set('Authorization', auth);
  return summary.body.expenses[0] as Record<string, unknown>;
}
const expenses = () => ExpenseModel.find().sort({ _id: 1 }).lean();
const otherGroup = () => GroupModel.create({ name: 'Other', currency: 'CAD', ownerId: f.ownerId, memberIds: [f.ownerId, f.memberId] });

// Interface POST /api/groups/:groupId/shared-entries
describe('Unmocked: POST /api/groups/:groupId/shared-entries', () => {
  testsRejectInvalidSessions((): request.Test => request(f.app).post(`/api/groups/${f.groupId}/shared-entries`).send({ sourceId, input }),
    () => f.memberAuth, expenses);

  // Input: a member shares a personal CAD 10.01 expense
  // Expected status code: 201
  // Expected behavior: a group bill paid by the member is created and split equally to the cent
  // Expected output: { expense } with createdBy/paidBy = member and personalEntry = { id, input }
  test('Creates an equally split group bill from a personal expense', async () => {
    const response = await share();
    expect(response.status).toBe(201);
    expect(response.body.expense).toMatchObject({ createdBy: f.memberId, paidBy: f.memberId, amountCents: 1001,
      splitMode: 'equal', personalEntry: { id: sourceId, input } });
    expect(response.body.expense.shares.reduce((sum: number, s: { amountCents: number }) => sum + s.amountCents, 0)).toBe(1001);
  });

  // Input: the same entry shared into two groups by the member, and once by the owner
  // Expected status code: 201 for each
  // Expected behavior: an entry is shared once per designated group and per user
  // Expected output: three stored copies
  test('Shares the same entry once per group and per user', async () => {
    const second = await GroupModel.create({ name: 'Second', currency: 'CAD', ownerId: f.ownerId, memberIds: [f.ownerId, f.memberId] });
    expect((await share()).status).toBe(201);
    expect((await share(f.memberAuth, requiredString(second.id))).status).toBe(201);
    expect((await share(f.ownerAuth)).status).toBe(201);
    expect(await ExpenseModel.countDocuments({ 'personalEntry.id': sourceId })).toBe(3);
  });

  // Input: two simultaneous shares, a later retry, then a retry whose amount was edited
  // Expected status code: 201 and 200, 200, then 409
  // Expected behavior: retries are idempotent and an edited source never duplicates or overwrites the copy
  // Expected output: { alreadyShared: true } for the retry
  test('Concurrent and restarted retries are idempotent', async () => {
    const replies = await Promise.all([share(), share()]);
    expect(replies.map(({ status }: { status: number }) => status).sort()).toEqual([200, 201]);
    expect(replies[0].body.expense.id).toBe(replies[1].body.expense.id);
    const replay = await share();
    expect(replay.body.alreadyShared).toBe(true);
    expect((await share(f.memberAuth, f.groupId, { sourceId, input: { ...input, amount: '12.00' } })).status).toBe(409);
    expect(await ExpenseModel.countDocuments({ 'personalEntry.id': sourceId })).toBe(1);
  });

  // Input: share by a nonmember, by a removed member, and into a deleted group
  // Expected status code: 404
  // Expected behavior: nothing is shared
  // Expected output: { error: "Group not found." }
  test('Requires current membership of an active group', async () => {
    expect((await share(f.inviteeAuth)).status).toBe(404);
    const other = await otherGroup();
    await GroupModel.updateOne({ _id: other.id }, { $set: { deletedAt: new Date() } });
    expect((await share(f.memberAuth, requiredString(other.id))).status).toBe(404);
    await GroupModel.updateOne({ _id: f.groupId }, { $pull: { memberIds: f.memberId } });
    expect((await share()).status).toBe(404);
    expect(await ExpenseModel.countDocuments({ 'personalEntry.id': sourceId })).toBe(0);
  });

  // Input: income, a different currency, zero/negative/sub-cent/oversized amount, invalid or future date, unknown category, long notes
  // Expected status code: 400
  // Expected behavior: nothing is shared
  // Expected output: error message
  test.each([
    { kind: 'income' }, { currency: 'USD' }, { amount: '0' }, { amount: '-1' }, { amount: '0.001' },
    { amount: '10000000000.01' }, { date: '2026-02-30' }, { date: '2099-01-01' },
    { category: 'Invalid' }, { notes: 'x'.repeat(1001) },
  ])('Rejects invalid or unsupported personal data: %o', async overrides => {
    expect((await share(f.memberAuth, f.groupId, { sourceId, input: { ...input, ...overrides } })).status).toBe(400);
    expect(await ExpenseModel.countDocuments({ 'personalEntry.id': sourceId })).toBe(0);
  });

  // Input: operator-shaped sourceId, a body that tries to set createdBy/paidBy, and a manual bill using the reserved prefix
  // Expected status code: 400, 201, then 400
  // Expected behavior: identity always comes from the session; the retry namespace is reserved
  // Expected output: { expense } with createdBy/paidBy = member
  test('Rejects invalid identifiers and ignores forged identity', async () => {
    expect((await share(f.memberAuth, f.groupId, { sourceId: { $ne: null }, input })).status).toBe(400);
    const response = await share(f.memberAuth, f.groupId, { sourceId, input, createdBy: f.ownerId, paidBy: f.ownerId });
    expect(response.body.expense.createdBy).toBe(f.memberId);
    expect(response.body.expense.paidBy).toBe(f.memberId);
    const forged = await request(f.app).post(`/api/groups/${f.groupId}/expenses`).set('Authorization', f.ownerAuth).send({
      description: 'Fake', amount: '1', paidBy: f.ownerId, splitMode: 'equal', shares: [{ userId: f.ownerId }], requestId: 'personal-entry-reserved',
    });
    expect(forged.status).toBe(400);
  });

  // Input: personal entry with an empty merchant
  // Expected status code: 201
  // Expected behavior: the blank merchant is stored as blank
  // Expected output: stored personalEntry.input.merchant = ""
  test('Shares an entry with a blank merchant without inventing one', async () => {
    const result = await share(f.memberAuth, f.groupId, { sourceId, input: { ...input, merchant: '' } });
    expect(result.status).toBe(201);
    const stored = await ExpenseModel.findOne({ 'personalEntry.id': sourceId }).lean();
    expect(stored?.personalEntry?.input.merchant).toBe('');
  });
});

// Interface GET /api/groups/:groupId/expenses/:expenseId/personal-entry
describe('Unmocked: GET /api/groups/:groupId/expenses/:expenseId/personal-entry', () => {
  testsRejectInvalidSessions((): request.Test => request(f.app).get(`/api/groups/${f.groupId}/expenses/${f.expenseId}/personal-entry`),
    () => f.memberAuth, expenses);

  // Input: the sharing member and the owner each request their personal entry for a shared bill
  // Expected status code: 200
  // Expected behavior: each user gets only their own share; the source ID is returned only to its sharer
  // Expected output: { input } with the member's share as amount and sourcePersonalId; null sourcePersonalId for the owner
  test('Returns only the caller\'s share with preserved metadata', async () => {
    const shared = await share();
    const exported = await mine(requiredString(shared.body.expense.id));
    expect(exported.status).toBe(200);
    const amount = shared.body.expense.shares.find((s: { userId: string }) => s.userId === f.memberId).amountCents;
    expect(exported.body).toMatchObject({ input: { ...input, amount: (amount / 100).toFixed(2) }, sourcePersonalId: sourceId });
    expect((await mine(requiredString(shared.body.expense.id), f.ownerAuth)).body.sourcePersonalId).toBeNull();
  });

  // Input: a member whose allocated share is zero, and a member not in the shares
  // Expected status code: 400; 200 for the payer with the whole share
  // Expected behavior: import uses allocated shares, never payer totals
  // Expected output: error message; amount "10.00" for the owner
  test('Uses allocated shares, never payer totals', async () => {
    await ExpenseModel.updateOne({ _id: f.expenseId }, { $set: { shares: [
      { userId: f.ownerId, amountCents: 1000 }, { userId: f.memberId, amountCents: 0 },
    ] } });
    expect((await mine()).status).toBe(400);
    expect((await mine(f.expenseId, f.ownerAuth)).body.input.amount).toBe('10.00');
    await ExpenseModel.updateOne({ _id: f.expenseId }, { $set: { shares: [{ userId: f.ownerId, amountCents: 1000 }] } });
    expect((await mine()).status).toBe(400);
  });

  // Input: JPY entry with a 120-character merchant, an old bill without a personal entry, and a finalized item split
  // Expected status code: 200
  // Expected behavior: whole-yen shares, long merchants, legacy bills and item allocations keep correct values
  // Expected output: { input } with merchant kept, JPY amount 50 or 51, legacy category "Other"
  test('Keeps correct values for zero-decimal, long, legacy and itemized bills', async () => {
    await GroupModel.updateOne({ _id: f.groupId }, { $set: { currency: 'JPY' } });
    const longMerchant = 'M'.repeat(120);
    const response = await share(f.memberAuth, f.groupId, { sourceId, input: { ...input, amount: '101', currency: 'JPY', merchant: longMerchant } });
    expect(response.status).toBe(201);
    const exported = await mine(requiredString(response.body.expense.id));
    expect(exported.body.input.merchant).toBe(longMerchant);
    expect(exported.body.input.amount).toMatch(/^5[01]$/);
    const legacy = await mine(f.expenseId);
    expect(legacy.body.input).toMatchObject({ amount: '5.00', currency: 'CAD', category: 'Other' });
    expect(legacy.body.input.date).toMatch(/^\d{4}-\d{2}-\d{2}$/);
    await ExpenseModel.updateOne({ _id: f.expenseId }, { $set: { splitSessionId: f.groupId } });
    expect((await mine(f.expenseId)).body.input.amount).toBe('5.00');
  });

  // Input: request by a nonmember, through another group, with invalid or operator-shaped expense IDs, and after removal
  // Expected status code: 404 (an operator in the query string is ignored: 200)
  // Expected behavior: lookups are scoped to the route group and current membership
  // Expected output: error message
  test('Protects the lookup with membership and group scope', async () => {
    expect((await mine(f.expenseId, f.inviteeAuth)).status).toBe(404);
    const other = await otherGroup();
    expect((await mine(f.expenseId, f.memberAuth, requiredString(other.id))).status).toBe(404);
    expect((await mine('invalid')).status).toBe(404);
    expect((await mine(encodeURIComponent('{"$ne":null}'))).status).toBe(404);
    expect((await mine().query({ expenseId: { $ne: null } })).status).toBe(200);
    await GroupModel.updateOne({ _id: f.groupId }, { $pull: { memberIds: f.memberId } });
    expect((await mine()).status).toBe(404);
  });
});

// Interface POST /api/groups/:groupId/expenses/:expenseId/personal-entry/imported
describe('Unmocked: POST /api/groups/:groupId/expenses/:expenseId/personal-entry/imported', () => {
  testsRejectInvalidSessions((): request.Test => request(f.app).post(`/api/groups/${f.groupId}/expenses/${f.expenseId}/personal-entry/imported`).send({}),
    () => f.memberAuth, expenses);

  // Input: a member marks their share imported twice
  // Expected status code: 200 for both
  // Expected behavior: the mark is stored once and shown only to that member
  // Expected output: { importedByMe: true }; summary shows importedByMe true for the member and false for the owner
  test('Marks a share imported repeatably and only for that member', async () => {
    expect((await importedInSummary(f.memberAuth)).importedByMe).toBe(false);
    expect((await mine()).body.importedByMe).toBe(false);
    expect((await markImported()).body).toEqual({ importedByMe: true });
    expect((await markImported()).status).toBe(200);
    const stored = await ExpenseModel.findById(f.expenseId).lean();
    expect(stored?.importedBy).toHaveLength(1);
    expect((await mine()).body.importedByMe).toBe(true);
    const own = await importedInSummary(f.memberAuth);
    expect(own.importedByMe).toBe(true);
    expect(own).not.toHaveProperty('importedBy');
    expect((await importedInSummary(f.ownerAuth)).importedByMe).toBe(false);
  });

  // Input: mark imported, then change the bill directly in storage
  // Expected status code: 200
  // Expected behavior: marking does not change the expense revision, and later edits keep the mark
  // Expected output: the same revision before and after
  test('Leaves the expense revision and later edits alone', async () => {
    const before = await importedInSummary(f.memberAuth);
    await markImported();
    const after = await importedInSummary(f.memberAuth);
    expect(after.revision).toBe(before.revision);
    await ExpenseModel.updateOne({ _id: f.expenseId }, { $set: { description: 'Changed' } });
    expect((await importedInSummary(f.memberAuth)).importedByMe).toBe(true);
  });

  // Input: mark by a nonmember, with an invalid ID, through another group, and when the member's share is zero
  // Expected status code: 404, 404, 404, then 400
  // Expected behavior: only a member with a positive share in this group can mark it
  // Expected output: error message
  test('Requires a positive share in this group', async () => {
    expect((await markImported(f.expenseId, f.inviteeAuth)).status).toBe(404);
    expect((await markImported('invalid')).status).toBe(404);
    const other = await otherGroup();
    expect((await markImported(f.expenseId, f.memberAuth, requiredString(other.id))).status).toBe(404);
    await ExpenseModel.updateOne({ _id: f.expenseId }, { $set: { 'shares.$[].amountCents': 0 } });
    expect((await markImported()).status).toBe(400);
  });
});
