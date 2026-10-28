import express from 'express';
import request from 'supertest';
import { createLedgerRouter } from '../../src/ledger/router';
import { FrankfurterFxProvider, OpenAiDocumentExtractor, LedgerServiceError, isDocumentRecognitionConfigured } from '../../src/ledger/providers';

const document = { fileName: 'receipt.pdf', mimeType: 'application/pdf', dataBase64: Buffer.from('%PDF-1.7\nexample').toString('base64'), defaultCurrency: 'CAD' };
const input = { kind: 'expense', amount: '12.34', currency: 'CAD', date: '2025-01-03', merchant: 'Cafe', category: 'Food', notes: '' };
const extraction = { candidates: [{ rowId: 'row-1', input, warnings: [] }], warnings: [] };
const originalKey = process.env.OPENAI_API_KEY;
afterAll(() => { if (originalKey === undefined) delete process.env.OPENAI_API_KEY; else process.env.OPENAI_API_KEY = originalKey; });

describe('Guest service boundary (no financial records are persisted)', () => {
  const app = (extract = jest.fn().mockResolvedValue(extraction), quote = jest.fn().mockResolvedValue({ rate: '1.4411', date: '2025-01-03', source: 'test' })) =>
    express().use('/api/ledger', createLedgerRouter({ extractor: { extract }, fx: { quote } }));

  test.each(['', '   ', 'your_openai_api_key', 'YOUR_API_KEY', 'sk-your-api-key', '<OPENAI_API_KEY>'])('metadata and provider agree that setup placeholders are unavailable', async key => {
    process.env.OPENAI_API_KEY = key;
    const response = await request(app()).get('/api/ledger/meta');
    expect(response.status).toBe(200);
    expect(response.body.documentImportAvailable).toBe(false);
  });

  test('validates file signature and returns editable previews only', async () => {
    const extract = jest.fn().mockResolvedValue(extraction);
    const response = await request(app(extract)).post('/api/ledger/imports/preview').send(document);
    expect(response.status).toBe(200);
    expect(response.body).toEqual(extraction);
    expect(response.headers['cache-control']).toBe('no-store');
    expect(extract.mock.calls[0]?.[0].bytes).toEqual(Buffer.from('%PDF-1.7\nexample'));
  });

  test('local PDF preview never calls AI, including when local extraction fails', async () => {
    const ai = jest.fn();
    const local = jest.fn().mockResolvedValue(extraction);
    const service = express().use('/api/ledger', createLedgerRouter({ extractor: { extract: ai }, localExtractor: { extract: local } }));
    const result = await request(service).post('/api/ledger/imports/statement-preview').send(document);
    expect(result.status).toBe(200);
    expect(result.body).toEqual(extraction);
    expect(result.headers['cache-control']).toBe('no-store');
    local.mockRejectedValueOnce(new LedgerServiceError(422, 'Could not reconcile statement totals.'));
    const failed = await request(service).post('/api/ledger/imports/statement-preview').send(document);
    expect(failed.status).toBe(422);
    expect(failed.body.error).toContain('reconcile');
    expect(ai).not.toHaveBeenCalled();
  });

  test.each([
    { ...document, mimeType: 'text/html' }, { ...document, mimeType: 'image/png' },
    { ...document, fileName: '../receipt.pdf' }, { ...document, defaultCurrency: 'BAD' },
    { ...document, dataBase64: 'abc%' }, { ...document, dataBase64: '' },
  ])('rejects unsupported/malformed input before any paid service call', async body => {
    const extract = jest.fn();
    const response = await request(app(extract)).post('/api/ledger/imports/preview').send(body);
    expect([400, 415]).toContain(response.status);
    expect(extract).not.toHaveBeenCalled();
  });

  test('handles malformed and oversized JSON safely', async () => {
    const service = app();
    expect((await request(service).post('/api/ledger/imports/preview').set('Content-Type', 'application/json').send('{')).status).toBe(400);
    const response = await request(service).post('/api/ledger/imports/preview').send({ ...document, dataBase64: 'x'.repeat(8 * 1024 * 1024) });
    expect(response.status).toBe(413);
  });

  test('limits extraction requests and hides unexpected internal errors', async () => {
    const service = app(jest.fn().mockRejectedValue(new Error('secret-key-or-file-content')));
    for (let i = 0; i < 5; i++) {
      const response = await request(service).post('/api/ledger/imports/preview').send(document);
      expect(response.status).toBe(500);
      expect(JSON.stringify(response.body)).not.toContain('secret');
    }
    expect((await request(service).post('/api/ledger/imports/preview').send(document)).status).toBe(429);
  });

  test('validates query fields and preserves exchange provider failure', async () => {
    const quote = jest.fn().mockRejectedValue(new LedgerServiceError(503, 'Unavailable'));
    const service = app(undefined, quote);
    expect((await request(service).get('/api/ledger/exchange?from=USD&to=CAD&date=2025-02-30')).status).toBe(400);
    expect(quote).not.toHaveBeenCalled();
    expect((await request(service).get('/api/ledger/exchange?from=USD&to=CAD&date=2025-01-03')).status).toBe(503);
  });
});

describe('Frankfurter historical rates', () => {
  test('keeps actual Friday quote date for Sunday and caches the result', async () => {
    const http = jest.fn<ReturnType<typeof fetch>, Parameters<typeof fetch>>().mockResolvedValue(new Response(JSON.stringify({ base: 'USD', date: '2025-01-03', rates: { CAD: 1.4411 } })));
    const fx = new FrankfurterFxProvider(http);
    expect(await fx.quote('USD', 'CAD', '2025-01-05')).toEqual({ rate: '1.4411', date: '2025-01-03', source: 'Frankfurter / ECB' });
    await fx.quote('USD', 'CAD', '2025-01-05');
    expect(http).toHaveBeenCalledTimes(1);
    expect(await fx.quote('CAD', 'CAD', '2025-01-05')).toEqual({ rate: '1', date: '2025-01-05', source: 'Original currency' });
  });

  test.each([
    { base: 'USD', date: '2025-01-06', rates: { CAD: 1.4 } },
    { base: 'USD', date: '2024-12-20', rates: { CAD: 1.4 } },
    { base: 'USD', date: '2025-01-03', rates: { CAD: 0 } },
    { base: 'EUR', date: '2025-01-03', rates: { CAD: 1.4 } },
  ])('rejects invalid, stale or wrong-pair responses', async payload => {
    const http = jest.fn<ReturnType<typeof fetch>, Parameters<typeof fetch>>().mockResolvedValue(new Response(JSON.stringify(payload)));
    await expect(new FrankfurterFxProvider(http).quote('USD', 'CAD', '2025-01-05')).rejects.toBeInstanceOf(LedgerServiceError);
  });
});

describe('OpenAI Responses adapter', () => {
  const file = { fileName: 'receipt.pdf', mimeType: 'application/pdf', bytes: Buffer.from('%PDF-1.7\nexample') };
  const envelope = (result: unknown) => ({ status: 'completed', output: [{ type: 'message', content: [{ type: 'output_text', text: JSON.stringify(result) }] }] });
  beforeEach(() => { process.env.OPENAI_API_KEY = 'test-key'; });

  test('sends a strict schema, store:false and server-only credential; preserves incomplete drafts', async () => {
    const http = jest.fn<ReturnType<typeof fetch>, Parameters<typeof fetch>>().mockResolvedValue(new Response(JSON.stringify(envelope({ transactions: [{ ...input, date: '', warnings: ['Date missing'] }], warnings: [] }))));
    const result = await new OpenAiDocumentExtractor(http).extract(file, 'CAD');
    expect(result.candidates[0]?.input.date).toBe('');
    expect(result.candidates[0]?.warnings).toEqual(['Date missing']);
    const call = http.mock.calls[0];
    const body = JSON.parse(String(call?.[1]?.body));
    expect(call?.[0]).toBe('https://api.openai.com/v1/responses');
    expect(body.store).toBe(false);
    expect(body.text.format.strict).toBe(true);
    expect(body.input[0].content[1].file_data).toBe('data:application/pdf;base64,' + file.bytes.toString('base64'));
    expect(JSON.stringify(result)).not.toContain('test-key');
  });

  test('uses image input for image files', async () => {
    const http = jest.fn<ReturnType<typeof fetch>, Parameters<typeof fetch>>().mockResolvedValue(new Response(JSON.stringify(envelope({ transactions: [], warnings: ['Unreadable'] }))));
    await new OpenAiDocumentExtractor(http).extract({ ...file, mimeType: 'image/jpeg' }, 'CAD');
    expect(JSON.parse(String(http.mock.calls[0]?.[1]?.body)).input[0].content[1].type).toBe('input_image');
  });

  test('excludes explicit card repayments returned by AI while keeping merchant credits', async () => {
    const http = jest.fn<ReturnType<typeof fetch>, Parameters<typeof fetch>>().mockResolvedValue(new Response(JSON.stringify(envelope({
      transactions: [
        { ...input, kind: 'income', merchant: 'ONLINE PAYMENT - THANK YOU', warnings: [] },
        { ...input, kind: 'income', merchant: 'Shop refund', warnings: ['Refund retained for review'] },
      ], warnings: [],
    }))));
    const result = await new OpenAiDocumentExtractor(http).extract(file, 'CAD');
    expect(result.candidates).toHaveLength(1);
    expect(result.candidates[0]?.input.merchant).toBe('Shop refund');
    expect(result.warnings.join(' ')).toContain('Skipped 1');
    const body = JSON.parse(String(http.mock.calls[0]?.[1]?.body));
    expect(body.instructions).toContain('billing-period');
    expect(body.instructions).toContain('rewards/redemption');
  });

  test.each(['', '   ', 'your_openai_api_key', 'YOUR_API_KEY', 'sk-your-api-key', '<OPENAI_API_KEY>'])('placeholder key is unavailable and never calls the external service', async key => {
    process.env.OPENAI_API_KEY = key;
    expect(isDocumentRecognitionConfigured()).toBe(false);
    const http = jest.fn<ReturnType<typeof fetch>, Parameters<typeof fetch>>();
    await expect(new OpenAiDocumentExtractor(http).extract(file, 'CAD')).rejects.toMatchObject({ status: 503 });
    expect(http).not.toHaveBeenCalled();
  });

  test('a slow recognition response is reported as a timeout, not as an unreachable service', async () => {
    const http = jest.fn<ReturnType<typeof fetch>, Parameters<typeof fetch>>((_url, init) =>
      new Promise((_resolve, reject) => init?.signal?.addEventListener('abort', () => reject(init.signal?.reason))));
    const error = await new OpenAiDocumentExtractor(http, 20).extract(file, 'CAD').catch((caught: unknown) => caught);
    expect(error).toMatchObject({ status: 504 });
    expect((error as Error).message).toContain('took too long');
  });

  test('a body that stops arriving is also reported as a timeout', async () => {
    const http = jest.fn<ReturnType<typeof fetch>, Parameters<typeof fetch>>(async (_url, init) => new Response(new ReadableStream({
      start(controller) { init?.signal?.addEventListener('abort', () => controller.error(init.signal?.reason)); },
    })));
    await expect(new OpenAiDocumentExtractor(http, 20).extract(file, 'CAD')).rejects.toMatchObject({ status: 504 });
  });

  test('a network failure is still reported as unreachable', async () => {
    const http = jest.fn<ReturnType<typeof fetch>, Parameters<typeof fetch>>().mockRejectedValue(new TypeError('fetch failed'));
    const error = await new OpenAiDocumentExtractor(http).extract(file, 'CAD').catch((caught: unknown) => caught);
    expect(error).toMatchObject({ status: 503 });
    expect((error as Error).message).toContain('Cannot reach');
  });

  test('missing key never calls the external service', async () => {
    delete process.env.OPENAI_API_KEY;
    const http = jest.fn<ReturnType<typeof fetch>, Parameters<typeof fetch>>();
    await expect(new OpenAiDocumentExtractor(http).extract(file, 'CAD')).rejects.toMatchObject({ status: 503 });
    expect(http).not.toHaveBeenCalled();
  });

  test.each([
    { status: 'incomplete', output: [] },
    { status: 'completed', output: [{ type: 'message', content: [{ type: 'refusal', refusal: 'No' }] }] },
    envelope({ transactions: [{ amount: 12 }], warnings: [] }),
  ])('rejects incomplete, refused, or malformed output without fabricated rows', async payload => {
    const http = jest.fn<ReturnType<typeof fetch>, Parameters<typeof fetch>>().mockResolvedValue(new Response(JSON.stringify(payload)));
    await expect(new OpenAiDocumentExtractor(http).extract(file, 'CAD')).rejects.toBeInstanceOf(LedgerServiceError);
  });
});
