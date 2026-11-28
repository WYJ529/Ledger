import { required, requiredString } from '../helpers/required';
import request from 'supertest';
import { LoginTicket, OAuth2Client, type TokenPayload } from 'google-auth-library';
import { DuplicateAccountError, UserModel, SessionModel, mongoUserStore } from '../../src/userStore';
import { useIsolatedMongo } from '../helpers/isolatedMongo';
import { createApp } from '../../src/app';
import type { TokenVerifier } from '../../src/googleAuth';
import { hashPassword, verifyPassword } from '../../src/password';
import { registerAccount } from '../helpers/registration';
import { memoryStores } from '../helpers/memoryStores';
import { passwordConfirmationCases } from '../helpers/passwordConfirmation';

// Mocked components: Google ID-token verification and the MongoDB user/session stores (in-memory).
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
const rejectingVerifier = () => Promise.reject(new Error('forged'));
const credentials = { username: 'joy_tao', password: 'long password', confirmPassword: 'long password' };
const googleBody = { idToken: 'credential', ...credentials };

beforeEach(() => { process.env.GOOGLE_CLIENT_ID = audience; });
afterAll(() => {
  if (originalClientId === undefined) delete process.env.GOOGLE_CLIENT_ID;
  else process.env.GOOGLE_CLIENT_ID = originalClientId;
});

// Interface POST /api/auth/google
describe('Mocked: POST /api/auth/google', () => {

  // Input: the OAuth client verifies a ticket with partial or absent profile fields.
  // Expected status code: 200.
  // Expected behavior: use available name fields and tolerate a missing email.
  // Expected output: a registration profile and the audience used for verification.
  test.each([['Joy', 'Joy Tao'], [undefined, '']])('reads the verified ticket profile %#', async (given, displayName) => {
    const { iss, aud, sub, iat, exp } = payload;
    const identity = { iss, aud, sub, iat, exp };
    const ticket = new LoginTicket(undefined, { ...identity, ...(given ? { given_name: given, family_name: 'Tao' } : {}) });
    const verify = jest.spyOn(OAuth2Client.prototype, 'verifyIdToken').mockResolvedValue(ticket as never);
    try {
      const response = await request(createApp(memoryStores())).post('/api/auth/google').send({ idToken: 'credential' });
      expect(response.status).toBe(200);
      expect(response.body.profile).toEqual({ displayName, email: '' });
      expect(verify).toHaveBeenCalledWith({ idToken: 'credential', audience });
    } finally { verify.mockRestore(); }
  });

  // Input: verified token for a new Google user that also sends a displayName
  // Expected status code: 200
  // Expected behavior: no account or session is created yet; the client-supplied name is ignored
  // Expected output: { status: "registration-required", profile: { email, displayName: "Joy Tao" } }
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

  // Input: verified token for an existing Google account
  // Expected status code: 200
  // Expected behavior: a session is stored by token hash
  // Expected output: { status: "signed-in", token, expiresAt, user }
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

  // Input: verified token whose email belongs to a password account
  // Expected status code: 200
  // Expected behavior: accounts are never linked automatically
  // Expected output: { status: "registration-required" }
  test('Refuses to take over a password account', async () => {
    const stores = memoryStores();
    await stores.users.create({ email: 'joy.tao@example.com', displayName: 'Joy', passwordHash: 'hash' });
    const res = await request(createApp({ verifyToken: verifier(), ...stores })).post('/api/auth/google').send({ idToken: 'credential' });
    expect(res.status).toBe(200);
    expect(res.body.status).toBe('registration-required');
    expect(stores.userList[0]?.googleId).toBeUndefined();
    expect(stores.sessionMap.size).toBe(0);
  });

  // Input: verified token without email ownership; mock: email_verified false
  // Expected status code: 200
  // Expected behavior: identity uses the verified subject, not the email
  // Expected output: { status: "registration-required" }
  test('Does not require email validation for Google identity', async () => {
    const res = await request(createApp({ verifyToken: verifier({ ...payload, email_verified: false }), ...memoryStores() }))
      .post('/api/auth/google').send({ idToken: 'credential' });
    expect(res.status).toBe(200);
    expect(res.body.status).toBe('registration-required');
  });

  // Input: token; mock: verification throws an error with sensitive details
  // Expected status code: 401
  // Expected behavior: verifier details are not leaked
  // Expected output: error message without "sensitive"
  test('Rejects failed verification', async () => {
    const res = await request(createApp({ verifyToken: () => Promise.reject(new Error('sensitive details')), ...memoryStores() }))
      .post('/api/auth/google').send({ idToken: 'bad-token' });
    expect(res.status).toBe(401);
    expect(JSON.stringify(res.body)).not.toContain('sensitive');
  });

  // Input: missing, numeric, empty or oversized ID token
  // Expected status code: 400
  // Expected behavior: Google is never called
  // Expected output: error message
  test.each([{}, { idToken: 123 }, { idToken: '' }, { idToken: 'a'.repeat(8193) }])('Rejects malformed input %#', async body => {
    const verify = verifier();
    expect((await request(createApp({ verifyToken: verify, ...memoryStores() })).post('/api/auth/google').send(body)).status).toBe(400);
    expect(verify).not.toHaveBeenCalled();
  });

  // Input: token while GOOGLE_CLIENT_ID is unset
  // Expected status code: 503
  // Expected behavior: verification is not attempted
  // Expected output: error message
  test('Reports missing OAuth configuration', async () => {
    delete process.env.GOOGLE_CLIENT_ID;
    const verify = verifier();
    expect((await request(createApp({ verifyToken: verify, ...memoryStores() })).post('/api/auth/google').send({ idToken: 'x' })).status).toBe(503);
    expect(verify).not.toHaveBeenCalled();
  });

  // Input: token; mock: the verifier returns expired claims
  // Expected status code: 401
  // Expected behavior: no user is signed in
  // Expected output: error message
  test('Rejects expired credentials', async () => {
    const res = await request(createApp({ verifyToken: verifier({ ...payload, exp: 1 }), ...memoryStores() }))
      .post('/api/auth/google').send({ idToken: 'expired' });
    expect(res.status).toBe(401);
  });

  // Input: malformed JSON body
  // Expected status code: 400
  // Expected behavior: the parser error is returned as JSON
  // Expected output: { error: "Invalid JSON body." }
  test('Reports malformed JSON', async () => {
    const res = await request(createApp(memoryStores())).post('/api/auth/google').set('Content-Type', 'application/json').send('{');
    expect(res.status).toBe(400);
    expect(res.body.error).toBe('Invalid JSON body.');
  });

  // Input: verified token; mock: the user store throws with an internal address
  // Expected status code: 500
  // Expected behavior: internal details are not leaked
  // Expected output: error message without "10.0.0.5"
  test('Reports database failures', async () => {
    const stores = memoryStores();
    stores.users.findByGoogleId = () => Promise.reject(new Error('connection refused at 10.0.0.5'));
    const res = await request(createApp({ verifyToken: verifier(), ...stores })).post('/api/auth/google').send({ idToken: 'credential' });
    expect(res.status).toBe(500);
    expect(JSON.stringify(res.body)).not.toContain('10.0.0.5');
  });

  // Input: 20 rounds of empty requests to each Google endpoint, then one more each, then a password login
  // Expected status code: 400 during the rounds, then 429; password login still 400
  // Expected behavior: Google endpoints share one cap that does not consume the password-login bucket
  // Expected output: rate-limit error
  test('Google endpoints share one rate limit', async () => {
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
});

// Interface POST /api/auth/google/register
describe('Mocked: POST /api/auth/google/register', () => {
  // Input: verified token with password/confirmation pairs that differ in type, length, bytes or Unicode form
  // Expected status code: 400 for every mismatch, 201 for exact matches
  // Expected behavior: nothing is stored on mismatch
  // Expected output: { error: "Passwords do not match." } on mismatch
  test.each(passwordConfirmationCases)('Password confirmation: %s', async (_label, password, confirmPassword, status) => {
    const stores = memoryStores();
    const res = await request(createApp({ verifyToken: verifier(), ...stores })).post('/api/auth/google/register')
      .send({ ...googleBody, password, confirmPassword });
    expect(res.status).toBe(status);
    if (status === 400) expect(res.body.error).toBe('Passwords do not match.');
    expect(stores.userList).toHaveLength(status === 400 ? 0 : 1);
    expect(stores.sessionMap.size).toBe(status === 400 ? 0 : 1);
  });

  // Input: verified token without username/password, with a blank username, short password, or bad confirmation
  // Expected status code: 400
  // Expected behavior: no account or session is created
  // Expected output: error message
  test('Requires a username, password and matching confirmation', async () => {
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

  // Input: verified token with valid credentials, then a password login, a Google sign-in and a repeated registration
  // Expected status code: 201, 200, 200, then 200
  // Expected behavior: one account gets both sign-in methods; repeating does not create another
  // Expected output: { user } with signInMethods ["password", "google"]
  test('Creates an account with both sign-in methods', async () => {
    const stores = memoryStores();
    const app = createApp({ verifyToken: verifier(), ...stores });
    const created = await request(app).post('/api/auth/google/register').send(googleBody);
    expect(created.status).toBe(201);
    expect(created.body.user).toMatchObject({ username: 'joy_tao', email: '', signInMethods: ['password', 'google'] });
    expect(await verifyPassword(credentials.password, required(required(stores.userList[0]).passwordHash))).toBe(true);
    expect(JSON.stringify(created.body)).not.toContain('passwordHash');
    expect((await request(app).post('/api/auth/login').send(credentials)).status).toBe(200);
    const google = await request(app).post('/api/auth/google').send({ idToken: 'credential' });
    expect(google.body.status).toBe('signed-in');
    expect(google.body.user.id).toBe(created.body.user.id);
    expect((await request(app).post('/api/auth/google/register').send(googleBody)).status).toBe(200);
    expect(stores.userList).toHaveLength(1);
  });

  // Input: verified token with a username that a password account already uses
  // Expected status code: 409
  // Expected behavior: the existing account is not modified
  // Expected output: error message
  test('Does not replace a registered username', async () => {
    const stores = memoryStores();
    const app = createApp({ verifyToken: verifier(), ...stores });
    await registerAccount(app, credentials);
    expect((await request(app).post('/api/auth/google/register').send(googleBody)).status).toBe(409);
    expect(stores.userList[0]?.googleId).toBeUndefined();
  });

  // Input: valid registration; mock: another account with the same identity appears first
  // Expected status code: 409
  // Expected behavior: no session is created
  // Expected output: error message
  test('Reports a unique-index race without creating a session', async () => {
    const stores = memoryStores();
    const create = stores.users.create.bind(stores.users);
    stores.users.create = async input => { await create({ ...input, googleId: 'other' }); return create(input); };
    expect((await request(createApp({ verifyToken: verifier(), ...stores })).post('/api/auth/google/register').send(googleBody)).status).toBe(409);
    expect(stores.sessionMap.size).toBe(0);
  });

  // Input: valid credentials; mock: token verification fails
  // Expected status code: 401
  // Expected behavior: no account is created
  // Expected output: error message
  test('Rejects an invalid Google token', async () => {
    const stores = memoryStores();
    const res = await request(createApp({ verifyToken: rejectingVerifier, ...stores })).post('/api/auth/google/register').send(googleBody);
    expect(res.status).toBe(401);
    expect(stores.userList).toHaveLength(0);
  });
});

// Interface POST /api/auth/google/link
describe('Mocked: POST /api/auth/google/link', () => {

  describe('MongoDB link writes with a verified identity', () => {
    useIsolatedMongo('google-link-contract', [UserModel, SessionModel]);
    afterEach(() => jest.restoreAllMocks());

    // Input: verified credentials while the database link write succeeds, conflicts, or fails.
    // Expected status code: 200, 409 or 500.
    // Expected behavior: only persist a link and issue a session for a successful write.
    // Expected output: signed-in account or a diagnostic with unchanged account links.
    test.each(['linked', 'duplicate', 'unavailable', 'invalid account ID'])('handles a %s result', async outcome => {
      const app = createApp({ verifyToken: verifier() });
      const registration = await registerAccount(app, credentials);
      expect(registration.status).toBe(201);
      if (outcome === 'invalid account ID') {
        const user = await mongoUserStore.findById(requiredString(registration.body.user.id));
        jest.spyOn(mongoUserStore, 'findByUsername').mockResolvedValueOnce({ ...required(user), id: 'invalid' });
      } else if (outcome !== 'linked') {
        const update = UserModel.findOneAndUpdate();
        jest.spyOn(update, 'exec').mockRejectedValueOnce(Object.assign(new Error('write failed'), { code: outcome === 'duplicate' ? 11000 : 42 }));
        jest.spyOn(UserModel, 'findOneAndUpdate').mockReturnValueOnce(update);
      }
      const response = await request(app).post('/api/auth/google/link').send(googleBody);
      expect(response.status).toBe(outcome === 'linked' ? 200 : outcome === 'unavailable' ? 500 : 409);
      const account = await UserModel.findOne({ username: 'joy_tao' }).lean();
      expect(account?._id.toString()).toBe(registration.body.user.id);
      expect(account?.googleId).toBe(outcome === 'linked' ? payload.sub : undefined);
      expect(await SessionModel.countDocuments()).toBe(outcome === 'linked' ? 2 : 1);
    });
  });


  // Input: verified credentials while the conditional Google link write fails.
  // Expected status code: 409 for duplicate identity or 500 for a storage error.
  // Expected behavior: withhold the session after a failed write.
  // Expected output: error and unchanged sign-in methods.
  test.each([[new DuplicateAccountError(), 409], [new Error('storage failure'), 500]] as const)(
    'reports a failed link write %#', async (error, status) => {
      const stores = memoryStores();
      const app = createApp({ ...stores, verifyToken: verifier() });
      await registerAccount(app, credentials);
      jest.spyOn(stores.users, 'linkGoogle').mockRejectedValueOnce(error);
      const response = await request(app).post('/api/auth/google/link').send(googleBody);
      expect(response.status).toBe(status);
      expect(response.body).not.toHaveProperty('token');
      expect(stores.userList[0]?.googleId).toBeUndefined();
    });

  // Input: verified token with the correct username (any case) and password of an existing account
  // Expected status code: 200; a repeat is also 200
  // Expected behavior: only the Google ID is added; the original user and profile are preserved
  // Expected output: { user } with the original ID and signInMethods ["password", "google"]
  test('Links after password verification and preserves the account', async () => {
    const stores = memoryStores();
    const verify = verifier();
    const app = createApp({ verifyToken: verify, ...stores });
    const original = await registerAccount(app, credentials);
    await stores.users.update(requiredString(original.body.user.id), { displayName: 'Existing profile', phoneNumber: '+1 604 555 0123' });
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

  // Input: verified token with a wrong password, and with an unknown username
  // Expected status code: 401
  // Expected behavior: nothing is linked and no session is created
  // Expected output: { error: "Incorrect username or password." }
  test('Rejects unknown users and wrong passwords', async () => {
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

  // Input: correct credentials; mock: token verification fails
  // Expected status code: 401
  // Expected behavior: nothing is linked
  // Expected output: error message
  test('Rejects an invalid Google token', async () => {
    const stores = memoryStores();
    const app = createApp({ verifyToken: rejectingVerifier, ...stores });
    await registerAccount(app, credentials);
    expect((await request(app).post('/api/auth/google/link').send(googleBody)).status).toBe(401);
    expect(stores.userList[0]?.googleId).toBeUndefined();
  });

  // Input: link when the account already has another Google ID, or the Google ID belongs to another account
  // Expected status code: 409
  // Expected behavior: an existing link is never replaced on either side
  // Expected output: error message; stored users unchanged
  test('Never replaces an existing link', async () => {
    for (const existingGoogle of ['another-google', payload.sub]) {
      const stores = memoryStores();
      const app = createApp({ verifyToken: verifier(), ...stores });
      await registerAccount(app, credentials);
      if (existingGoogle === payload.sub) await stores.users.create({ username: 'other', displayName: 'Other', googleId: existingGoogle });
      else required(stores.userList[0]).googleId = existingGoogle;
      const before = structuredClone(stores.userList);
      expect((await request(app).post('/api/auth/google/link').send(googleBody)).status).toBe(409);
      expect(stores.userList).toEqual(before);
    }
  });

  // Input: correct credentials; mock: the password changes while the link is in progress
  // Expected status code: 409
  // Expected behavior: the conditional link refuses the stale password; no session is created
  // Expected output: error message
  test('Rejects a password changed during the link', async () => {
    const stores = memoryStores();
    const app = createApp({ verifyToken: verifier(), ...stores });
    await registerAccount(app, credentials);
    const link = stores.users.linkGoogle.bind(stores.users);
    stores.users.linkGoogle = async (id, googleId, passwordHash) => {
      required(stores.userList[0]).passwordHash = await hashPassword('replacement password');
      return link(id, googleId, passwordHash);
    };
    expect((await request(app).post('/api/auth/google/link').send(googleBody)).status).toBe(409);
    expect(stores.userList[0]?.googleId).toBeUndefined();
    expect(stores.sessionMap.size).toBe(1);
  });
});
