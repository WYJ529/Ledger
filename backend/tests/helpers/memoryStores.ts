import { DuplicateAccountError, sessionMatchesAccount, type SessionRecord, type SessionStore, type UserRecord, type UserStore } from '../../src/userStore';
import { memoryRegistration } from './registration';

/** In-memory stand-ins for the MongoDB user and session collections. */
export function memoryStores() {
  const userList: UserRecord[] = [];
  const sessionMap = new Map<string, SessionRecord>();
  const users: UserStore = {
    async update(id, changes, retainedSessionHash) {
      const user = userList.find(value => value.id === id);
      if (!user) return null;
      if (changes.email && userList.some(value => value.id !== id && value.email === changes.email)) throw new DuplicateAccountError();
      Object.assign(user, changes);
      if (retainedSessionHash) {
        user.sessionVersion = (user.sessionVersion ?? 0) + 1;
        user.retainedSessionHash = retainedSessionHash;
      }
      return user;
    },
    async findById(id) { return userList.find(user => user.id === id) ?? null; },
    async findByEmail(email) { return userList.find(user => user.email === email) ?? null; },
    async findByGoogleId(googleId) { return userList.find(user => user.googleId === googleId) ?? null; },
    async create(input) {
      if (userList.some(user => user.email === input.email || (input.googleId && user.googleId === input.googleId))) {
        throw new DuplicateAccountError();
      }
      const user: UserRecord = { ...input, id: `user-${userList.length + 1}`, createdAt: new Date() };
      userList.push(user);
      return user;
    },
  };
  const sessions: SessionStore = {
    async create(tokenHash, session) { sessionMap.set(tokenHash, session); },
    async find(tokenHash) {
      const session = sessionMap.get(tokenHash);
      if (!session) return null;
      const user = await users.findById(session.userId);
      if (!user) { sessionMap.delete(tokenHash); return null; }
      return sessionMatchesAccount(session, user, tokenHash) ? session : null;
    },
    async delete(tokenHash) { sessionMap.delete(tokenHash); },
  };
  return { users, sessions, userList, sessionMap, ...memoryRegistration() };
}
