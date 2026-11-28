import { required, requiredString } from '../helpers/required';
import request from 'supertest';
import { createApp } from '../../src/app';
import { UserModel, mongoUserStore } from '../../src/userStore';
import { hashToken, SESSION_TTL_MS } from '../../src/session';
import { memoryStores } from '../helpers/memoryStores';
import { registerAccount } from '../helpers/registration';

// Mocked component: the MongoDB user and session stores are replaced by in-memory stores
// whose methods individual tests override to inject failures and races.
const account = { username: ' Sam_Lee ', password: 'correct horse', confirmPassword: 'correct horse' };

// Silences the expected 500 logs; individual tests replace store methods to inject failures.
beforeEach(() => { jest.spyOn(console, 'error').mockImplementation(() => undefined); });
afterEach(() => jest.restoreAllMocks());
const unavailable = (): Promise<never> => Promise.reject(new Error('Database unavailable'));

async function registered() {
  const stores = memoryStores();
  const app = createApp(stores);
  const res = await registerAccount(app, account);
  return { stores, app, token: res.body.token as string };
}

// Interface POST /api/auth/register
describe('Mocked: POST /api/auth/register', () => {
  // Input: 2,000 distinct proxy clients submit invalid registrations within one window.
  // Expected status code: 400 for invalid input, 503 at capacity, then 400 after expiry.
  // Expected behavior: bound active client buckets and reclaim them when the window expires.
  // Expected output: the registration busy response only while all buckets are occupied.
  test('recovers registration capacity at the window boundary', async () => {
    const app = createApp(memoryStores());
    app.set('trust proxy', 1);
    const clock = jest.spyOn(Date, 'now').mockReturnValue(1000000);
    const server = app.listen(0, '127.0.0.1');
    try {
      await new Promise<void>(resolve => server.once('listening', resolve));
      const address = server.address();
      if (!address || typeof address === 'string') throw new Error('Expected TCP listener');
      const register = async (index: number) => {
        const response = await fetch(`http://127.0.0.1:${address.port}/api/auth/register`, {
          method: 'POST', headers: { 'X-Forwarded-For': `10.0.${Math.floor(index / 255)}.${index % 255}`, 'Content-Type': 'application/json' },
          body: '{}',
        });
        return { status: response.status, body: await response.json() };
      };
      for (let offset = 0; offset < 2000; offset += 20) {
        const batch = await Promise.all(Array.from({ length: 20 }, (_, i) => register(offset + i)));
        expect(batch.every(result => result.status === 400)).toBe(true);
      }
      expect(await register(2000)).toEqual({ status: 503, body: { error: 'Registration is busy. Please try again later.' } });
      clock.mockReturnValue(1600000);
      expect((await register(2000)).status).toBe(400);
    } finally {
      await new Promise<void>(resolve => server.close(() => { resolve(); }));
    }
  }, 60000);

  // Input: registration requests through a transport with no Express IP, with or without a socket address.
  // Expected status code: 400 then 429 for each independent address bucket.
  // Expected behavior: limit requests consistently for fallback and unidentified clients.
  // Expected output: the retry interval after 30 submissions per bucket.
  test('limits registrations from transports with partial address metadata', async () => {
    const app = createApp(memoryStores());
    Object.defineProperty(app.request, 'ip', { get: () => undefined });
    const server = app.listen(0, '127.0.0.1');
    server.prependListener('request', req => {
      if (req.headers['x-test-no-peer']) Object.defineProperty(req.socket, 'remoteAddress', { value: undefined });
    });
    try {
      for (const unknown of [false, true]) {
        for (let i = 0; i <= 30; i++) {
          const submission = request(server).post('/api/auth/register').send({});
          const response = await (unknown ? submission.set('x-test-no-peer', '1') : submission);
          expect(response.status).toBe(i === 30 ? 429 : 400);
        }
      }
    } finally {
      await new Promise<void>(resolve => server.close(() => { resolve(); }));
    }
  });


  // Input: an available username while the MongoDB insert fails.
  // Expected status code: 500.
  // Expected behavior: propagate non-duplicate database errors without creating a session.
  // Expected output: a generic failure.
  test('propagates a MongoDB insert error', async () => {
    const stores = memoryStores();
    jest.spyOn(mongoUserStore, 'findByUsername').mockResolvedValueOnce(null);
    jest.spyOn(UserModel, 'create').mockRejectedValueOnce(new Error('write failed'));
    const response = await registerAccount(createApp({ users: mongoUserStore, sessions: stores.sessions }), account);
    expect(response.status).toBe(500);
    expect(stores.sessionMap.size).toBe(0);
  });


  // Input: the native password derivation callback reports a failure.
  // Expected status code: 500.
  // Expected behavior: abort registration before storing an account or starting a session.
  // Expected output: generic error without secrets.
  test('aborts registration when password hashing fails', async () => {
    const crypto: typeof import('node:crypto') = jest.requireActual<typeof import('node:crypto')>('node:crypto');
    jest.spyOn(crypto, 'scrypt').mockImplementationOnce((_password, _salt, _length, _options, callback) => {
      callback(new Error('hash failure'), Buffer.alloc(0));
    });
    const stores = memoryStores();
    const response = await registerAccount(createApp(stores), account);
    expect(response.status).toBe(500);
    expect(stores.userList).toHaveLength(0);
    expect(stores.sessionMap.size).toBe(0);
  });


  // Input: the user insert rejects with a non-Error database diagnostic.
  // Expected status code: 500.
  // Expected behavior: omit internal details and do not start a session.
  // Expected output: generic failure.
  test('reports an unexpected account insert failure', async () => {
    const stores = memoryStores();
    jest.spyOn(stores.users, 'create').mockRejectedValueOnce('database offline');
    const response = await registerAccount(createApp(stores), account);
    expect(response.status).toBe(500);
    expect(response.body.error).not.toContain('database offline');
    expect(stores.sessionMap.size).toBe(0);
  });

  // Input: valid username, password and confirmation
  // Expected status code: 201
  // Expected behavior: a scrypt hash is stored; a session starts; no secret is returned
  // Expected output: { user, token, expiresAt } with the normalized username
  test('Creates a username account and signs in', async () => {
    const stores = memoryStores();
    const res = await registerAccount(createApp(stores), account);
    expect(res.status).toBe(201);
    expect(res.headers['cache-control']).toBe('no-store');
    expect(res.body.user).toEqual({
      id: 'user-1', email: '', username: 'sam_lee', displayName: 'sam_lee', signInMethods: ['password'], createdAt: expect.any(String),
      phoneNumber: '', avatarDataUrl: '',
    });
    expect(res.body.expiresAt).toBeGreaterThan(Date.now() + SESSION_TTL_MS - 60000);
    expect(stores.userList[0]?.passwordHash).toMatch(/^scrypt\$/);
    expect(JSON.stringify(res.body)).not.toContain('correct horse');
    expect(JSON.stringify(res.body)).not.toContain('scrypt');
    expect(stores.sessionMap.has(hashToken(requiredString(res.body.token)))).toBe(true);
  });

  // Input: a username already registered in a different case
  // Expected status code: 409
  // Expected behavior: no second account is created
  // Expected output: error message
  test('Rejects a duplicate username', async () => {
    const { app } = await registered();
    const res = await request(app).post('/api/auth/register').send({ ...account, username: 'SAM_LEE' });
    expect(res.status).toBe(409);
  });

  // Input: valid registration; mock: the store creates the same user twice, as a concurrent sign-up would
  // Expected status code: 409
  // Expected behavior: the duplicate reported by the unique index is surfaced as a conflict
  // Expected output: error message
  test('Reports a duplicate created concurrently', async () => {
    const stores = memoryStores();
    const create = stores.users.create.bind(stores.users);
    stores.users.create = async input => { await create(input); return create(input); };
    expect((await registerAccount(createApp(stores), account)).status).toBe(409);
  });

  // Input: registration that includes an invalid email, and the retired code-request endpoint
  // Expected status code: 201 for registration, 404 for the retired endpoint
  // Expected behavior: email input is ignored at registration
  // Expected output: stored user has no email
  test('Ignores email input and has no email verification endpoint', async () => {
    const stores = memoryStores();
    const app = createApp(stores);
    expect((await request(app).post('/api/auth/register/code').send({ email: 'unused@example.com' })).status).toBe(404);
    const res = await request(app).post('/api/auth/register').send({ ...account, email: 'invalid' });
    expect(res.status).toBe(201);
    expect(stores.userList[0]?.email).toBeUndefined();
  });

});

// Interface POST /api/auth/login
describe('Mocked: POST /api/auth/login', () => {

  // Input: a legacy account with a malformed stored password hash.
  // Expected status code: 401.
  // Expected behavior: reject authentication and leave existing sessions untouched.
  // Expected output: credential error without a token.
  test.each(['legacy', 'scrypt$bad$8$1$c2FsdA==$a2V5', 'scrypt$0$8$1$c2FsdA==$a2V5'])(
    'rejects a malformed stored password hash %#', async stored => {
      const { app, stores } = await registered();
      required(stores.userList[0]).passwordHash = stored;
      const response = await request(app).post('/api/auth/login').send(account);
      expect(response.status).toBe(401);
      expect(response.body).not.toHaveProperty('token');
      expect(stores.sessionMap.size).toBe(1);
    });

  // Input: correct credentials with the username in another case
  // Expected status code: 200
  // Expected behavior: a new session is stored for the account
  // Expected output: { user, token } with username "sam_lee"
  test('Signs in with username and password', async () => {
    const { app, stores } = await registered();
    const res = await request(app).post('/api/auth/login').send({ username: 'SAM_LEE', password: account.password });
    expect(res.status).toBe(200);
    expect(res.body.user.username).toBe('sam_lee');
    expect(stores.sessionMap.size).toBe(2);
  });

  // Input: wrong password, unknown username, and a Google-only account
  // Expected status code: 401
  // Expected behavior: the same message is returned for every case; no session is stored
  // Expected output: { error: "Incorrect username or password." }
  test('Rejects bad credentials without revealing which part is wrong', async () => {
    const { app, stores } = await registered();
    await stores.users.create({ username: 'google_user', displayName: 'G', googleId: 'g-1' });
    for (const body of [
      { username: account.username, password: 'wrong password' },
      { username: 'nobody', password: account.password },
      { username: 'google_user', password: account.password },
    ]) {
      const res = await request(app).post('/api/auth/login').send(body);
      expect(res.status).toBe(401);
      expect(res.body.error).toBe('Incorrect username or password.');
    }
    expect(stores.sessionMap.size).toBe(1);
  });

  // Input: valid credentials; mock: the user lookup throws
  // Expected status code: 500
  // Expected behavior: no session is created and no internal detail is leaked
  // Expected output: { error: "Something went wrong. Please try again." }
  test('Reports a user store failure', async () => {
    const { app, stores } = await registered();
    stores.users.findByUsername = () => Promise.reject(new Error('connection refused at 10.0.0.5'));
    const res = await request(app).post('/api/auth/login').send(account);
    expect(res.status).toBe(500);
    expect(JSON.stringify(res.body)).not.toContain('10.0.0.5');
    expect(stores.sessionMap.size).toBe(1);
  });
});

// Interface POST /api/auth/logout
describe('Mocked: POST /api/auth/logout', () => {
  // Input: a valid bearer token while another session exists
  // Expected status code: 204
  // Expected behavior: only the current session ends
  // Expected output: empty body; the other token still works
  test('Ends the current session only', async () => {
    const { app, token } = await registered();
    const other = (await request(app).post('/api/auth/login').send(account)).body.token as string;
    expect((await request(app).post('/api/auth/logout').set('Authorization', `Bearer ${token}`)).status).toBe(204);
    expect((await request(app).get('/api/users/me').set('Authorization', `Bearer ${token}`)).status).toBe(401);
    expect((await request(app).get('/api/users/me').set('Authorization', `Bearer ${other}`)).status).toBe(200);
  });

  // Input: no header, an empty Bearer, an unknown token, or Basic authentication
  // Expected status code: 401
  // Expected behavior: nothing is deleted
  // Expected output: Bearer challenge
  test.each([undefined, 'Bearer', 'Bearer unknown-token-unknown-token', 'Basic abc'])('Requires a session (%s)', async header => {
    const { app } = await registered();
    const req = request(app).post('/api/auth/logout');
    const res = await (header ? req.set('Authorization', header) : req);
    expect(res.status).toBe(401);
    expect(res.headers['www-authenticate']).toBe('Bearer');
  });

  // Input: valid token; mock: session deletion throws
  // Expected status code: 500
  // Expected behavior: logout is not reported as successful
  // Expected output: { error: "Something went wrong. Please try again." }
  test('Reports a session store failure', async () => {
    const { app, token, stores } = await registered();
    stores.sessions.delete = unavailable;
    expect((await request(app).post('/api/auth/logout').auth(token, { type: 'bearer' })).status).toBe(500);
    expect(stores.sessionMap.size).toBe(1);
  });
});

// Interface GET /api/users/me
describe('Mocked: GET /api/users/me', () => {
  // Input: an existing session whose store predates per-account session versions.
  // Expected status code: 200 on the original account version and 401 after rotation.
  // Expected behavior: treat an absent version as zero while honoring later revocation.
  // Expected output: the original user followed by a Bearer challenge.
  test('honors rotation for legacy sessions', async () => {
    const { stores, app, token } = await registered();
    delete required(stores.sessionMap.get(hashToken(token))).sessionVersion;
    const me = (): request.Test => request(app).get('/api/users/me').auth(token, { type: 'bearer' });
    expect((await me()).status).toBe(200);
    required(stores.userList[0]).sessionVersion = 1;
    expect((await me()).status).toBe(401);
  });

  // Input: the session provider returns a user identifier incompatible with MongoDB.
  // Expected status code: 401.
  // Expected behavior: invalidate the session and withhold account details.
  // Expected output: a Bearer challenge and deletion of the stale token.
  test('invalidates a session with a malformed account identifier', async () => {
    const stores = memoryStores();
    const app = createApp({ users: mongoUserStore, sessions: stores.sessions });
    const token = 'malformed-account-id-session';
    jest.spyOn(stores.sessions, 'find').mockResolvedValue({ userId: 'invalid-id', expiresAt: new Date(Date.now() + 60000) });
    const remove = jest.spyOn(stores.sessions, 'delete');
    const response = await request(app).get('/api/users/me').auth(token, { type: 'bearer' });
    expect(response.status).toBe(401);
    expect(remove).toHaveBeenCalledWith(hashToken(token));
  });


  // Input: the account disappears after the session lookup completes.
  // Expected status code: 401.
  // Expected behavior: invalidate the orphan session.
  // Expected output: a Bearer challenge and no user data.
  test('revokes a session when the user lookup races with deletion', async () => {
    const { app, stores, token } = await registered();
    jest.spyOn(stores.sessions, 'find').mockResolvedValueOnce(required(stores.sessionMap.get(hashToken(token))));
    jest.spyOn(stores.users, 'findById').mockResolvedValueOnce(null);
    const response = await request(app).get('/api/users/me').auth(token, { type: 'bearer' });
    expect(response.status).toBe(401);
    expect(response.headers['www-authenticate']).toBe('Bearer');
    expect(stores.sessionMap.size).toBe(0);
  });

  // Input: valid bearer token
  // Expected status code: 200
  // Expected behavior: the account is read without its password hash
  // Expected output: { user } with username "sam_lee"
  test('Returns the signed-in account', async () => {
    const { app, token } = await registered();
    const res = await request(app).get('/api/users/me').set('Authorization', `Bearer ${token}`);
    expect(res.status).toBe(200);
    expect(res.body.user).toMatchObject({ email: '', username: 'sam_lee', displayName: 'sam_lee', signInMethods: ['password'] });
    expect(JSON.stringify(res.body)).not.toContain('scrypt');
  });

  // Input: token whose session expiry has passed
  // Expected status code: 401
  // Expected behavior: the expired session is refused
  // Expected output: Bearer challenge
  test('Rejects an expired session', async () => {
    const { app, token, stores } = await registered();
    required(stores.sessionMap.get(hashToken(token))).expiresAt = new Date(Date.now() - 1);
    expect((await request(app).get('/api/users/me').set('Authorization', `Bearer ${token}`)).status).toBe(401);
  });

  // Input: token whose account was deleted
  // Expected status code: 401
  // Expected behavior: the orphaned session is removed
  // Expected output: Bearer challenge
  test('Rejects a session for a missing account', async () => {
    const { app, token, stores } = await registered();
    stores.userList.length = 0;
    expect((await request(app).get('/api/users/me').set('Authorization', `Bearer ${token}`)).status).toBe(401);
    expect(stores.sessionMap.size).toBe(0);
  });

  // Input: valid token; mock: the session lookup throws
  // Expected status code: 500
  // Expected behavior: account data is not returned
  // Expected output: { error: "Something went wrong. Please try again." }
  test('Reports a session store failure', async () => {
    const { app, token, stores } = await registered();
    stores.sessions.find = unavailable;
    const res = await request(app).get('/api/users/me').auth(token, { type: 'bearer' });
    expect(res.status).toBe(500);
    expect(res.body).not.toHaveProperty('user');
  });
});
