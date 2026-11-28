import { randomBytes } from 'node:crypto';
import mongoose from 'mongoose';
import request, { type Test } from 'supertest';
import { createApp } from '../../src/app';
import { ExpenseModel, GroupInvitationModel, GroupModel } from '../../src/groupStore';
import { hashToken, startSession } from '../../src/session';
import { createSplitSessionsRouter } from '../../src/splitSessions';
import { mongoSessionStore, mongoUserStore, SessionModel } from '../../src/userStore';
import {
  api, claim, clientFor, collect, createSplit, harness, logout, nextEvent, subscribe, subscribed, useLiveHarness,
} from '../helpers/liveHarness';

// NFR-1 Access Control and Privacy: every protected request and live subscription verifies the session,
// and group data is only served to current members. Real HTTP, Socket.IO and MongoDB; nothing is mocked.
useLiveHarness('nfr-access-control');

type Method = 'get' | 'post' | 'put' | 'patch' | 'delete';
interface Ids { groupId: string; expenseId: string; splitId: string; operationKey: string; invitation: string; memberId: string }
interface Protected { keys: string[]; method: Method; url: (ids: Ids) => string; body?: object; groupData?: boolean }

// Routes that are public by design. Each one either creates a session from credentials in the body,
// serves non-personal data, or is a rate-limited guest service.
const PUBLIC_ROUTES = [
  'POST /register', 'POST /login', 'POST /google', 'POST /google/register', 'POST /google/link',
  'GET /meta', 'GET /exchange', 'POST /imports/statement-preview',
  'GET /invite/:token', 'GET /.well-known/assetlinks.json', 'GET /health',
];

// `keys` are the paths as declared in their routers; `groupData` marks routes that expose or change one group's data.
const PROTECTED_ROUTES: Protected[] = [
  { keys: ['POST /api/ledger/imports/preview', 'POST /imports/preview'], method: 'post', url: () => '/api/ledger/imports/preview', body: {} },
  { keys: ['POST /logout'], method: 'post', url: () => '/api/auth/logout' },
  { keys: ['GET /me'], method: 'get', url: () => '/api/users/me' },
  { keys: ['PATCH /me'], method: 'patch', url: () => '/api/users/me', body: { displayName: 'Changed' } },
  { keys: ['DELETE /me'], method: 'delete', url: () => '/api/users/me', body: {} },
  { keys: ['GET /'], method: 'get', url: () => '/api/ledger-saves' },
  { keys: ['GET /:slot'], method: 'get', url: () => '/api/ledger-saves/1' },
  { keys: ['PUT /:slot'], method: 'put', url: () => '/api/ledger-saves/1', body: {} },
  { keys: ['PATCH /:slot'], method: 'patch', url: () => '/api/ledger-saves/1', body: { name: 'Renamed' } },
  { keys: ['DELETE /:slot'], method: 'delete', url: () => '/api/ledger-saves/1' },
  { keys: ['GET /'], method: 'get', url: () => '/api/groups' },
  { keys: ['POST /'], method: 'post', url: () => '/api/groups', body: { name: 'New group', currency: 'CAD', requestId: 'nfr-new-group-0001' } },
  { keys: ['GET /invitations/:token'], method: 'get', url: ids => `/api/groups/invitations/${ids.invitation}` },
  { keys: ['POST /invitations/:token/accept'], method: 'post', url: ids => `/api/groups/invitations/${ids.invitation}/accept` },
  { keys: ['POST /:groupId/invitations'], method: 'post', url: ids => `/api/groups/${ids.groupId}/invitations`, groupData: true },
  { keys: ['PUT /:groupId/name'], method: 'put', url: ids => `/api/groups/${ids.groupId}/name`, body: { name: 'Renamed' }, groupData: true },
  { keys: ['DELETE /:groupId'], method: 'delete', url: ids => `/api/groups/${ids.groupId}`, groupData: true },
  { keys: ['POST /:groupId/leave'], method: 'post', url: ids => `/api/groups/${ids.groupId}/leave`, groupData: true },
  { keys: ['DELETE /:groupId/members/:memberId'], method: 'delete', url: ids => `/api/groups/${ids.groupId}/members/${ids.memberId}`, groupData: true },
  { keys: ['GET /:groupId/summary'], method: 'get', url: ids => `/api/groups/${ids.groupId}/summary`, groupData: true },
  {
    keys: ['POST /:groupId/expenses'], method: 'post', url: ids => `/api/groups/${ids.groupId}/expenses`, groupData: true,
    body: { description: 'Taxi', amount: '10.00', splitMode: 'equal', requestId: 'nfr-new-expense-0001' },
  },
  {
    keys: ['PUT /:groupId/expenses/:expenseId'], method: 'put', url: ids => `/api/groups/${ids.groupId}/expenses/${ids.expenseId}`,
    body: { description: 'Changed', amount: '10.00', splitMode: 'equal', revision: 0 }, groupData: true,
  },
  { keys: ['DELETE /:groupId/expenses/:expenseId'], method: 'delete', url: ids => `/api/groups/${ids.groupId}/expenses/${ids.expenseId}`, groupData: true },
  { keys: ['POST /:groupId/shared-entries'], method: 'post', url: ids => `/api/groups/${ids.groupId}/shared-entries`, body: {}, groupData: true },
  {
    keys: ['GET /:groupId/expenses/:expenseId/personal-entry'], method: 'get', groupData: true,
    url: ids => `/api/groups/${ids.groupId}/expenses/${ids.expenseId}/personal-entry`,
  },
  {
    keys: ['POST /:groupId/expenses/:expenseId/personal-entry/imported'], method: 'post', body: {}, groupData: true,
    url: ids => `/api/groups/${ids.groupId}/expenses/${ids.expenseId}/personal-entry/imported`,
  },
  { keys: ['POST /groups/:groupId/split-sessions'], method: 'post', url: ids => `/api/groups/${ids.groupId}/split-sessions`, body: {}, groupData: true },
  { keys: ['GET /groups/:groupId/snapshot'], method: 'get', url: ids => `/api/groups/${ids.groupId}/snapshot`, groupData: true },
  {
    keys: ['POST /split-sessions/:sessionId/claims'], method: 'post', url: ids => `/api/split-sessions/${ids.splitId}/claims`, groupData: true,
    body: { unitId: 'fries-1', action: 'claim', version: 1, operationKey: 'nfr-claim-fries-0001' },
  },
  { keys: ['POST /split-sessions/:sessionId/finalize'], method: 'post', url: ids => `/api/split-sessions/${ids.splitId}/finalize`, body: {}, groupData: true },
  { keys: ['GET /operations/:operationKey'], method: 'get', url: ids => `/api/operations/${ids.operationKey}`, groupData: true },
];

interface Layer { route?: { path: string; methods: Record<string, boolean> }; handle?: { stack?: Layer[] } }

function declaredRoutes(stack: Layer[]): string[] {
  return stack.flatMap(layer => {
    if (layer.route) {
      const path = layer.route.path;
      return Object.keys(layer.route.methods).map(method => `${method.toUpperCase()} ${path}`);
    }
    return layer.handle?.stack ? declaredRoutes(layer.handle.stack) : [];
  });
}

function send(route: Protected, ids: Ids): Test {
  const call = request(harness.server)[route.method](route.url(ids));
  return route.body ? call.send(route.body) : call;
}

/** Every stored document, so a rejected request can be shown to have changed nothing. */
async function storedData() {
  const database = mongoose.connection.db;
  if (!database) throw new Error('The access-control test database is not connected.');
  const collections = await database.collections();
  const entries = await Promise.all(collections.map(async collection =>
    [collection.collectionName, await collection.find({}).sort({ _id: 1 }).toArray()] as const));
  return Object.fromEntries(entries.sort(([a], [b]) => a.localeCompare(b)));
}

const ids: Ids = { groupId: '', expenseId: '', splitId: '', operationKey: 'nfr-claim-burger-0001', invitation: '', memberId: '' };

beforeEach(async () => {
  ids.groupId = harness.groupId;
  ids.memberId = harness.member.id;
  const expense = await ExpenseModel.create({
    groupId: harness.groupId, description: 'Dinner', amountCents: 1000, currency: 'CAD', paidBy: harness.owner.id,
    createdBy: harness.owner.id, splitMode: 'equal', requestId: 'nfr-fixture-expense',
    shares: [{ userId: harness.owner.id, amountCents: 500 }, { userId: harness.member.id, amountCents: 500 }],
  });
  ids.expenseId = expense.id;
  const split = await createSplit();
  expect(split.status).toBe(201);
  ids.splitId = split.body.session.id;
  expect((await claim(ids.splitId, harness.member, 'burger-1', 0, ids.operationKey)).status).toBe(200);
  ids.invitation = randomBytes(32).toString('base64url');
  await GroupInvitationModel.create({
    groupId: harness.groupId, inviterId: harness.owner.id, tokenHash: hashToken(ids.invitation), expiresAt: new Date(Date.now() + 86400000),
  });
});

describe('NFR-1 Access Control and Privacy', () => {
  // Input: the route table of the real Express app, plus the split-session router that app.ts dispatches through a closure
  // Expected behavior: every declared route is either in PROTECTED_ROUTES or deliberately listed as public
  // Expected output: the two lists match exactly, so a new route cannot skip the checks below unnoticed
  test('Every backend route is classified as protected or public', () => {
    const app = createApp() as unknown as { router: { stack: Layer[] } };
    const split = createSplitSessionsRouter(mongoUserStore, mongoSessionStore) as unknown as { stack: Layer[] };
    const declared = [...declaredRoutes(app.router.stack), ...declaredRoutes(split.stack)].sort();
    const classified = [...PUBLIC_ROUTES, ...PROTECTED_ROUTES.flatMap(route => route.keys)].sort();
    expect(declared).toEqual(classified);
  });

  const credentials: [string, () => string | undefined | Promise<string>][] = [
    ['no session', () => undefined],
    ['an unknown session', () => 'unknown-session-token-00000000'],
    ['an expired session', async () => {
      await SessionModel.updateOne({ tokenHash: hashToken(harness.owner.token) }, { expiresAt: new Date(Date.now() - 1000) });
      return harness.owner.token;
    }],
    ['a signed-out session', async () => {
      expect((await logout(harness.owner)).status).toBe(204);
      return harness.owner.token;
    }],
  ];

  // Input: each protected route, sent by the group owner's client with no, unknown, expired or signed-out credentials
  // Expected status code: 401
  // Expected behavior: the request is rejected before any data is read or changed
  // Expected output: Bearer challenge and { error: "Your session has expired. Please sign in again." }; stored data unchanged
  test.each(credentials)('Every protected route rejects %s', async (_name, token) => {
    const bearer = await token();
    const before = await storedData();
    for (const route of PROTECTED_ROUTES) {
      const call = send(route, ids);
      const response = await (bearer ? call.set('Authorization', `Bearer ${bearer}`) : call);
      expect({ route: route.keys[0], status: response.status, challenge: response.headers['www-authenticate'] })
        .toEqual({ route: route.keys[0], status: 401, challenge: 'Bearer' });
      expect(response.body).toEqual({ error: 'Your session has expired. Please sign in again.' });
    }
    expect(await storedData()).toEqual(before);
  });

  // Input: every route that exposes or changes one group's data, sent with a valid session of a user outside the group
  // Expected status code: 404
  // Expected behavior: a non-member cannot tell the group exists; stored data unchanged
  // Expected output: a "not found" error for every route
  test('Group routes treat a signed-in non-member as if the group did not exist', async () => {
    const before = await storedData();
    for (const route of PROTECTED_ROUTES.filter(entry => entry.groupData)) {
      const response = await send(route, ids).set('Authorization', `Bearer ${harness.outsider.token}`);
      expect({ route: route.keys[0], status: response.status }).toEqual({ route: route.keys[0], status: 404 });
      expect(response.body.error).toMatch(/not found/);
    }
    expect(await storedData()).toEqual(before);
  });

  // Input: owner-only actions (delete group, remove member, finalize split) sent by a group member who is not the owner
  // Expected status code: 403
  // Expected behavior: role permissions are enforced; stored data unchanged
  // Expected output: the owner-only error message for each action
  test('Owner-only actions are refused for an ordinary member', async () => {
    const before = await storedData();
    const attempts: [string, Test, string][] = [
      ['delete group', api('delete', `/api/groups/${ids.groupId}`, harness.member), 'Only the group owner can delete this group.'],
      ['remove member', api('delete', `/api/groups/${ids.groupId}/members/${harness.owner.id}`, harness.member), 'Only the group owner can remove members.'],
      ['finalize split', api('post', `/api/split-sessions/${ids.splitId}/finalize`, harness.member).send({}),
        'Only the group owner can finalize a split session.'],
    ];
    for (const [action, attempt, error] of attempts) {
      const response = await attempt;
      expect({ action, status: response.status, error: response.body.error }).toEqual({ action, status: 403, error });
    }
    expect(await storedData()).toEqual(before);
  });

  // Input: Socket.IO connections to /dutch with no token, an unknown token, and a valid outsider subscribing to the group
  // Expected behavior: unauthenticated connections are refused; a non-member's subscription is refused
  // Expected output: connect_error "Authentication required." / "Session expired."; acknowledgement { ok: false, error: "Group not found." }
  test('Live updates refuse unauthenticated clients and non-members', async () => {
    for (const [token, message] of [[undefined, 'Authentication required.'], ['unknown-session-token-00000000', 'Session expired.']] as const) {
      const client = clientFor(token);
      const refused = nextEvent<Error>(client, 'connect_error');
      client.connect();
      expect((await refused).message).toBe(message);
    }
    const outsider = clientFor(harness.outsider.token);
    const connected = nextEvent<undefined>(outsider, 'connect');
    outsider.connect();
    await connected;
    expect(await subscribe(outsider)).toEqual({ ok: false, error: 'Group not found.' });
  });

  // Input: a subscribed member is removed by the owner, and another subscribed member signs out; the owner then claims an item
  // Expected behavior: the removed member gets only their own access notice; the signed-out socket is disconnected; neither gets group data
  // Expected output: the owner's socket receives the claim; the other two sockets receive no onGroupUpdated event
  test('Live updates stop for removed members and signed-out sessions', async () => {
    const extra = await mongoUserStore.create({ displayName: 'Second', email: 'second@example.com' });
    const secondAccount = { id: extra.id, token: (await startSession(mongoSessionStore, extra)).token };
    await GroupModel.updateOne({ _id: ids.groupId }, { $push: { memberIds: extra.id } });
    const ownerSocket = await subscribed(harness.owner);
    const removedSocket = await subscribed(harness.member);
    const signedOutSocket = await subscribed(secondAccount);
    const removedUpdates = collect(removedSocket, 'onGroupUpdated');
    const signedOutUpdates = collect(signedOutSocket, 'onGroupUpdated');

    const notice = nextEvent<{ type: string }>(removedSocket, 'onGroupMembershipChanged');
    expect((await api('delete', `/api/groups/${ids.groupId}/members/${harness.member.id}`, harness.owner)).status).toBe(204);
    expect((await notice).type).toBe('removed');
    expect((await logout(secondAccount)).status).toBe(204);

    const disconnected = nextEvent<string>(signedOutSocket, 'disconnect');
    const delivered = nextEvent(ownerSocket, 'onGroupUpdated');
    expect((await claim(ids.splitId, harness.owner, 'fries-1', 1, 'nfr-owner-claims-fries')).status).toBe(200);
    await delivered;
    expect(await disconnected).toBe('io server disconnect');
    expect(removedUpdates).toEqual([]);
    expect(signedOutUpdates).toEqual([]);
  });
});
