import request from 'supertest';
import type { TokenPayload } from 'google-auth-library';
import { createApp } from '../../src/app';
import type { TokenVerifier } from '../../src/googleAuth';
import { hashPassword, verifyPassword } from '../../src/password';
import { registerAccount } from '../helpers/registration';
import { memoryStores } from '../helpers/memoryStores';

const originalClientId = process.env.GOOGLE_CLIENT_ID;
const audience = 'test-web-client.apps.googleusercontent.com';
const payload: TokenPayload = {
  iss: 'https://accounts.google.com', aud: audience, sub: 'google-user-id',
  iat: Math.floor(Date.now() / 1000), exp: Math.floor(Date.now() / 1000) + 3600,
  email: 'Joy.Tao@Example.com', email_verified: true,
  given_name: 'Joy', family_name: 'Tao', name: 'Joy Tao',
};
const verifier = (result: TokenPayload = payload) =>
  jest.fn<ReturnType<TokenVerifier>, Parameters<TokenVerifier>>().mockResolvedValue(result);

beforeEach(() => { process.env.GOOGLE_CLIENT_ID = audience; });
afterAll(() => {
  if (originalClientId === undefined) delete process.env.GOOGLE_CLIENT_ID;
  else process.env.GOOGLE_CLIENT_ID = originalClientId;
});

// Interface POST /api/auth/google. Mocks: Google token verification, User DB.
test('Google endpoints share one cap without consuming the password-login bucket', async () => {
  const verify = verifier();
  const app = createApp({ verifyToken: verify, ...memoryStores() });
  const endpoints = ['/api/auth/google', '/api/auth/google/register', '/api/auth/google/link'];
  for (let round = 0; round < 20; round++) {
    for (const endpoint of endpoints) expect((await request(app).post(endpoint).send({})).status).toBe(400);
  }
  for (const endpoint of endpoints) expect((await request(app).post(endpoint).send({})).status).toBe(429);
  expect((await request(app).post('/api/auth/login').send({})).status).toBe(400);
  expect(verify).not.toHaveBeenCalled();
});

describe('Mocked: POST /api/auth/google', () => {
  // Input: verified token for a new Google user. Expected: 200, registration prompt, no account or session yet.
  test('Asks a new Google user to create an account', async () => {
    const stores = memoryStores();
    const verify = verifier();
    const res = await request(createApp({ verifyToken: verify, ...stores }))
      .post('/api/auth/google').send({ idToken: 'credential', displayName: 'Injected' });
    expect(verify).toHaveBeenCalledWith('credential', audience);
    expect(res.status).toBe(200);
    expect(res.body).toEqual({ status: 'registration-required', profile: { email: 'joy.tao@example.com', displayName: 'Joy Tao' } });
    expect(res.headers['cache-control']).toBe('no-store');
    expect(stores.userList).toHaveLength(0);
    expect(stores.sessionMap.size).toBe(0);
  });
  // Input: verified token for an existing Google account. Expected: 200, session token and stored account info.
  test('Signs in an existing Google account', async () => {
    const stores = memoryStores();
    await stores.users.create({ email: 'joy.tao@example.com', displayName: 'Joy', googleId: 'google-user-id' });
    const res = await request(createApp({ verifyToken: verifier(), ...stores })).post('/api/auth/google').send({ idToken: 'credential' });
    expect(res.status).toBe(200);
    expect(res.body.status).toBe('signed-in');
    expect(res.body.token).toEqual(expect.any(String));
    expect(res.body.expiresAt).toBeGreaterThan(Date.now());
    expect(res.body.user).toMatchObject({ id: 'user-1', email: 'joy.tao@example.com', displayName: 'Joy', signInMethods: ['google'] });
    expect(stores.sessionMap.size).toBe(1);
    expect([...stores.sessionMap.keys()]).not.toContain(res.body.token);
    expect(JSON.stringify(res.body)).not.toContain('credential');
  });
  // Input: Google email already used by a password account. Expected: registration prompt; accounts are not linked automatically.
  test('Refuses to take over an email/password account', async () => {
    const stores = memoryStores();
    await stores.users.create({ email: 'joy.tao@example.com', displayName: 'Joy', passwordHash: 'hash' });
    const res = await request(createApp({ verifyToken: verifier(), ...stores })).post('/api/auth/google').send({ idToken: 'credential' });
    expect(res.status).toBe(200);
    expect(res.body.status).toBe('registration-required');
    expect(stores.userList[0]?.googleId).toBeUndefined();
    expect(stores.sessionMap.size).toBe(0);
  });
  // Mock: Google account without email ownership claims. Expected: identity uses the verified subject.
  test('Does not require email validation for Google identity', async () => {
    const res = await request(createApp({ verifyToken: verifier({ ...payload, email_verified: false }), ...memoryStores() }))
      .post('/api/auth/google').send({ idToken: 'credential' });
    expect(res.status).toBe(200);
    expect(res.body.status).toBe('registration-required');
  });
  // Mock: signature/audience/issuer verification rejected. Expected: 401; no leaked token/errors.
  test('Rejects failed verification', async () => {
    const res = await request(createApp({ verifyToken: async () => { throw new Error('sensitive details'); }, ...memoryStores() }))
      .post('/api/auth/google').send({ idToken: 'bad-token' });
    expect(res.status).toBe(401);
    expect(JSON.stringify(res.body)).not.toContain('sensitive');
  });
  // Input: missing or invalid ID token. Expected: 400 before calling Google.
  test.each([{}, { idToken: 123 }, { idToken: '' }, { idToken: 'a'.repeat(8193) }])('Rejects malformed input', async body => {
    const verify = verifier();
    expect((await request(createApp({ verifyToken: verify, ...memoryStores() })).post('/api/auth/google').send(body)).status).toBe(400);
    expect(verify).not.toHaveBeenCalled();
  });
  // Input: missing Web client configuration. Expected: 503, no verification attempt.
  test('Reports missing OAuth configuration', async () => {
    delete process.env.GOOGLE_CLIENT_ID;
    const verify = verifier();
    expect((await request(createApp({ verifyToken: verify, ...memoryStores() })).post('/api/auth/google').send({ idToken: 'x' })).status).toBe(503);
    expect(verify).not.toHaveBeenCalled();
  });
  // Mock: expired claims. Expected: 401, no signed-in user.
  test('Rejects expired credentials', async () => {
    const res = await request(createApp({ verifyToken: verifier({ ...payload, exp: 1 }), ...memoryStores() }))
      .post('/api/auth/google').send({ idToken: 'expired' });
    expect(res.status).toBe(401);
  });
  // Input: invalid JSON. Expected: 400 with a JSON error response.
  test('Reports malformed JSON', async () => {
    const res = await request(createApp(memoryStores())).post('/api/auth/google').set('Content-Type', 'application/json').send('{');
    expect(res.status).toBe(400);
    expect(res.body.error).toBe('Invalid JSON body.');
  });
  // Mock: User DB unavailable. Expected: 500 without internal details.
  test('Reports database failures', async () => {
    const stores = memoryStores();
    stores.users.findByGoogleId = async () => { throw new Error('connection refused at 10.0.0.5'); };
    const res = await request(createApp({ verifyToken: verifier(), ...stores })).post('/api/auth/google').send({ idToken: 'credential' });
    expect(res.status).toBe(500);
    expect(JSON.stringify(res.body)).not.toContain('10.0.0.5');
  });
});

const credentials = { username: 'joy_tao', password: 'long password', confirmPassword: 'long password' };
const googleBody = { idToken: 'credential', ...credentials };

describe('Google registration and explicit linking', () => {
  test('requires a username, password and matching confirmation', async () => {
    for (const body of [{ idToken: 'credential' }, { ...googleBody, username: '' },
      { ...googleBody, password: 'short' }, { ...googleBody, confirmPassword: 'different' },
      { ...googleBody, confirmPassword: undefined }]) {
      const stores = memoryStores();
      const res = await request(createApp({ verifyToken: verifier(), ...stores })).post('/api/auth/google/register').send(body);
      expect(res.status).toBe(400);
      expect(stores.userList).toHaveLength(0);
      expect(stores.sessionMap.size).toBe(0);
    }
  });

  test('creates an account with both sign-in methods, then signs in directly', async () => {
    const stores = memoryStores();
    const app = createApp({ verifyToken: verifier(), ...stores });
    const created = await request(app).post('/api/auth/google/register').send(googleBody);
    expect(created.status).toBe(201);
    expect(created.body.user).toMatchObject({ username: 'joy_tao', email: '', signInMethods: ['password', 'google'] });
    expect(await verifyPassword(credentials.password, stores.userList[0]!.passwordHash!)).toBe(true);
    expect(JSON.stringify(created.body)).not.toContain('passwordHash');
    const login = await request(app).post('/api/auth/login').send(credentials);
    expect(login.status).toBe(200);
    const google = await request(app).post('/api/auth/google').send({ idToken: 'credential' });
    expect(google.body.status).toBe('signed-in');
    expect(google.body.user.id).toBe(created.body.user.id);
    const repeat = await request(app).post('/api/auth/google/register').send(googleBody);
    expect(repeat.status).toBe(200);
    expect(stores.userList).toHaveLength(1);
  });

  test('does not replace a registered username during Google creation', async () => {
    const stores = memoryStores();
    const app = createApp({ verifyToken: verifier(), ...stores });
    await registerAccount(app, credentials);
    expect((await request(app).post('/api/auth/google/register').send(googleBody)).status).toBe(409);
    expect(stores.userList[0]?.googleId).toBeUndefined();
  });

  test('reports a unique-index race without creating a session', async () => {
    const stores = memoryStores();
    const create = stores.users.create;
    stores.users.create = async input => { await create({ ...input, googleId: 'other' }); return create(input); };
    expect((await request(createApp({ verifyToken: verifier(), ...stores })).post('/api/auth/google/register').send(googleBody)).status).toBe(409);
    expect(stores.sessionMap.size).toBe(0);
  });

  test('links only after password verification and preserves the original user and profile', async () => {
    const stores = memoryStores();
    const verify = verifier();
    const app = createApp({ verifyToken: verify, ...stores });
    const original = await registerAccount(app, credentials);
    await stores.users.update(original.body.user.id, { displayName: 'Existing profile', phoneNumber: '+1 604 555 0123' });
    const before = { ...stores.userList[0] };
    const linked = await request(app).post('/api/auth/google/link').send({ idToken: 'credential', username: ' JOY_TAO ', password: credentials.password });
    expect(linked.status).toBe(200);
    expect(verify).toHaveBeenCalledWith('credential', audience);
    expect(linked.body.user).toMatchObject({ id: original.body.user.id, displayName: 'Existing profile', phoneNumber: '+1 604 555 0123', signInMethods: ['password', 'google'] });
    expect(stores.userList[0]).toEqual({ ...before, googleId: payload.sub });
    expect(stores.userList).toHaveLength(1);
    expect((await request(app).post('/api/auth/google').send({ idToken: 'credential' })).body.user.id).toBe(original.body.user.id);
    expect((await request(app).post('/api/auth/google/link').send(googleBody)).status).toBe(200);
  });

  test('rejects unknown users and wrong passwords without linking', async () => {
    const stores = memoryStores();
    const app = createApp({ verifyToken: verifier(), ...stores });
    await registerAccount(app, credentials);
    for (const body of [{ ...googleBody, password: 'wrong' }, { ...googleBody, username: 'unknown' }]) {
      const res = await request(app).post('/api/auth/google/link').send(body);
      expect(res.status).toBe(401);
      expect(res.body.error).toBe('Incorrect username or password.');
    }
    expect(stores.userList[0]?.googleId).toBeUndefined();
    expect(stores.sessionMap.size).toBe(1);
  });

  test('rejects invalid Google tokens on both completion endpoints', async () => {
    const stores = memoryStores();
    const app = createApp({ verifyToken: async () => { throw new Error('forged'); }, ...stores });
    await registerAccount(app, credentials);
    for (const path of ['/api/auth/google/register', '/api/auth/google/link']) {
      expect((await request(app).post(path).send(googleBody)).status).toBe(401);
    }
    expect(stores.userList[0]?.googleId).toBeUndefined();
  });

  test('never replaces an existing link on either side', async () => {
    for (const existingGoogle of ['another-google', payload.sub]) {
      const stores = memoryStores();
      const app = createApp({ verifyToken: verifier(), ...stores });
      await registerAccount(app, credentials);
      if (existingGoogle === payload.sub) await stores.users.create({ username: 'other', displayName: 'Other', googleId: existingGoogle });
      else stores.userList[0]!.googleId = existingGoogle;
      const before = structuredClone(stores.userList);
      expect((await request(app).post('/api/auth/google/link').send(googleBody)).status).toBe(409);
      expect(stores.userList).toEqual(before);
    }
  });

  test('rejects a password changed while the link request was in progress', async () => {
    const stores = memoryStores();
    const app = createApp({ verifyToken: verifier(), ...stores });
    await registerAccount(app, credentials);
    const link = stores.users.linkGoogle;
    stores.users.linkGoogle = async (id, googleId, passwordHash) => {
      stores.userList[0]!.passwordHash = await hashPassword('replacement password');
      return link(id, googleId, passwordHash);
    };
    expect((await request(app).post('/api/auth/google/link').send(googleBody)).status).toBe(409);
    expect(stores.userList[0]?.googleId).toBeUndefined();
    expect(stores.sessionMap.size).toBe(1);
  });
});
