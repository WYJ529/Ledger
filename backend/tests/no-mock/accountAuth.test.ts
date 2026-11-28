import { required, requiredString } from '../helpers/required';
import { passwordConfirmationCases } from '../helpers/passwordConfirmation';
import request from 'supertest';
import { connectDatabase, migrateLegacyUsernames, mongoUserStore, SessionModel, UserModel } from '../../src/userStore';
import { hashPassword } from '../../src/password';
import { registerAccount } from '../helpers/registration';
import { patAccount as account, useAccountDatabase } from '../helpers/accountDatabase';

// Uses the real MongoDB stores against a local mongod. No mocked components.
const db = useAccountDatabase('ledger-test');
let app = db.app;
beforeEach(() => { app = db.app; });

// Interface POST /api/auth/register
describe('Unmocked: POST /api/auth/register', () => {
  // Input: 30 invalid registrations from one client, then a valid one
  // Expected status code: 400 thirty times, then 429
  // Expected behavior: registration attempts are rate-limited per client
  // Expected output: Retry-After header equal to retryAfterSeconds in the body
  test('Limits registration attempts per client', async () => {
    for (let i = 0; i < 30; i++) expect((await request(app).post('/api/auth/register').send({})).status).toBe(400);
    const limited = await request(app).post('/api/auth/register').send(account);
    expect(limited.status).toBe(429);
    expect(Number(limited.headers['retry-after'])).toBeGreaterThan(0);
    expect(limited.body.retryAfterSeconds).toBe(Number(limited.headers['retry-after']));
    expect(await UserModel.countDocuments()).toBe(0);
    expect(await SessionModel.countDocuments()).toBe(0);
  });

  // Input: password and confirmation pairs that differ in type, length, bytes or Unicode form
  // Expected status code: 400 for every mismatch, 201 for exact matches
  // Expected behavior: confirmation is compared exactly; nothing is stored on mismatch
  // Expected output: { error: "Passwords do not match." } on mismatch; { user, token, expiresAt } on success
  test.each(passwordConfirmationCases)('Password confirmation: %s', async (_label, password, confirmPassword, status) => {
    const res = await request(app).post('/api/auth/register').send({ username: 'joy_tao', password, confirmPassword });
    expect(res.status).toBe(status);
    if (status === 400) expect(res.body.error).toBe('Passwords do not match.');
    else expect(res.body).toMatchObject({ user: { username: 'joy_tao' }, token: expect.any(String), expiresAt: expect.any(Number) });
    expect(await UserModel.countDocuments()).toBe(status === 400 ? 0 : 1);
    expect(await SessionModel.countDocuments()).toBe(status === 400 ? 0 : 1);
  });

  // Input: invalid username, short/long/missing password, or a missing/different confirmation
  // Expected status code: 400
  // Expected behavior: nothing is stored
  // Expected output: error naming the problem
  test.each([
    [{ ...account, username: 'bad name' }, 'username'],
    [{ ...account, password: 'short' }, 'at least 8'],
    [{ ...account, password: 'x'.repeat(129) }, 'at most 128'],
    [{ ...account, password: undefined }, 'password'],
    [{ ...account, username: '' }, 'name'],
    [{ ...account, username: 'x'.repeat(31) }, 'name'],
    [{ ...account, confirmPassword: undefined }, 'Passwords do not match'],
    [{ ...account, confirmPassword: 'different' }, 'Passwords do not match'],
  ])('Rejects invalid input %#', async (body, message) => {
    const res = await request(app).post('/api/auth/register').send(body);
    expect(res.status).toBe(400);
    expect(res.body.error).toContain(message);
    expect(await UserModel.countDocuments()).toBe(0);
    expect(await SessionModel.countDocuments()).toBe(0);
  });

  // Input: three simultaneous registrations for the same username
  // Expected status code: one 201, two 409
  // Expected behavior: the unique index admits exactly one account and one session
  // Expected output: session token for the winner; error message for the others
  test('Atomically accepts only one simultaneous registration for a normalized username', async () => {
    const results = await Promise.all(Array.from({ length: 3 }, () => registerAccount(app, account)));
    expect(results.map(res => res.status).sort()).toEqual([201, 409, 409]);
    expect(await UserModel.countDocuments()).toBe(1);
    expect(await SessionModel.countDocuments()).toBe(1);
  });

  // Input: two registrations with different usernames and no email address
  // Expected status code: 201 for both
  // Expected behavior: the sparse email index allows several email-free accounts
  // Expected output: two created accounts
  test('Allows multiple accounts without any email address', async () => {
    expect((await registerAccount(app, account)).status).toBe(201);
    expect((await registerAccount(app, { ...account, username: 'another' })).status).toBe(201);
    expect(await UserModel.countDocuments({ email: { $exists: false } })).toBe(2);
  });

  // Input: valid new username, password and confirmation
  // Expected status code: 201
  // Expected behavior: user is stored with a scrypt hash; only the token hash of the session is stored
  // Expected output: { user, token, expiresAt }
  test('Stores the account and session in MongoDB', async () => {
    const res = await registerAccount(app, account);
    expect(res.status).toBe(201);
    const stored = await UserModel.findOne({ username: 'pat_kim' }).lean();
    expect(stored?.passwordHash).toMatch(/^scrypt\$/);
    expect(stored?.googleId).toBeUndefined();
    expect(stored).not.toHaveProperty('timeZone');
    expect(stored).not.toHaveProperty('defaultCurrency');
    expect(await SessionModel.countDocuments({ userId: required(stored)._id })).toBe(1);
    expect(await SessionModel.countDocuments({ tokenHash: res.body.token })).toBe(0);
  });

  // Input: the same username registered again in a different case
  // Expected status code: 409
  // Expected behavior: the unique username index rejects the duplicate; one account remains
  // Expected output: error message
  test('Enforces unique usernames', async () => {
    await registerAccount(app, account);
    const res = await request(app).post('/api/auth/register').send({ ...account, username: 'PAT_KIM' });
    expect(res.status).toBe(409);
    expect(await UserModel.countDocuments()).toBe(1);
  });

  // Input: registrations after the database still has the legacy required email index
  // Expected status code: 201 for both
  // Expected behavior: connecting upgrades the index to sparse so email-free accounts are allowed
  // Expected output: two created accounts
  test('Upgrades the legacy required email index and permits multiple email-free accounts', async () => {
    await UserModel.collection.dropIndex('email_1');
    await UserModel.collection.createIndex({ email: 1 }, { unique: true });
    await connectDatabase(db.uri());
    const emailIndex = (await UserModel.collection.indexes()).find(index => index.name === 'email_1');
    expect(emailIndex?.sparse).toBe(true);
    expect((await registerAccount(app, account)).status).toBe(201);
    expect((await registerAccount(app, { ...account, username: 'another' })).status).toBe(201);
  });
});

// Interface POST /api/auth/login
describe('Unmocked: POST /api/auth/login', () => {
  // Input: 60 invalid login attempts from one client, then another
  // Expected status code: 400 sixty times, then 429
  // Expected behavior: repeated login attempts are limited and no session is created
  // Expected output: rate-limit error
  test('Limits login attempts per client', async () => {
    for (let i = 0; i < 60; i++) expect((await request(app).post('/api/auth/login').send({})).status).toBe(400);
    const limited = await request(app).post('/api/auth/login').send({});
    expect(limited.status).toBe(429);
    expect(typeof limited.body.error).toBe('string');
    expect(Number(limited.headers['retry-after'])).toBeGreaterThan(0);
    expect(limited.body.retryAfterSeconds).toBe(Number(limited.headers['retry-after']));
    expect(await SessionModel.countDocuments()).toBe(0);
  });

  // Input: missing fields, an invalid username, or a non-string password
  // Expected status code: 400
  // Expected behavior: no account or session is created
  // Expected output: error message
  test.each([{}, { username: account.username }, { username: '!', password: 'password1' }, { username: account.username, password: 5 }])(
    'Rejects incomplete input %#', async body => {
      const res = await request(app).post('/api/auth/login').send(body);
      expect(res.status).toBe(400);
      expect(res.body).toEqual({ error: 'Enter your username and password.' });
      expect(await UserModel.countDocuments()).toBe(0);
      expect(await SessionModel.countDocuments()).toBe(0);
    });

  // Input: correct password for a migrated legacy account
  // Expected status code: 200
  // Expected behavior: the repeatable migration assigns unique usernames without touching identities or passwords
  // Expected output: { user, token, expiresAt }
  test('Signs in a legacy account with its migrated username', async () => {
    const passwordHash = await hashPassword(account.password);
    await UserModel.create({ username: 'patkim', displayName: 'Reserved name' });
    const legacy = await UserModel.create({ email: 'old@example.com', displayName: 'Pat Kim', passwordHash, googleId: 'old-google' });
    const second = await UserModel.create({ displayName: 'Pat Kim' });
    const unicode = await UserModel.create({ displayName: '测试' });
    await migrateLegacyUsernames();
    const migrated = required(await mongoUserStore.findById(requiredString(legacy.id)));
    expect(migrated).toMatchObject({ id: legacy.id, username: 'patkim_1', email: 'old@example.com', displayName: 'Pat Kim', passwordHash, googleId: 'old-google' });
    expect((await mongoUserStore.findById(second.id))?.username).toBe('patkim_2');
    expect((await mongoUserStore.findById(unicode.id))?.username).toBe('user');
    await migrateLegacyUsernames();
    expect(await mongoUserStore.findById(legacy.id)).toEqual(migrated);
    expect((await request(app).post('/api/auth/login').send({ username: migrated.username, password: account.password })).status).toBe(200);
  });

  // Input: login to a stored record that still contains retired timeZone/defaultCurrency fields
  // Expected status code: 200
  // Expected behavior: retired fields stay in storage but are never returned or required
  // Expected output: user without timeZone or defaultCurrency
  test('Ignores retired fields in legacy records', async () => {
    const first = await registerAccount(app, account);
    expect(first.status).toBe(201);
    const stored = await UserModel.findOne({ username: 'pat_kim' }).lean();
    const legacy = { timeZone: 'Pacific/Kiritimati', defaultCurrency: 'JPY' };
    // Bypass the current schema to reproduce a pre-retirement database record.
    await UserModel.collection.updateOne({ _id: required(stored)._id }, { $set: legacy, $unset: { phoneNumber: '', avatarDataUrl: '', sessionVersion: '' } });
    const login = await request(app).post('/api/auth/login').send(account);
    expect(login.status).toBe(200);
    const auth = `Bearer ${login.body.token}`;
    const refreshed = await request(app).get('/api/users/me').set('Authorization', auth);
    expect(refreshed.status).toBe(200);
    const updated = await request(app).patch('/api/users/me').set('Authorization', auth).send({ displayName: 'Updated legacy account' });
    expect(updated.status).toBe(200);
    for (const user of [login.body.user, refreshed.body.user, updated.body.user, await mongoUserStore.findById(required(stored)._id.toString())]) {
      expect(user).not.toHaveProperty('timeZone');
      expect(user).not.toHaveProperty('defaultCurrency');
    }
    expect(updated.body.user.displayName).toBe('Updated legacy account');
    expect(await UserModel.collection.findOne({ username: 'pat_kim' })).toMatchObject(legacy);
  });

  // Input: registered username with a wrong password
  // Expected status code: 401
  // Expected behavior: no new session is stored
  // Expected output: { error: "Incorrect username or password." }
  test('Rejects a wrong password', async () => {
    await registerAccount(app, account);
    const res = await request(app).post('/api/auth/login').send({ username: account.username, password: 'wrong password' });
    expect(res.status).toBe(401);
    expect(await SessionModel.countDocuments()).toBe(1);
  });

  // Input: login body larger than the 16 KiB account limit
  // Expected status code: 413
  // Expected behavior: the body parser rejects it before authentication
  // Expected output: { error: "Request body too large." }
  test('Rejects an oversized request body', async () => {
    expect((await request(app).post('/api/auth/login').send({ username: 'x'.repeat(20 * 1024) })).status).toBe(413);
  });
});

// Interface GET /api/users/me
describe('Unmocked: GET /api/users/me', () => {

  // Input: an account is removed while its session still exists.
  // Expected status code: 401.
  // Expected behavior: delete the orphan session during authentication.
  // Expected output: a Bearer challenge and no profile.
  test('removes the session of a deleted account', async () => {
    const registered = await registerAccount(app, { username: 'former_user', password: 'good password' });
    expect(registered.status).toBe(201);
    await UserModel.deleteOne({ username: 'former_user' });
    const result = await request(app).get('/api/users/me').auth(requiredString(registered.body.token), { type: 'bearer' });
    expect(result.status).toBe(401);
    expect(result.headers['www-authenticate']).toBe('Bearer');
    expect(await SessionModel.countDocuments()).toBe(0);
  });

  // Input: bearer token from registration
  // Expected status code: 200
  // Expected behavior: account is read from MongoDB; no credential fields are exposed
  // Expected output: { user } with username, displayName and signInMethods
  test('Returns the stored account', async () => {
    const created = await registerAccount(app, account);
    const me = await request(app).get('/api/users/me').set('Authorization', `Bearer ${created.body.token}`);
    expect(me.status).toBe(200);
    expect(me.body.user).toMatchObject({ id: created.body.user.id, username: 'pat_kim', signInMethods: ['password'] });
    expect(JSON.stringify(me.body)).not.toContain('scrypt');
    expect(me.headers['cache-control']).toBe('no-store');
  });

  // Input: no Authorization header
  // Expected status code: 401
  // Expected behavior: no account data is read
  // Expected output: Bearer challenge
  test('Requires a session', async () => {
    const res = await request(app).get('/api/users/me');
    expect(res.status).toBe(401);
    expect(res.headers['www-authenticate']).toBe('Bearer');
  });
});

// Interface POST /api/auth/logout
describe('Unmocked: POST /api/auth/logout', () => {
  // Input: register, log in, read the account, then log out with the login token
  // Expected status code: 204
  // Expected behavior: the session is deleted; the same token is rejected afterwards
  // Expected output: empty body; later GET /api/users/me returns 401
  test('Ends a username account session', async () => {
    await registerAccount(app, account);
    const login = await request(app).post('/api/auth/login').send({ username: account.username, password: account.password });
    expect(login.status).toBe(200);
    const auth = `Bearer ${login.body.token}`;
    const me = await request(app).get('/api/users/me').set('Authorization', auth);
    expect(me.status).toBe(200);
    expect(me.body.user).toMatchObject({ username: account.username, displayName: 'pat_kim', signInMethods: ['password'] });
    expect((await request(app).post('/api/auth/logout').set('Authorization', auth)).status).toBe(204);
    expect((await request(app).get('/api/users/me').set('Authorization', auth)).status).toBe(401);
  });
});
