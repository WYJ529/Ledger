import { required } from '../helpers/required';
import request from 'supertest';
import { createApp } from '../../src/app';
import { memoryStores } from '../helpers/memoryStores';
import { startSession } from '../../src/session';
import { DuplicateAccountError, mongoUserStore, UserModel } from '../../src/userStore';
import { verifyPassword } from '../../src/password';
import { registerAccount } from '../helpers/registration';

// Mocked components: the MongoDB user/session stores (in-memory, with injected failures) and Google token verification.
const original = { username: 'profile', password: 'original password', displayName: 'Profile User' };
async function setup() {
  const stores = memoryStores();
  const app = createApp(stores);
  const created = await registerAccount(app, original);
  const auth = `Bearer ${created.body.token}`;
  return { stores, app, auth };
}
const patch = (app: ReturnType<typeof createApp>, auth: string, body: object) =>
  request(app).patch('/api/users/me').set('Authorization', auth).send(body);
const login = (app: ReturnType<typeof createApp>, body: object = original) => request(app).post('/api/auth/login').send(body);

// Interface PATCH /api/users/me
describe('Mocked: PATCH /api/users/me', () => {

  // Input: a valid profile update with an invalid stored ID or a failed MongoDB conditional write.
  // Expected status code: 401, 409 or 500.
  // Expected behavior: preserve the existing profile when its update fails.
  // Expected output: error without user data.
  test.each(['bad ID', 'duplicate', 'unavailable'])('handles a MongoDB update failure: %s', async outcome => {
    const { stores, auth } = await setup();
    const persisted = required(stores.userList[0]);
    const id = outcome === 'bad ID' ? persisted.id : '000000000000000000000001';
    const app = createApp({ sessions: stores.sessions, users: { ...mongoUserStore, findById: () => Promise.resolve({ ...persisted, id }) } });
    if (outcome !== 'bad ID') {
      const query = UserModel.findByIdAndUpdate();
      jest.spyOn(query, 'exec').mockRejectedValueOnce(Object.assign(new Error('write failed'), { code: outcome === 'duplicate' ? 11000 : 42 }));
      jest.spyOn(UserModel, 'findByIdAndUpdate').mockReturnValueOnce(query);
    }
    try {
      const response = await patch(app, auth, { displayName: 'Changed' });
      expect(response.status).toBe(outcome === 'bad ID' ? 401 : outcome === 'duplicate' ? 409 : 500);
      expect(response.body).not.toHaveProperty('user');
    } finally { jest.restoreAllMocks(); }
  });


  // Input: a confirmation without edits, a disappearing account or a legacy account without sign-in credentials.
  // Expected status code: 400, 401 and 403.
  // Expected behavior: refuse an empty edit or an edit that cannot be authorized.
  // Expected output: diagnostic and no profile changes.
  test('rejects no-op edits and unavailable accounts', async () => {
    const { stores, app, auth } = await setup();
    expect((await patch(app, auth, { currentPassword: original.password })).status).toBe(400);
    jest.spyOn(stores.users, 'update').mockResolvedValueOnce(null);
    expect((await patch(app, auth, { displayName: 'Updated' })).status).toBe(401);
    delete required(stores.userList[0]).passwordHash;
    expect((await patch(app, auth, { newPassword: 'another password', currentPassword: original.password })).status).toBe(403);
    expect(stores.userList[0]?.displayName).toBe(original.username);
  });

  // Input: display name with extra spaces and a phone number
  // Expected status code: 200
  // Expected behavior: fields are normalized and a refresh returns the same account; credentials are never exposed
  // Expected output: { user } with displayName "Updated Name"
  test('Saves normalized profile fields', async () => {
    const { app, auth } = await setup();
    const changes = { displayName: '  Updated   Name ', phoneNumber: '+852 6123 4567' };
    const updated = await patch(app, auth, changes);
    expect(updated.status).toBe(200);
    expect(updated.body.user).toMatchObject({ ...changes, displayName: 'Updated Name' });
    const refreshed = await request(app).get('/api/users/me').set('Authorization', auth);
    expect(refreshed.body).toEqual(updated.body);
    expect(JSON.stringify(updated.body)).not.toContain('passwordHash');
  });

  // Input: empty body, another user's ID, blank name, bad email/phone, external or malformed avatar, short password
  // Expected status code: 400
  // Expected behavior: the account is unchanged
  // Expected output: error message
  test.each([{}, { id: 'another-user' }, { displayName: '' }, { email: 'bad' }, { phoneNumber: '123' },
    { phoneNumber: 123456789 }, { displayName: 'invalid\u007fname' },
    { avatarDataUrl: 'https://external.example/photo.jpg' }, { avatarDataUrl: 'data:image/jpeg;base64,YWJj' },
    { avatarDataUrl: 'a'.repeat(180001) }, { newPassword: 'short' }])('Rejects invalid updates %#', async changes => {
    const { app, auth, stores } = await setup();
    const before = JSON.stringify(stores.userList);
    const res = await patch(app, auth, changes);
    expect(res.status).toBe(400);
    expect(JSON.stringify(stores.userList)).toBe(before);
  });

  // Input: retired timeZone or defaultCurrency together with a valid display name
  // Expected status code: 400
  // Expected behavior: the whole update is rejected atomically
  // Expected output: error message
  test.each([{ timeZone: 'Asia/Hong_Kong' }, { defaultCurrency: 'USD' }])('Rejects retired account settings: %j', async retired => {
    const { app, auth, stores } = await setup();
    const before = structuredClone(stores.userList);
    const res = await patch(app, auth, { displayName: 'Must not be saved', ...retired });
    expect(res.status).toBe(400);
    expect(stores.userList).toEqual(before);
  });

  // Input: update without a session, then with one while another account exists
  // Expected status code: 401, then 200
  // Expected behavior: only the session's own account can change
  // Expected output: the other account keeps its name
  test('Requires a session and never changes another user', async () => {
    const { app, auth, stores } = await setup();
    const other = await stores.users.create({ email: 'other@example.com', displayName: 'Other' });
    expect((await request(app).patch('/api/users/me').send({ displayName: 'Changed' })).status).toBe(401);
    expect((await patch(app, auth, { displayName: 'Changed' })).status).toBe(200);
    expect((await stores.users.findById(other.id))?.displayName).toBe('Other');
  });

  // Input: email or password change without the current password, or with a wrong one
  // Expected status code: 403
  // Expected behavior: credentials are unchanged
  // Expected output: error message; the original password still logs in
  test('Requires the current password before changing email or password', async () => {
    const { app, auth } = await setup();
    for (const changes of [{ email: 'changed@example.com' }, { newPassword: 'new password' }, { email: 'changed@example.com', currentPassword: 'wrong' }]) {
      expect((await patch(app, auth, changes)).status).toBe(403);
    }
    expect((await login(app)).status).toBe(200);
  });

  // Input: new email and password with the correct current password while another session exists
  // Expected status code: 200
  // Expected behavior: both change together; this session stays valid; other sessions are revoked
  // Expected output: { user } with email "new@example.com"
  test('Changes credentials together and revokes other sessions', async () => {
    const { app, auth, stores } = await setup();
    const otherToken = (await login(app)).body.token as string;
    const updated = await patch(app, auth, { email: ' NEW@example.com ', newPassword: 'new passphrase', currentPassword: original.password });
    expect(updated.status).toBe(200);
    expect(updated.body.user.email).toBe('new@example.com');
    expect((await request(app).get('/api/users/me').set('Authorization', auth)).status).toBe(200);
    expect((await request(app).get('/api/users/me').set('Authorization', `Bearer ${otherToken}`)).status).toBe(401);
    expect((await login(app)).status).toBe(401);
    expect((await login(app, { username: original.username, password: 'new passphrase' })).status).toBe(200);
    expect(await verifyPassword('new passphrase', required(required(stores.userList[0]).passwordHash))).toBe(true);
  });

  // Input: a display-name change while another session exists
  // Expected status code: 200
  // Expected behavior: ordinary profile changes keep other sessions
  // Expected output: the other session still reads the account
  test('Ordinary profile changes preserve other sessions', async () => {
    const { app, auth } = await setup();
    const otherToken = (await login(app)).body.token as string;
    expect((await patch(app, auth, { displayName: 'New Name' })).status).toBe(200);
    expect((await request(app).get('/api/users/me').set('Authorization', `Bearer ${otherToken}`)).status).toBe(200);
  });

  // Input: an email used by another account; mock: then the store reports a concurrent duplicate
  // Expected status code: 409 for both
  // Expected behavior: no duplicate email is saved
  // Expected output: error message
  test('Duplicate email and concurrent duplicate both return conflict', async () => {
    const { app, auth, stores } = await setup();
    await stores.users.create({ email: 'other@example.com', displayName: 'Other' });
    expect((await patch(app, auth, { email: 'OTHER@example.com', currentPassword: original.password })).status).toBe(409);
    stores.users.update = () => Promise.reject(new DuplicateAccountError());
    expect((await patch(app, auth, { email: 'unique@example.com', currentPassword: original.password })).status).toBe(409);
  });

  // Input: credential change; mock: the user store write throws
  // Expected status code: 500
  // Expected behavior: the account and every session are unchanged
  // Expected output: { error: "Something went wrong. Please try again." }
  test('A failed credential write leaves the account and sessions unchanged', async () => {
    const { app, auth, stores } = await setup();
    const otherToken = (await login(app)).body.token as string;
    const before = structuredClone(stores.userList);
    const log = jest.spyOn(console, 'error').mockImplementation(() => undefined);
    stores.users.update = () => Promise.reject(new Error('Database unavailable'));
    try {
      const response = await patch(app, auth, { email: 'changed@example.com', newPassword: 'new passphrase', currentPassword: original.password });
      expect(response.status).toBe(500);
      expect(stores.userList).toEqual(before);
      for (const authorization of [auth, `Bearer ${otherToken}`]) {
        expect((await request(app).get('/api/users/me').set('Authorization', authorization)).status).toBe(200);
      }
      expect((await login(app)).status).toBe(200);
    } finally { log.mockRestore(); }
  });

  // Input: Google-only account sets a password without a token, with another user's token, then with its own; mock: Google verifier
  // Expected status code: 403, 403, then 200
  // Expected behavior: the same Google identity must be reconfirmed before adding a password
  // Expected output: { user } with signInMethods ["password", "google"]
  test('Google-only accounts must reconfirm the same Google identity', async () => {
    const previous = process.env.GOOGLE_CLIENT_ID;
    process.env.GOOGLE_CLIENT_ID = 'test.apps.googleusercontent.com';
    try {
      const stores = memoryStores();
      const user = await stores.users.create({ email: 'google@example.com', displayName: 'Google', googleId: 'google-user' });
      const session = await startSession(stores.sessions, user);
      const verifyToken = jest.fn((token: string) => Promise.resolve({ sub: token, email_verified: true, exp: Date.now() / 1000 + 600,
        iss: 'https://accounts.google.com', aud: 'test.apps.googleusercontent.com', iat: Date.now() / 1000 }));
      const app = createApp({ ...stores, verifyToken });
      const auth = `Bearer ${session.token}`;
      expect((await patch(app, auth, { newPassword: 'new password' })).status).toBe(403);
      expect((await patch(app, auth, { newPassword: 'new password', googleIdToken: 'wrong-user' })).status).toBe(403);
      const response = await patch(app, auth, { newPassword: 'new password', googleIdToken: 'google-user' });
      expect(response.status).toBe(200);
      expect(response.body.user.signInMethods).toEqual(['password', 'google']);
    } finally {
      if (previous === undefined) delete process.env.GOOGLE_CLIENT_ID; else process.env.GOOGLE_CLIENT_ID = previous;
    }
  });

  // Input: an 18 KB JPEG photo, an empty photo, then a 200 KB payload
  // Expected status code: 200, 200, then 413
  // Expected behavior: photos are bounded and can be cleared
  // Expected output: stored avatarDataUrl equals the photo, then ""
  test('Bounds avatar payloads and permits clearing a photo', async () => {
    const { app, auth, stores } = await setup();
    // Only the transport envelope and JPEG boundary validation are under test here.
    const jpeg = Buffer.concat([Buffer.from([0xff, 0xd8]), Buffer.alloc(18000), Buffer.from([0xff, 0xd9])]);
    const photo = `data:image/jpeg;base64,${jpeg.toString('base64')}`;
    expect((await patch(app, auth, { avatarDataUrl: photo })).status).toBe(200);
    expect(required(stores.userList[0]).avatarDataUrl).toBe(photo);
    expect((await patch(app, auth, { avatarDataUrl: '' })).status).toBe(200);
    expect(required(stores.userList[0]).avatarDataUrl).toBe('');
    expect((await patch(app, auth, { avatarDataUrl: 'x'.repeat(200000) })).status).toBe(413);
  });
});
