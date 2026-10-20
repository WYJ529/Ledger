import request from 'supertest';
import { createApp } from '../../src/app';

describe('Ledger service public interfaces without provider mocks', () => {
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
