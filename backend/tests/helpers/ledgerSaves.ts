import { LedgerSaveConflictError, type LedgerSave, type LedgerSaveRecord, type LedgerSaveStore } from '../../src/ledgerSaveStore';

/** In-memory stand-in for the MongoDB ledger save collection, with the same revision checks. */
export function memoryLedgerSaves() {
  const saves = new Map<string, LedgerSave>();
  const key = (userId: string, slot: number) => `${userId}:${slot}`;
  const summary = ({ records: _records, ...rest }: LedgerSave) => ({ ...rest });
  const ledgerSaves: LedgerSaveStore = {
    async list(userId) {
      return [...saves.entries()].filter(([id]) => id.startsWith(`${userId}:`)).map(([, save]) => summary(save))
        .sort((a, b) => a.slot - b.slot);
    },
    async find(userId, slot) { return saves.get(key(userId, slot)) ?? null; },
    async put(userId, slot, content, expectedRevision) {
      const existing = saves.get(key(userId, slot));
      if ((existing?.revision ?? null) !== expectedRevision) throw new LedgerSaveConflictError();
      const save = { slot, revision: (existing?.revision ?? 0) + 1, savedAt: new Date(), recordCount: content.records.length, ...content };
      saves.set(key(userId, slot), save);
      return summary(save);
    },
    async delete(userId, slot, expectedRevision) {
      const existing = saves.get(key(userId, slot));
      if (existing && existing.revision !== expectedRevision) throw new LedgerSaveConflictError();
      saves.delete(key(userId, slot));
    },
  };
  return { ledgerSaves, saves };
}

/** A valid saved USD expense reported in CAD: 12.50 USD x 1.36 = 17.00 CAD. */
export function savedRecord(overrides: Partial<LedgerSaveRecord> = {}): LedgerSaveRecord {
  return {
    id: '7b0c2f3e-4d55-4b1a-9a43-0a6c2d7e9f10',
    input: { kind: 'expense', amount: '12.50', currency: 'USD', date: '2026-10-01', merchant: 'Corner Cafe', category: 'Food', notes: '' },
    amountMinor: 1250,
    fx: { baseCurrency: 'CAD', rate: '1.36', rateDate: '2026-10-01', source: 'Frankfurter (ECB)', convertedMinor: 1700 },
    source: 'manual', sourceFileName: '', revision: 1,
    createdAt: '2026-10-01T12:00:00.123456Z', updatedAt: '2026-10-01T12:00:00Z',
    ...overrides,
  };
}
