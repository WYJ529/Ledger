import { Router, json, type ErrorRequestHandler, type RequestHandler } from 'express';
import { CURRENCIES, CATEGORIES } from './domain';
import { extractStatement } from './statements';
import { FrankfurterFxProvider, OpenAiDocumentExtractor, LedgerServiceError, isSupportedCurrency, isHistoricalDate, isDocumentRecognitionConfigured,
  type DocumentExtractor, type FxProvider, type DocumentFile } from './providers';

export const MAX_DOCUMENT_BYTES = 5 * 1024 * 1024;

function readDocument(body: unknown): { file: DocumentFile; currency: string } {
  if (!body || typeof body !== 'object' || Array.isArray(body)) throw new LedgerServiceError(400, 'A document is required.');
  const { fileName, mimeType, dataBase64, defaultCurrency } = body as Record<string, unknown>;
  if (typeof fileName !== 'string' || !fileName.trim() || fileName.length > 180 || /[\u0000-\u001f/\\]/.test(fileName)) {
    throw new LedgerServiceError(400, 'Choose a document with a valid file name (up to 180 characters).');
  }
  if (!isSupportedCurrency(defaultCurrency)) throw new LedgerServiceError(400, 'Choose a supported default currency.');
  if (typeof mimeType !== 'string' || !['image/jpeg', 'image/png', 'image/webp', 'application/pdf'].includes(mimeType)) {
    throw new LedgerServiceError(415, 'Choose a JPEG, PNG, WebP image or PDF. CSV files are imported directly on your device.');
  }
  if (typeof dataBase64 !== 'string' || dataBase64.length === 0 || dataBase64.length > Math.ceil(MAX_DOCUMENT_BYTES / 3) * 4 ||
      dataBase64.length % 4 !== 0 || !/^[A-Za-z0-9+/]*={0,2}$/.test(dataBase64)) {
    throw new LedgerServiceError(400, 'The document is invalid or larger than 5 MiB. Choose a smaller file.');
  }
  const bytes = Buffer.from(dataBase64, 'base64');
  if (bytes.length > MAX_DOCUMENT_BYTES || bytes.toString('base64') !== dataBase64) throw new LedgerServiceError(413, 'Choose a document smaller than 5 MiB.');
  const valid = mimeType === 'image/jpeg' ? bytes.subarray(0, 3).equals(Buffer.from([0xff, 0xd8, 0xff]))
    : mimeType === 'image/png' ? bytes.subarray(0, 8).equals(Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]))
    : mimeType === 'image/webp' ? bytes.toString('ascii', 0, 4) === 'RIFF' && bytes.toString('ascii', 8, 12) === 'WEBP'
    : bytes.toString('ascii', 0, 5) === '%PDF-';
  if (!valid) throw new LedgerServiceError(415, 'The file content does not match its type. Choose an original image or PDF.');
  return { file: { fileName: fileName.trim(), mimeType, bytes }, currency: defaultCurrency };
}

/** Local guest proxy guard. Shared/cloud deployment must add the team's session authentication. */
function rateLimit(maximum: number, windowMs: number): RequestHandler {
  const clients = new Map<string, { count: number; reset: number }>();
  return (req, res, next) => {
    const now = Date.now();
    for (const [key, value] of clients) if (value.reset <= now) clients.delete(key);
    const key = req.ip ?? req.socket.remoteAddress ?? 'unknown';
    let entry = clients.get(key);
    if (!entry) {
      if (clients.size >= 2000) { res.status(503).json({ error: 'The service is busy. Please retry later.' }); return; }
      entry = { count: 0, reset: now + windowMs }; clients.set(key, entry);
    }
    if (entry.count >= maximum) {
      res.setHeader('Retry-After', Math.ceil((entry.reset - now) / 1000));
      res.status(429).json({ error: 'Too many requests. Please wait a few minutes and retry.' }); return;
    }
    entry.count++; next();
  };
}

export function createLedgerRouter(dependencies: { fx?: FxProvider; extractor?: DocumentExtractor; localExtractor?: DocumentExtractor } = {}): Router {
  const router = Router();
  const fx = dependencies.fx ?? new FrankfurterFxProvider();
  const extractor = dependencies.extractor ?? new OpenAiDocumentExtractor();
  const localExtractor = dependencies.localExtractor ?? { extract: extractStatement };
  let activeExtractions = 0;
  router.use((_req, res, next) => { res.setHeader('Cache-Control', 'no-store'); next(); });
  router.get('/meta', (_req, res) => res.json({ currencies: CURRENCIES, categories: CATEGORIES,
    documentImportAvailable: isDocumentRecognitionConfigured(), localStatementImportAvailable: true, maxDocumentBytes: MAX_DOCUMENT_BYTES }));
  router.get('/exchange', rateLimit(120, 60000), async (req, res) => {
    const { from, to, date } = req.query;
    if (!isSupportedCurrency(from) || !isSupportedCurrency(to) || !isHistoricalDate(date)) {
      throw new LedgerServiceError(400, 'Choose supported currencies and a valid date from 1999-01-04 through today.');
    }
    res.json(await fx.quote(from, to, date));
  });
  router.post('/imports/preview', rateLimit(5, 600000), json({ limit: '7mb' }), async (req, res) => {
    const { file, currency } = readDocument(req.body);
    if (activeExtractions >= 2) throw new LedgerServiceError(503, 'Document recognition is busy. Please retry shortly.');
    activeExtractions++;
    try { res.json(await extractor.extract(file, currency)); }
    finally { activeExtractions--; }
  });
  router.post('/imports/statement-preview', rateLimit(20, 600000), json({ limit: '7mb' }), async (req, res) => {
    const { file, currency } = readDocument(req.body);
    if (activeExtractions >= 2) throw new LedgerServiceError(503, 'Document recognition is busy. Please retry shortly.');
    activeExtractions++;
    try { res.json(await localExtractor.extract(file, currency)); }
    finally { activeExtractions--; }
  });
  const handleError: ErrorRequestHandler = (error, _req, res, _next) => {
    if (error instanceof LedgerServiceError) { res.status(error.status).json({ error: error.message }); return; }
    if (error?.type === 'entity.too.large') { res.status(413).json({ error: 'Choose a document smaller than 5 MiB.' }); return; }
    if (error?.type === 'entity.parse.failed') { res.status(400).json({ error: 'Invalid JSON document request.' }); return; }
    // Never log an upstream response, credential, or document contents.
    res.status(500).json({ error: 'The ledger service could not complete the request. Please retry.' });
  };
  router.use(handleError);
  return router;
}
