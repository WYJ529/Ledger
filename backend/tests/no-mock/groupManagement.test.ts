import { required, requiredString } from '../helpers/required';
import request from 'supertest';
import { Types } from 'mongoose';
import { useIsolatedMongo } from '../helpers/isolatedMongo';
import { createApp } from '../../src/app';
import { GroupModel, GroupInvitationModel, ExpenseModel } from '../../src/groupStore';
import { hashToken, startSession } from '../../src/session';
import { mongoUserStore, mongoSessionStore, UserModel, SessionModel } from '../../src/userStore';
import { testsRejectInvalidSessions } from '../helpers/sessionRejection';
import { expectEachRejected, operatorShapedIds } from '../helpers/operatorInput';

// Real HTTP routing, session verification and MongoDB. No mocked components.
const app = createApp();
let accounts: { id: string; auth: string }[];
const shared = { groupId: '', token: '' };
const originalBase = process.env.PUBLIC_APP_URL;
const originalFingerprints = process.env.ANDROID_SHA256_FINGERPRINTS;
useIsolatedMongo('group-management-test', [GroupInvitationModel, ExpenseModel, GroupModel, SessionModel, UserModel]);
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
afterAll(() => {
  if (originalBase === undefined) delete process.env.PUBLIC_APP_URL; else process.env.PUBLIC_APP_URL = originalBase;
  if (originalFingerprints === undefined) delete process.env.ANDROID_SHA256_FINGERPRINTS; else process.env.ANDROID_SHA256_FINGERPRINTS = originalFingerprints;
});

function create(overrides: Record<string, unknown> = {}): request.Test {
  return request(app).post('/api/groups').set('Authorization', required(accounts[0]).auth).send({
    name: 'Roommates', description: 'Shared groceries', currency: 'CAD', requestId: 'group-submission-0001', ...overrides });
}
function get(path: string, account = 0): request.Test { return request(app).get(path).set('Authorization', required(accounts.at(account)).auth); }
function post(path: string, account = 0): request.Test { return request(app).post(path).set('Authorization', required(accounts.at(account)).auth); }
function rename(id: string, name: string, account = 0, extra: object = {}): request.Test {
  return request(app).put(`/api/groups/${id}/name`).set('Authorization', required(accounts.at(account)).auth).send({ name, ...extra });
}
function remove(id: string, account = 0): request.Test { return request(app).delete(`/api/groups/${id}`).set('Authorization', required(accounts.at(account)).auth); }
function tokenOf(res: request.Response): string { return required(requiredString(res.body.invitation.deepLink).split('/').pop()); }
function accept(token: string, account = 1): request.Test { return post(`/api/groups/invitations/${token}/accept`, account); }
// Owner creates a group; account 1 joins it.
async function joinedGroup() {
  const created = await create();
  await accept(tokenOf(created));
  return { id: created.body.group.id as string, token: tokenOf(created) };
}
async function prepareSharedGroup() {
  const created = await create({ requestId: 'session-check-group-01' });
  expect(created.status).toBe(201);
  shared.groupId = requiredString(created.body.group.id);
  shared.token = tokenOf(created);
}
async function snapshot() {
  const [groups, invitations, expenses] = await Promise.all([
    GroupModel.find().sort({ _id: 1 }).lean(), GroupInvitationModel.find().sort({ _id: 1 }).lean(),
    ExpenseModel.find().sort({ _id: 1 }).lean(),
  ]);
  return { groups, invitations, expenses };
}
const ownerAuth = () => required(accounts[0]).auth;

// Interface POST /api/groups
describe('Unmocked: POST /api/groups', () => {

  // Input: an absent name followed by a valid group with optional fields omitted.
  // Expected status code: 400, 201 and 200 on replay.
  // Expected behavior: apply the default currency and accept a legacy null description.
  // Expected output: one CAD group with an empty description.
  test('normalizes optional fields on creation and replay', async () => {
    expect((await create({ name: null })).status).toBe(400);
    const optional = { description: undefined, currency: undefined };
    const created = await create(optional);
    expect(created.status).toBe(201);
    await GroupModel.updateOne({ _id: created.body.group.id }, { description: null });
    const replay = await create(optional);
    expect(replay.status).toBe(200);
    expect(replay.body.group).toMatchObject({ id: created.body.group.id, description: '', currency: 'CAD' });
    const invalidRename = await request(app).put(`/api/groups/${created.body.group.id}/name`)
      .set('Authorization', required(accounts[0]).auth).send({});
    expect(invalidRename.status).toBe(400);
  });

  testsRejectInvalidSessions((): request.Test => request(app).post('/api/groups')
    .send({ name: 'Unauthorized group', requestId: 'unauthorized-group-request' }), ownerAuth, snapshot);

  // Input: valid group that also tries to set ownerId and memberIds
  // Expected status code: 201
  // Expected behavior: the caller becomes the only member and owner; only the invitation token hash is stored
  // Expected output: { group, invitation } with a 7-day invitation link
  test('Creates the authenticated owner and stores only the invitation hash', async () => {
    const res = await create({ ownerId: required(accounts[1]).id, memberIds: [required(accounts[1]).id] });
    expect(res.status).toBe(201);
    expect(res.body.group).toMatchObject({ name: 'Roommates', ownerId: required(accounts[0]).id, memberCount: 1 });
    const group = await GroupModel.findById(res.body.group.id).lean();
    expect(required(group).memberIds.map(String)).toEqual([required(accounts[0]).id]);
    const token = tokenOf(res);
    const stored = await GroupInvitationModel.findOne().lean();
    expect(required(stored).tokenHash).toBe(hashToken(token));
    expect(JSON.stringify(stored)).not.toContain(token);
    expect(Date.parse(requiredString(res.body.invitation.expiresAt)) - Date.now()).toBeGreaterThan(6.9 * 86400000);
  });

  // Input: the same request twice at once, then the same requestId with a different name
  // Expected status code: 201 and 200, then 409
  // Expected behavior: retries return one group; conflicting reuse of the requestId is refused
  // Expected output: the same group ID for both retries
  test('Concurrent retries return one group and reject conflicting reuse', async () => {
    const results = await Promise.all([create(), create()]);
    expect(results.map(({ status }: { status: number }) => status).sort()).toEqual([200, 201]);
    expect(required(results[0]).body.group.id).toBe(required(results[1]).body.group.id);
    expect(await GroupModel.countDocuments()).toBe(1);
    expect((await create({ name: 'Different' })).status).toBe(409);
  });

  // Input: blank or 61-character name, 241-character description, unsupported currency, empty requestId
  // Expected status code: 400
  // Expected behavior: no group is written
  // Expected output: error message
  test('Validates creation without writing a group', async () => {
    for (const body of [{ name: ' ' }, { name: 'a'.repeat(61) }, { description: 'a'.repeat(241) },
      { currency: 'XXX' }, { requestId: '' }]) expect((await create(body)).status).toBe(400);
    expect(await GroupModel.countDocuments()).toBe(0);
  });

  // Input: requestId given as a query operator object or array
  // Expected status code: 400
  // Expected behavior: operators are never used in a MongoDB filter; no group is written
  // Expected output: error message
  test('Rejects query operators as submission IDs', async () => {
    await expectEachRejected(operatorShapedIds('group-submission-0001'), requestId => create({ requestId }));
    expect(await GroupModel.countDocuments()).toBe(0);
  });

  // Input: one group for each added currency (AUD, HKD, SGD, KRW, MXN, INR)
  // Expected status code: 201 for each
  // Expected behavior: each currency is stored
  // Expected output: { group } with the requested currency
  test('Creates groups in each added currency', async () => {
    for (const [index, currency] of ['AUD', 'HKD', 'SGD', 'KRW', 'MXN', 'INR'].entries()) {
      const res = await create({ name: `Trip ${currency}`, currency, requestId: `currency-group-000${index}` });
      expect(res.status).toBe(201);
      expect(res.body.group.currency).toBe(currency);
    }
    expect(await GroupModel.countDocuments()).toBe(6);
  });

  // Input: valid group while PUBLIC_APP_URL contains an unsupported path
  // Expected status code: 503
  // Expected behavior: misconfiguration is reported before anything is written
  // Expected output: error message
  test('Rejects invalid deployment configuration before creation', async () => {
    process.env.PUBLIC_APP_URL = 'https://ledger.example/unsupported-path';
    expect((await create()).status).toBe(503);
    expect(await GroupModel.countDocuments()).toBe(0);
  });
});

// Interface GET /api/groups/invitations/:token
describe('Unmocked: GET /api/groups/invitations/:token', () => {

  // Input: a live invitation whose inviter account was deleted.
  // Expected status code: 404.
  // Expected behavior: withhold group and inviter details.
  // Expected output: an unavailable invitation.
  test('hides invitations from a deleted inviter', async () => {
    const token = tokenOf(await create());
    await UserModel.deleteOne({ _id: required(accounts[0]).id });
    const response = await get(`/api/groups/invitations/${token}`, 1);
    expect(response.status).toBe(404);
    expect(response.body).not.toHaveProperty('group');
  });

  testsRejectInvalidSessions((): request.Test => request(app).get(`/api/groups/invitations/${shared.token}`), ownerAuth, snapshot, prepareSharedGroup);

  // Input: invitation token previewed by a signed-in nonmember
  // Expected status code: 200
  // Expected behavior: preview never joins the group or exposes its summary
  // Expected output: { inviterName: "Joy", alreadyMember: false, group: { name: "Roommates" } }
  test('Previews an invitation without joining', async () => {
    const created = await create(); const token = tokenOf(created); const id = created.body.group.id;
    const preview = await get(`/api/groups/invitations/${token}`, 1);
    expect(preview.status).toBe(200);
    expect(preview.body).toMatchObject({ inviterName: 'Joy', alreadyMember: false, group: { name: 'Roommates' } });
    expect((await get('/api/groups', 1)).body.groups).toEqual([]);
    expect((await get(`/api/groups/${id}/summary`, 1)).status).toBe(404);
  });

  // Input: preview after the invitee has accepted
  // Expected status code: 200
  // Expected behavior: preview reports existing membership
  // Expected output: { alreadyMember: true }
  test('Reports existing membership', async () => {
    const { token } = await joinedGroup();
    expect((await get(`/api/groups/invitations/${token}`, 1)).body.alreadyMember).toBe(true);
  });

  // Input: expired token, malformed token, and a well-formed unknown token
  // Expected status code: 404
  // Expected behavior: no invitation details are disclosed
  // Expected output: error message
  test('Hides expired and malformed invitations', async () => {
    const created = await create(); const token = tokenOf(created);
    await GroupInvitationModel.updateOne({ tokenHash: hashToken(token) }, { expiresAt: new Date(Date.now() - 1000) });
    for (const invalid of [token, 'invalid', 'b'.repeat(43)]) {
      expect((await get(`/api/groups/invitations/${invalid}`, 1)).status).toBe(404);
    }
  });
});

// Interface POST /api/groups/invitations/:token/accept
describe('Unmocked: POST /api/groups/invitations/:token/accept', () => {
  testsRejectInvalidSessions((): request.Test => request(app).post(`/api/groups/invitations/${shared.token}/accept`), ownerAuth, snapshot, prepareSharedGroup);

  // Input: the same invitation accepted twice at once by one invitee
  // Expected status code: 200 for both
  // Expected behavior: the invitee joins as a member exactly once; the owner keeps the owner role
  // Expected output: { group } whose summary lists one owner and one member
  test('Joins as a member exactly once', async () => {
    const created = await create(); const token = tokenOf(created); const id = created.body.group.id;
    const results = await Promise.all([accept(token), accept(token)]);
    expect(results.map(({ status }: { status: number }) => status)).toEqual([200, 200]);
    const summary = await get(`/api/groups/${id}/summary`, 1);
    expect(summary.body.group.members).toEqual(expect.arrayContaining([
      expect.objectContaining({ id: required(accounts[0]).id, role: 'owner' }),
      expect.objectContaining({ id: required(accounts[1]).id, role: 'member' }),
    ]));
    expect(summary.body.group.members).toHaveLength(2);
  });

  // Input: expired token, malformed token, and a well-formed unknown token
  // Expected status code: 404
  // Expected behavior: no member is added
  // Expected output: error message
  test('Does not add members through expired or malformed invitations', async () => {
    const created = await create(); const token = tokenOf(created);
    await GroupInvitationModel.updateOne({ tokenHash: hashToken(token) }, { expiresAt: new Date(Date.now() - 1000) });
    for (const invalid of [token, 'invalid', 'b'.repeat(43)]) expect((await accept(invalid)).status).toBe(404);
    expect(required(await GroupModel.findById(created.body.group.id)).memberIds).toHaveLength(1);
  });

  // Input: two new users accept at once when the group has 99 of 100 members; then an existing member accepts
  // Expected status code: one 200 and one 409, then 200
  // Expected behavior: the member limit is enforced atomically; existing members may accept again
  // Expected output: the group has exactly 100 members
  test('Enforces the member limit atomically', async () => {
    const created = await create(); const token = tokenOf(created);
    await GroupModel.updateOne({ _id: created.body.group.id }, { memberIds: [required(accounts[0]).id, ...Array.from({ length: 98 }, () => new Types.ObjectId())] });
    const results = await Promise.all([accept(token, 1), accept(token, 2)]);
    expect(results.map(({ status }: { status: number }) => status).sort()).toEqual([200, 409]);
    expect((await accept(token, 0)).status).toBe(200);
    expect(required(await GroupModel.findById(created.body.group.id)).memberIds).toHaveLength(100);
  });
});

// Interface POST /api/groups/:groupId/invitations
describe('Unmocked: POST /api/groups/:groupId/invitations', () => {

  // Input: the public origin uses HTTP in production.
  // Expected status code: 503.
  // Expected behavior: reject the invitation before inserting another token.
  // Expected output: configuration diagnostic and the original invitation count.
  test('rejects an insecure production invitation origin', async () => {
    const group = await create();
    const originalEnv = process.env.NODE_ENV;
    process.env.NODE_ENV = 'production';
    process.env.PUBLIC_APP_URL = 'http://ledger.example';
    try {
      const response = await post(`/api/groups/${group.body.group.id}/invitations`);
      expect(response.status).toBe(503);
      expect(response.body.error).toContain('HTTPS');
      expect(await GroupInvitationModel.countDocuments()).toBe(1);
    } finally { process.env.NODE_ENV = originalEnv; }
  });

  testsRejectInvalidSessions((): request.Test => request(app).post(`/api/groups/${shared.groupId}/invitations`), ownerAuth, snapshot, prepareSharedGroup);

  // Input: a regular member creates an invitation; a nonmember tries to
  // Expected status code: 201 for the member, 404 for the nonmember
  // Expected behavior: the new invitation names the member as inviter
  // Expected output: { invitation } with a deep link
  test('Lets any member invite and hides the group from nonmembers', async () => {
    const { id } = await joinedGroup();
    const invite = await post(`/api/groups/${id}/invitations`, 1);
    expect(invite.status).toBe(201);
    expect((await get(`/api/groups/invitations/${tokenOf(invite)}`, 2)).body.inviterName).toBe('Alex');
    expect((await post(`/api/groups/${id}/invitations`, 2)).status).toBe(404);
  });
});

// Interface PUT /api/groups/:groupId/name
describe('Unmocked: PUT /api/groups/:groupId/name', () => {
  testsRejectInvalidSessions((): request.Test => request(app).put(`/api/groups/${shared.groupId}/name`).send({ name: 'Unauthorized rename' }),
    ownerAuth, snapshot, prepareSharedGroup);

  // Input: a member renames to " New name " while also sending ownerId, currency and memberIds
  // Expected status code: 200
  // Expected behavior: only the trimmed name changes; owner, currency and members are untouched
  // Expected output: { group } with name "New name", the original owner and currency CAD
  test('Lets a member rename without changing anything else', async () => {
    const { id } = await joinedGroup();
    const renamed = await rename(id, ' New name ', 1, { ownerId: required(accounts[1]).id, currency: 'USD', memberIds: [] });
    expect(renamed.status).toBe(200);
    expect(renamed.body.group).toMatchObject({ name: 'New name', ownerId: required(accounts[0]).id, currency: 'CAD', memberCount: 2 });
  });

  // Input: rename by a nonmember, and a blank name from a member
  // Expected status code: 404, then 400
  // Expected behavior: the stored name is unchanged
  // Expected output: error message
  test('Rejects nonmembers and blank names', async () => {
    const { id } = await joinedGroup();
    expect((await rename(id, 'Stolen', 2)).status).toBe(404);
    expect((await rename(id, ' ', 1)).status).toBe(400);
    expect((await GroupModel.findById(id))?.name).toBe('Roommates');
  });
});

// Interface DELETE /api/groups/:groupId
describe('Unmocked: DELETE /api/groups/:groupId', () => {
  testsRejectInvalidSessions((): request.Test => request(app).delete(`/api/groups/${shared.groupId}`), ownerAuth, snapshot, prepareSharedGroup);

  // Input: the owner deletes a group that has one member and one expense
  // Expected status code: 204
  // Expected behavior: the group is soft-deleted and hidden from everyone; its invitations stop working; expenses are kept
  // Expected output: empty body; later reads return 404
  test('Hides the group from everyone and invalidates its invitations', async () => {
    const { id, token } = await joinedGroup();
    expect((await post(`/api/groups/${id}/expenses`).send({ description: 'Milk', amount: '3.00', paidBy: required(accounts[0]).id,
      splitMode: 'equal', shares: [{ userId: required(accounts[0]).id }], requestId: 'bill-submission-0001' })).status).toBe(201);
    expect((await remove(id)).status).toBe(204);
    for (const account of [0, 1]) {
      expect((await get('/api/groups', account)).body.groups).toEqual([]);
      expect((await get(`/api/groups/${id}/summary`, account)).status).toBe(404);
    }
    expect((await accept(token, 2)).status).toBe(404);
    expect((await get(`/api/groups/invitations/${token}`, 2)).status).toBe(404);
    expect((await request(app).get(`/invite/${token}`)).status).toBe(404);
    expect(await ExpenseModel.countDocuments({ groupId: id })).toBe(1);
    expect(required(await GroupModel.findById(id)).deletedAt).toBeTruthy();
  });

  // Input: a regular member tries to delete the group
  // Expected status code: 403
  // Expected behavior: the group stays active
  // Expected output: error message
  test('Only the owner can delete', async () => {
    const { id } = await joinedGroup();
    expect((await remove(id, 1)).status).toBe(403);
    expect(required(await GroupModel.findById(id)).deletedAt).toBeFalsy();
  });
});

// Interface GET /invite/:token
describe('Unmocked: GET /invite/:token', () => {
  // Input: public request for a group whose name contains HTML
  // Expected status code: 200
  // Expected behavior: text is escaped, the page links into the app, and opening it never joins the group
  // Expected output: HTML with the escaped name, a ledger:// link and no-store/no-referrer headers
  test('Escapes group text and provides the app link without joining', async () => {
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
    expect(required(await GroupModel.findById(created.body.group.id)).memberIds).toHaveLength(1);
  });

  // Input: a token containing an HTML attribute injection
  // Expected status code: 404
  // Expected behavior: the token never becomes HTML; a strict content security policy is sent
  // Expected output: HTML without <img> or href
  test('Never turns a malicious token into HTML', async () => {
    const token = encodeURIComponent('"><img src=x onerror=alert(1)>');
    const page = await request(app).get(`/invite/${token}`);
    expect(page.status).toBe(404);
    expect(page.text).not.toContain('<img');
    expect(page.text).not.toContain('href=');
    expect(page.headers['content-security-policy']).toContain("default-src 'none'");
  });
});

// Interface GET /.well-known/assetlinks.json
describe('Unmocked: GET /.well-known/assetlinks.json', () => {
  // Input: request without, then with, a valid ANDROID_SHA256_FINGERPRINTS value
  // Expected status code: 503, then 200
  // Expected behavior: App Links are only advertised when the signing fingerprint is configured
  // Expected output: [{ target: { package_name: "com.example.cpen321application" } }]
  test('Serves the configured app association', async () => {
    expect((await request(app).get('/.well-known/assetlinks.json')).status).toBe(503);
    process.env.ANDROID_SHA256_FINGERPRINTS = Array(32).fill('AB').join(':');
    const association = await request(app).get('/.well-known/assetlinks.json');
    expect(association.status).toBe(200);
    expect(association.body[0].target.package_name).toBe('com.example.cpen321application');
  });
});
