import request from 'supertest';
import { createApp } from '../../src/app';
import { verifyPassword } from '../../src/password';
import { MAX_CODE_ATTEMPTS } from '../../src/registrationVerification';
import { memoryStores } from '../helpers/memoryStores';
import { registrationProof } from '../helpers/registration';

const account = { email: 'sam@example.com', displayName: 'Sam', password: 'a long passphrase' };
function setup() { const stores = memoryStores(); return { ...stores, app: createApp(stores) }; }

test('sends a six-digit code without creating an account or exposing it in the response or storage', async () => {
  const { app, codes, records, userList, sessionMap } = setup();
  const sent = await request(app).post('/api/auth/register/code').send({ email: ' Sam@EXAMPLE.com ' });
  expect(sent.status).toBe(202);
  expect(sent.headers['cache-control']).toBe('no-store');
  expect(sent.body).toMatchObject({ email: account.email, verificationId: expect.stringMatching(/^[a-f0-9]{64}$/), retryAfterSeconds: 60 });
  expect(sent.body.expiresAt).toBeGreaterThan(Date.now() + 590000);
  expect(sent.body).not.toHaveProperty('code');
  const record = records.get(sent.body.verificationId)!;
  expect(record.codeHash).toMatch(/^scrypt\$/);
  expect(await verifyPassword(codes.get(account.email)!, record.codeHash)).toBe(true);
  expect(userList).toHaveLength(0);
  expect(sessionMap.size).toBe(0);
  expect((await request(app).post('/api/auth/login').send(account)).status).toBe(401);
});

test.each([undefined, '', '12345', '1234567', 123456, { $ne: '' }, '１２３４５６'])('requires a six-digit string code (%p)', async code => {
  const { app, codes, userList } = setup();
  const proof = await registrationProof(app, codes, account.email);
  const res = await request(app).post('/api/auth/register').send({ ...account, ...proof, code });
  expect(res.status).toBe(400);
  expect(userList).toHaveLength(0);
});

test('rejects the old direct registration request', async () => {
  const { app, userList } = setup();
  expect((await request(app).post('/api/auth/register').send(account)).status).toBe(400);
  expect(userList).toHaveLength(0);
});

test('binds a code to the email and challenge, and permits correction after a wrong code', async () => {
  const { app, codes, userList } = setup();
  const proof = await registrationProof(app, codes, account.email);
  for (const changes of [
    { email: 'other@example.com' }, { verificationId: '0'.repeat(64) },
    { code: proof.code === '000000' ? '000001' : '000000' },
  ]) {
    expect((await request(app).post('/api/auth/register').send({ ...account, ...proof, ...changes })).status).toBe(400);
  }
  expect(userList).toHaveLength(0);
  expect((await request(app).post('/api/auth/register').send({ ...account, ...proof })).status).toBe(201);
});

test('rejects expired codes before MongoDB TTL cleanup', async () => {
  const { app, codes, records, userList } = setup();
  const proof = await registrationProof(app, codes, account.email);
  records.get(proof.verificationId)!.expiresAt = new Date(Date.now() - 1);
  expect((await request(app).post('/api/auth/register').send({ ...account, ...proof })).status).toBe(400);
  expect(userList).toHaveLength(0);
});

test('allows only five code attempts, including concurrent submissions', async () => {
  const { app, codes, userList, records } = setup();
  const proof = await registrationProof(app, codes, account.email);
  const code = proof.code === '000000' ? '000001' : '000000';
  const results = await Promise.all(Array.from({ length: MAX_CODE_ATTEMPTS + 2 }, () =>
    request(app).post('/api/auth/register').send({ ...account, ...proof, code })));
  expect(results.every(res => res.status === 400)).toBe(true);
  expect(records.get(proof.verificationId)?.attempts).toBe(MAX_CODE_ATTEMPTS);
  expect((await request(app).post('/api/auth/register').send({ ...account, ...proof })).status).toBe(400);
  expect(userList).toHaveLength(0);
});

test('throttles resends, invalidates the old challenge and resets the attempt budget', async () => {
  const { app, codes, records } = setup();
  const old = await registrationProof(app, codes, account.email);
  const blocked = await request(app).post('/api/auth/register/code').send({ email: 'SAM@example.com' });
  expect(blocked.status).toBe(429);
  expect(blocked.headers['retry-after']).toBe('60');
  records.get(old.verificationId)!.resendAt = new Date(Date.now() - 1);
  const fresh = await registrationProof(app, codes, account.email);
  expect(fresh.verificationId).not.toBe(old.verificationId);
  expect((await request(app).post('/api/auth/register').send({ ...account, ...old })).status).toBe(400);
  expect((await request(app).post('/api/auth/register').send({ ...account, ...fresh })).status).toBe(201);
});

test('a code can create only one account and one session under simultaneous verification', async () => {
  const { app, codes, userList, sessionMap } = setup();
  const proof = await registrationProof(app, codes, account.email);
  const results = await Promise.all(Array.from({ length: 3 }, () => request(app).post('/api/auth/register').send({ ...account, ...proof })));
  expect(results.filter(res => res.status === 201)).toHaveLength(1);
  expect(userList).toHaveLength(1);
  expect(sessionMap.size).toBe(1);
});

test('delivery failures are safe to retry after the cooldown and never expose SMTP details', async () => {
  const stores = memoryStores();
  const sendCode = jest.fn(async () => { throw new Error('private SMTP credentials'); });
  const app = createApp({ ...stores, registrationEmail: { sendCode } });
  const failed = await request(app).post('/api/auth/register/code').send({ email: account.email });
  expect(failed.status).toBe(503);
  expect(JSON.stringify(failed.body)).not.toContain('private');
  expect(stores.userList).toHaveLength(0);
  expect([...stores.records.values()][0]?.ready).toBe(false);
  expect((await request(app).post('/api/auth/register/code').send({ email: account.email })).status).toBe(429);
  expect(sendCode).toHaveBeenCalledTimes(1);
});

test('limits code requests across email addresses from one client', async () => {
  const { app } = setup();
  // Malformed requests also consume the IP budget, before any mail is sent.
  for (let i = 0; i < 10; i++) expect((await request(app).post('/api/auth/register/code').send({})).status).toBe(400);
  const blocked = await request(app).post('/api/auth/register/code').send({ email: account.email });
  expect(blocked.status).toBe(429);
  expect(Number(blocked.headers['retry-after'])).toBeGreaterThan(0);
});

test('does not send codes to an already registered email', async () => {
  const { app, users, codes } = setup();
  await users.create({ email: account.email, displayName: account.displayName });
  expect((await request(app).post('/api/auth/register/code').send({ email: account.email })).status).toBe(409);
  expect(codes.size).toBe(0);
});

test('direct deployments ignore spoofed forwarding headers when limiting registration', async () => {
  const previous = process.env.TRUST_PROXY_HOPS;
  delete process.env.TRUST_PROXY_HOPS;
  let app: ReturnType<typeof createApp>;
  try { app = setup().app; }
  finally { if (previous !== undefined) process.env.TRUST_PROXY_HOPS = previous; }
  for (let i = 0; i < 10; i++) {
    expect((await request(app).post('/api/auth/register/code').set('X-Forwarded-For', `198.51.100.${i}`).send({})).status).toBe(400);
  }
  expect((await request(app).post('/api/auth/register/code').set('X-Forwarded-For', '203.0.113.5').send({})).status).toBe(429);
});

test('the cloud proxy gives separate clients separate budgets and ignores spoofed earlier hops', async () => {
  const previous = process.env.TRUST_PROXY_HOPS;
  process.env.TRUST_PROXY_HOPS = '1';
  let app: ReturnType<typeof createApp>;
  try { app = setup().app; }
  finally { if (previous === undefined) delete process.env.TRUST_PROXY_HOPS; else process.env.TRUST_PROXY_HOPS = previous; }
  for (let i = 0; i < 10; i++) {
    expect((await request(app).post('/api/auth/register/code')
      .set('X-Forwarded-For', `203.0.113.${i}, 198.51.100.1`).send({})).status).toBe(400);
  }
  expect((await request(app).post('/api/auth/register/code').set('X-Forwarded-For', '203.0.113.100, 198.51.100.1').send({})).status).toBe(429);
  expect((await request(app).post('/api/auth/register/code').set('X-Forwarded-For', '198.51.100.2').send({})).status).toBe(400);
});
