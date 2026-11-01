import { Router, type Response } from 'express';
import { isSupportedCurrencyCode } from './currencies';
import { convertMinor, LedgerValidationError, validateInput } from './ledger/domain';
import {
  LEDGER_SAVE_SLOTS, LedgerSaveConflictError, mongoLedgerSaveStore,
  type LedgerSaveContent, type LedgerSaveRecord, type LedgerSaveStore, type LedgerSaveSummary,
} from './ledgerSaveStore';
import { requireSession, type AuthenticatedLocals } from './session';
import type { SessionStore, UserStore } from './userStore';

/** A save holds the whole ledger, so the cap bounds both the request body and the MongoDB document. */
export const MAX_SAVE_RECORDS = 5000;
const RECORD_FIELDS = ['id', 'input', 'amountMinor', 'fx', 'source', 'sourceFileName', 'revision', 'createdAt', 'updatedAt'];
const FX_FIELDS = ['baseCurrency', 'rate', 'rateDate', 'source', 'convertedMinor'];
// 'group' marks the member's own share imported from a group expense.
const SOURCES = new Set(['manual', 'csv', 'receipt', 'group']);
const DATE = /^\d{4}-\d{2}-\d{2}$/;
const TIMESTAMP = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?Z$/;
// Device clocks and time zones differ: a record dated "today" on the device may be tomorrow in UTC.
const DATE_TOLERANCE_MS = 36 * 60 * 60 * 1000;

class SaveInputError extends Error {}

function text(value: unknown, field: string, max: number, pattern?: RegExp): string {
  if (typeof value !== 'string' || value.length > max || (pattern && !pattern.test(value))) throw new SaveInputError(`Saved transaction has an invalid ${field}.`);
  return value;
}

function hasOnly(value: unknown, fields: string[]): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value) && Object.keys(value).every(key => fields.includes(key));
}

function saveRecord(value: unknown, baseCurrency: string, today: Date): LedgerSaveRecord {
  if (!hasOnly(value, RECORD_FIELDS) || !hasOnly(value.fx, FX_FIELDS)) throw new SaveInputError('Saved transactions have unsupported fields.');
  let input;
  try { input = validateInput(value.input, today); }
  catch (error) {
    if (error instanceof LedgerValidationError) throw new SaveInputError(`A saved transaction is invalid: ${error.message}`);
    throw error;
  }
  const fx = value.fx;
  const rate = text(fx.rate, 'exchange rate', 32);
  if (fx.baseCurrency !== baseCurrency) throw new SaveInputError('Every saved transaction must use the save\'s reporting currency.');
  if (value.amountMinor !== input.amountMinor) throw new SaveInputError('A saved transaction amount does not match its minor units.');
  let converted;
  try { converted = convertMinor(input.amountMinor, input.currency, baseCurrency, rate); }
  catch (error) {
    if (error instanceof LedgerValidationError) throw new SaveInputError('A saved transaction has an invalid exchange rate.');
    throw error;
  }
  if (fx.convertedMinor !== converted) throw new SaveInputError('A saved transaction conversion does not match its exchange rate.');
  if (typeof value.source !== 'string' || !SOURCES.has(value.source)) throw new SaveInputError('Saved transaction has an invalid source.');
  if (!Number.isSafeInteger(value.revision) || (value.revision as number) < 1) throw new SaveInputError('Saved transaction has an invalid revision.');
  return {
    id: text(value.id, 'id', 64, /^[A-Za-z0-9-]+$/),
    input: { kind: input.kind, amount: input.amount, currency: input.currency, date: input.date,
      merchant: input.merchant, category: input.category, notes: input.notes },
    amountMinor: input.amountMinor,
    fx: { baseCurrency, rate, rateDate: text(fx.rateDate, 'exchange date', 10, DATE),
      source: text(fx.source, 'exchange source', 200), convertedMinor: converted },
    source: value.source,
    sourceFileName: text(value.sourceFileName ?? '', 'file name', 255),
    revision: value.revision as number,
    createdAt: text(value.createdAt, 'creation time', 40, TIMESTAMP),
    updatedAt: text(value.updatedAt, 'update time', 40, TIMESTAMP),
  };
}

function saveContent(body: unknown): { content: LedgerSaveContent; replaces: number | null } {
  if (!hasOnly(body, ['deviceName', 'baseCurrency', 'records', 'replaces'])) throw new SaveInputError('Provide a valid ledger save.');
  const { baseCurrency, records, replaces } = body;
  if (!isSupportedCurrencyCode(baseCurrency)) throw new SaveInputError('Choose a supported reporting currency.');
  if (replaces !== null && (!Number.isSafeInteger(replaces) || (replaces as number) < 1)) throw new SaveInputError('Provide the save being replaced.');
  if (!Array.isArray(records)) throw new SaveInputError('Provide the ledger transactions to save.');
  if (records.length > MAX_SAVE_RECORDS) throw new SaveInputError(`A save can hold at most ${MAX_SAVE_RECORDS} transactions.`);
  const deviceName = typeof body.deviceName === 'string' ? body.deviceName.trim().replace(/\s+/gu, ' ').slice(0, 60) : '';
  const today = new Date(Date.now() + DATE_TOLERANCE_MS);
  const saved = records.map(record => saveRecord(record, baseCurrency, today));
  if (new Set(saved.map(record => record.id)).size !== saved.length) throw new SaveInputError('Saved transaction identifiers must be unique.');
  return { content: { deviceName: deviceName || 'Android device', baseCurrency, records: saved }, replaces: replaces as number | null };
}

function slotParam(value: unknown): number | null {
  const slot = typeof value === 'string' && /^\d$/.test(value) ? Number(value) : NaN;
  return (LEDGER_SAVE_SLOTS as readonly number[]).includes(slot) ? slot : null;
}

function summaryJson(save: LedgerSaveSummary) {
  return { slot: save.slot, revision: save.revision, savedAt: save.savedAt.toISOString(), deviceName: save.deviceName,
    baseCurrency: save.baseCurrency, recordCount: save.recordCount };
}

function conflict(res: Response) {
  res.status(409).json({ error: 'This save slot changed on another device. Review the save slots and try again.' });
}

/** Three manual cloud save slots per account; each slot holds one complete ledger snapshot. */
export function ledgerSavesRouter(users: UserStore, sessions: SessionStore, store: LedgerSaveStore = mongoLedgerSaveStore): Router {
  const router = Router();
  router.use(requireSession(users, sessions), (_req, res, next) => { res.setHeader('Cache-Control', 'no-store'); next(); });
  router.get('/', async (_req, res) => {
    const { user } = res.locals as AuthenticatedLocals;
    res.json({ slots: (await store.list(user.id)).map(summaryJson) });
  });
  router.get('/:slot', async (req, res) => {
    const slot = slotParam(req.params.slot);
    if (slot === null) { res.status(400).json({ error: 'Choose save slot 1, 2 or 3.' }); return; }
    const { user } = res.locals as AuthenticatedLocals;
    const save = await store.find(user.id, slot);
    if (!save) { res.status(404).json({ error: 'This save slot is empty.' }); return; }
    res.json({ save: { ...summaryJson(save), records: save.records } });
  });
  router.put('/:slot', async (req, res) => {
    const slot = slotParam(req.params.slot);
    if (slot === null) { res.status(400).json({ error: 'Choose save slot 1, 2 or 3.' }); return; }
    let input;
    try { input = saveContent(req.body); }
    catch (error) {
      if (!(error instanceof SaveInputError)) throw error;
      res.status(400).json({ error: error.message }); return;
    }
    const { user } = res.locals as AuthenticatedLocals;
    try { res.json({ save: summaryJson(await store.put(user.id, slot, input.content, input.replaces)) }); }
    catch (error) {
      if (!(error instanceof LedgerSaveConflictError)) throw error;
      conflict(res);
    }
  });
  router.delete('/:slot', async (req, res) => {
    const slot = slotParam(req.params.slot);
    const revision = typeof req.query.revision === 'string' && /^\d{1,15}$/.test(req.query.revision) ? Number(req.query.revision) : 0;
    if (slot === null || revision < 1) { res.status(400).json({ error: 'Choose a save slot and the save to delete.' }); return; }
    const { user } = res.locals as AuthenticatedLocals;
    try { await store.delete(user.id, slot, revision); res.status(204).end(); }
    catch (error) {
      if (!(error instanceof LedgerSaveConflictError)) throw error;
      conflict(res);
    }
  });
  return router;
}
