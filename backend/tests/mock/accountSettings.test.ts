import request from 'supertest';
import { createApp } from '../../src/app';
import { memoryStores } from '../helpers/memoryStores';
import { startSession } from '../../src/session';
import { DuplicateAccountError } from '../../src/userStore';
import { verifyPassword } from '../../src/password';
import { registerVerified } from '../helpers/registration';

const original = { email: 'profile@example.com', password: 'original password', displayName: 'Profile User' };
async function setup() {
  const stores = memoryStores();
  const app = createApp(stores);
  const created = await registerVerified(app, stores.codes, original);
  const auth = `Bearer ${created.body.token}`;
  return { stores, app, auth };
}

test('saves normalized profile fields, persists a refresh, and never exposes credentials', async () => {
  const { app, auth } = await setup();
  const changes = { displayName: '  Updated   Name ', phoneNumber: '+852 6123 4567', timeZone: 'Asia/Hong_Kong', defaultCurrency: 'USD' };
  const updated = await request(app).patch('/api/users/me').set('Authorization', auth).send(changes);
  expect(updated.status).toBe(200);
  expect(updated.body.user).toMatchObject({ ...changes, displayName: 'Updated Name' });
  const refreshed = await request(app).get('/api/users/me').set('Authorization', auth);
  expect(refreshed.body).toEqual(updated.body);
  expect(JSON.stringify(updated.body)).not.toContain('passwordHash');
});

test.each([{}, { id: 'another-user' }, { displayName: '' }, { email: 'bad' }, { phoneNumber: '123' },
  { phoneNumber: 123456789 }, { timeZone: 'Fake/Place' }, { timeZone: 42 }, { defaultCurrency: 'FAKE' },
  { avatarDataUrl: 'https://external.example/photo.jpg' }, { avatarDataUrl: 'data:image/jpeg;base64,YWJj' },
  { avatarDataUrl: 'a'.repeat(180001) }, { newPassword: 'short' }])('rejects invalid updates without changing the account %#', async changes => {
  const { app, auth, stores } = await setup();
  const before = JSON.stringify(stores.userList);
  const res = await request(app).patch('/api/users/me').set('Authorization', auth).send(changes);
  expect(res.status).toBe(400);
  expect(JSON.stringify(stores.userList)).toBe(before);
});

test('requires a session and does not let a request choose another user', async () => {
  const { app, auth, stores } = await setup();
  const other = await stores.users.create({ email: 'other@example.com', displayName: 'Other' });
  expect((await request(app).patch('/api/users/me').send({ displayName: 'Changed' })).status).toBe(401);
  expect((await request(app).patch('/api/users/me').set('Authorization', auth).send({ displayName: 'Changed' })).status).toBe(200);
  expect((await stores.users.findById(other.id))?.displayName).toBe('Other');
});

test('requires the current password before changing email or password', async () => {
  const { app, auth } = await setup();
  for (const changes of [{ email: 'changed@example.com' }, { newPassword: 'new password' }, { email: 'changed@example.com', currentPassword: 'wrong' }]) {
    expect((await request(app).patch('/api/users/me').set('Authorization', auth).send(changes)).status).toBe(403);
  }
  expect((await request(app).post('/api/auth/login').send(original)).status).toBe(200);
});

test('changes credentials together, keeps this session, and revokes other sessions', async () => {
  const { app, auth, stores } = await setup();
  const otherToken = (await request(app).post('/api/auth/login').send(original)).body.token as string;
  const changes = { email: ' NEW@example.com ', newPassword: 'new passphrase', currentPassword: original.password };
  const updated = await request(app).patch('/api/users/me').set('Authorization', auth).send(changes);
  expect(updated.status).toBe(200);
  expect(updated.body.user.email).toBe('new@example.com');
  expect((await request(app).get('/api/users/me').set('Authorization', auth)).status).toBe(200);
  expect((await request(app).get('/api/users/me').set('Authorization', `Bearer ${otherToken}`)).status).toBe(401);
  expect((await request(app).post('/api/auth/login').send(original)).status).toBe(401);
  expect((await request(app).post('/api/auth/login').send({ email: 'new@example.com', password: 'new passphrase' })).status).toBe(200);
  expect(await verifyPassword('new passphrase', stores.userList[0]!.passwordHash!)).toBe(true);
});

test('duplicate email and concurrent duplicate both return conflict', async () => {
  const { app, auth, stores } = await setup();
  await stores.users.create({ email: 'other@example.com', displayName: 'Other' });
  expect((await request(app).patch('/api/users/me').set('Authorization', auth)
    .send({ email: 'OTHER@example.com', currentPassword: original.password })).status).toBe(409);
  stores.users.update = async () => { throw new DuplicateAccountError(); };
  expect((await request(app).patch('/api/users/me').set('Authorization', auth)
    .send({ email: 'unique@example.com', currentPassword: original.password })).status).toBe(409);
});

test('a failed credential write leaves the account and all sessions unchanged', async () => {
  const { app, auth, stores } = await setup();
  const otherToken = (await request(app).post('/api/auth/login').send(original)).body.token as string;
  const before = structuredClone(stores.userList);
  const log = jest.spyOn(console, 'error').mockImplementation(() => {});
  stores.users.update = async () => { throw new Error('Database unavailable'); };
  try {
    const response = await request(app).patch('/api/users/me').set('Authorization', auth)
      .send({ email: 'changed@example.com', newPassword: 'new passphrase', currentPassword: original.password });
    expect(response.status).toBe(500);
    expect(stores.userList).toEqual(before);
    for (const authorization of [auth, `Bearer ${otherToken}`]) {
      expect((await request(app).get('/api/users/me').set('Authorization', authorization)).status).toBe(200);
    }
    expect((await request(app).post('/api/auth/login').send(original)).status).toBe(200);
  } finally { log.mockRestore(); }
});

test('ordinary profile changes preserve other sessions', async () => {
  const { app, auth } = await setup();
  const otherToken = (await request(app).post('/api/auth/login').send(original)).body.token as string;
  expect((await request(app).patch('/api/users/me').set('Authorization', auth).send({ displayName: 'New Name' })).status).toBe(200);
  expect((await request(app).get('/api/users/me').set('Authorization', `Bearer ${otherToken}`)).status).toBe(200);
});

test('Google-only accounts must reconfirm the same Google identity to change credentials', async () => {
  const previous = process.env.GOOGLE_CLIENT_ID;
  process.env.GOOGLE_CLIENT_ID = 'test.apps.googleusercontent.com';
  try {
    const stores = memoryStores();
    const user = await stores.users.create({ email: 'google@example.com', displayName: 'Google', googleId: 'google-user' });
    const session = await startSession(stores.sessions, user);
    const verifyToken = jest.fn(async (token: string) => ({ sub: token, email_verified: true, exp: Date.now() / 1000 + 600,
      iss: 'https://accounts.google.com', aud: 'test.apps.googleusercontent.com', iat: Date.now() / 1000 }));
    const app = createApp({ ...stores, verifyToken });
    const auth = `Bearer ${session.token}`;
    expect((await request(app).patch('/api/users/me').set('Authorization', auth).send({ newPassword: 'new password' })).status).toBe(403);
    expect((await request(app).patch('/api/users/me').set('Authorization', auth).send({ newPassword: 'new password', googleIdToken: 'wrong-user' })).status).toBe(403);
    const response = await request(app).patch('/api/users/me').set('Authorization', auth)
      .send({ newPassword: 'new password', googleIdToken: 'google-user' });
    expect(response.status).toBe(200);
    expect(response.body.user.signInMethods).toEqual(['password', 'google']);
  } finally {
    if (previous === undefined) delete process.env.GOOGLE_CLIENT_ID; else process.env.GOOGLE_CLIENT_ID = previous;
  }
});

test('bounds avatar payloads and permits clearing a photo', async () => {
  const { app, auth, stores } = await setup();
  // Only the transport envelope and JPEG boundary validation are under test here.
  const jpeg = Buffer.concat([Buffer.from([0xff, 0xd8]), Buffer.alloc(18000), Buffer.from([0xff, 0xd9])]);
  const photo = `data:image/jpeg;base64,${jpeg.toString('base64')}`;
  expect((await request(app).patch('/api/users/me').set('Authorization', auth).send({ avatarDataUrl: photo })).status).toBe(200);
  expect(stores.userList[0]!.avatarDataUrl).toBe(photo);
  expect((await request(app).patch('/api/users/me').set('Authorization', auth).send({ avatarDataUrl: '' })).status).toBe(200);
  expect(stores.userList[0]!.avatarDataUrl).toBe('');
  expect((await request(app).patch('/api/users/me').set('Authorization', auth).send({ avatarDataUrl: 'x'.repeat(200000) })).status).toBe(413);
});

test.each(['AUD', 'HKD', 'SGD', 'KRW', 'MXN', 'INR'])('accepts %s as the default currency', async defaultCurrency => {
  const { app, auth } = await setup();
  const res = await request(app).patch('/api/users/me').set('Authorization', auth).send({ defaultCurrency });
  expect(res.status).toBe(200);
  expect(res.body.user.defaultCurrency).toBe(defaultCurrency);
});
