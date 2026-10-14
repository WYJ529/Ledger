import request from 'supertest';
import type { TokenPayload } from 'google-auth-library';
import { createApp } from '../../src/app';
import type { TokenVerifier } from '../../src/googleAuth';

const originalClientId = process.env.GOOGLE_CLIENT_ID;
const audience = 'test-web-client.apps.googleusercontent.com';
const payload: TokenPayload = {
  iss: 'https://accounts.google.com', aud: audience, sub: 'google-user-id',
  iat: Math.floor(Date.now() / 1000), exp: Math.floor(Date.now() / 1000) + 3600,
  given_name: 'Joy', family_name: 'Tao', name: 'Joy Tao',
};

// Interface POST /api/auth/google. Mock: official Google token verification boundary.
describe('Mocked: POST /api/auth/google', () => {
  beforeEach(() => { process.env.GOOGLE_CLIENT_ID = audience; });
  afterAll(() => {
    if (originalClientId === undefined) delete process.env.GOOGLE_CLIENT_ID;
    else process.env.GOOGLE_CLIENT_ID = originalClientId;
  });
  // Input: token verified by Google library. Expected: 200, identity from verified claims only.
  test('Uses Web client audience and only verified profile', async () => {
    const verify = jest.fn<ReturnType<TokenVerifier>, Parameters<TokenVerifier>>().mockResolvedValue(payload);
    const res = await request(createApp(verify)).post('/api/auth/google').send({ idToken: 'credential', firstName: 'Injected' });
    expect(verify).toHaveBeenCalledWith('credential', audience);
    expect(res.status).toBe(200);
    expect(res.body.user).toEqual({ id: 'google-user-id', firstName: 'Joy', lastName: 'Tao', displayName: 'Joy Tao' });
    expect(res.body.expiresAt).toBe(payload.exp * 1000);
    expect(res.headers['cache-control']).toBe('no-store');
    expect(JSON.stringify(res.body)).not.toContain('credential');
  });
  // Mock: signature/audience/issuer verification rejected. Expected: 401; no leaked token/errors.
  test('Rejects failed verification', async () => {
    const res = await request(createApp(async () => { throw new Error('sensitive details'); }))
      .post('/api/auth/google').send({ idToken: 'bad-token' });
    expect(res.status).toBe(401);
    expect(JSON.stringify(res.body)).not.toContain('sensitive');
  });
  // Input: missing or invalid ID token. Expected: 400 before calling Google.
  test.each([{}, { idToken: 123 }, { idToken: '' }, { idToken: 'a'.repeat(8193) }])('Rejects malformed input', async body => {
    const verify = jest.fn<ReturnType<TokenVerifier>, Parameters<TokenVerifier>>();
    expect((await request(createApp(verify)).post('/api/auth/google').send(body)).status).toBe(400);
    expect(verify).not.toHaveBeenCalled();
  });
  // Input: missing Web client configuration. Expected: 503, no verification attempt.
  test('Reports missing OAuth configuration', async () => {
    delete process.env.GOOGLE_CLIENT_ID;
    const verify = jest.fn<ReturnType<TokenVerifier>, Parameters<TokenVerifier>>();
    expect((await request(createApp(verify)).post('/api/auth/google').send({ idToken: 'x' })).status).toBe(503);
    expect(verify).not.toHaveBeenCalled();
  });
  // Mock: expired claims. Expected: 401, no signed-in user.
  test('Rejects expired credentials', async () => {
    const res = await request(createApp(async () => ({ ...payload, exp: 1 })))
      .post('/api/auth/google').send({ idToken: 'expired' });
    expect(res.status).toBe(401);
  });
  // Input: invalid JSON. Expected: 400 with a JSON error response.
  test('Reports malformed JSON', async () => {
    const res = await request(createApp()).post('/api/auth/google').set('Content-Type', 'application/json').send('{');
    expect(res.status).toBe(400);
    expect(res.body.error).toBe('Invalid JSON body.');
  });
});
