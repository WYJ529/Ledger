import request from 'supertest';
import { createApp } from '../../src/app';

describe('Ledger service public interfaces without provider mocks', () => {
  test.each(['/api/ledger/imports/preview', '/api/ledger/imports/statement-preview'])(
    '%s accepts document-sized JSON before validating the file signature', async path => {
      const response = await request(createApp()).post(path).send({
        fileName: 'invalid.png', mimeType: 'image/png', defaultCurrency: 'CAD',
        dataBase64: Buffer.alloc(20 * 1024, 65).toString('base64'),
      });
      expect(response.status).toBe(415);
      expect(response.body.error).toContain('file content');
    },
  );

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
    expect(meta.body.maxDocumentBytes).toBe(5 * 1024 * 1024);
    const rate = await request(app).get('/api/ledger/exchange?from=CAD&to=CAD&date=2025-01-05');
    expect(rate.status).toBe(200);
    expect(rate.body).toEqual({ rate: '1', date: '2025-01-05', source: 'Original currency' });
  });

  test('fails clearly when optional document recognition is not configured', async () => {
    const key = process.env.OPENAI_API_KEY;
    delete process.env.OPENAI_API_KEY;
    try {
      const response = await request(createApp()).post('/api/ledger/imports/preview').send({
        fileName: 'receipt.pdf', mimeType: 'application/pdf', defaultCurrency: 'CAD',
        dataBase64: Buffer.from('%PDF-1.7\nexample').toString('base64'),
      });
      expect(response.status).toBe(503);
      expect(response.body.error).toContain('not configured');
    } finally { if (key !== undefined) process.env.OPENAI_API_KEY = key; }
  });
});
