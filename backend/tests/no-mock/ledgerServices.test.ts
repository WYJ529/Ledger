import request from 'supertest';
import { createApp } from '../../src/app';
import { MongoMemoryServer } from 'mongodb-memory-server';
import { connectDatabase, disconnectDatabase, SessionModel, UserModel } from '../../src/userStore';
import { registerAccount } from '../helpers/registration';

let mongo: MongoMemoryServer;
beforeAll(async () => {
  mongo = await MongoMemoryServer.create();
  await connectDatabase(mongo.getUri('ledger-recognition-test'));
}, 120000);
afterEach(async () => { await Promise.all([SessionModel.deleteMany({}), UserModel.deleteMany({})]); });
afterAll(async () => { await disconnectDatabase(); await mongo.stop(); }, 30000);

async function signedIn() {
  const app = createApp();
  const result = await registerAccount(app, { username: 'recognition_test', password: 'synthetic test passphrase' });
  expect(result.status).toBe(201);
  return { app, token: result.body.token as string };
}

describe('Ledger service public interfaces without provider mocks', () => {
  test.each(['/api/ledger/imports/preview', '/api/ledger/imports/statement-preview'])(
    '%s accepts document-sized JSON before validating the file signature', async path => {
      const { app, token } = await signedIn();
      const response = await request(app).post(path).auth(token, { type: 'bearer' }).send({
        fileName: 'invalid.png', mimeType: 'image/png', defaultCurrency: 'CAD',
        dataBase64: Buffer.alloc(20 * 1024, 65).toString('base64'),
      });
      expect(response.status).toBe(415);
      expect(response.body.error).toContain('file content');
    },
  );

  test('paid recognition rejects anonymous and revoked sessions before parsing an upload', async () => {
    const { app, token } = await signedIn();
    const path = '/api/ledger/imports/preview';
    expect((await request(app).post(path).set('Content-Type', 'application/json').send('{')).status).toBe(401);
    expect((await request(app).post(path).auth('not-a-valid-session-token', { type: 'bearer' }).send({})).status).toBe(401);
    expect((await request(app).post('/api/auth/logout').auth(token, { type: 'bearer' })).status).toBe(204);
    expect((await request(app).post(path).auth(token, { type: 'bearer' }).send({})).status).toBe(401);
    // Free local statement parsing remains available without an account.
    expect((await request(app).post('/api/ledger/imports/statement-preview').send({})).status).toBe(400);
  });

  test('retains the small account body limit and authenticated account/group routes', async () => {
    const app = createApp();
    expect((await request(app).post('/api/auth/login').send({ email: 'x'.repeat(20 * 1024) })).status).toBe(413);
    expect((await request(app).get('/api/users/me')).status).toBe(401);
    expect((await request(app).get('/api/groups')).status).toBe(401);
  });

  test('lists the supported formats/currencies and returns an identity rate without network', async () => {
    const app = createApp();
    const meta = await request(app).get('/api/ledger/meta');
    expect(meta.status).toBe(200);
    expect(meta.body.currencies).toContain('CAD');
    expect(meta.body.maxDocumentBytes).toBe(20 * 1024 * 1024);
    expect(meta.body.maxImageBytes).toBe(5 * 1024 * 1024);
    const rate = await request(app).get('/api/ledger/exchange?from=CAD&to=CAD&date=2025-01-05');
    expect(rate.status).toBe(200);
    expect(rate.body).toEqual({ rate: '1', date: '2025-01-05', source: 'Original currency' });
  });

  test('fails clearly when optional document recognition is not configured', async () => {
    const key = process.env.OPENAI_API_KEY;
    delete process.env.OPENAI_API_KEY;
    try {
      const { app, token } = await signedIn();
      const response = await request(app).post('/api/ledger/imports/preview').auth(token, { type: 'bearer' }).send({
        fileName: 'receipt.pdf', mimeType: 'application/pdf', defaultCurrency: 'CAD',
        dataBase64: Buffer.from('%PDF-1.7\nexample').toString('base64'),
      });
      expect(response.status).toBe(503);
      expect(response.body.error).toContain('not configured');
    } finally { if (key !== undefined) process.env.OPENAI_API_KEY = key; }
  });
});
