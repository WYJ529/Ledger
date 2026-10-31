import request from 'supertest';
import { Types } from 'mongoose';
import { MongoMemoryServer } from 'mongodb-memory-server';
import { createApp } from '../../src/app';
import { GroupModel, GroupInvitationModel, ExpenseModel } from '../../src/groupStore';
import { hashToken, startSession } from '../../src/session';
import { connectDatabase, disconnectDatabase, mongoUserStore, mongoSessionStore, UserModel, SessionModel } from '../../src/userStore';

const app = createApp();
let mongo: MongoMemoryServer;
let accounts: { id: string; auth: string }[];
const originalBase = process.env.PUBLIC_APP_URL;
const originalFingerprints = process.env.ANDROID_SHA256_FINGERPRINTS;
beforeAll(async () => {
  mongo = await MongoMemoryServer.create();
  await connectDatabase(mongo.getUri('group-management-test'));
}, 120000);
beforeEach(async () => {
  delete process.env.PUBLIC_APP_URL;
  delete process.env.ANDROID_SHA256_FINGERPRINTS;
  accounts = [];
  for (const displayName of ['Joy', 'Alex', 'Sam']) {
    const user = await mongoUserStore.create({ displayName, email: `${displayName.toLowerCase()}@example.com` });
    const session = await startSession(mongoSessionStore, user);
    accounts.push({ id: user.id, auth: `Bearer ${session.token}` });
  }
});
afterEach(async () => {
  await Promise.all([GroupInvitationModel.deleteMany({}), ExpenseModel.deleteMany({}), GroupModel.deleteMany({}),
    SessionModel.deleteMany({}), UserModel.deleteMany({})]);
});
afterAll(async () => {
  if (originalBase === undefined) delete process.env.PUBLIC_APP_URL; else process.env.PUBLIC_APP_URL = originalBase;
  if (originalFingerprints === undefined) delete process.env.ANDROID_SHA256_FINGERPRINTS; else process.env.ANDROID_SHA256_FINGERPRINTS = originalFingerprints;
  await disconnectDatabase(); await mongo?.stop();
}, 30000);

function create(overrides: Record<string, unknown> = {}) {
  return request(app).post('/api/groups').set('Authorization', accounts[0]!.auth).send({
    name: 'Roommates', description: 'Shared groceries', currency: 'CAD', requestId: 'group-submission-0001', ...overrides });
}
function get(path: string, account = 0) { return request(app).get(path).set('Authorization', accounts[account]!.auth); }
function post(path: string, account = 0) { return request(app).post(path).set('Authorization', accounts[account]!.auth); }
function tokenOf(res: request.Response): string { return res.body.invitation.deepLink.split('/').pop(); }
function accept(token: string, account = 1) { return post(`/api/groups/invitations/${token}/accept`, account); }

test('creates the authenticated owner, ignores supplied roles and stores only the invitation hash', async () => {
  const res = await create({ ownerId: accounts[1]!.id, memberIds: [accounts[1]!.id] });
  expect(res.status).toBe(201);
  expect(res.body.group).toMatchObject({ name: 'Roommates', ownerId: accounts[0]!.id, memberCount: 1 });
  const group = await GroupModel.findById(res.body.group.id).lean();
  expect(group!.memberIds.map(String)).toEqual([accounts[0]!.id]);
  const token = tokenOf(res);
  const stored = await GroupInvitationModel.findOne().lean();
  expect(stored!.tokenHash).toBe(hashToken(token));
  expect(JSON.stringify(stored)).not.toContain(token);
  expect(Date.parse(res.body.invitation.expiresAt) - Date.now()).toBeGreaterThan(6.9 * 86400000);
});

test('concurrent create retries return one group and reject conflicting reuse', async () => {
  const results = await Promise.all([create(), create()]);
  expect(results.map(res => res.status).sort()).toEqual([200, 201]);
  expect(results[0]!.body.group.id).toBe(results[1]!.body.group.id);
  expect(await GroupModel.countDocuments()).toBe(1);
  expect((await create({ name: 'Different' })).status).toBe(409);
});

test('validates creation without writing a group', async () => {
  for (const body of [{ name: ' ' }, { name: 'a'.repeat(61) }, { description: 'a'.repeat(241) },
    { currency: 'XXX' }, { requestId: '' }]) expect((await create(body)).status).toBe(400);
  expect((await request(app).post('/api/groups').send({})).status).toBe(401);
  expect(await GroupModel.countDocuments()).toBe(0);
});

test('preview never joins; Accept joins as member exactly once and exposes roles', async () => {
  const created = await create(); const token = tokenOf(created); const id = created.body.group.id;
  expect((await request(app).get(`/api/groups/invitations/${token}`)).status).toBe(401);
  const preview = await get(`/api/groups/invitations/${token}`, 1);
  expect(preview.body).toMatchObject({ inviterName: 'Joy', alreadyMember: false, group: { name: 'Roommates' } });
  expect((await get('/api/groups', 1)).body.groups).toEqual([]);
  expect((await get(`/api/groups/${id}/summary`, 1)).status).toBe(404);
  const results = await Promise.all([accept(token), accept(token)]);
  expect(results.map(res => res.status)).toEqual([200, 200]);
  const summary = await get(`/api/groups/${id}/summary`, 1);
  expect(summary.body.group.members).toEqual(expect.arrayContaining([
    expect.objectContaining({ id: accounts[0]!.id, role: 'owner' }),
    expect.objectContaining({ id: accounts[1]!.id, role: 'member' }),
  ]));
  expect(summary.body.group.members).toHaveLength(2);
  expect((await get(`/api/groups/invitations/${token}`, 1)).body.alreadyMember).toBe(true);
});

test('members can invite and rename but cannot change ownership or delete', async () => {
  const created = await create(); const id = created.body.group.id;
  await accept(tokenOf(created));
  const invite = await post(`/api/groups/${id}/invitations`, 1);
  expect(invite.status).toBe(201);
  expect((await get(`/api/groups/invitations/${tokenOf(invite)}`, 2)).body.inviterName).toBe('Alex');
  const renamed = await request(app).put(`/api/groups/${id}/name`).set('Authorization', accounts[1]!.auth)
    .send({ name: ' New name ', ownerId: accounts[1]!.id, currency: 'USD', memberIds: [] });
  expect(renamed.status).toBe(200);
  expect(renamed.body.group).toMatchObject({ name: 'New name', ownerId: accounts[0]!.id, currency: 'CAD', memberCount: 2 });
  expect((await request(app).delete(`/api/groups/${id}`).set('Authorization', accounts[1]!.auth)).status).toBe(403);
  expect((await post(`/api/groups/${id}/invitations`, 2)).status).toBe(404);
  expect((await request(app).put(`/api/groups/${id}/name`).set('Authorization', accounts[2]!.auth).send({ name: 'Stolen' })).status).toBe(404);
  expect((await request(app).put(`/api/groups/${id}/name`).set('Authorization', accounts[1]!.auth).send({ name: ' ' })).status).toBe(400);
});

test('owner deletion hides the group from everyone and invalidates outstanding invitations', async () => {
  const created = await create(); const id = created.body.group.id; const token = tokenOf(created);
  await accept(token);
  expect((await post(`/api/groups/${id}/expenses`).send({ description: 'Milk', amount: '3.00', paidBy: accounts[0]!.id,
    splitMode: 'equal', shares: [{ userId: accounts[0]!.id }], requestId: 'bill-submission-0001' })).status).toBe(201);
  expect((await request(app).delete(`/api/groups/${id}`).set('Authorization', accounts[0]!.auth)).status).toBe(204);
  for (const account of [0, 1]) {
    expect((await get('/api/groups', account)).body.groups).toEqual([]);
    expect((await get(`/api/groups/${id}/summary`, account)).status).toBe(404);
  }
  expect((await accept(token, 2)).status).toBe(404);
  expect((await get(`/api/groups/invitations/${token}`, 2)).status).toBe(404);
  expect((await request(app).get(`/invite/${token}`)).status).toBe(404);
  expect(await ExpenseModel.countDocuments({ groupId: id })).toBe(1);
  expect((await GroupModel.findById(id))!.deletedAt).toBeTruthy();
});

test('expired and malformed invitations do not add members', async () => {
  const created = await create(); const token = tokenOf(created);
  await GroupInvitationModel.updateOne({ tokenHash: hashToken(token) }, { expiresAt: new Date(Date.now() - 1000) });
  for (const invalid of [token, 'invalid', 'b'.repeat(43)]) {
    expect((await accept(invalid)).status).toBe(404);
    expect((await get(`/api/groups/invitations/${invalid}`, 1)).status).toBe(404);
  }
  expect((await GroupModel.findById(created.body.group.id))!.memberIds).toHaveLength(1);
});

test('enforces the member limit atomically while allowing existing members to accept again', async () => {
  const created = await create(); const token = tokenOf(created);
  await GroupModel.updateOne({ _id: created.body.group.id }, { memberIds: [accounts[0]!.id, ...Array.from({ length: 98 }, () => new Types.ObjectId())] });
  const results = await Promise.all([accept(token, 1), accept(token, 2)]);
  expect(results.map(res => res.status).sort()).toEqual([200, 409]);
  expect((await accept(token, 0)).status).toBe(200);
  expect((await GroupModel.findById(created.body.group.id))!.memberIds).toHaveLength(100);
});

test('public fallback escapes group text, provides app link and does not join', async () => {
  process.env.PUBLIC_APP_URL = 'https://ledger.example';
  const created = await create({ name: '<script>alert(1)</script>' }); const token = tokenOf(created);
  expect(created.body.invitation.link).toBe(`https://ledger.example/invite/${token}`);
  const page = await request(app).get(`/invite/${token}`);
  expect(page.status).toBe(200);
  expect(page.text).toContain('&lt;script&gt;');
  expect(page.text).not.toContain('<script>');
  expect(page.text).toContain(`ledger://invite/${token}`);
  expect(page.headers['cache-control']).toBe('no-store');
  expect(page.headers['referrer-policy']).toBe('no-referrer');
  expect((await GroupModel.findById(created.body.group.id))!.memberIds).toHaveLength(1);
});

test('rejects invalid deployment configuration before creation and serves configured app associations', async () => {
  process.env.PUBLIC_APP_URL = 'https://ledger.example/unsupported-path';
  expect((await create()).status).toBe(503);
  expect(await GroupModel.countDocuments()).toBe(0);
  expect((await request(app).get('/.well-known/assetlinks.json')).status).toBe(503);
  process.env.ANDROID_SHA256_FINGERPRINTS = Array(32).fill('AB').join(':');
  const association = await request(app).get('/.well-known/assetlinks.json');
  expect(association.status).toBe(200);
  expect(association.body[0].target.package_name).toBe('com.example.cpen321application');
});

// Input: one group for each added currency. Expected: 201 and the currency is stored.
test('creates groups in each added currency', async () => {
  for (const [index, currency] of ['AUD', 'HKD', 'SGD', 'KRW', 'MXN', 'INR'].entries()) {
    const res = await create({ name: `Trip ${currency}`, currency, requestId: `currency-group-000${index}` });
    expect(res.status).toBe(201);
    expect(res.body.group.currency).toBe(currency);
  }
  expect(await GroupModel.countDocuments()).toBe(6);
});
