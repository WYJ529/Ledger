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
import { startSession } from '../../src/session';
import { connectDatabase, disconnectDatabase, mongoSessionStore, mongoUserStore, SessionModel, UserModel } from '../../src/userStore';

export interface TestAccount { id: string; token: string }
export interface SubscriptionResult { ok: boolean; groupId?: string; error?: string }

/** Current per-test state of the live server; refreshed before every test. */
export const harness = {
  server: undefined as unknown as HttpServer,
  socketServer: undefined as unknown as SocketServer,
  live: undefined as unknown as ReturnType<typeof attachSplitSessionLive>,
  owner: undefined as unknown as TestAccount,
  member: undefined as unknown as TestAccount,
  outsider: undefined as unknown as TestAccount,
  groupId: '',
  membershipChanges: [] as GroupMembershipChange[],
};
let address = '';
let clients: Socket[] = [];
const captureMembership = (change: GroupMembershipChange) => harness.membershipChanges.push(change);

/**
 * A real HTTP app, Socket.IO server, Ledger sessions and an isolated in-memory MongoDB.
 * Each test gets an owner and member in one group plus an outsider.
 */
export function useLiveHarness(databaseName: string) {
  let mongo: MongoMemoryServer | undefined;
  let activeLive: ReturnType<typeof attachSplitSessionLive> | undefined;
  let closeServer: (() => Promise<void>) | undefined;
  beforeAll(async () => {
    mongo = await MongoMemoryServer.create();
    await connectDatabase(mongo.getUri(databaseName));
  }, 120000);

  beforeEach(async () => {
    clients = [];
    harness.membershipChanges = [];
    groupMembershipEvents.on('changed', captureMembership);
    const accounts = await Promise.all(['owner', 'member', 'outsider'].map(async name => {
      const document = await UserModel.create({ email: `${name}@example.com`, displayName: name });
      const user = await mongoUserStore.findById(document.id);
      if (!user) throw new Error('Test account did not persist.');
      return { id: user.id, token: (await startSession(mongoSessionStore, user)).token };
    }));
    [harness.owner, harness.member, harness.outsider] = accounts as [TestAccount, TestAccount, TestAccount];
    const group = await GroupModel.create({
      name: 'Live dinner', currency: 'CAD', ownerId: harness.owner.id, memberIds: [harness.owner.id, harness.member.id],
    });
    harness.groupId = group.id;
    harness.server = createServer(createApp());
    harness.socketServer = new SocketServer(harness.server, { transports: ['websocket'], serveClient: false });
    closeServer = async () => { await harness.socketServer.close(); };
    harness.live = attachSplitSessionLive(harness.socketServer, mongoUserStore, mongoSessionStore);
    activeLive = harness.live;
    await new Promise<void>(resolve => harness.server.listen(0, '127.0.0.1', resolve));
    const bound = harness.server.address();
    if (!bound || typeof bound === 'string') throw new Error('Missing HTTP server port.');
    address = `http://127.0.0.1:${bound.port}`;
  });

  afterEach(async () => {
    jest.restoreAllMocks();
    activeLive?.close();
    activeLive = undefined;
    groupMembershipEvents.off('changed', captureMembership);
    clients.forEach(client => { client.removeAllListeners(); client.disconnect(); });
    await closeServer?.();
    closeServer = undefined;
    await Promise.all([
      SplitSessionModel.deleteMany({}), ExpenseModel.deleteMany({}), GroupInvitationModel.deleteMany({}), GroupModel.deleteMany({}),
      SessionModel.deleteMany({}), UserModel.deleteMany({}),
    ]);
  });

  afterAll(async () => {
    await disconnectDatabase();
    await mongo?.stop();
  }, 30000);
}

export function clientFor(token?: string): Socket {
  const client = io(`${address}/dutch`, {
    transports: ['websocket'], reconnection: false, forceNew: true, autoConnect: false,
    ...(token ? { auth: { token } } : {}),
  });
  clients.push(client);
  return client;
}

export function trackClient(client: Socket) { clients.push(client); }

export function nextEvent<T>(client: Socket, event: string): Promise<T> {
  return new Promise<T>((resolve, reject) => {
    const receive = (value: T) => { clearTimeout(timer); resolve(value); };
    const timer = setTimeout(() => {
      client.off(event, receive);
      reject(new Error(`Timed out waiting for ${event}.`));
    }, 3000);
    client.once(event, receive);
  });
}

export async function connect(token: string): Promise<Socket> {
  const client = clientFor(token);
  const connected = nextEvent<undefined>(client, 'connect');
  client.connect();
  await connected;
  return client;
}

export function subscribe(client: Socket, requestedGroup: unknown = harness.groupId): Promise<SubscriptionResult> {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => { reject(new Error('Timed out waiting for subscription acknowledgement.')); }, 3000);
    client.emit('subscribe', { groupId: requestedGroup }, (result: SubscriptionResult) => {
      clearTimeout(timer);
      resolve(result);
    });
  });
}

/** Connects a socket for the account and subscribes it to the group; the subscription must succeed. */
export async function subscribed(account: TestAccount, group: string = harness.groupId): Promise<Socket> {
  const client = await connect(account.token);
  expect(await subscribe(client, group)).toEqual({ ok: true, groupId: group });
  return client;
}

/** Collects every event of this name the socket receives. */
export function collect<T>(client: Socket, event: string): T[] {
  const received: T[] = [];
  client.on(event, (value: T) => received.push(value));
  return received;
}

export function createSplit(requestId = 'live-start-00000001') {
  return request(harness.server).post(`/api/groups/${harness.groupId}/split-sessions`).set('Authorization', `Bearer ${harness.owner.token}`).send({
    reviewed: true, requestId, description: 'Dinner', amountCents: 1800, paidBy: harness.owner.id,
    units: [
      { unitId: 'burger-1', description: 'Burger', amountCents: 1200 },
      { unitId: 'fries-1', description: 'Fries', amountCents: 600 },
    ],
  });
}

export function claim(sessionId: string, account: TestAccount, unitId = 'burger-1', version = 0, operationKey = 'live-claim-00000001') {
  return request(harness.server).post(`/api/split-sessions/${sessionId}/claims`).set('Authorization', `Bearer ${account.token}`)
    .send({ unitId, action: 'claim', version, operationKey });
}

export function logout(account: TestAccount) {
  return request(harness.server).post('/api/auth/logout').set('Authorization', `Bearer ${account.token}`);
}

export function api(method: 'get' | 'post' | 'put' | 'delete', path: string, account?: TestAccount) {
  const agent = request(harness.server);
  const call = method === 'get'
    ? agent.get(path)
    : method === 'post'
    ? agent.post(path)
    : method === 'put'
    ? agent.put(path)
    : agent.delete(path);
  return account ? call.set('Authorization', `Bearer ${account.token}`) : call;
}
