import { DuplicateAccountError, sessionMatchesAccount, type SessionRecord, type SessionStore, type UserRecord, type UserStore } from '../../src/userStore';

/** In-memory stand-ins for the MongoDB user and session collections. */
export function memoryStores() {
  const userList: UserRecord[] = [];
  const sessionMap = new Map<string, SessionRecord>();
  let nextId = 1;
  const users: UserStore = {
    deleteAccount(id) {
      const index = userList.findIndex(user => user.id === id);
      if (index >= 0) userList.splice(index, 1);
      for (const [key, session] of sessionMap) if (session.userId === id) sessionMap.delete(key);
      return Promise.resolve();
    },
    update(id, changes, retainedSessionHash) {
      const user = userList.find(value => value.id === id);
      if (!user) return Promise.resolve(null);
      if (changes.email && userList.some(value => value.id !== id && value.email === changes.email)) return Promise.reject(new DuplicateAccountError());
      Object.assign(user, changes);
      if (retainedSessionHash) {
        user.sessionVersion = (user.sessionVersion ?? 0) + 1;
        user.retainedSessionHash = retainedSessionHash;
      }
      return Promise.resolve(user);
    },
    findById(id) { return Promise.resolve(userList.find(user => user.id === id) ?? null); },
    findByUsername(username) { return Promise.resolve(userList.find(user => user.username === username) ?? null); },
    linkGoogle(id, googleId, passwordHash) {
      const user = userList.find(value => value.id === id && value.passwordHash === passwordHash && (!value.googleId || value.googleId === googleId));
      if (!user) return Promise.resolve(null);
      if (userList.some(value => value.id !== id && value.googleId === googleId)) return Promise.reject(new DuplicateAccountError());
      user.googleId = googleId;
      return Promise.resolve(user);
    },
    findByEmail(email) { return Promise.resolve(userList.find(user => user.email === email) ?? null); },
    findByGoogleId(googleId) { return Promise.resolve(userList.find(user => user.googleId === googleId) ?? null); },
    create(input) {
      if (userList.some(user => (Boolean(input.username) && user.username === input.username) || (Boolean(input.email) && user.email === input.email) || (Boolean(input.googleId) && user.googleId === input.googleId))) {
        return Promise.reject(new DuplicateAccountError());
      }
      const user: UserRecord = { ...input, id: `user-${nextId++}`, createdAt: new Date() };
      userList.push(user);
      return Promise.resolve(user);
    },
  };
  const sessions: SessionStore = {
    create(tokenHash, session) { sessionMap.set(tokenHash, session); return Promise.resolve(); },
    async find(tokenHash) {
      const session = sessionMap.get(tokenHash);
      if (!session) return null;
      const user = await users.findById(session.userId);
      if (!user) { sessionMap.delete(tokenHash); return null; }
      return sessionMatchesAccount(session, user, tokenHash) ? session : null;
    },
    delete(tokenHash) { sessionMap.delete(tokenHash); return Promise.resolve(); },
  };
  return { users, sessions, userList, sessionMap };
}
