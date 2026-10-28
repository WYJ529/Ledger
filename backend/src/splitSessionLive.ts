import type { Server } from 'socket.io';
import { Types } from 'mongoose';
import { GroupModel } from './groupStore';
import { hashToken } from './session';
import { splitSessionEvents, type SplitSessionUpdate } from './splitSessions';
import type { SessionStore, UserStore } from './userStore';

/** Authenticated Socket.IO delivery for committed split-session changes. */
export function attachSplitSessionLive(io: Server, users: UserStore, sessions: SessionStore) {
  const namespace = io.of('/dutch');
  namespace.use(async (socket, next) => {
    try {
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
      next();
    } catch { next(new Error('Authentication unavailable.')); }
  });

  namespace.on('connection', socket => {
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
        if (!saved || saved.expiresAt.getTime() <= Date.now() || saved.userId !== userId) {
          reply({ ok: false, error: 'Session expired.' });
          socket.disconnect(true);
          return;
        }
        if (typeof groupId !== 'string' || !Types.ObjectId.isValid(groupId)) {
          reply({ ok: false, error: 'Group not found.' });
          return;
        }
        const group = await GroupModel.findOne({ _id: groupId, memberIds: userId, deletedAt: null }).select('_id').lean();
        if (!group) { reply({ ok: false, error: 'Group not found.' }); return; }
        await socket.join(groupId);
        reply({ ok: true, groupId });
      } catch { reply({ ok: false, error: 'Unable to subscribe. Reconnect and retry.' }); }
    });
    socket.on('unsubscribe', (payload: unknown) => {
      const groupId = payload && typeof payload === 'object' ? (payload as { groupId?: unknown }).groupId : null;
      if (typeof groupId === 'string' && Types.ObjectId.isValid(groupId)) void socket.leave(groupId);
    });
  });

  const publish = async (update: SplitSessionUpdate) => {
    try {
      const group = await GroupModel.findOne({ _id: update.event.groupId, deletedAt: null })
        .select('memberIds').lean<{ memberIds: Types.ObjectId[] }>();
      if (!group) return;
      const allowed = new Set(group.memberIds.map(id => id.toString()));
      const roomSockets = await namespace.in(update.event.groupId).fetchSockets();
      await Promise.all(roomSockets.map(async socket => {
        // A deleted group or revoked membership must not leak later events to a stale socket.
        if (!allowed.has(String(socket.data.userId))) return;
        const saved = await sessions.find(String(socket.data.tokenHash));
        if (!saved || saved.expiresAt.getTime() <= Date.now() || saved.userId !== socket.data.userId) {
          socket.disconnect(true);
          return;
        }
        socket.emit('onGroupUpdated', update);
      }));
    } catch {
      // REST snapshot/recovery remains authoritative if the push transport is unavailable.
    }
  };
  splitSessionEvents.on('onGroupUpdated', publish);
  return { close: () => splitSessionEvents.off('onGroupUpdated', publish) };
}
