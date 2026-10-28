import request from 'supertest';
import { createApp } from '../../src/app';
import { hashToken, SESSION_TTL_MS } from '../../src/session';
import { memoryStores } from '../helpers/memoryStores';

const account = { email: ' Sam@Example.com ', password: 'correct horse', displayName: 'Sam Lee' };

async function registered() {
  const stores = memoryStores();
  const app = createApp(stores);
  const res = await request(app).post('/api/auth/register').send(account);
  return { stores, app, token: res.body.token as string };
}

// Interface POST /api/auth/register. Mock: User DB.
describe('Mocked: POST /api/auth/register', () => {
  // Input: valid email, password and name. Expected: 201, hashed password stored, session started.
  test('Creates an email account and signs in', async () => {
    const stores = memoryStores();
    const res = await request(createApp(stores)).post('/api/auth/register').send(account);
    expect(res.status).toBe(201);
    expect(res.headers['cache-control']).toBe('no-store');
    expect(res.body.user).toEqual({
      id: 'user-1', email: 'sam@example.com', displayName: 'Sam Lee', signInMethods: ['password'], createdAt: expect.any(String),
      phoneNumber: '', timeZone: 'UTC', defaultCurrency: 'CAD', avatarDataUrl: '',
    });
    expect(res.body.expiresAt).toBeGreaterThan(Date.now() + SESSION_TTL_MS - 60000);
    expect(stores.userList[0]?.passwordHash).toMatch(/^scrypt\$/);
    expect(JSON.stringify(res.body)).not.toContain('correct horse');
    expect(JSON.stringify(res.body)).not.toContain('scrypt');
    expect(stores.sessionMap.has(hashToken(res.body.token))).toBe(true);
  });
  // Input: invalid fields. Expected: 400 with a specific message; nothing stored.
  test.each([
    [{ ...account, email: 'not-an-email' }, 'email'],
    [{ ...account, password: 'short' }, 'at least 8'],
    [{ ...account, password: 'x'.repeat(129) }, 'at most 128'],
    [{ ...account, password: undefined }, 'password'],
    [{ ...account, displayName: '' }, 'name'],
    [{ ...account, displayName: 'x'.repeat(61) }, 'name'],
  ])('Rejects invalid input %#', async (body, message) => {
    const stores = memoryStores();
    const res = await request(createApp(stores)).post('/api/auth/register').send(body);
    expect(res.status).toBe(400);
    expect(res.body.error).toContain(message);
    expect(stores.userList).toHaveLength(0);
  });
  // Input: email that is already registered (different case). Expected: 409.
  test('Rejects a duplicate email', async () => {
    const { app } = await registered();
    const res = await request(app).post('/api/auth/register').send({ ...account, email: 'SAM@example.com' });
    expect(res.status).toBe(409);
  });
  // Mock: duplicate detected by the unique index during a concurrent sign-up. Expected: 409.
  test('Reports a duplicate created concurrently', async () => {
    const stores = memoryStores();
    const create = stores.users.create;
    stores.users.create = async input => { await create(input); return create(input); };
    expect((await request(createApp(stores)).post('/api/auth/register').send(account)).status).toBe(409);
  });
});

// Interface POST /api/auth/login. Mock: User DB.
describe('Mocked: POST /api/auth/login', () => {
  // Input: correct credentials, any email case. Expected: 200, new session for the account.
  test('Signs in with email and password', async () => {
    const { app, stores } = await registered();
    const res = await request(app).post('/api/auth/login').send({ email: 'SAM@EXAMPLE.COM', password: account.password });
    expect(res.status).toBe(200);
    expect(res.body.user.email).toBe('sam@example.com');
    expect(stores.sessionMap.size).toBe(2);
  });
  // Input: wrong password, unknown email, Google-only account. Expected: the same 401 message for all.
  test('Rejects bad credentials without revealing which part is wrong', async () => {
    const { app, stores } = await registered();
    await stores.users.create({ email: 'google@example.com', displayName: 'G', googleId: 'g-1' });
    const attempts = [
      { email: account.email, password: 'wrong password' },
      { email: 'nobody@example.com', password: account.password },
      { email: 'google@example.com', password: account.password },
    ];
    for (const body of attempts) {
      const res = await request(app).post('/api/auth/login').send(body);
      expect(res.status).toBe(401);
      expect(res.body.error).toBe('Incorrect email or password.');
    }
    expect(stores.sessionMap.size).toBe(1);
  });
  // Input: missing fields. Expected: 400.
  test.each([{}, { email: account.email }, { email: 'bad', password: 'password1' }, { email: account.email, password: 5 }])(
    'Rejects incomplete input', async body => {
      expect((await request(createApp(memoryStores())).post('/api/auth/login').send(body)).status).toBe(400);
    });
});

// Interface POST /api/auth/logout. Mock: User DB.
describe('Mocked: POST /api/auth/logout', () => {
  // Input: valid bearer token. Expected: 204; the token no longer works.
  test('Ends the current session only', async () => {
    const { app, token } = await registered();
    const other = (await request(app).post('/api/auth/login').send(account)).body.token as string;
    expect((await request(app).post('/api/auth/logout').set('Authorization', `Bearer ${token}`)).status).toBe(204);
    expect((await request(app).get('/api/users/me').set('Authorization', `Bearer ${token}`)).status).toBe(401);
    expect((await request(app).get('/api/users/me').set('Authorization', `Bearer ${other}`)).status).toBe(200);
  });
  // Input: missing or unknown token. Expected: 401.
  test.each([undefined, 'Bearer', 'Bearer unknown-token-unknown-token', 'Basic abc'])('Requires a session (%s)', async header => {
    const { app } = await registered();
    const req = request(app).post('/api/auth/logout');
    const res = await (header ? req.set('Authorization', header) : req);
    expect(res.status).toBe(401);
    expect(res.headers['www-authenticate']).toBe('Bearer');
  });
});

// Interface GET /api/users/me. Mock: User DB.
describe('Mocked: GET /api/users/me', () => {
  // Input: valid bearer token. Expected: 200 with account info and no password hash.
  test('Returns the signed-in account', async () => {
    const { app, token } = await registered();
    const res = await request(app).get('/api/users/me').set('Authorization', `Bearer ${token}`);
    expect(res.status).toBe(200);
    expect(res.body.user).toMatchObject({ email: 'sam@example.com', displayName: 'Sam Lee', signInMethods: ['password'] });
    expect(JSON.stringify(res.body)).not.toContain('scrypt');
  });
  // Input: expired session. Expected: 401.
  test('Rejects an expired session', async () => {
    const { app, token, stores } = await registered();
    stores.sessionMap.get(hashToken(token))!.expiresAt = new Date(Date.now() - 1);
    expect((await request(app).get('/api/users/me').set('Authorization', `Bearer ${token}`)).status).toBe(401);
  });
  // Input: session whose account was deleted. Expected: 401 and the session is removed.
  test('Rejects a session for a missing account', async () => {
    const { app, token, stores } = await registered();
    stores.userList.length = 0;
    expect((await request(app).get('/api/users/me').set('Authorization', `Bearer ${token}`)).status).toBe(401);
    expect(stores.sessionMap.size).toBe(0);
  });
});
