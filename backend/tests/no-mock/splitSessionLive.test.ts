import { createServer, type Server as HttpServer } from 'node:http';
import request from 'supertest';
import { MongoMemoryServer } from 'mongodb-memory-server';
import { Server as SocketServer } from 'socket.io';
import { io, type Socket } from 'socket.io-client';
import { createApp } from '../../src/app';
import { GroupModel, ExpenseModel, GroupInvitationModel } from '../../src/groupStore';
import { groupMembershipEvents, type GroupMembershipChange } from '../../src/groupMembershipEvents';
import { attachSplitSessionLive } from '../../src/splitSessionLive';
import { SplitSessionModel } from '../../src/splitSessionStore';
import { splitSessionEvents, type SplitSessionUpdate } from '../../src/splitSessions';
import { startSession } from '../../src/session';
import {
  connectDatabase, disconnectDatabase, mongoSessionStore, mongoUserStore, SessionModel, UserModel,
} from '../../src/userStore';

type TestAccount = { id: string; token: string };
type SubscriptionResult = { ok: boolean; groupId?: string; error?: string };

let mongo: MongoMemoryServer;
let server: HttpServer;
let socketServer: SocketServer;
let live: ReturnType<typeof attachSplitSessionLive>;
let address: string;
let clients: Socket[] = [];
let owner: TestAccount;
let member: TestAccount;
let outsider: TestAccount;
let groupId: string;
let membershipChanges: GroupMembershipChange[];
const captureMembership = (change: GroupMembershipChange) => membershipChanges.push(change);

// The HTTP app, Socket.IO transports, Ledger sessions and MongoDB persistence are all real.
beforeAll(async () => {
  mongo = await MongoMemoryServer.create();
  await connectDatabase(mongo.getUri('split-session-live-test'));
}, 120000);

beforeEach(async () => {
  clients = [];
  membershipChanges = [];
  groupMembershipEvents.on('changed', captureMembership);
  const accounts = await Promise.all(['owner', 'member', 'outsider'].map(async name => {
    const document = await UserModel.create({ email: `${name}@example.com`, displayName: name });
    const user = await mongoUserStore.findById(document.id);
    if (!user) throw new Error('Test account did not persist.');
    const session = await startSession(mongoSessionStore, user);
    return { id: user.id, token: session.token };
  }));
  [owner, member, outsider] = accounts as [TestAccount, TestAccount, TestAccount];
  const group = await GroupModel.create({
    name: 'Live dinner', currency: 'CAD', ownerId: owner.id, memberIds: [owner.id, member.id],
  });
  groupId = group.id;
  server = createServer(createApp());
  socketServer = new SocketServer(server, { transports: ['websocket'], serveClient: false });
  live = attachSplitSessionLive(socketServer, mongoUserStore, mongoSessionStore);
  await new Promise<void>(resolve => server.listen(0, '127.0.0.1', resolve));
  const bound = server.address();
  if (!bound || typeof bound === 'string') throw new Error('Missing HTTP server port.');
  address = `http://127.0.0.1:${bound.port}`;
});

afterEach(async () => {
  live?.close();
  groupMembershipEvents.off('changed', captureMembership);
  clients.forEach(client => { client.removeAllListeners(); client.disconnect(); });
  if (socketServer) await new Promise<void>(resolve => { socketServer.close(() => resolve()); });
  await Promise.all([
    SplitSessionModel.deleteMany({}), ExpenseModel.deleteMany({}), GroupInvitationModel.deleteMany({}), GroupModel.deleteMany({}),
    SessionModel.deleteMany({}), UserModel.deleteMany({}),
  ]);
});

afterAll(async () => {
  await disconnectDatabase();
  await mongo?.stop();
}, 30000);

function clientFor(token?: string): Socket {
  const client = io(`${address}/dutch`, {
    transports: ['websocket'], reconnection: false, forceNew: true, autoConnect: false,
    ...(token ? { auth: { token } } : {}),
  });
  clients.push(client);
  return client;
}

function nextEvent<T>(client: Socket, event: string): Promise<T> {
  return new Promise<T>((resolve, reject) => {
    const receive = (value: T) => { clearTimeout(timer); resolve(value); };
    const timer = setTimeout(() => {
      client.off(event, receive);
      reject(new Error(`Timed out waiting for ${event}.`));
    }, 3000);
    client.once(event, receive);
  });
}

async function connect(token: string): Promise<Socket> {
  const client = clientFor(token);
  const connected = nextEvent<void>(client, 'connect');
  client.connect();
  await connected;
  return client;
}

function subscribe(client: Socket, requestedGroup: unknown = groupId): Promise<SubscriptionResult> {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('Timed out waiting for subscription acknowledgement.')), 3000);
    client.emit('subscribe', { groupId: requestedGroup }, (result: SubscriptionResult) => {
      clearTimeout(timer);
      resolve(result);
    });
  });
}

function createSplit(requestId = 'live-start-00000001') {
  return request(server).post(`/api/groups/${groupId}/split-sessions`).set('Authorization', `Bearer ${owner.token}`).send({
    reviewed: true, requestId, description: 'Dinner', amountCents: 1800, paidBy: owner.id,
    units: [
      { unitId: 'burger-1', description: 'Burger', amountCents: 1200 },
      { unitId: 'fries-1', description: 'Fries', amountCents: 600 },
    ],
  });
}

function claim(sessionId: string, account: TestAccount, unitId = 'burger-1', version = 0, operationKey = 'live-claim-00000001') {
  return request(server).post(`/api/split-sessions/${sessionId}/claims`).set('Authorization', `Bearer ${account.token}`)
    .send({ unitId, action: 'claim', version, operationKey });
}

test('denies a live connection without a Ledger session', async () => {
  const client = clientFor();
  const rejected = nextEvent<Error>(client, 'connect_error');
  client.connect();
  expect((await rejected).message).toMatch(/Authentication required/i);
  expect(client.connected).toBe(false);
  expect(socketServer.of('/dutch').sockets.size).toBe(0);
});

test('denies an authenticated outsider a group subscription', async () => {
  const client = await connect(outsider.token);
  expect(await subscribe(client)).toEqual({ ok: false, error: 'Group not found.' });
  expect(socketServer.of('/dutch').adapter.rooms.get(groupId)).toBeUndefined();
});

test('rejects query operators and malformed group identifiers without joining rooms', async () => {
  const client = await connect(member.token);
  for (const invalid of [{ $ne: null }, { $gt: '' }, { $eq: groupId }, { nested: { $ne: null } }, {}, [groupId], 'invalid', null]) {
    expect(await subscribe(client, invalid)).toEqual({ ok: false, error: 'Group not found.' });
  }
  expect(socketServer.of('/dutch').adapter.rooms.get(groupId)).toBeUndefined();
  expect(await subscribe(client)).toEqual({ ok: true, groupId });
});

test('closing Dutch is idempotent, refuses reconnects and preserves a shared namespace transport', async () => {
  socketServer.of('/pixel').on('connection', socket => {
    socket.on('probe', () => socket.emit('probe-result', 'alive'));
  });
  const dutch = await connect(member.token);
  expect((await subscribe(dutch)).ok).toBe(true);
  const pixel = dutch.io.socket('/pixel');
  clients.push(pixel);
  const pixelConnected = nextEvent<void>(pixel, 'connect');
  pixel.connect();
  await pixelConnected;
  expect(pixel.io).toBe(dutch.io);
  const beforeClose = splitSessionEvents.listenerCount('onGroupUpdated');
  const disconnected = nextEvent<string>(dutch, 'disconnect');
  live.close();
  live.close();
  expect(await disconnected).toBe('io server disconnect');
  expect(splitSessionEvents.listenerCount('onGroupUpdated')).toBe(beforeClose - 1);
  expect(socketServer.of('/dutch').sockets.size).toBe(0);
  expect(pixel.connected).toBe(true);
  const alive = nextEvent<string>(pixel, 'probe-result');
  pixel.emit('probe');
  expect(await alive).toBe('alive');
  const retry = clientFor(member.token);
  const rejected = nextEvent<Error>(retry, 'connect_error');
  retry.connect();
  expect((await rejected).message).toMatch(/closed/);
});

test('ignores nonfunction acknowledgement arguments and continues serving subscriptions', async () => {
  const client = await connect(member.token);
  for (const malformed of ['not-an-ack', 42, { acknowledgement: true }]) {
    client.emit('subscribe', { groupId: 'invalid' }, malformed);
    expect(await subscribe(client)).toEqual({ ok: true, groupId });
  }
  expect(client.connected).toBe(true);
  expect(socketServer.of('/dutch').adapter.rooms.get(groupId)?.size).toBe(1);
});

test('delivers committed claim state to subscribed members and never to an outsider', async () => {
  const created = await createSplit();
  expect(created.status).toBe(201);
  const ownerClient = await connect(owner.token);
  const memberClient = await connect(member.token);
  const outsiderClient = await connect(outsider.token);
  expect(await subscribe(ownerClient)).toEqual({ ok: true, groupId });
  expect(await subscribe(memberClient)).toEqual({ ok: true, groupId });
  expect((await subscribe(outsiderClient)).ok).toBe(false);
  const outsiderUpdates: SplitSessionUpdate[] = [];
  outsiderClient.on('onGroupUpdated', (update: SplitSessionUpdate) => outsiderUpdates.push(update));
  const ownerUpdate = nextEvent<SplitSessionUpdate>(ownerClient, 'onGroupUpdated');
  const memberUpdate = nextEvent<SplitSessionUpdate>(memberClient, 'onGroupUpdated');
  const accepted = await claim(created.body.session.id as string, member);
  expect(accepted.status).toBe(200);
  const updates = await Promise.all([ownerUpdate, memberUpdate]);
  for (const update of updates) {
    expect(update).toMatchObject({
      groupId, sequence: 2,
      event: { groupId, type: 'claim', actorId: member.id, operationKey: 'live-claim-00000001', sequence: 2 },
      session: { id: created.body.session.id, version: 1, sequence: 2 },
    });
    expect(update.session).toEqual(accepted.body.session);
  }
  const stored = await SplitSessionModel.findById(created.body.session.id).lean();
  expect(stored?.units.find(unit => unit.unitId === 'burger-1')?.ownerId?.toString()).toBe(member.id);
  expect(stored?.operations.find(operation => operation.operationKey === 'live-claim-00000001')?.state).toBe('complete');
  expect(outsiderUpdates).toEqual([]);
});

test('revocation after connection rejects another subscription and disconnects the socket', async () => {
  const client = await connect(member.token);
  expect((await request(server).post('/api/auth/logout').set('Authorization', `Bearer ${member.token}`)).status).toBe(204);
  const disconnected = nextEvent<string>(client, 'disconnect');
  expect(await subscribe(client)).toEqual({ ok: false, error: 'Session expired.' });
  expect(await disconnected).toBe('io server disconnect');
  expect(client.connected).toBe(false);
  expect(socketServer.of('/dutch').adapter.rooms.get(groupId)).toBeUndefined();
});

test('a previously subscribed revoked session cannot receive a subsequent committed update', async () => {
  const created = await createSplit();
  expect(created.status).toBe(201);
  const ownerClient = await connect(owner.token);
  const memberClient = await connect(member.token);
  expect((await subscribe(ownerClient)).ok).toBe(true);
  expect((await subscribe(memberClient)).ok).toBe(true);
  const leaked: SplitSessionUpdate[] = [];
  memberClient.on('onGroupUpdated', (update: SplitSessionUpdate) => leaked.push(update));
  expect((await request(server).post('/api/auth/logout').set('Authorization', `Bearer ${member.token}`)).status).toBe(204);
  const disconnected = nextEvent<string>(memberClient, 'disconnect');
  const delivered = nextEvent<SplitSessionUpdate>(ownerClient, 'onGroupUpdated');
  expect((await claim(created.body.session.id as string, owner)).status).toBe(200);
  expect((await delivered).session.version).toBe(1);
  expect(await disconnected).toBe('io server disconnect');
  expect(leaked).toEqual([]);
  expect((await request(server).get(`/api/groups/${groupId}/snapshot`).set('Authorization', `Bearer ${member.token}`)).status).toBe(401);
});

test('a fresh reconnect installs the latest snapshot across sessions then receives the next sequence', async () => {
  const first = await createSplit();
  expect(first.status).toBe(201);
  const original = await connect(member.token);
  expect((await subscribe(original)).ok).toBe(true);
  const before = await request(server).get(`/api/groups/${groupId}/snapshot`).set('Authorization', `Bearer ${member.token}`);
  expect(before.status).toBe(200);
  expect(before.body.splitSessions).toHaveLength(1);
  expect(before.body.splitSessions[0]).toMatchObject({ id: first.body.session.id, sequence: 1 });
  original.disconnect();

  expect((await claim(first.body.session.id as string, owner)).status).toBe(200);
  const second = await createSplit('live-start-00000002');
  expect(second.status).toBe(201);
  expect((await claim(second.body.session.id as string, owner, 'burger-1', 0, 'live-claim-00000002')).status).toBe(200);

  const reconnected = await connect(member.token);
  expect((await subscribe(reconnected)).ok).toBe(true);
  const snapshot = await request(server).get(`/api/groups/${groupId}/snapshot`).set('Authorization', `Bearer ${member.token}`);
  expect(snapshot.status).toBe(200);
  expect(snapshot.body.splitSessions).toHaveLength(2);
  expect(snapshot.body.splitSessions).toEqual(expect.arrayContaining([
    expect.objectContaining({ id: first.body.session.id, version: 1, sequence: 2 }),
    expect.objectContaining({ id: second.body.session.id, version: 1, sequence: 2 }),
  ]));
  const update = nextEvent<SplitSessionUpdate>(reconnected, 'onGroupUpdated');
  const accepted = await claim(first.body.session.id as string, member, 'fries-1', 1, 'live-claim-00000003');
  expect(accepted.status).toBe(200);
  const received = await update;
  expect(received).toMatchObject({ event: { sessionId: first.body.session.id, sequence: 3 }, session: { version: 2, sequence: 3 } });
  expect(received.session).toEqual(accepted.body.session);
});

async function joinInvitation() {
  const response = await request(server).post(`/api/groups/${groupId}/invitations`)
    .set('Authorization', `Bearer ${owner.token}`);
  expect(response.status).toBe(201);
  return String(response.body.invitation.deepLink).split('/').at(-1)!;
}

function acceptInvitation(token: string) {
  return request(server).post(`/api/groups/invitations/${token}/accept`)
    .set('Authorization', `Bearer ${outsider.token}`);
}

test('a persisted join notifies existing members, but never outsiders or another group', async () => {
  const ownerClient = await connect(owner.token);
  const memberClient = await connect(member.token);
  const outsiderClient = await connect(outsider.token);
  expect((await subscribe(ownerClient)).ok).toBe(true);
  expect((await subscribe(memberClient)).ok).toBe(true);
  const other = await GroupModel.create({ name: 'Other', ownerId: outsider.id, memberIds: [outsider.id] });
  expect((await subscribe(outsiderClient, other.id)).ok).toBe(true);
  const leaked: unknown[] = [];
  outsiderClient.on('onGroupMembershipChanged', event => leaked.push(event));
  const token = await joinInvitation();
  const deliveries = [ownerClient, memberClient].map(client => nextEvent<GroupMembershipChange>(client, 'onGroupMembershipChanged'));
  expect((await acceptInvitation(token)).status).toBe(200);
  expect(await Promise.all(deliveries)).toEqual([
    { groupId, type: 'joined' }, { groupId, type: 'joined' },
  ]);
  const canonical = await request(server).get(`/api/groups/${groupId}/summary`).set('Authorization', `Bearer ${member.token}`);
  expect(canonical.body.group.memberCount).toBe(3);
  expect(canonical.body.group.members).toEqual(expect.arrayContaining([expect.objectContaining({ id: outsider.id })]));
  expect(leaked).toEqual([]);
});

test('concurrent and repeated accepts publish exactly one join and persist one member', async () => {
  const client = await connect(owner.token);
  expect((await subscribe(client)).ok).toBe(true);
  const token = await joinInvitation();
  const delivered = nextEvent<GroupMembershipChange>(client, 'onGroupMembershipChanged');
  const responses = await Promise.all([acceptInvitation(token), acceptInvitation(token)]);
  expect(responses.map(response => response.status)).toEqual([200, 200]);
  expect(responses.map(response => response.body.group.memberCount)).toEqual([3, 3]);
  await delivered;
  expect((await acceptInvitation(token)).status).toBe(200);
  expect(membershipChanges).toEqual([{ groupId, type: 'joined' }]);
  expect((await GroupModel.findById(groupId))!.memberIds.filter(id => id.toString() === outsider.id)).toHaveLength(1);
});

test('expired and unauthorized invitation acceptance never publishes a successful join', async () => {
  expect((await acceptInvitation('a'.repeat(43))).status).toBe(404);
  const token = await joinInvitation();
  expect((await request(server).post(`/api/groups/invitations/${token}/accept`)).status).toBe(401);
  expect(membershipChanges).toEqual([]);
});

test('a revoked subscriber receives no membership change, while an active member does', async () => {
  const ownerClient = await connect(owner.token);
  const memberClient = await connect(member.token);
  await subscribe(ownerClient); await subscribe(memberClient);
  const leaked: unknown[] = [];
  memberClient.on('onGroupMembershipChanged', event => leaked.push(event));
  await request(server).post('/api/auth/logout').set('Authorization', `Bearer ${member.token}`);
  const token = await joinInvitation();
  const disconnected = nextEvent<string>(memberClient, 'disconnect');
  const delivered = nextEvent<GroupMembershipChange>(ownerClient, 'onGroupMembershipChanged');
  expect((await acceptInvitation(token)).status).toBe(200);
  expect((await delivered).type).toBe('joined');
  expect(await disconnected).toBe('io server disconnect');
  expect(leaked).toEqual([]);
});

test('reconnecting after an offline join recovers the canonical roster', async () => {
  const client = await connect(member.token);
  await subscribe(client); client.disconnect();
  const token = await joinInvitation();
  expect((await acceptInvitation(token)).status).toBe(200);
  const reconnected = await connect(member.token);
  expect((await subscribe(reconnected)).ok).toBe(true);
  const summary = await request(server).get(`/api/groups/${groupId}/summary`).set('Authorization', `Bearer ${member.token}`);
  expect(summary.body.group.memberCount).toBe(3);
  expect(summary.body.group.members.map((item: { id: string }) => item.id)).toContain(outsider.id);
});

test('group deletion notifies members and releases their group subscription', async () => {
  const client = await connect(member.token);
  await subscribe(client);
  const deleted = nextEvent<GroupMembershipChange>(client, 'onGroupMembershipChanged');
  expect((await request(server).delete(`/api/groups/${groupId}`).set('Authorization', `Bearer ${owner.token}`)).status).toBe(204);
  expect(await deleted).toEqual({ groupId, type: 'deleted' });
  expect(socketServer.of('/dutch').adapter.rooms.get(groupId)).toBeUndefined();
  expect((await subscribe(client)).ok).toBe(false);
});
