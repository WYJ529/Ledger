import request from 'supertest';
import { MongoMemoryServer } from 'mongodb-memory-server';
import { createApp } from '../../src/app';
import { connectDatabase, disconnectDatabase, DuplicateAccountError, mongoSessionStore, mongoUserStore, SessionModel, UserModel } from '../../src/userStore';
import { hashToken, startSession } from '../../src/session';
import { RegistrationVerificationModel } from '../../src/registrationVerification';
import { registerVerified, registrationProof } from '../helpers/registration';
import { startSmtpServer } from '../helpers/smtpServer';

// Uses the real MongoDB stores against a local mongod and the real Google verifier.
let mongo: MongoMemoryServer;
let app: ReturnType<typeof createApp>;
let mail: Awaited<ReturnType<typeof startSmtpServer>>;
const smtpKeys = ['SMTP_HOST', 'SMTP_PORT', 'SMTP_USER', 'SMTP_PASSWORD', 'SMTP_FROM'] as const;
const originalSmtp = smtpKeys.map(key => process.env[key]);
const account = { email: 'pat@example.com', password: 'a long passphrase', displayName: 'Pat Kim' };

beforeAll(async () => {
  mongo = await MongoMemoryServer.create();
  await connectDatabase(mongo.getUri('ledger-test'));
  mail = await startSmtpServer();
  process.env.SMTP_HOST = '127.0.0.1';
  process.env.SMTP_PORT = String(mail.port);
  process.env.SMTP_FROM = 'Ledger <ledger@example.com>';
  delete process.env.SMTP_USER;
  delete process.env.SMTP_PASSWORD;
}, 120000);
beforeEach(() => { app = createApp(); mail.codes.clear(); mail.messages.length = 0; });
afterEach(async () => { await Promise.all([UserModel.deleteMany({}), SessionModel.deleteMany({}), RegistrationVerificationModel.deleteMany({})]); });
afterAll(async () => {
  smtpKeys.forEach((key, i) => { const value = originalSmtp[i]; if (value === undefined) delete process.env[key]; else process.env[key] = value; });
  await mail?.close();
  await disconnectDatabase();
  await mongo.stop();
}, 30000);

// Interface POST /api/auth/register. No mocked components.
describe('Unmocked: POST /api/auth/register', () => {
  test('persists only a hashed code and survives an application restart before verification', async () => {
    const proof = await registrationProof(app, mail.codes, account.email);
    expect(mail.messages[0]).toContain('Subject: Your Ledger verification code');
    expect(await UserModel.countDocuments()).toBe(0);
    expect(await SessionModel.countDocuments()).toBe(0);
    const stored = await RegistrationVerificationModel.findOne({ email: account.email }).lean();
    expect(stored?.codeHash).toMatch(/^scrypt\$/);
    expect(stored?.codeHash).not.toContain(proof.code);
    const restarted = createApp();
    const res = await request(restarted).post('/api/auth/register').send({ ...account, ...proof });
    expect(res.status).toBe(201);
    expect(await RegistrationVerificationModel.countDocuments()).toBe(0);
  });

  test('atomically accepts only one simultaneous code request and one verification', async () => {
    const sent = await Promise.all(Array.from({ length: 3 }, () => request(app).post('/api/auth/register/code').send({ email: account.email })));
    expect(sent.map(res => res.status).sort()).toEqual([202, 429, 429]);
    expect(mail.messages).toHaveLength(1);
    const proof = { verificationId: sent.find(res => res.status === 202)!.body.verificationId, code: mail.codes.get(account.email) };
    const results = await Promise.all(Array.from({ length: 3 }, () => request(app).post('/api/auth/register').send({ ...account, ...proof })));
    expect(results.filter(res => res.status === 201)).toHaveLength(1);
    expect(await UserModel.countDocuments()).toBe(1);
    expect(await SessionModel.countDocuments()).toBe(1);
  });

  test('enforces expiry, attempts and resend invalidation in MongoDB', async () => {
    const old = await registrationProof(app, mail.codes, account.email);
    await RegistrationVerificationModel.updateOne({ email: account.email }, { $set: { expiresAt: new Date(Date.now() - 1), resendAt: new Date(0) } });
    expect((await request(app).post('/api/auth/register').send({ ...account, ...old })).status).toBe(400);
    const fresh = await registrationProof(app, mail.codes, account.email);
    expect((await request(app).post('/api/auth/register').send({ ...account, ...old })).status).toBe(400);
    const code = fresh.code === '000000' ? '000001' : '000000';
    await Promise.all(Array.from({ length: 7 }, () => request(app).post('/api/auth/register').send({ ...account, ...fresh, code })));
    expect((await RegistrationVerificationModel.findOne({ email: account.email }).lean())?.attempts).toBe(5);
    expect((await request(app).post('/api/auth/register').send({ ...account, ...fresh })).status).toBe(400);
    expect(await UserModel.countDocuments()).toBe(0);
  });

  // Input: new account. Expected: 201; the user and a hashed session are persisted.
  test('Stores the account and session in MongoDB', async () => {
    const res = await registerVerified(app, mail.codes, account);
    expect(res.status).toBe(201);
    const stored = await UserModel.findOne({ email: account.email }).lean();
    expect(stored?.passwordHash).toMatch(/^scrypt\$/);
    expect(stored?.googleId).toBeUndefined();
    expect(await SessionModel.countDocuments({ userId: stored!._id })).toBe(1);
    expect(await SessionModel.countDocuments({ tokenHash: res.body.token })).toBe(0);
  });
  // Input: same email twice. Expected: 409 from the unique email index; one account remains.
  test('Enforces unique email addresses', async () => {
    await registerVerified(app, mail.codes, account);
    const res = await request(app).post('/api/auth/register').send({ ...account, email: 'PAT@example.com' });
    expect(res.status).toBe(409);
    expect(await UserModel.countDocuments()).toBe(1);
  });
});

// Interfaces POST /api/auth/login, GET /api/users/me, POST /api/auth/logout. No mocked components.
describe('Unmocked: login, account info and logout', () => {
  test('atomically invalidates legacy and delayed sessions while retaining only the latest changing session', async () => {
    const first = await registerVerified(app, mail.codes, account);
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
    const first = await registerVerified(app, mail.codes, account);
    const other = await request(app).post('/api/auth/login').send(account);
    await registerVerified(app, mail.codes, { ...account, email: 'taken@example.com' });
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
    const first = await registerVerified(app, mail.codes, account);
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
    await registerVerified(app, mail.codes, account);
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
    await registerVerified(app, mail.codes, account);
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
