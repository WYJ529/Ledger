import { useIsolatedMongo } from '../helpers/isolatedMongo';
import { requiredString } from '../helpers/required';
import request from 'supertest';
import { createApp } from '../../src/app';
import { LedgerSaveModel, mongoLedgerSaveStore } from '../../src/ledgerSaveStore';
import { startSession } from '../../src/session';
import { mongoSessionStore, mongoUserStore, SessionModel, UserModel } from '../../src/userStore';
import { savedRecord } from '../helpers/ledgerSaves';
import { testsRejectInvalidSessions } from '../helpers/sessionRejection';

// Real routing, sessions and MongoDB ledger save collection. No mocked components.
const app = createApp();
let auth = '';

useIsolatedMongo('ledger-save-tests', [LedgerSaveModel, UserModel, SessionModel]);

beforeEach(async () => {
  const user = await mongoUserStore.create({ email: 'saver@example.com', displayName: 'Saver' });
  auth = `Bearer ${(await startSession(mongoSessionStore, user)).token}`;
});

const body = (replaces: number | null, records = [savedRecord()]) => ({ deviceName: 'Pixel 9', baseCurrency: 'CAD', records, replaces });
const put = (slot: number, payload: object, token = auth): request.Test => request(app).put(`/api/ledger-saves/${slot}`).set('Authorization', token).send(payload);
const savedSlots = () => LedgerSaveModel.find().sort({ _id: 1 }).lean();

async function otherAccount() {
  const other = await mongoUserStore.create({ email: 'other@example.com', displayName: 'Other' });
  return `Bearer ${(await startSession(mongoSessionStore, other)).token}`;
}

// Interface GET /api/ledger-saves
describe('Unmocked: GET /api/ledger-saves', () => {
  testsRejectInvalidSessions((): request.Test => request(app).get('/api/ledger-saves'), () => auth, savedSlots);

  // Input: list after saving slot 3, then list from another account
  // Expected status code: 200
  // Expected behavior: lists only this account's slot summaries, never transaction records
  // Expected output: { slots: [summary] } for the owner and { slots: [] } for the other account
  test('Lists slot summaries for the signed-in account only', async () => {
    const saved = await put(3, body(null));
    const listed = await request(app).get('/api/ledger-saves').set('Authorization', auth);
    expect(listed.status).toBe(200);
    expect(listed.body.slots).toEqual([saved.body.save]);
    expect(JSON.stringify(listed.body)).not.toContain('Corner Cafe');
    expect((await request(app).get('/api/ledger-saves').set('Authorization', await otherAccount())).body).toEqual({ slots: [] });
  });
});

// Interface GET /api/ledger-saves/:slot
describe('Unmocked: GET /api/ledger-saves/:slot', () => {

  // Input: stored slot from a client that omitted its optional name and records.
  // Expected status code: 200.
  // Expected behavior: restore an empty ledger with the original revision.
  // Expected output: empty name and records.
  test('restores an older empty slot', async () => {
    const owner = await UserModel.findOne().orFail();
    const summary = await mongoLedgerSaveStore.put(requiredString(owner.id), 1, { deviceName: 'Pixel', baseCurrency: 'CAD', records: [] }, null);
    expect(summary.name).toBe('');
    await LedgerSaveModel.updateOne({ slot: 1 }, { $unset: { name: '', records: '' } });
    const response = await request(app).get('/api/ledger-saves/1').set('Authorization', auth);
    expect(response.status).toBe(200);
    expect(response.body.save).toMatchObject({ name: '', records: [], revision: 1 });
  });

  testsRejectInvalidSessions((): request.Test => request(app).get('/api/ledger-saves/1'), () => auth, savedSlots);

  // Input: restore slot 3 after saving one record, and an empty slot 2
  // Expected status code: 200 for slot 3, 404 for slot 2
  // Expected behavior: the stored records are returned exactly as saved
  // Expected output: { save } containing records equal to the saved record
  test('Restores the saved records', async () => {
    await put(3, body(null));
    const restored = await request(app).get('/api/ledger-saves/3').set('Authorization', auth);
    expect(restored.status).toBe(200);
    expect(restored.body.save.records).toEqual([savedRecord()]);
    expect((await request(app).get('/api/ledger-saves/2').set('Authorization', auth)).status).toBe(404);
  });
});

// Interface PUT /api/ledger-saves/:slot
describe('Unmocked: PUT /api/ledger-saves/:slot', () => {

  // Input: invalid nested transaction data and valid, missing optional legacy fields.
  // Expected status code: 400 for invalid records and 200 for the legacy save.
  // Expected behavior: reject an entire invalid save and supply defaults for optional fields.
  // Expected output: the specific validation error or the normalized, persisted save.
  test('validates transaction details and normalizes old device records', async () => {
    const record = savedRecord();
    for (const [input, error] of [
      [null, 'object'], [{ ...record.input, extra: '' }, 'Unsupported'],
      [{ ...record.input, merchant: 5 }, 'must be text'], [{ ...record.input, currency: 'ZZZ' }, 'Currency'],
      [{ ...record.input, merchant: 'x'.repeat(121) }, '120 characters'],
      [{ ...record.input, notes: 'x'.repeat(1001) }, '1000 characters'],
    ] as const) {
      const response = await put(1, { ...body(null), records: [{ ...record, input }] });
      expect(response.status).toBe(400);
      expect(response.body.error).toContain(error);
    }
    for (const rate of ['0', '9999999999999999999999']) {
      const response = await put(1, { ...body(null), records: [{ ...record, fx: { ...record.fx, rate } }] });
      expect(response.status).toBe(400);
      expect(response.body.error).toContain('exchange rate');
    }
    expect(await LedgerSaveModel.countDocuments()).toBe(0);
    const response = await put(1, { ...body(null), deviceName: undefined, records: [
      { ...record, input: { ...record.input, notes: undefined }, sourceFileName: undefined },
    ] });
    expect(response.status).toBe(200);
    expect(response.body.save.deviceName).toBe('Android device');
    const restored = await request(app).get('/api/ledger-saves/1').set('Authorization', auth);
    expect(restored.body.save.records).toEqual([record]);
  });

  testsRejectInvalidSessions((): request.Test => request(app).put('/api/ledger-saves/1').send(body(null)), () => auth, savedSlots);

  // Input: save to an empty slot without a name, then overwrite revision 1 with no records
  // Expected status code: 200 for both
  // Expected behavior: a nameless save gets an empty name; overwriting advances the revision
  // Expected output: { save } with revision 1 and name "", then revision 2 and recordCount 0
  test('Stores and overwrites a slot', async () => {
    const saved = await put(3, body(null));
    expect(saved.status).toBe(200);
    expect(saved.body.save).toMatchObject({ slot: 3, revision: 1, name: '', recordCount: 1 });
    const replaced = await put(3, body(1, []));
    expect(replaced.body.save).toMatchObject({ revision: 2, recordCount: 0 });
  });

  // Input: save name with surrounding spaces
  // Expected status code: 200
  // Expected behavior: the name is trimmed before it is stored
  // Expected output: { save } with name "Trip"
  test('Trims a supplied save name', async () => {
    const saved = await put(1, { ...body(null), name: '  Trip  ' });
    expect(saved.status).toBe(200);
    expect(saved.body.save.name).toBe('Trip');
  });

  // Input: empty, blank, 41-character, numeric or null save name
  // Expected status code: 400
  // Expected behavior: nothing is stored
  // Expected output: error message
  test.each(['', '  ', 'x'.repeat(41), 42, null])('Rejects invalid supplied save name %p', async name => {
    expect((await put(1, { ...body(null), name })).status).toBe(400);
    expect(await LedgerSaveModel.countDocuments()).toBe(0);
  });

  // Input: two devices fill the same empty slot at the same time
  // Expected status code: one 200 and one 409
  // Expected behavior: the conditional insert admits exactly one save
  // Expected output: { save } for the winner; conflict error for the other
  test('Lets exactly one of two devices fill the same empty slot', async () => {
    const results = await Promise.all([put(1, body(null)), put(1, body(null))]);
    expect(results.map(({ status }: { status: number }) => status).sort()).toEqual([200, 409]);
    expect(await LedgerSaveModel.countDocuments({ slot: 1 })).toBe(1);
  });

  // Input: two devices overwrite revision 1 of the same slot at the same time
  // Expected status code: one 200 and one 409
  // Expected behavior: the revision check admits exactly one overwrite
  // Expected output: the slot is listed once at revision 2
  test('Lets exactly one of two devices overwrite the same revision', async () => {
    await put(2, body(null));
    const results = await Promise.all([put(2, body(1, [])), put(2, body(1, [savedRecord()]))]);
    expect(results.map(({ status }: { status: number }) => status).sort()).toEqual([200, 409]);
    const listed = await request(app).get('/api/ledger-saves').set('Authorization', auth);
    expect(listed.body.slots).toEqual([expect.objectContaining({ slot: 2, revision: 2 })]);
  });
});

// Interface PATCH /api/ledger-saves/:slot
describe('Unmocked: PATCH /api/ledger-saves/:slot', () => {
  testsRejectInvalidSessions((): request.Test => request(app).patch('/api/ledger-saves/1').send({ name: 'Trip', revision: 1 }), () => auth, savedSlots);

  // Input: rename revision 1 to " Summer ", repeat with stale revision 1, and attempt from another account
  // Expected status code: 200, then 409, then 409
  // Expected behavior: rename is atomic, trims the name, advances the revision and keeps records
  // Expected output: { save } with name "Summer" and revision 2
  test('Renames atomically and only for the owning account', async () => {
    const saved = await put(1, { ...body(null), name: 'Trip' });
    const renamed = await request(app).patch('/api/ledger-saves/1').set('Authorization', auth).send({ name: ' Summer ', revision: 1 });
    expect(renamed.status).toBe(200);
    expect(renamed.body.save).toEqual({ ...saved.body.save, name: 'Summer', revision: 2 });
    expect((await request(app).get('/api/ledger-saves/1').set('Authorization', auth)).body.save.records).toEqual([savedRecord()]);
    expect((await request(app).patch('/api/ledger-saves/1').set('Authorization', auth).send({ name: 'Stale', revision: 1 })).status).toBe(409);
    const foreign = await request(app).patch('/api/ledger-saves/1').set('Authorization', await otherAccount()).send({ name: 'Foreign', revision: 2 });
    expect(foreign.status).toBe(409);
    expect((await request(app).get('/api/ledger-saves').set('Authorization', auth)).body.slots[0].name).toBe('Summer');
  });

  // Input: 40-character name for a nameless save
  // Expected status code: 200
  // Expected behavior: the maximum length is accepted
  // Expected output: { save } with the 40-character name
  test('Accepts a 40-character name', async () => {
    await put(1, body(null));
    const res = await request(app).patch('/api/ledger-saves/1').set('Authorization', auth).send({ name: 'x'.repeat(40), revision: 1 });
    expect(res.status).toBe(200);
    expect(res.body.save.name).toBe('x'.repeat(40));
  });
});

// Interface DELETE /api/ledger-saves/:slot
describe('Unmocked: DELETE /api/ledger-saves/:slot', () => {
  testsRejectInvalidSessions((): request.Test => request(app).delete('/api/ledger-saves/1?revision=1'), () => auth, savedSlots);

  // Input: delete revision 1 of a slot that is now at revision 2, then delete revision 2
  // Expected status code: 409, then 204
  // Expected behavior: only the listed revision is deleted
  // Expected output: empty body; no save remains in MongoDB
  test('Deletes a slot only at its current revision', async () => {
    await put(3, body(null));
    await put(3, body(1, []));
    expect((await request(app).delete('/api/ledger-saves/3?revision=1').set('Authorization', auth)).status).toBe(409);
    expect((await request(app).delete('/api/ledger-saves/3?revision=2').set('Authorization', auth)).status).toBe(204);
    expect(await LedgerSaveModel.countDocuments()).toBe(0);
  });
});
