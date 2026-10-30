import type { Server } from 'socket.io';
import { Types } from 'mongoose';
import sanitize from 'mongo-sanitize';
import { GroupModel } from './groupStore';
import { hashToken } from './session';
import { splitSessionEvents, type SplitSessionUpdate } from './splitSessions';
import type { SessionStore, UserStore } from './userStore';
import { groupMembershipEvents, type GroupMembershipChange } from './groupMembershipEvents';

/** Authenticated Socket.IO delivery for committed split-session changes. */
export function attachSplitSessionLive(io: Server, users: UserStore, sessions: SessionStore) {
  const namespace = io.of('/dutch');
  let closed = false;
  namespace.use(async (socket, next) => {
    try {
      if (closed) { next(new Error('Live updates are closed.')); return; }
      const authToken = socket.handshake.auth?.token;
      const authorization = socket.handshake.headers.authorization;
      const headerToken = typeof authorization === 'string' ? /^Bearer ([A-Za-z0-9_-]{20,200})$/.exec(authorization)?.[1] : undefined;
      const token = typeof authToken === 'string' ? authToken : headerToken;
      if (typeof token !== 'string' || !/^[A-Za-z0-9_-]{20,200}$/.test(token)) {
        next(new Error('Authentication required.'));
        return;
      }
      const saved = await sessions.find(hashToken(token));
      if (!saved || saved.expiresAt.getTime() <= Date.now()) {
        next(new Error('Session expired.'));
        return;
      }
      const user = await users.findById(saved.userId);
      if (!user) { next(new Error('Session expired.')); return; }
      socket.data.userId = user.id;
      socket.data.tokenHash = hashToken(token);
      if (closed) { next(new Error('Live updates are closed.')); return; }
      next();
    } catch { next(new Error('Authentication unavailable.')); }
  });

  namespace.on('connection', socket => {
    if (closed) { socket.disconnect(); return; }
    // Private server-assigned room: invalidate all devices of a removed member,
    // even if a concurrent data broadcast already removed their group-room entry.
    void socket.join(`user:${String(socket.data.userId)}`);
    socket.on('subscribe', async (payload: unknown, acknowledge?: unknown) => {
      // Every event argument is client-controlled; only Socket.IO acknowledgement functions are callable.
      const reply = (result: unknown) => {
        if (typeof acknowledge !== 'function') return;
        try { acknowledge(result); } catch {
          // A failed acknowledgement transport must not reject the asynchronous event listener.
        }
      };
      const groupId = payload && typeof payload === 'object' ? (payload as { groupId?: unknown }).groupId : null;
      const userId = socket.data.userId;
      try {
        const saved = await sessions.find(String(socket.data.tokenHash));
        if (!saved || saved.expiresAt.getTime() <= Date.now() || saved.userId !== userId ||
            typeof userId !== 'string' || !Types.ObjectId.isValid(userId)) {
          reply({ ok: false, error: 'Session expired.' });
          socket.disconnect(true);
          return;
        }
        if (typeof groupId !== 'string' || !Types.ObjectId.isValid(groupId)) {
          reply({ ok: false, error: 'Group not found.' });
          return;
        }
        const group = await GroupModel.findOne({ _id: { $eq: new Types.ObjectId(sanitize(groupId)) },
          memberIds: { $eq: new Types.ObjectId(sanitize(userId)) }, deletedAt: null }).select('_id').lean();
        if (!group) { reply({ ok: false, error: 'Group not found.' }); return; }
        if (closed || !socket.connected) return;
        await socket.join(groupId);
        reply({ ok: true, groupId });
      } catch { reply({ ok: false, error: 'Unable to subscribe. Reconnect and retry.' }); }
    });
    socket.on('unsubscribe', (payload: unknown) => {
      const groupId = payload && typeof payload === 'object' ? (payload as { groupId?: unknown }).groupId : null;
      if (typeof groupId === 'string' && Types.ObjectId.isValid(groupId)) void socket.leave(groupId);
    });
  });

  async function publishToMembers(groupId: string, event: string, payload: unknown, deleted = false, revokedUserId?: string) {
    try {
      if (closed) return;
      const group = await GroupModel.findOne({ _id: { $eq: new Types.ObjectId(sanitize(groupId)) } })
        .select('memberIds deletedAt').lean<{ memberIds: Types.ObjectId[]; deletedAt?: Date }>();
      if (!group || Boolean(group.deletedAt) !== deleted) return;
      const allowed = new Set(group.memberIds.map(id => id.toString()));
      const rooms = revokedUserId ? [groupId, `user:${revokedUserId}`] : [groupId];
      const roomSockets = await namespace.in(rooms).fetchSockets();
      await Promise.all(roomSockets.map(async socket => {
        // A deleted group or revoked membership must not leak later events to a stale socket.
        const isMember = allowed.has(String(socket.data.userId));
        // Former members receive only their own access invalidation, never group data.
        if (!isMember && socket.data.userId !== revokedUserId) { await socket.leave(groupId); return; }
        const saved = await sessions.find(String(socket.data.tokenHash));
        if (!saved || saved.expiresAt.getTime() <= Date.now() || saved.userId !== socket.data.userId) {
          socket.disconnect(true);
          return;
        }
        if (!closed) {
          socket.emit(event, payload);
          if (deleted || !isMember) await socket.leave(groupId);
        }
      }));
    } catch {
      // REST snapshot/recovery remains authoritative if the push transport is unavailable.
    }
  }
  const publish = (update: SplitSessionUpdate) => publishToMembers(update.event.groupId, 'onGroupUpdated', update);
  const publishMembership = (change: GroupMembershipChange) =>
    publishToMembers(change.groupId, 'onGroupMembershipChanged', { groupId: change.groupId, type: change.type },
      change.type === 'deleted', change.affectedUserId);
  splitSessionEvents.on('onGroupUpdated', publish);
  groupMembershipEvents.on('changed', publishMembership);
  return { close: () => {
    if (closed) return;
    closed = true;
    splitSessionEvents.off('onGroupUpdated', publish);
    groupMembershipEvents.off('changed', publishMembership);
    // Other namespaces can share the Engine.IO connection; close Dutch only.
    namespace.disconnectSockets(false);
  } };
}
