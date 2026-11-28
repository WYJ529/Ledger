import request from 'supertest';
import { SessionModel, UserModel } from '../../src/userStore';
import { registerAccount } from '../helpers/registration';
import { useAccountDatabase } from '../helpers/accountDatabase';

// The real Google verifier and MongoDB stores. No mocked components: a forged token must fail verification.
const db = useAccountDatabase('google-auth-test');
const originalClientId = process.env.GOOGLE_CLIENT_ID;
const credentials = { username: 'joy_tao', password: 'long password', confirmPassword: 'long password' };
beforeEach(() => { process.env.GOOGLE_CLIENT_ID = 'test-web-client.apps.googleusercontent.com'; });
afterAll(() => {
  if (originalClientId === undefined) delete process.env.GOOGLE_CLIENT_ID; else process.env.GOOGLE_CLIENT_ID = originalClientId;
});

const withForgedToken = (path: string, extra: object = {}): request.Test =>
  request(db.app).post(path).send({ idToken: 'forged.token.value', ...extra });
const storedCounts = async () => [await UserModel.countDocuments(), await SessionModel.countDocuments()];

// Interface POST /api/auth/google
describe('Unmocked: POST /api/auth/google', () => {
  // Input: a token that is not a Google-signed JWT
  // Expected status code: 401
  // Expected behavior: the real verifier rejects it; no account or session is created
  // Expected output: error message
  test('Rejects a forged ID token', async () => {
    expect((await withForgedToken('/api/auth/google')).status).toBe(401);
    expect(await storedCounts()).toEqual([0, 0]);
  });

  // Input: valid-looking token while GOOGLE_CLIENT_ID is not configured
  // Expected status code: 503
  // Expected behavior: Google sign-in reports that it is unavailable without calling Google
  // Expected output: error message
  test('Reports missing OAuth configuration', async () => {
    delete process.env.GOOGLE_CLIENT_ID;
    const res = await withForgedToken('/api/auth/google');
    expect(res.status).toBe(503);
    expect(typeof res.body.error).toBe('string');
  });
});

// Interface POST /api/auth/google/register
describe('Unmocked: POST /api/auth/google/register', () => {
  // Input: forged ID token with a valid username, password and confirmation
  // Expected status code: 401
  // Expected behavior: no account is created without a verified Google identity
  // Expected output: error message
  test('Rejects a forged ID token without creating an account', async () => {
    const res = await withForgedToken('/api/auth/google/register', credentials);
    expect(res.status).toBe(401);
    expect(await storedCounts()).toEqual([0, 0]);
  });
});

// Interface POST /api/auth/google/link
describe('Unmocked: POST /api/auth/google/link', () => {
  // Input: forged ID token with the correct username and password of an existing account
  // Expected status code: 401
  // Expected behavior: the stored account keeps no Google identity; no new session is created
  // Expected output: error message
  test('Rejects a forged ID token without linking', async () => {
    await registerAccount(db.app, credentials);
    expect((await withForgedToken('/api/auth/google/link', credentials)).status).toBe(401);
    expect((await UserModel.findOne({ username: 'joy_tao' }).lean())?.googleId).toBeUndefined();
    expect(await storedCounts()).toEqual([1, 1]);
  });
});
