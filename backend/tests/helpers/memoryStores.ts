import { DuplicateAccountError, type SessionRecord, type SessionStore, type UserRecord, type UserStore } from '../../src/userStore';

/** In-memory stand-ins for the MongoDB user and session collections. */
export function memoryStores() {
  const userList: UserRecord[] = [];
  const sessionMap = new Map<string, SessionRecord>();
  const users: UserStore = {
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
    async find(tokenHash) { return sessionMap.get(tokenHash) ?? null; },
    async delete(tokenHash) { sessionMap.delete(tokenHash); },
  };
  return { users, sessions, userList, sessionMap };
}
