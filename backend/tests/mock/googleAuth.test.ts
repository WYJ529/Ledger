import request from 'supertest';
import type { TokenPayload } from 'google-auth-library';
import { createApp } from '../../src/app';
import type { TokenVerifier } from '../../src/googleAuth';
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
  // Input: Google email already used by a password account. Expected: 409; accounts are not linked automatically.
  test('Refuses to take over an email/password account', async () => {
    const stores = memoryStores();
    await stores.users.create({ email: 'joy.tao@example.com', displayName: 'Joy', passwordHash: 'hash' });
    const res = await request(createApp({ verifyToken: verifier(), ...stores })).post('/api/auth/google').send({ idToken: 'credential' });
    expect(res.status).toBe(409);
    expect(stores.sessionMap.size).toBe(0);
  });
  // Mock: Google account without a verified email. Expected: 403.
  test('Requires a verified Google email', async () => {
    const res = await request(createApp({ verifyToken: verifier({ ...payload, email_verified: false }), ...memoryStores() }))
      .post('/api/auth/google').send({ idToken: 'credential' });
    expect(res.status).toBe(403);
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

// Interface POST /api/auth/google/register. Mocks: Google token verification, User DB.
describe('Mocked: POST /api/auth/google/register', () => {
  // Input: verified token and chosen name. Expected: 201, Google account created and signed in.
  test('Creates a Google account with the chosen name', async () => {
    const stores = memoryStores();
    const res = await request(createApp({ verifyToken: verifier(), ...stores }))
      .post('/api/auth/google/register').send({ idToken: 'credential', displayName: '  Joy   T ' });
    expect(res.status).toBe(201);
    expect(res.body.status).toBe('signed-in');
    expect(res.body.user).toMatchObject({ email: 'joy.tao@example.com', displayName: 'Joy T', signInMethods: ['google'] });
    expect(stores.userList[0]).toMatchObject({ googleId: 'google-user-id' });
    expect(stores.userList[0]?.passwordHash).toBeUndefined();
  });
  // Input: no display name. Expected: 201 using the Google profile name.
  test('Defaults to the Google profile name', async () => {
    const res = await request(createApp({ verifyToken: verifier(), ...memoryStores() }))
      .post('/api/auth/google/register').send({ idToken: 'credential' });
    expect(res.status).toBe(201);
    expect(res.body.user.displayName).toBe('Joy Tao');
  });
  // Input: repeated registration (e.g. double tap). Expected: 200, signs in the same account.
  test('Signs in when the Google account already exists', async () => {
    const stores = memoryStores();
    const app = createApp({ verifyToken: verifier(), ...stores });
    await request(app).post('/api/auth/google/register').send({ idToken: 'credential' });
    const res = await request(app).post('/api/auth/google/register').send({ idToken: 'credential' });
    expect(res.status).toBe(200);
    expect(stores.userList).toHaveLength(1);
  });
  // Input: blank name. Expected: 400, nothing created.
  test('Rejects an invalid display name', async () => {
    const stores = memoryStores();
    const res = await request(createApp({ verifyToken: verifier(), ...stores }))
      .post('/api/auth/google/register').send({ idToken: 'credential', displayName: '   ' });
    expect(res.status).toBe(400);
    expect(stores.userList).toHaveLength(0);
  });
  // Input: email already registered with a password. Expected: 409, nothing created.
  test('Rejects an email that is already registered', async () => {
    const stores = memoryStores();
    await stores.users.create({ email: 'joy.tao@example.com', displayName: 'Joy', passwordHash: 'hash' });
    const res = await request(createApp({ verifyToken: verifier(), ...stores }))
      .post('/api/auth/google/register').send({ idToken: 'credential' });
    expect(res.status).toBe(409);
    expect(stores.userList).toHaveLength(1);
  });
  // Mock: concurrent sign-up wins the unique index race. Expected: 409.
  test('Reports a duplicate created concurrently', async () => {
    const stores = memoryStores();
    const create = stores.users.create;
    stores.users.create = async input => {
      await create({ ...input, googleId: 'other' });
      return create(input);
    };
    const res = await request(createApp({ verifyToken: verifier(), ...stores }))
      .post('/api/auth/google/register').send({ idToken: 'credential' });
    expect(res.status).toBe(409);
  });
});
