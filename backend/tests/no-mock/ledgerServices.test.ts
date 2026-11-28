import request from 'supertest';
import { createApp } from '../../src/app';
import { useIsolatedMongo } from '../helpers/isolatedMongo';
import { SessionModel, UserModel } from '../../src/userStore';
import { registerAccount } from '../helpers/registration';

// Real routing, sessions, MongoDB and local statement parser. No provider is mocked;
// paid OpenAI recognition and the Frankfurter network are not called by these tests.
useIsolatedMongo('ledger-recognition-test', [SessionModel, UserModel]);

async function signedIn() {
  const app = createApp();
  const result = await registerAccount(app, { username: 'recognition_test', password: 'synthetic test passphrase' });
  expect(result.status).toBe(201);
  return { app, token: result.body.token as string };
}

function invalidPng() {
  return { fileName: 'invalid.png', mimeType: 'image/png', defaultCurrency: 'CAD',
    dataBase64: Buffer.alloc(20 * 1024, 65).toString('base64') };
}

// Interface GET /api/ledger/meta
describe('Unmocked: GET /api/ledger/meta', () => {
  // Input: anonymous GET request
  // Expected status code: 200
  // Expected behavior: lists supported currencies, categories and upload limits
  // Expected output: { currencies, categories, documentImportAvailable, localStatementImportAvailable, maxDocumentBytes, maxImageBytes }
  test('Lists the supported formats and currencies', async () => {
    const meta = await request(createApp()).get('/api/ledger/meta');
    expect(meta.status).toBe(200);
    expect(meta.body.currencies).toContain('CAD');
    expect(meta.body.localStatementImportAvailable).toBe(true);
    expect(meta.body.maxDocumentBytes).toBe(20 * 1024 * 1024);
    expect(meta.body.maxImageBytes).toBe(5 * 1024 * 1024);
  });
});

// Interface GET /api/ledger/exchange
describe('Unmocked: GET /api/ledger/exchange', () => {
  // Input: from=CAD, to=CAD, date=2025-01-05
  // Expected status code: 200
  // Expected behavior: same-currency conversion is answered without a network request
  // Expected output: { rate: "1", date: "2025-01-05", source: "Original currency" }
  test('Returns an identity rate for the same currency', async () => {
    const rate = await request(createApp()).get('/api/ledger/exchange?from=CAD&to=CAD&date=2025-01-05');
    expect(rate.status).toBe(200);
    expect(rate.body).toEqual({ rate: '1', date: '2025-01-05', source: 'Original currency' });
  });

  // Input: unsupported currency or an impossible date
  // Expected status code: 400
  // Expected behavior: rejected before any rate lookup
  // Expected output: error message
  test.each(['from=XXX&to=CAD&date=2025-01-05', 'from=USD&to=CAD&date=2025-02-30', 'from=USD&to=CAD'])(
    'Rejects invalid query %s', async query => {
      const res = await request(createApp()).get(`/api/ledger/exchange?${query}`);
      expect(res.status).toBe(400);
      expect(res.body.error).toContain('supported currencies');
    });
});

// Interface POST /api/ledger/imports/preview
describe('Unmocked: POST /api/ledger/imports/preview', () => {
  // Input: signed-in request with a 20 KiB body whose bytes are not a PNG
  // Expected status code: 415
  // Expected behavior: document-sized JSON is accepted, then the file signature check rejects it
  // Expected output: error mentioning the file content
  test('Validates the file signature of a document-sized upload', async () => {
    const { app, token } = await signedIn();
    const response = await request(app).post('/api/ledger/imports/preview').auth(token, { type: 'bearer' }).send(invalidPng());
    expect(response.status).toBe(415);
    expect(response.body.error).toContain('file content');
  });

  // Input: anonymous, unknown-token and logged-out requests
  // Expected status code: 401
  // Expected behavior: paid recognition authenticates before parsing an upload
  // Expected output: Bearer challenge
  test('Rejects anonymous and revoked sessions before parsing an upload', async () => {
    const { app, token } = await signedIn();
    const path = '/api/ledger/imports/preview';
    expect((await request(app).post(path).set('Content-Type', 'application/json').send('{')).status).toBe(401);
    expect((await request(app).post(path).auth('not-a-valid-session-token', { type: 'bearer' }).send({})).status).toBe(401);
    expect((await request(app).post('/api/auth/logout').auth(token, { type: 'bearer' })).status).toBe(204);
    expect((await request(app).post(path).auth(token, { type: 'bearer' }).send({})).status).toBe(401);
  });

  // Input: valid PDF while OPENAI_API_KEY is not set
  // Expected status code: 503
  // Expected behavior: recognition reports that it is not configured; no external call is made
  // Expected output: error containing "not configured"
  test('Fails clearly when document recognition is not configured', async () => {
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

// Interface POST /api/ledger/imports/statement-preview
describe('Unmocked: POST /api/ledger/imports/statement-preview', () => {
  // Input: anonymous request with an empty body
  // Expected status code: 400
  // Expected behavior: free local parsing needs no account but still validates the document fields
  // Expected output: error asking for a valid file name
  test('Is available without an account', async () => {
    const res = await request(createApp()).post('/api/ledger/imports/statement-preview').send({});
    expect(res.status).toBe(400);
    expect(res.body.error).toContain('valid file name');
  });

  // Input: 20 KiB body whose bytes are not a PNG
  // Expected status code: 415
  // Expected behavior: the document-sized body is accepted and the signature check rejects it
  // Expected output: error mentioning the file content
  test('Validates the file signature of a document-sized upload', async () => {
    const response = await request(createApp()).post('/api/ledger/imports/statement-preview').send(invalidPng());
    expect(response.status).toBe(415);
    expect(response.body.error).toContain('file content');
  });
});
