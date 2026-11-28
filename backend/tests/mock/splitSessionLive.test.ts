import { required } from '../helpers/required';
import { GroupModel } from '../../src/groupStore';
import { groupMembershipEvents, type GroupChange, type GroupMembershipChange } from '../../src/groupMembershipEvents';
import type { SplitSessionUpdate } from '../../src/splitSessions';
import { hashToken } from '../../src/session';
import { mongoSessionStore } from '../../src/userStore';
import { databaseFailure, failNextFindOne } from '../helpers/groupApiFixture';
import { api, claim, clientFor, collect, connect, createSplit, harness, nextEvent, subscribe, useLiveHarness, type TestAccount } from '../helpers/liveHarness';

// Mocked components: the MongoDB session store and group model lookups used by the live namespace reject.
// The HTTP app, Socket.IO transport and the rest of MongoDB are real.
useLiveHarness('split-session-live-mock-test');
beforeEach(() => { jest.spyOn(console, 'error').mockImplementation(() => undefined); });

// Fails only the live publisher's roster read, recognised by the fields it selects; HTTP lookups stay real.
function failPublisherGroupLookup() {
  const realFindOne = GroupModel.findOne.bind(GroupModel);
  jest.spyOn(GroupModel, 'findOne').mockImplementation(((...args: Parameters<typeof GroupModel.findOne>) => {
    const query = realFindOne(...args);
    const realSelect = query.select.bind(query);
    jest.spyOn(query, 'select').mockImplementation(((fields: string) => {
      if (fields === 'memberIds deletedAt') jest.spyOn(query, 'exec').mockRejectedValueOnce(databaseFailure());
      return realSelect(fields);
    }) as typeof query.select);
    return query;
  }) as typeof GroupModel.findOne);
}
// Fails session checks for one account only, so other callers still authenticate.
function failSessionChecksFor(account: TestAccount) {
  const realFind = mongoSessionStore.find.bind(mongoSessionStore);
  const blocked = hashToken(account.token);
  jest.spyOn(mongoSessionStore, 'find').mockImplementation(async tokenHash => {
    if (tokenHash === blocked) throw databaseFailure();
    return realFind(tokenHash);
  });
}
const publishSettled = () => new Promise(resolve => setTimeout(resolve, 150));

// Interface Socket.IO /dutch connection
describe('Mocked: Socket.IO /dutch connection', () => {
  // Input: shutdown while verifying a valid token or after the handshake middleware accepts it.
  // Expected status code: none (Socket.IO).
  // Expected behavior: prevent a new connection from remaining in the closed namespace.
  // Expected output: handshake error or immediate disconnect, with no live sockets.
  test.each(['during verification', 'after verification'])('closes a connection %s', async phase => {
    const namespace = harness.socketServer.of('/dutch');
    const client = clientFor(harness.member.token);
    if (phase === 'during verification') {
      const find = mongoSessionStore.find.bind(mongoSessionStore);
      jest.spyOn(mongoSessionStore, 'find').mockImplementationOnce(async key => {
        const session = await find(key);
        harness.live.close();
        return session;
      });
      const error = nextEvent<Error>(client, 'connect_error');
      client.connect();
      expect((await error).message).toBe('Live updates are closed.');
    } else {
      namespace.use((_socket, next) => { harness.live.close(); next(); });
      const disconnected = nextEvent<string>(client, 'disconnect');
      client.connect();
      expect(await disconnected).toBe('io server disconnect');
    }
    expect(namespace.sockets.size).toBe(0);
  });

  // Input: connection with a valid token; mock: the session store lookup rejects
  // Expected status code: none (Socket.IO connect_error)
  // Expected behavior: the handshake fails closed instead of admitting an unverified socket
  // Expected output: connect_error "Authentication unavailable."
  test('Refuses the connection when the session store fails', async () => {
    jest.spyOn(mongoSessionStore, 'find').mockRejectedValueOnce(databaseFailure());
    const client = clientFor(harness.member.token);
    const rejected = nextEvent<Error>(client, 'connect_error');
    client.connect();
    expect((await rejected).message).toBe('Authentication unavailable.');
    expect(harness.socketServer.of('/dutch').sockets.size).toBe(0);
  });
});

// Interface Socket.IO /dutch subscribe
describe('Mocked: Socket.IO /dutch subscribe', () => {
  // Input: shutdown while a subscription's session lookup is in flight.
  // Expected status code: none (Socket.IO).
  // Expected behavior: finish the in-flight check without joining or acknowledging success.
  // Expected output: no room and no acknowledgement.
  test('discards an in-flight subscription on shutdown', async () => {
    const client = await connect(harness.member.token);
    const namespace = harness.socketServer.of('/dutch');
    const socket = required(namespace.sockets.get(required(client.id)));
    const find = mongoSessionStore.find.bind(mongoSessionStore);
    jest.spyOn(mongoSessionStore, 'find').mockImplementationOnce(async key => {
      const saved = await find(key);
      harness.live.close();
      return saved;
    });
    const ack = jest.fn();
    const [subscribeListener] = socket.listeners('subscribe');
    await required(subscribeListener)({ groupId: harness.groupId }, ack);
    expect(namespace.adapter.rooms.has(harness.groupId)).toBe(false);
    expect(ack).not.toHaveBeenCalled();
  });

  // Input: a member subscribes to their group; mock: the membership lookup rejects once
  // Expected status code: none (acknowledgement)
  // Expected behavior: the socket does not join the room; a retry succeeds
  // Expected output: { ok: false, error: "Unable to subscribe. Reconnect and retry." }, then { ok: true }
  test('Reports a failed membership lookup and allows a retry', async () => {
    const client = await connect(harness.member.token);
    failNextFindOne(GroupModel);
    expect(await subscribe(client)).toEqual({ ok: false, error: 'Unable to subscribe. Reconnect and retry.' });
    expect(harness.socketServer.of('/dutch').adapter.rooms.get(harness.groupId)).toBeUndefined();
    expect(await subscribe(client)).toEqual({ ok: true, groupId: harness.groupId });
  });

  // Input: a member subscribes; mock: the session re-check rejects
  // Expected status code: none (acknowledgement)
  // Expected behavior: the subscription is refused without joining the room
  // Expected output: { ok: false, error: "Unable to subscribe. Reconnect and retry." }
  test('Refuses the subscription when the session re-check fails', async () => {
    const client = await connect(harness.member.token);
    jest.spyOn(mongoSessionStore, 'find').mockRejectedValueOnce(databaseFailure());
    expect(await subscribe(client)).toEqual({ ok: false, error: 'Unable to subscribe. Reconnect and retry.' });
    expect(harness.socketServer.of('/dutch').adapter.rooms.get(harness.groupId)).toBeUndefined();
  });
});

// Interface Socket.IO /dutch onGroupUpdated (server push)
describe('Mocked: Socket.IO /dutch onGroupUpdated', () => {
  // Input: a member claims an item while subscribed; mock: the publish-time membership lookup rejects
  // Expected status code: 200 for the claim
  // Expected behavior: no push is sent without a membership check; the REST snapshot still has the claim
  // Expected output: no onGroupUpdated event; snapshot shows version 1
  test('Sends nothing when publish-time membership cannot be read', async () => {
    const created = await createSplit();
    const client = await connect(harness.member.token);
    expect((await subscribe(client)).ok).toBe(true);
    const received = collect<SplitSessionUpdate>(client, 'onGroupUpdated');
    failPublisherGroupLookup();
    expect((await claim(created.body.session.id as string, harness.member)).status).toBe(200);
    await publishSettled();
    jest.restoreAllMocks();
    const snapshot = await api('get', `/api/groups/${harness.groupId}/snapshot`, harness.member);
    expect(snapshot.body.splitSessions[0]).toMatchObject({ version: 1 });
    expect(received).toEqual([]);
  });
});

// Interface Socket.IO /dutch onGroupChanged (server push)
describe('Mocked: Socket.IO /dutch onGroupChanged', () => {
  // Input: an already-dispatched group change during deletion, removal or shutdown.
  // Expected status code: none (Socket.IO).
  // Expected behavior: discard stale changes and evict a former member from the room.
  // Expected output: no group event for the subscriber.
  test.each(['missing group', 'deleted group', 'former member', 'already closed', 'closing during delivery'])(
    'withholds a queued change for a %s', async state => {
      const client = await connect(harness.member.token);
      expect((await subscribe(client)).ok).toBe(true);
      const namespace = harness.socketServer.of('/dutch');
      const socket = required(namespace.sockets.get(required(client.id)));
      const emit = jest.spyOn(socket, 'emit');
      // EventEmitter snapshots its listeners at dispatch. Await the registered publisher to
      // distinguish a suppressed event from one that is still reading membership or sessions.
      const [publish] = groupMembershipEvents.listeners('groupChanged');
      if (state === 'missing group') await GroupModel.deleteOne({ _id: harness.groupId });
      if (state === 'deleted group') await GroupModel.updateOne({ _id: harness.groupId }, { deletedAt: new Date() });
      if (state === 'former member') await GroupModel.updateOne({ _id: harness.groupId }, { $pull: { memberIds: harness.member.id } });
      if (state === 'already closed') harness.live.close();
      if (state === 'closing during delivery') {
        const find = mongoSessionStore.find.bind(mongoSessionStore);
        jest.spyOn(mongoSessionStore, 'find').mockImplementationOnce(async key => {
          const session = await find(key);
          harness.live.close();
          return session;
        });
      }
      await required(publish)({ groupId: harness.groupId, type: 'renamed' });
      expect(emit).not.toHaveBeenCalledWith('onGroupChanged', expect.anything());
      if (state === 'former member') expect(socket.rooms.has(harness.groupId)).toBe(false);
    });

  // Input: the owner renames the group while a member is subscribed; mock: the subscriber's session re-check rejects
  // Expected status code: 200 for the rename
  // Expected behavior: the event is withheld when the session cannot be verified; the rename is still stored
  // Expected output: no onGroupChanged event; the summary shows the new name
  test('Withholds the event when the subscriber session cannot be verified', async () => {
    const client = await connect(harness.member.token);
    expect((await subscribe(client)).ok).toBe(true);
    const received = collect<GroupChange>(client, 'onGroupChanged');
    failSessionChecksFor(harness.member);
    expect((await api('put', `/api/groups/${harness.groupId}/name`, harness.owner).send({ name: 'Renamed' })).status).toBe(200);
    await publishSettled();
    jest.restoreAllMocks();
    expect(received).toEqual([]);
    expect((await api('get', `/api/groups/${harness.groupId}/summary`, harness.member)).body.group.name).toBe('Renamed');
  });
});

// Interface Socket.IO /dutch onGroupMembershipChanged (server push)
describe('Mocked: Socket.IO /dutch onGroupMembershipChanged', () => {
  // Input: the owner removes a member while the owner is subscribed; mock: the publish-time group lookup rejects
  // Expected status code: 204 for the removal
  // Expected behavior: no event is sent, but the stored roster is authoritative
  // Expected output: no onGroupMembershipChanged event; the summary has 1 member
  test('Sends nothing when the group cannot be read, leaving the roster authoritative', async () => {
    const client = await connect(harness.owner.token);
    expect((await subscribe(client)).ok).toBe(true);
    const received = collect<GroupMembershipChange>(client, 'onGroupMembershipChanged');
    failPublisherGroupLookup();
    expect((await api('delete', `/api/groups/${harness.groupId}/members/${harness.member.id}`, harness.owner)).status).toBe(204);
    await publishSettled();
    jest.restoreAllMocks();
    expect(received).toEqual([]);
    expect((await api('get', `/api/groups/${harness.groupId}/summary`, harness.owner)).body.group.memberCount).toBe(1);
  });
});
