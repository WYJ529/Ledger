import { required, requiredString } from '../helpers/required';
import request from 'supertest';
import { GroupModel, ExpenseModel } from '../../src/groupStore';
import { type GroupMembershipChange, type GroupChange } from '../../src/groupMembershipEvents';
import { SplitSessionModel } from '../../src/splitSessionStore';
import { splitSessionEvents, type SplitSessionUpdate } from '../../src/splitSessions';
import {
  claim, clientFor, collect, connect, createSplit, harness, logout, nextEvent, subscribe, subscribed, trackClient, useLiveHarness, type TestAccount,
} from '../helpers/liveHarness';

// The HTTP app, Socket.IO transports, Ledger sessions and MongoDB persistence are all real. No mocked components.
useLiveHarness('split-session-live-test');
let server: typeof harness.server;
let socketServer: typeof harness.socketServer;
let live: typeof harness.live;
let owner: TestAccount;
let member: TestAccount;
let outsider: TestAccount;
let groupId: string;
let membershipChanges: GroupMembershipChange[];
beforeEach(() => { ({ server, socketServer, live, owner, member, outsider, groupId, membershipChanges } = harness); });

async function joinInvitation() {
  const response = await request(server).post(`/api/groups/${groupId}/invitations`)
    .set('Authorization', `Bearer ${owner.token}`);
  expect(response.status).toBe(201);
  return required(String(response.body.invitation.deepLink).split('/').at(-1));
}

function acceptInvitation(token: string) {
  return request(server).post(`/api/groups/invitations/${token}/accept`)
    .set('Authorization', `Bearer ${outsider.token}`);
}

// A started split with owner and member subscribed, collecting any update the member receives.
async function splitWithSubscribers() {
  const created = await createSplit();
  const ownerClient = await subscribed(owner);
  const memberClient = await subscribed(member);
  return { created, ownerClient, memberClient, leaked: collect<SplitSessionUpdate>(memberClient, 'onGroupUpdated') };
}

// Interface Socket.IO /dutch connection
describe('Unmocked: Socket.IO /dutch connection', () => {

  // Input: a handshake after logout with a well-formed former token.
  // Expected status code: connect_error.
  // Expected behavior: reject the revoked session before joining any room.
  // Expected output: Session expired.
  test('rejects a revoked token at handshake', async () => {
    expect((await logout(member)).status).toBe(204);
    const client = clientFor(member.token);
    const rejected = nextEvent<Error>(client, 'connect_error');
    client.connect();
    expect((await rejected).message).toBe('Session expired.');
    expect(socketServer.of('/dutch').sockets.size).toBe(0);
  });

  // Input: connection attempt without a Ledger session token
  // Expected status code: none (Socket.IO connect_error)
  // Expected behavior: the handshake is refused and no socket is kept
  // Expected output: connect_error "Authentication required."
  test('Denies a connection without a Ledger session', async () => {
    const client = clientFor();
    const rejected = nextEvent<Error>(client, 'connect_error');
    client.connect();
    expect((await rejected).message).toMatch(/Authentication required/i);
    expect(client.connected).toBe(false);
    expect(socketServer.of('/dutch').sockets.size).toBe(0);
  });

  // Input: valid member connection
  // Expected status code: none (Socket.IO connect)
  // Expected behavior: the handshake succeeds with the member's session
  // Expected output: connect event; one socket in /dutch
  test('Accepts a connection with a valid session', async () => {
    const client = await connect(member.token);
    expect(client.connected).toBe(true);
    expect(socketServer.of('/dutch').sockets.size).toBe(1);
  });

  // Input: close the Dutch namespace twice while another namespace shares the transport, then reconnect
  // Expected status code: none (Socket.IO events)
  // Expected behavior: closing is idempotent, disconnects only Dutch and refuses later connections
  // Expected output: disconnect "io server disconnect"; the other namespace still answers; reconnect gets connect_error "closed"
  test('Closing is idempotent and preserves a shared transport', async () => {
    socketServer.of('/test-probe').on('connection', socket => {
      socket.on('probe', () => socket.emit('probe-result', 'alive'));
    });
    const dutch = await connect(member.token);
    expect((await subscribe(dutch)).ok).toBe(true);
    const probeClient = dutch.io.socket('/test-probe');
    trackClient(probeClient);
    const probeConnected = nextEvent<undefined>(probeClient, 'connect');
    probeClient.connect();
    await probeConnected;
    expect(probeClient.io).toBe(dutch.io);
    const beforeClose = splitSessionEvents.listenerCount('onGroupUpdated');
    const disconnected = nextEvent<string>(dutch, 'disconnect');
    live.close();
    live.close();
    expect(await disconnected).toBe('io server disconnect');
    expect(splitSessionEvents.listenerCount('onGroupUpdated')).toBe(beforeClose - 1);
    expect(socketServer.of('/dutch').sockets.size).toBe(0);
    expect(probeClient.connected).toBe(true);
    const alive = nextEvent<string>(probeClient, 'probe-result');
    probeClient.emit('probe');
    expect(await alive).toBe('alive');
    const retry = clientFor(member.token);
    const rejected = nextEvent<Error>(retry, 'connect_error');
    retry.connect();
    expect((await rejected).message).toMatch(/closed/);
  });
});

describe('Unmocked: Socket.IO /dutch unsubscribe', () => {
  // Input: invalid unsubscribe payloads followed by a valid group ID.
  // Expected status code: none (Socket.IO).
  // Expected behavior: invalid messages preserve membership, and a valid message leaves only the group room.
  // Expected output: the server room membership reflects each event.
  test('leaves the subscribed group and retains the connection', async () => {
    const client = await subscribed(member);
    const room = () => socketServer.of('/dutch').adapter.rooms.get(groupId);
    for (const message of [null, { groupId: 'invalid' }, { groupId }]) {
      client.emit('unsubscribe', message);
      // The acknowledgement on this socket follows the unsubscribe in transport order.
      const acknowledgement = await client.emitWithAck('subscribe', null);
      expect(acknowledgement).toEqual({ ok: false, error: 'Group not found.' });
      expect(Boolean(room()?.has(required(client.id)))).toBe(message?.groupId !== groupId);
    }
    expect(client.connected).toBe(true);
  });
});

// Interface Socket.IO /dutch subscribe
describe('Unmocked: Socket.IO /dutch subscribe', () => {
  // Input: subscribe { groupId } from an authenticated nonmember
  // Expected status code: none (acknowledgement)
  // Expected behavior: the socket does not join the group room
  // Expected output: { ok: false, error: "Group not found." }
  test('Denies an authenticated outsider', async () => {
    const client = await connect(outsider.token);
    expect(await subscribe(client)).toEqual({ ok: false, error: 'Group not found.' });
    expect(socketServer.of('/dutch').adapter.rooms.get(groupId)).toBeUndefined();
  });

  // Input: operator-shaped and malformed group IDs, then the real group ID
  // Expected status code: none (acknowledgement)
  // Expected behavior: invalid IDs never join a room; the valid one does
  // Expected output: { ok: false, error: "Group not found." } for each invalid ID, then { ok: true, groupId }
  test('Rejects query operators and malformed group identifiers', async () => {
    const client = await connect(member.token);
    for (const invalid of [{ $ne: null }, { $gt: '' }, { $eq: groupId }, { nested: { $ne: null } }, {}, [groupId], 'invalid', null]) {
      expect(await subscribe(client, invalid)).toEqual({ ok: false, error: 'Group not found.' });
    }
    expect(socketServer.of('/dutch').adapter.rooms.get(groupId)).toBeUndefined();
    expect(await subscribe(client)).toEqual({ ok: true, groupId });
  });

  // Input: subscribe events whose acknowledgement argument is not a function
  // Expected status code: none (acknowledgement)
  // Expected behavior: malformed arguments are ignored and the socket keeps working
  // Expected output: later valid subscriptions answer { ok: true, groupId }
  test('Ignores nonfunction acknowledgement arguments', async () => {
    const client = await connect(member.token);
    for (const malformed of ['not-an-ack', 42, { acknowledgement: true }]) {
      client.emit('subscribe', { groupId: 'invalid' }, malformed);
      expect(await subscribe(client)).toEqual({ ok: true, groupId });
    }
    expect(client.connected).toBe(true);
    expect(socketServer.of('/dutch').adapter.rooms.get(groupId)?.size).toBe(1);
  });

  // Input: subscribe after the socket's session was logged out
  // Expected status code: none (acknowledgement and disconnect)
  // Expected behavior: the session is checked on every subscription and the socket is disconnected
  // Expected output: { ok: false, error: "Session expired." }; disconnect "io server disconnect"
  test('Rejects a subscription after the session is revoked', async () => {
    const client = await connect(member.token);
    expect((await logout(member)).status).toBe(204);
    const disconnected = nextEvent<string>(client, 'disconnect');
    expect(await subscribe(client)).toEqual({ ok: false, error: 'Session expired.' });
    expect(await disconnected).toBe('io server disconnect');
    expect(client.connected).toBe(false);
    expect(socketServer.of('/dutch').adapter.rooms.get(groupId)).toBeUndefined();
  });
});

// Interface Socket.IO /dutch onGroupUpdated (server push)
describe('Unmocked: Socket.IO /dutch onGroupUpdated', () => {
  // Input: a member claims an item while owner, member and outsider sockets are connected
  // Expected status code: 200 for the claim
  // Expected behavior: the committed session state is pushed to subscribed members only
  // Expected output: onGroupUpdated with sequence 2 and the same session as the claim response; nothing for the outsider
  test('Delivers committed claim state to members and never to an outsider', async () => {
    const created = await createSplit();
    expect(created.status).toBe(201);
    const ownerClient = await connect(owner.token);
    const memberClient = await connect(member.token);
    const outsiderClient = await connect(outsider.token);
    expect(await subscribe(ownerClient)).toEqual({ ok: true, groupId });
    expect(await subscribe(memberClient)).toEqual({ ok: true, groupId });
    expect((await subscribe(outsiderClient)).ok).toBe(false);
    const outsiderUpdates = collect<SplitSessionUpdate>(outsiderClient, 'onGroupUpdated');
    const ownerUpdate = nextEvent<SplitSessionUpdate>(ownerClient, 'onGroupUpdated');
    const memberUpdate = nextEvent<SplitSessionUpdate>(memberClient, 'onGroupUpdated');
    const accepted = await claim(created.body.session.id as string, member);
    expect(accepted.status).toBe(200);
    for (const update of await Promise.all([ownerUpdate, memberUpdate])) {
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

  // Input: a subscribed member logs out, then the owner claims an item
  // Expected status code: 200 for the claim
  // Expected behavior: the revoked socket is disconnected instead of receiving the update
  // Expected output: owner gets version 1; the revoked socket gets nothing
  test('A revoked session cannot receive a later committed update', async () => {
    const { created, ownerClient, memberClient, leaked } = await splitWithSubscribers();
    expect((await logout(member)).status).toBe(204);
    const disconnected = nextEvent<string>(memberClient, 'disconnect');
    const delivered = nextEvent<SplitSessionUpdate>(ownerClient, 'onGroupUpdated');
    expect((await claim(created.body.session.id as string, owner)).status).toBe(200);
    expect((await delivered).session.version).toBe(1);
    expect(await disconnected).toBe('io server disconnect');
    expect(leaked).toEqual([]);
    expect((await request(server).get(`/api/groups/${groupId}/snapshot`).set('Authorization', `Bearer ${member.token}`)).status).toBe(401);
  });

  // Input: a subscribed member leaves (or is removed), then the owner claims an item
  // Expected status code: 200 for leave or 204 for removal; 200 for the claim
  // Expected behavior: the former member receives no later group data
  // Expected output: both get onGroupMembershipChanged; only the owner gets onGroupUpdated
  test.each(['leave', 'remove'])('A member who exits through %s cannot receive later updates', async action => {
    const { created, ownerClient, memberClient, leaked } = await splitWithSubscribers();
    const changes = [ownerClient, memberClient].map(client => nextEvent<GroupMembershipChange>(client, 'onGroupMembershipChanged'));
    const exited = action === 'leave'
      ? await request(server).post(`/api/groups/${groupId}/leave`).set('Authorization', `Bearer ${member.token}`)
      : await request(server).delete(`/api/groups/${groupId}/members/${member.id}`).set('Authorization', `Bearer ${owner.token}`);
    expect(exited.status).toBe(action === 'leave' ? 200 : 204);
    const type = action === 'leave' ? 'left' : 'removed';
    expect(await Promise.all(changes)).toEqual([{ groupId, type }, { groupId, type }]);
    const delivered = nextEvent<SplitSessionUpdate>(ownerClient, 'onGroupUpdated');
    expect((await claim(created.body.session.id as string, owner)).status).toBe(200);
    expect((await delivered).session.version).toBe(1);
    expect((await subscribe(memberClient)).ok).toBe(false);
    expect(leaked).toEqual([]);
    expect((await request(server).get(`/api/groups/${groupId}/snapshot`).set('Authorization', `Bearer ${member.token}`)).status).toBe(404);
    if (action === 'remove') {
      expect((await request(server).delete(`/api/groups/${groupId}/members/${member.id}`)
        .set('Authorization', `Bearer ${owner.token}`)).status).toBe(204);
      expect(membershipChanges.filter(change => change.type === 'removed')).toHaveLength(1);
    }
  });

  // Input: a member disconnects, two sessions change while offline, the member reconnects and reads the snapshot
  // Expected status code: 200 for the snapshot
  // Expected behavior: the snapshot installs the latest state and the next push continues from it
  // Expected output: two sessions at sequence 2; the next onGroupUpdated has sequence 3
  test('A fresh reconnect installs the latest snapshot then receives the next sequence', async () => {
    const first = await createSplit();
    const original = await connect(member.token);
    expect((await subscribe(original)).ok).toBe(true);
    const before = await request(server).get(`/api/groups/${groupId}/snapshot`).set('Authorization', `Bearer ${member.token}`);
    expect(before.body.splitSessions).toHaveLength(1);
    expect(before.body.splitSessions[0]).toMatchObject({ id: first.body.session.id, sequence: 1 });
    original.disconnect();
    expect((await claim(first.body.session.id as string, owner)).status).toBe(200);
    const second = await createSplit('live-start-00000002');
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
});

// Interface Socket.IO /dutch onGroupChanged (server push)
describe('Unmocked: Socket.IO /dutch onGroupChanged', () => {
  // Input: the owner adds a bill, a member logs out, then the owner renames the group
  // Expected status code: 201 for the bill, 204 for logout, 200 for the rename
  // Expected behavior: minimal invalidations go only to authorized members after persistence
  // Expected output: { groupId, type: "expense-added" } to both members, then { groupId, type: "renamed" } to the owner only
  test('Delivers minimal invalidations only to authorized members', async () => {
    const ownerClient = await subscribed(owner);
    const memberClient = await subscribed(member);
    const outsiderClient = await connect(outsider.token);
    expect((await subscribe(outsiderClient)).ok).toBe(false);
    const outsiderUpdates = collect<GroupChange>(outsiderClient, 'onGroupChanged');
    const ownerUpdate = nextEvent<GroupChange>(ownerClient, 'onGroupChanged');
    const memberUpdate = nextEvent<GroupChange>(memberClient, 'onGroupChanged');
    const saved = await request(server).post(`/api/groups/${groupId}/expenses`)
      .set('Authorization', `Bearer ${owner.token}`).send({
        description: 'Private dinner', amount: '10.00', paidBy: owner.id, splitMode: 'equal',
        shares: [{ userId: owner.id }, { userId: member.id }], requestId: 'live-expense-00000001',
      });
    expect(saved.status).toBe(201);
    expect(await Promise.all([ownerUpdate, memberUpdate])).toEqual([{ groupId, type: 'expense-added' }, { groupId, type: 'expense-added' }]);
    expect(await ExpenseModel.findById(requiredString(saved.body.expense.id)).lean()).toMatchObject({ description: 'Private dinner' });
    expect(outsiderUpdates).toEqual([]);
    // A session revoked after subscription must lose access to ordinary group events too.
    expect((await logout(member)).status).toBe(204);
    const deniedUpdates = collect<GroupChange>(memberClient, 'onGroupChanged');
    const disconnected = nextEvent<string>(memberClient, 'disconnect');
    const ownerRename = nextEvent<GroupChange>(ownerClient, 'onGroupChanged');
    expect((await request(server).put(`/api/groups/${groupId}/name`)
      .set('Authorization', `Bearer ${owner.token}`).send({ name: 'New dinner' })).status).toBe(200);
    expect(await ownerRename).toEqual({ groupId, type: 'renamed' });
    expect(await disconnected).toBe('io server disconnect');
    expect(deniedUpdates).toEqual([]);
  });
});

// Interface Socket.IO /dutch onGroupMembershipChanged (server push)
describe('Unmocked: Socket.IO /dutch onGroupMembershipChanged', () => {
  // Input: the owner leaves while owner and member are subscribed
  // Expected status code: 200 for leave
  // Expected behavior: deletion is broadcast and the group room is cleared
  // Expected output: { groupId, type: "deleted" } to both sockets
  test('Owner departure broadcasts deletion and clears the room', async () => {
    const ownerClient = await connect(owner.token);
    const memberClient = await connect(member.token);
    await subscribe(ownerClient); await subscribe(memberClient);
    const changes = [ownerClient, memberClient].map(client => nextEvent<GroupMembershipChange>(client, 'onGroupMembershipChanged'));
    const response = await request(server).post(`/api/groups/${groupId}/leave`).set('Authorization', `Bearer ${owner.token}`);
    expect(response.body).toEqual({ deleted: true });
    expect(await Promise.all(changes)).toEqual([{ groupId, type: 'deleted' }, { groupId, type: 'deleted' }]);
    expect(socketServer.of('/dutch').adapter.rooms.get(groupId)).toBeUndefined();
    expect((await request(server).get('/api/groups').set('Authorization', `Bearer ${member.token}`)).body.groups).toEqual([]);
  });

  // Input: the owner deletes the group while a member is subscribed
  // Expected status code: 204 for delete
  // Expected behavior: the member is notified and their subscription is released
  // Expected output: { groupId, type: "deleted" }; later subscribe answers ok: false
  test('Group deletion notifies members and releases their subscription', async () => {
    const client = await connect(member.token);
    await subscribe(client);
    const deleted = nextEvent<GroupMembershipChange>(client, 'onGroupMembershipChanged');
    expect((await request(server).delete(`/api/groups/${groupId}`).set('Authorization', `Bearer ${owner.token}`)).status).toBe(204);
    expect(await deleted).toEqual({ groupId, type: 'deleted' });
    expect(socketServer.of('/dutch').adapter.rooms.get(groupId)).toBeUndefined();
    expect((await subscribe(client)).ok).toBe(false);
  });

  // Input: a member with two devices (one subscribed, one not) is removed
  // Expected status code: 204 for removal
  // Expected behavior: every device of the removed member is told through its private user room
  // Expected output: { groupId, type: "removed" } on both devices
  test('Removal reaches another device that was not subscribed', async () => {
    const memberClient = await connect(member.token);
    const otherDevice = await connect(member.token);
    await subscribe(memberClient);
    const changes = [memberClient, otherDevice].map(client => nextEvent<GroupMembershipChange>(client, 'onGroupMembershipChanged'));
    expect((await request(server).delete(`/api/groups/${groupId}/members/${member.id}`)
      .set('Authorization', `Bearer ${owner.token}`)).status).toBe(204);
    expect(await Promise.all(changes)).toEqual([{ groupId, type: 'removed' }, { groupId, type: 'removed' }]);
    expect((await subscribe(otherDevice)).ok).toBe(false);
  });

  // Input: an outsider accepts an invitation while owner and member are subscribed and the outsider watches another group
  // Expected status code: 200 for accept
  // Expected behavior: existing members are notified after the join is stored; nobody else is
  // Expected output: { groupId, type: "joined" } to owner and member; summary lists 3 members
  test('A stored join notifies existing members only', async () => {
    const ownerClient = await subscribed(owner);
    const memberClient = await subscribed(member);
    const outsiderClient = await connect(outsider.token);
    const other = await GroupModel.create({ name: 'Other', ownerId: outsider.id, memberIds: [outsider.id] });
    expect((await subscribe(outsiderClient, other.id)).ok).toBe(true);
    const leaked = collect<unknown>(outsiderClient, 'onGroupMembershipChanged');
    const token = await joinInvitation();
    const deliveries = [ownerClient, memberClient].map(client => nextEvent<GroupMembershipChange>(client, 'onGroupMembershipChanged'));
    expect((await acceptInvitation(token)).status).toBe(200);
    expect(await Promise.all(deliveries)).toEqual([{ groupId, type: 'joined' }, { groupId, type: 'joined' }]);
    const canonical = await request(server).get(`/api/groups/${groupId}/summary`).set('Authorization', `Bearer ${member.token}`);
    expect(canonical.body.group.memberCount).toBe(3);
    expect(canonical.body.group.members).toEqual(expect.arrayContaining([expect.objectContaining({ id: outsider.id })]));
    expect(leaked).toEqual([]);
  });

  // Input: two simultaneous accepts of one invitation, then a repeat
  // Expected status code: 200 for each
  // Expected behavior: exactly one join is published and one member stored
  // Expected output: membership changes = [{ groupId, type: "joined" }]
  test('Concurrent and repeated accepts publish exactly one join', async () => {
    const client = await connect(owner.token);
    expect((await subscribe(client)).ok).toBe(true);
    const token = await joinInvitation();
    const delivered = nextEvent<GroupMembershipChange>(client, 'onGroupMembershipChanged');
    const responses = await Promise.all([acceptInvitation(token), acceptInvitation(token)]);
    expect(responses.map(response => response.status)).toEqual([200, 200]);
    for (const response of responses) expect(response.body.group.memberCount).toBe(3);
    await delivered;
    expect((await acceptInvitation(token)).status).toBe(200);
    expect(membershipChanges).toEqual([{ groupId, type: 'joined' }]);
    expect(required(await GroupModel.findById(groupId)).memberIds.filter(id => id.toString() === outsider.id)).toHaveLength(1);
  });

  // Input: accept with an unknown token, and accept without a session
  // Expected status code: 404, then 401
  // Expected behavior: failed accepts never publish a join
  // Expected output: membership changes = []
  test('Failed acceptance never publishes a join', async () => {
    expect((await acceptInvitation('a'.repeat(43))).status).toBe(404);
    const token = await joinInvitation();
    expect((await request(server).post(`/api/groups/invitations/${token}/accept`)).status).toBe(401);
    expect(membershipChanges).toEqual([]);
  });

  // Input: a subscribed member logs out, then an outsider joins
  // Expected status code: 200 for accept
  // Expected behavior: the revoked socket is disconnected instead of receiving the change
  // Expected output: the owner gets type "joined"; the revoked socket gets nothing
  test('A revoked subscriber receives no membership change', async () => {
    const ownerClient = await connect(owner.token);
    const memberClient = await connect(member.token);
    await subscribe(ownerClient); await subscribe(memberClient);
    const leaked = collect<unknown>(memberClient, 'onGroupMembershipChanged');
    await logout(member);
    const token = await joinInvitation();
    const disconnected = nextEvent<string>(memberClient, 'disconnect');
    const delivered = nextEvent<GroupMembershipChange>(ownerClient, 'onGroupMembershipChanged');
    expect((await acceptInvitation(token)).status).toBe(200);
    expect((await delivered).type).toBe('joined');
    expect(await disconnected).toBe('io server disconnect');
    expect(leaked).toEqual([]);
  });

  // Input: a member disconnects, an outsider joins, the member reconnects and reads the summary
  // Expected status code: 200 for the summary
  // Expected behavior: a missed push is recovered from the canonical roster
  // Expected output: memberCount 3 including the new member
  test('Reconnecting after an offline join recovers the roster', async () => {
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
});
