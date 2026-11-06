import request from 'supertest';
import { ExpenseModel, GroupModel } from '../../src/groupStore';
import { useGroupApiFixture } from '../helpers/groupApiFixture';

const f = useGroupApiFixture();
const input = { kind: 'expense', amount: '10.01', currency: 'CAD', date: '2026-01-15', merchant: 'Dinner', category: 'Food', notes: 'Shared receipt' };
const sourceId = 'personal-record-00000001';
function share(auth = f.memberAuth, groupId = f.groupId, body: object = { sourceId, input }) {
  return request(f.app).post(`/api/groups/${groupId}/shared-entries`).set('Authorization', auth).send(body);
}
function mine(expenseId = f.expenseId, auth = f.memberAuth, groupId = f.groupId) {
  return request(f.app).get(`/api/groups/${groupId}/expenses/${expenseId}/personal-entry`).set('Authorization', auth);
}

test('a member shares a personal expense and imports only their share with preserved metadata', async () => {
  const response = await share();
  expect(response.status).toBe(201);
  expect(response.body.expense).toMatchObject({ createdBy: f.memberId, paidBy: f.memberId, amountCents: 1001,
    splitMode: 'equal', personalEntry: { id: sourceId, input } });
  expect(response.body.expense.shares.reduce((sum: number, s: { amountCents: number }) => sum + s.amountCents, 0)).toBe(1001);
  const exported = await mine(response.body.expense.id);
  expect(exported.status).toBe(200);
  const amount = response.body.expense.shares.find((s: { userId: string }) => s.userId === f.memberId).amountCents;
  expect(exported.body).toMatchObject({ input: { ...input, amount: (amount / 100).toFixed(2) }, sourcePersonalId: sourceId });
  const ownersShare = await mine(response.body.expense.id, f.ownerAuth);
  expect(ownersShare.body.sourcePersonalId).toBeNull();
});

test('shares the same entry once per designated group and per authenticated user', async () => {
  const second = await GroupModel.create({ name: 'Second', currency: 'CAD', ownerId: f.ownerId, memberIds: [f.ownerId, f.memberId] });
  expect((await share()).status).toBe(201);
  expect((await share(f.memberAuth, second.id)).status).toBe(201);
  expect((await share(f.ownerAuth)).status).toBe(201);
  expect(await ExpenseModel.countDocuments({ 'personalEntry.id': sourceId })).toBe(3);
});

test('concurrent and restarted retries are idempotent; edited source cannot duplicate or overwrite the copy', async () => {
  const replies = await Promise.all([share(), share()]);
  expect(replies.map(r => r.status).sort()).toEqual([200, 201]);
  expect(replies[0]?.body.expense.id).toBe(replies[1]?.body.expense.id);
  const replay = await share();
  expect(replay.body.alreadyShared).toBe(true);
  expect((await share(f.memberAuth, f.groupId, { sourceId, input: { ...input, amount: '12.00' } })).status).toBe(409);
  expect(await ExpenseModel.countDocuments({ 'personalEntry.id': sourceId })).toBe(1);
});

test('sessions, membership and group-scoped expense lookup protect both directions', async () => {
  expect((await request(f.app).post(`/api/groups/${f.groupId}/shared-entries`).send({ sourceId, input })).status).toBe(401);
  expect((await request(f.app).get(`/api/groups/${f.groupId}/expenses/${f.expenseId}/personal-entry`)).status).toBe(401);
  expect((await share(f.inviteeAuth)).status).toBe(404);
  expect((await mine(f.expenseId, f.inviteeAuth)).status).toBe(404);
  const other = await GroupModel.create({ name: 'Other', currency: 'CAD', ownerId: f.ownerId, memberIds: [f.ownerId, f.memberId] });
  expect((await mine(f.expenseId, f.memberAuth, other.id)).status).toBe(404);
  expect((await mine('invalid')).status).toBe(404);
  expect((await mine(encodeURIComponent('{"$ne":null}'))).status).toBe(404);
  expect((await mine().query({ expenseId: { $ne: null } })).status).toBe(200);
  await GroupModel.updateOne({ _id: f.groupId }, { $pull: { memberIds: f.memberId } });
  expect((await share()).status).toBe(404);
  expect((await mine()).status).toBe(404);
  await GroupModel.updateOne({ _id: other.id }, { $set: { deletedAt: new Date() } });
  expect((await share(f.memberAuth, other.id)).status).toBe(404);
});

test.each([
  { kind: 'income' }, { currency: 'USD' }, { amount: '0' }, { amount: '-1' }, { amount: '0.001' },
  { amount: '10000000000.01' }, { date: '2026-02-30' }, { date: '2099-01-01' },
  { category: 'Invalid' }, { notes: 'x'.repeat(1001) },
])('rejects invalid or unsupported personal data: %o', async overrides => {
  expect((await share(f.memberAuth, f.groupId, { sourceId, input: { ...input, ...overrides } })).status).toBe(400);
  expect(await ExpenseModel.countDocuments({ 'personalEntry.id': sourceId })).toBe(0);
});

test('rejects invalid identifiers, reserves retry namespace, and ignores forged identity', async () => {
  expect((await share(f.memberAuth, f.groupId, { sourceId: { $ne: null }, input })).status).toBe(400);
  const response = await share(f.memberAuth, f.groupId, { sourceId, input, createdBy: f.ownerId, paidBy: f.ownerId });
  expect(response.body.expense.createdBy).toBe(f.memberId);
  expect(response.body.expense.paidBy).toBe(f.memberId);
  const forged = await request(f.app).post(`/api/groups/${f.groupId}/expenses`).set('Authorization', f.ownerAuth).send({
    description: 'Fake', amount: '1', paidBy: f.ownerId, splitMode: 'equal', shares: [{ userId: f.ownerId }], requestId: 'personal-entry-reserved',
  });
  expect(forged.status).toBe(400);
});

test('import uses allocated shares, never payer totals or client-supplied identity', async () => {
  await ExpenseModel.updateOne({ _id: f.expenseId }, { $set: { shares: [
    { userId: f.ownerId, amountCents: 1000 }, { userId: f.memberId, amountCents: 0 },
  ] } });
  expect((await mine()).status).toBe(400);
  expect((await mine(f.expenseId, f.ownerAuth)).body.input.amount).toBe('10.00');
  await ExpenseModel.updateOne({ _id: f.expenseId }, { $set: { shares: [{ userId: f.ownerId, amountCents: 1000 }] } });
  expect((await mine()).status).toBe(400);
});

test('zero-decimal currencies, long merchants, old bills and finalized item allocations retain correct values', async () => {
  await GroupModel.updateOne({ _id: f.groupId }, { $set: { currency: 'JPY' } });
  const longMerchant = 'M'.repeat(120);
  const response = await share(f.memberAuth, f.groupId, { sourceId, input: { ...input, amount: '101', currency: 'JPY', merchant: longMerchant } });
  expect(response.status).toBe(201);
  const exported = await mine(response.body.expense.id);
  expect(exported.body.input.merchant).toBe(longMerchant);
  expect(exported.body.input.amount).toMatch(/^5[01]$/);
  const legacy = await mine(f.expenseId);
  expect(legacy.body.input).toMatchObject({ amount: '5.00', currency: 'CAD', category: 'Other' });
  expect(legacy.body.input.date).toMatch(/^\d{4}-\d{2}-\d{2}$/);
  await ExpenseModel.updateOne({ _id: f.expenseId }, { $set: { splitSessionId: f.groupId } });
  expect((await mine(f.expenseId)).body.input.amount).toBe('5.00');
});

test('shares an entry with an optional blank merchant without inventing a merchant', async () => {
  const result = await share(f.memberAuth, f.groupId, { sourceId, input: { ...input, merchant: '' } });
  expect(result.status).toBe(201);
  const stored = await ExpenseModel.findOne({ 'personalEntry.id': sourceId }).lean();
  expect(stored?.personalEntry?.input.merchant).toBe('');
});
