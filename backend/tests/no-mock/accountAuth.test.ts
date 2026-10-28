import request from 'supertest';
import { MongoMemoryServer } from 'mongodb-memory-server';
import { createApp } from '../../src/app';
import { connectDatabase, disconnectDatabase, DuplicateAccountError, mongoSessionStore, mongoUserStore, SessionModel, UserModel } from '../../src/userStore';
import { hashToken, startSession } from '../../src/session';

// Uses the real MongoDB stores against a local mongod and the real Google verifier.
let mongo: MongoMemoryServer;
const app = createApp();
const account = { email: 'pat@example.com', password: 'a long passphrase', displayName: 'Pat Kim' };

beforeAll(async () => {
  mongo = await MongoMemoryServer.create();
  await connectDatabase(mongo.getUri('ledger-test'));
}, 120000);
afterEach(async () => { await Promise.all([UserModel.deleteMany({}), SessionModel.deleteMany({})]); });
afterAll(async () => {
  await disconnectDatabase();
  await mongo.stop();
}, 30000);

// Interface POST /api/auth/register. No mocked components.
describe('Unmocked: POST /api/auth/register', () => {
  // Input: new account. Expected: 201; the user and a hashed session are persisted.
  test('Stores the account and session in MongoDB', async () => {
    const res = await request(app).post('/api/auth/register').send(account);
    expect(res.status).toBe(201);
    const stored = await UserModel.findOne({ email: account.email }).lean();
    expect(stored?.passwordHash).toMatch(/^scrypt\$/);
    expect(stored?.googleId).toBeUndefined();
    expect(await SessionModel.countDocuments({ userId: stored!._id })).toBe(1);
    expect(await SessionModel.countDocuments({ tokenHash: res.body.token })).toBe(0);
  });
  // Input: same email twice. Expected: 409 from the unique email index; one account remains.
  test('Enforces unique email addresses', async () => {
    await request(app).post('/api/auth/register').send(account);
    const res = await request(app).post('/api/auth/register').send({ ...account, email: 'PAT@example.com' });
    expect(res.status).toBe(409);
    expect(await UserModel.countDocuments()).toBe(1);
  });
});

// Interfaces POST /api/auth/login, GET /api/users/me, POST /api/auth/logout. No mocked components.
describe('Unmocked: login, account info and logout', () => {
  test('atomically invalidates legacy and delayed sessions while retaining only the latest changing session', async () => {
    const first = await request(app).post('/api/auth/register').send(account);
    const other = await request(app).post('/api/auth/login').send(account);
    const userId = first.body.user.id as string;
    // Existing accounts and sessions created before versioning must keep working until a credential change.
    await UserModel.updateOne({ _id: userId }, { $unset: { sessionVersion: '' } });
    await SessionModel.updateMany({ userId }, { $unset: { sessionVersion: '' } });
    const staleUser = (await mongoUserStore.findById(userId))!;
    const auth = `Bearer ${first.body.token}`;
    expect((await request(app).get('/api/users/me').set('Authorization', auth)).status).toBe(200);
    const changed = await request(app).patch('/api/users/me').set('Authorization', auth)
      .send({ newPassword: 'new passphrase', currentPassword: account.password });
    expect(changed.status).toBe(200);
    expect(changed.body.user).not.toHaveProperty('sessionVersion');
    expect(changed.body.user).not.toHaveProperty('retainedSessionHash');
    // A login that read the old password before the update may create its session afterwards.
    const delayed = await startSession(mongoSessionStore, staleUser);
    for (const token of [other.body.token, delayed.token]) {
      expect(await mongoSessionStore.find(hashToken(token))).toBeNull();
      expect((await request(app).get('/api/users/me').set('Authorization', `Bearer ${token}`)).status).toBe(401);
    }
    expect((await request(app).get('/api/users/me').set('Authorization', auth)).status).toBe(200);
    const latest = await request(app).post('/api/auth/login').send({ email: account.email, password: 'new passphrase' });
    expect(latest.status).toBe(200);
    const latestAuth = `Bearer ${latest.body.token}`;
    expect((await request(app).patch('/api/users/me').set('Authorization', latestAuth)
      .send({ newPassword: 'next passphrase', currentPassword: 'new passphrase' })).status).toBe(200);
    expect((await request(app).get('/api/users/me').set('Authorization', auth)).status).toBe(401);
    expect((await request(app).get('/api/users/me').set('Authorization', latestAuth)).status).toBe(200);
  });

  test('a duplicate-key failure rolls back credential changes and session invalidation together', async () => {
    const first = await request(app).post('/api/auth/register').send(account);
    const other = await request(app).post('/api/auth/login').send(account);
    await request(app).post('/api/auth/register').send({ ...account, email: 'taken@example.com' });
    const userId = first.body.user.id as string;
    const before = await mongoUserStore.findById(userId);
    await expect(mongoUserStore.update(userId, { email: 'taken@example.com', displayName: 'Should not save' }, hashToken(first.body.token)))
      .rejects.toBeInstanceOf(DuplicateAccountError);
    expect(await mongoUserStore.findById(userId)).toEqual(before);
    for (const token of [first.body.token, other.body.token]) {
      expect((await request(app).get('/api/users/me').set('Authorization', `Bearer ${token}`)).status).toBe(200);
    }
  });

  test('Persists profile and credential changes with the real MongoDB stores', async () => {
    const first = await request(app).post('/api/auth/register').send(account);
    const second = await request(app).post('/api/auth/login').send(account);
    const auth = `Bearer ${first.body.token}`;
    const changes = { displayName: 'Updated Pat', phoneNumber: '+1 604 555 0123', timeZone: 'America/Vancouver',
      defaultCurrency: 'USD', email: 'updated-pat@example.com', newPassword: 'updated passphrase', currentPassword: account.password };
    const saved = await request(app).patch('/api/users/me').set('Authorization', auth).send(changes);
    expect(saved.status).toBe(200);
    expect(saved.body.user).toMatchObject({ displayName: changes.displayName, phoneNumber: changes.phoneNumber,
      timeZone: changes.timeZone, defaultCurrency: changes.defaultCurrency, email: changes.email });
    const refreshed = await request(app).get('/api/users/me').set('Authorization', auth);
    expect(refreshed.body).toEqual(saved.body);
    expect((await request(app).get('/api/users/me').set('Authorization', `Bearer ${second.body.token}`)).status).toBe(401);
    expect((await request(app).post('/api/auth/login').send(account)).status).toBe(401);
    expect((await request(app).post('/api/auth/login').send({ email: changes.email, password: changes.newPassword })).status).toBe(200);
  });
  // Input: register, log in, read account, log out. Expected: the token works until logout.
  test('Full email account session lifecycle', async () => {
    await request(app).post('/api/auth/register').send(account);
    const login = await request(app).post('/api/auth/login').send({ email: account.email, password: account.password });
    expect(login.status).toBe(200);
    const auth = `Bearer ${login.body.token}`;
    const me = await request(app).get('/api/users/me').set('Authorization', auth);
    expect(me.status).toBe(200);
    expect(me.body.user).toMatchObject({ email: account.email, displayName: 'Pat Kim', signInMethods: ['password'] });
    expect((await request(app).post('/api/auth/logout').set('Authorization', auth)).status).toBe(204);
    expect((await request(app).get('/api/users/me').set('Authorization', auth)).status).toBe(401);
  });
  // Input: wrong password. Expected: 401, no session stored.
  test('Rejects a wrong password', async () => {
    await request(app).post('/api/auth/register').send(account);
    const res = await request(app).post('/api/auth/login').send({ email: account.email, password: 'wrong password' });
    expect(res.status).toBe(401);
    expect(await SessionModel.countDocuments()).toBe(1);
  });
});

// Interface POST /api/auth/google. No mocked components: the real Google verifier rejects a forged token.
describe('Unmocked: POST /api/auth/google', () => {
  // Input: a token that is not a Google-signed JWT. Expected: 401, no account created.
  test('Rejects a forged ID token', async () => {
    const original = process.env.GOOGLE_CLIENT_ID;
    process.env.GOOGLE_CLIENT_ID = 'test-web-client.apps.googleusercontent.com';
    try {
      const res = await request(app).post('/api/auth/google').send({ idToken: 'forged.token.value' });
      expect(res.status).toBe(401);
      expect(await UserModel.countDocuments()).toBe(0);
    } finally {
      if (original === undefined) delete process.env.GOOGLE_CLIENT_ID; else process.env.GOOGLE_CLIENT_ID = original;
    }
  });
});
