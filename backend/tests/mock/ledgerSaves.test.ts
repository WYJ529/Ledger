import request from 'supertest';
import { createApp } from '../../src/app';
import { startSession } from '../../src/session';
import { MAX_SAVE_RECORDS } from '../../src/ledgerSaves';
import * as money from '../../src/money';
import * as domain from '../../src/ledger/domain';
import { LedgerSaveModel } from '../../src/ledgerSaveStore';
import { memoryStores } from '../helpers/memoryStores';
import { memoryLedgerSaves, savedRecord } from '../helpers/ledgerSaves';

// Mocked components: the MongoDB ledger save store and user/session stores are in-memory,
// with individual methods replaced to inject failures.
async function setup() {
  const stores = memoryStores();
  const { ledgerSaves, saves } = memoryLedgerSaves();
  const app = createApp({ ...stores, ledgerSaves });
  const owner = await stores.users.create({ email: 'saver@example.com', displayName: 'Saver' });
  const other = await stores.users.create({ email: 'other@example.com', displayName: 'Other' });
  const auth = `Bearer ${(await startSession(stores.sessions, owner)).token}`;
  const otherAuth = `Bearer ${(await startSession(stores.sessions, other)).token}`;
  return { app, auth, otherAuth, saves, ledgerSaves };
}

const body = (overrides: Record<string, unknown> = {}) =>
  ({ deviceName: '  Pixel   9 ', baseCurrency: 'CAD', records: [savedRecord()], replaces: null, ...overrides });
const record = savedRecord();
function silenceErrors() { return jest.spyOn(console, 'error').mockImplementation(() => undefined); }
afterEach(() => jest.restoreAllMocks());

// Interface GET /api/ledger-saves
describe('Mocked: GET /api/ledger-saves', () => {
  // Input: a storage provider returns an older summary without a name.
  // Expected status code: 200.
  // Expected behavior: retain the slot and normalize its missing display name.
  // Expected output: slot 2 with an empty name.
  test('lists unnamed summaries from older stores', async () => {
    const { app, auth, ledgerSaves } = await setup();
    jest.spyOn(ledgerSaves, 'list').mockResolvedValueOnce([{
      slot: 2, revision: 1, savedAt: new Date(), deviceName: 'Pixel', baseCurrency: 'CAD', recordCount: 0,
    }]);
    const result = await request(app).get('/api/ledger-saves').set('Authorization', auth);
    expect(result.status).toBe(200);
    expect(result.body.slots).toEqual([expect.objectContaining({ slot: 2, name: '' })]);
  });


  // Input: an authenticated legacy store returns an invalid MongoDB account ID.
  // Expected status code: 200 for the list and 404 for a single slot.
  // Expected behavior: keep any other user's slots private and skip invalid database queries.
  // Expected output: empty slot list and empty-slot diagnostic.
  test('treats an incompatible account identifier as having no stored ledger', async () => {
    const stores = memoryStores();
    const user = await stores.users.create({ displayName: 'Legacy' });
    const { token } = await startSession(stores.sessions, user);
    const app = createApp(stores);
    expect((await request(app).get('/api/ledger-saves').auth(token, { type: 'bearer' })).body).toEqual({ slots: [] });
    expect((await request(app).get('/api/ledger-saves/1').auth(token, { type: 'bearer' })).status).toBe(404);
  });

  // Input: list with no saves, then after saving slot 2
  // Expected status code: 200
  // Expected behavior: only summaries are listed, never transaction contents
  // Expected output: { slots: [] }, then { slots: [summary of slot 2] }
  test('Lists slot summaries without records', async () => {
    const { app, auth } = await setup();
    expect((await request(app).get('/api/ledger-saves').set('Authorization', auth)).body).toEqual({ slots: [] });
    const saved = await request(app).put('/api/ledger-saves/2').set('Authorization', auth).send(body());
    const listed = await request(app).get('/api/ledger-saves').set('Authorization', auth);
    expect(listed.body.slots).toEqual([saved.body.save]);
    expect(JSON.stringify(listed.body)).not.toContain('Corner Cafe');
  });

  // Input: list from another account after the owner saved slot 1
  // Expected status code: 200
  // Expected behavior: saves are private to their account
  // Expected output: { slots: [] }
  test('Keeps every account\'s slots private', async () => {
    const { app, auth, otherAuth } = await setup();
    await request(app).put('/api/ledger-saves/1').set('Authorization', auth).send(body());
    expect((await request(app).get('/api/ledger-saves').set('Authorization', otherAuth)).body).toEqual({ slots: [] });
  });

  // Input: list without a session
  // Expected status code: 401
  // Expected behavior: the store is not read
  // Expected output: Bearer challenge
  test('Requires a session', async () => {
    const { app } = await setup();
    expect((await request(app).get('/api/ledger-saves')).status).toBe(401);
  });

  // Input: valid list; mock: the save store's list throws
  // Expected status code: 500
  // Expected behavior: an empty list is never reported in place of a failure
  // Expected output: { error: "Something went wrong. Please try again." }
  test('Reports a store failure instead of an empty list', async () => {
    const { app, auth, ledgerSaves } = await setup();
    silenceErrors();
    ledgerSaves.list = () => Promise.reject(new Error('Database unavailable'));
    const res = await request(app).get('/api/ledger-saves').set('Authorization', auth);
    expect(res.status).toBe(500);
    expect(res.body).not.toHaveProperty('slots');
  });
});

// Interface GET /api/ledger-saves/:slot
describe('Mocked: GET /api/ledger-saves/:slot', () => {
  // Input: restore slot 2 after saving one record
  // Expected status code: 200
  // Expected behavior: the save is returned unchanged
  // Expected output: { save } equal to the summary plus the saved records
  test('Restores a saved ledger unchanged', async () => {
    const { app, auth } = await setup();
    const saved = await request(app).put('/api/ledger-saves/2').set('Authorization', auth).send(body());
    const restored = await request(app).get('/api/ledger-saves/2').set('Authorization', auth);
    expect(restored.status).toBe(200);
    expect(restored.body.save).toEqual({ ...saved.body.save, records: [savedRecord()] });
  });

  // Input: restore of the owner's slot from another account
  // Expected status code: 404
  // Expected behavior: another account's save is not disclosed
  // Expected output: error message
  test('Hides another account\'s save', async () => {
    const { app, auth, otherAuth } = await setup();
    await request(app).put('/api/ledger-saves/1').set('Authorization', auth).send(body());
    expect((await request(app).get('/api/ledger-saves/1').set('Authorization', otherAuth)).status).toBe(404);
  });

  // Input: slot 0, 4, "one", "1.0" or 11
  // Expected status code: 400
  // Expected behavior: only slots 1–3 exist
  // Expected output: error message
  test.each(['0', '4', 'one', '1.0', '11'])('Rejects slot %s', async slot => {
    const { app, auth } = await setup();
    expect((await request(app).get(`/api/ledger-saves/${slot}`).set('Authorization', auth)).status).toBe(400);
  });

  // Input: valid restore; mock: the save store's find throws
  // Expected status code: 500
  // Expected behavior: no records are returned
  // Expected output: { error: "Something went wrong. Please try again." }
  test('Reports a store failure', async () => {
    const { app, auth, ledgerSaves } = await setup();
    silenceErrors();
    ledgerSaves.find = () => Promise.reject(new Error('Database unavailable'));
    expect((await request(app).get('/api/ledger-saves/1').set('Authorization', auth)).status).toBe(500);
  });
});

// Interface PUT /api/ledger-saves/:slot
describe('Mocked: PUT /api/ledger-saves/:slot', () => {

  // Input: a valid save while MongoDB reports an unexpected insert failure.
  // Expected status code: 500.
  // Expected behavior: propagate the storage failure and withhold the save acknowledgement.
  // Expected output: generic error without a save.
  test('reports a failed MongoDB insert', async () => {
    silenceErrors();
    const stores = memoryStores();
    const user = await stores.users.create({ displayName: 'Saver' });
    const { token } = await startSession(stores.sessions, user);
    jest.spyOn(LedgerSaveModel, 'create').mockRejectedValueOnce(new Error('database unavailable'));
    const response = await request(createApp(stores)).put('/api/ledger-saves/1').auth(token, { type: 'bearer' }).send(body());
    expect(response.status).toBe(500);
    expect(response.body).not.toHaveProperty('save');
  });


  // Input: a valid save while an amount parser, formatter or currency conversion fails unexpectedly.
  // Expected status code: 500.
  // Expected behavior: propagate an internal failure and never write a partial save.
  // Expected output: generic error and an empty store.
  test.each(['parse', 'format', 'convert'])('preserves the slot on an unexpected %s error', async step => {
    silenceErrors();
    const { app, auth, saves } = await setup();
    const failure = () => { throw new Error('internal amount service error'); };
    if (step === 'parse') jest.spyOn(money, 'parseMoney').mockImplementationOnce(failure);
    else if (step === 'format') jest.spyOn(money, 'formatMinorUnits').mockImplementationOnce(failure);
    else jest.spyOn(domain, 'convertMinor').mockImplementationOnce(failure);
    const response = await request(app).put('/api/ledger-saves/1').set('Authorization', auth).send(body());
    expect(response.status).toBe(500);
    expect(response.body.error).not.toContain('internal amount');
    expect(saves.size).toBe(0);
  });

  // Input: save without a session
  // Expected status code: 401
  // Expected behavior: nothing is stored
  // Expected output: Bearer challenge
  test('Requires a session', async () => {
    const { app, saves } = await setup();
    expect((await request(app).put('/api/ledger-saves/1').send(body())).status).toBe(401);
    expect(saves.size).toBe(0);
  });

  // Input: one-record ledger saved to an empty slot 2
  // Expected status code: 200
  // Expected behavior: the device name is normalized and revision 1 is stored
  // Expected output: { save } with slot 2, revision 1, deviceName "Pixel 9" and recordCount 1
  test('Saves a complete ledger to an empty slot', async () => {
    const { app, auth } = await setup();
    const saved = await request(app).put('/api/ledger-saves/2').set('Authorization', auth).send(body());
    expect(saved.status).toBe(200);
    expect(saved.headers['cache-control']).toBe('no-store');
    expect(saved.body.save).toMatchObject({ slot: 2, revision: 1, deviceName: 'Pixel 9', baseCurrency: 'CAD', recordCount: 1 });
  });

  // Input: an empty ledger, then a record with untidy merchant and lower-case category and no device name
  // Expected status code: 200
  // Expected behavior: text is normalized and a default device name is used
  // Expected output: recordCount 0; restored merchant "Corner Cafe", category "Food", deviceName "Android device"
  test('Saves an empty ledger and normalizes transaction text', async () => {
    const { app, auth } = await setup();
    expect((await request(app).put('/api/ledger-saves/1').set('Authorization', auth).send(body({ records: [] }))).body.save.recordCount).toBe(0);
    const messy = savedRecord({ input: { ...savedRecord().input, merchant: '  Corner   Cafe ', category: 'food' } });
    const saved = await request(app).put('/api/ledger-saves/3').set('Authorization', auth).send(body({ records: [messy], deviceName: '' }));
    expect(saved.body.save.deviceName).toBe('Android device');
    const restored = await request(app).get('/api/ledger-saves/3').set('Authorization', auth);
    expect(restored.body.save.records[0].input).toMatchObject({ merchant: 'Corner Cafe', category: 'Food' });
  });

  // Input: a ledger containing a share imported from a group
  // Expected status code: 200
  // Expected behavior: group-sourced records are kept
  // Expected output: restored records equal the imported record
  test('Keeps shares imported from groups', async () => {
    const { app, auth } = await setup();
    const imported = savedRecord({ source: 'group', sourceFileName: 'Ski trip' });
    expect((await request(app).put('/api/ledger-saves/1').set('Authorization', auth).send(body({ records: [imported] }))).status).toBe(200);
    expect((await request(app).get('/api/ledger-saves/1').set('Authorization', auth)).body.save.records).toEqual([imported]);
  });

  // Input: a save to a filled slot claiming it is empty, an overwrite of revision 1, then a stale overwrite
  // Expected status code: 409, 200, 409
  // Expected behavior: a slot is overwritten only when the device saw its current revision
  // Expected output: { save } at revision 2 with recordCount 0
  test('Overwrites only the current revision', async () => {
    const { app, auth } = await setup();
    await request(app).put('/api/ledger-saves/1').set('Authorization', auth).send(body());
    expect((await request(app).put('/api/ledger-saves/1').set('Authorization', auth).send(body())).status).toBe(409);
    const replaced = await request(app).put('/api/ledger-saves/1').set('Authorization', auth).send(body({ records: [], replaces: 1 }));
    expect(replaced.body.save).toMatchObject({ revision: 2, recordCount: 0 });
    expect((await request(app).put('/api/ledger-saves/1').set('Authorization', auth).send(body({ replaces: 1 }))).status).toBe(409);
    expect((await request(app).get('/api/ledger-saves/1').set('Authorization', auth)).body.save.records).toEqual([]);
  });

  // Input: slot 0, 4, "one", "1.0" or 11
  // Expected status code: 400
  // Expected behavior: nothing is stored
  // Expected output: error message
  test.each(['0', '4', 'one', '1.0', '11'])('Rejects slot %s', async slot => {
    const { app, auth, saves } = await setup();
    expect((await request(app).put(`/api/ledger-saves/${slot}`).set('Authorization', auth).send(body())).status).toBe(400);
    expect(saves.size).toBe(0);
  });

  // Input: an invalid body or record (see the case name)
  // Expected status code: 400
  // Expected behavior: the slot stays empty
  // Expected output: { error } as a string
  test.each([
    ['an unknown body field', body({ owner: 'someone' })],
    ['an unsupported reporting currency', body({ baseCurrency: 'XYZ' })],
    ['a missing record list', body({ records: undefined })],
    ['an invalid replaced revision', body({ replaces: 0 })],
    ['an unknown record field', body({ records: [{ ...record, extra: true }] })],
    ['an invalid transaction', body({ records: [savedRecord({ input: { ...record.input, kind: 'transfer' } })] })],
    ['a future transaction date', body({ records: [savedRecord({ input: { ...record.input, date: '2999-01-01' } })] })],
    ['mismatched minor units', body({ records: [savedRecord({ amountMinor: 1251 })] })],
    ['a different reporting currency', body({ records: [savedRecord({ fx: { ...record.fx, baseCurrency: 'USD' } })] })],
    ['a wrong conversion', body({ records: [savedRecord({ fx: { ...record.fx, convertedMinor: 1699 } })] })],
    ['an invalid rate', body({ records: [savedRecord({ fx: { ...record.fx, rate: '-1' } })] })],
    ['an unknown source', body({ records: [savedRecord({ source: 'bank' })] })],
    ['an invalid revision', body({ records: [savedRecord({ revision: 0 })] })],
    ['an invalid timestamp', body({ records: [savedRecord({ createdAt: 'yesterday' })] })],
    ['duplicate identifiers', body({ records: [record, record] })],
    ['too many transactions', body({ records: Array.from({ length: MAX_SAVE_RECORDS + 1 }, () => record) })],
  ])('Rejects a save with %s', async (_case, payload) => {
    const { app, auth, saves } = await setup();
    const res = await request(app).put('/api/ledger-saves/1').set('Authorization', auth).send(payload);
    expect(res.status).toBe(400);
    expect(typeof res.body.error).toBe('string');
    expect(saves.size).toBe(0);
  });

  // Input: valid save; mock: the save store's put throws
  // Expected status code: 500
  // Expected behavior: success is not reported and nothing is stored
  // Expected output: { error: "Something went wrong. Please try again." }
  test('Reports a store failure', async () => {
    const { app, auth, ledgerSaves, saves } = await setup();
    silenceErrors();
    ledgerSaves.put = () => Promise.reject(new Error('Database unavailable'));
    const res = await request(app).put('/api/ledger-saves/1').set('Authorization', auth).send(body());
    expect(res.status).toBe(500);
    expect(res.body).not.toHaveProperty('save');
    expect(saves.size).toBe(0);
  });
});

// Interface PATCH /api/ledger-saves/:slot
describe('Mocked: PATCH /api/ledger-saves/:slot', () => {

  // Input: a valid rename against an older store without a rename method.
  // Expected status code: 500.
  // Expected behavior: keep the original slot intact.
  // Expected output: generic failure and unchanged name and revision.
  test('leaves the slot intact when the store lacks rename support', async () => {
    silenceErrors();
    const { app, auth, ledgerSaves } = await setup();
    const saved = await request(app).put('/api/ledger-saves/1').set('Authorization', auth).send(body());
    delete ledgerSaves.rename;
    const response = await request(app).patch('/api/ledger-saves/1').set('Authorization', auth).send({ name: 'Trip', revision: 1 });
    expect(response.status).toBe(500);
    expect((await request(app).get('/api/ledger-saves').set('Authorization', auth)).body.slots).toEqual([saved.body.save]);
  });

  // Input: rename revision 1 of slot 1 to " Trip "
  // Expected status code: 200
  // Expected behavior: the trimmed name is stored and the revision advances; records are kept
  // Expected output: { save } with name "Trip" and revision 2
  test('Renames the current revision', async () => {
    const { app, auth } = await setup();
    await request(app).put('/api/ledger-saves/1').set('Authorization', auth).send(body());
    const renamed = await request(app).patch('/api/ledger-saves/1').set('Authorization', auth).send({ name: ' Trip ', revision: 1 });
    expect(renamed.status).toBe(200);
    expect(renamed.body.save).toMatchObject({ name: 'Trip', revision: 2, recordCount: 1 });
    expect((await request(app).get('/api/ledger-saves/1').set('Authorization', auth)).body.save.records).toEqual([savedRecord()]);
  });

  // Input: missing name, unknown field, blank name, missing or zero revision
  // Expected status code: 400
  // Expected behavior: the save is unchanged
  // Expected output: error message
  test.each([{ revision: 1 }, { name: 'Trip', revision: 1, owner: 'x' }, { name: ' ', revision: 1 }, { name: 'Trip' }, { name: 'Trip', revision: 0 }])(
    'Rejects invalid rename %#', async payload => {
      const { app, auth } = await setup();
      await request(app).put('/api/ledger-saves/1').set('Authorization', auth).send(body());
      expect((await request(app).patch('/api/ledger-saves/1').set('Authorization', auth).send(payload)).status).toBe(400);
      expect((await request(app).get('/api/ledger-saves').set('Authorization', auth)).body.slots[0].revision).toBe(1);
    });

  // Input: rename with a stale revision, and from another account
  // Expected status code: 409
  // Expected behavior: the save is unchanged
  // Expected output: conflict error
  test('Rejects stale and foreign renames', async () => {
    const { app, auth, otherAuth } = await setup();
    await request(app).put('/api/ledger-saves/1').set('Authorization', auth).send(body());
    await request(app).put('/api/ledger-saves/1').set('Authorization', auth).send(body({ replaces: 1 }));
    expect((await request(app).patch('/api/ledger-saves/1').set('Authorization', auth).send({ name: 'Stale', revision: 1 })).status).toBe(409);
    expect((await request(app).patch('/api/ledger-saves/1').set('Authorization', otherAuth).send({ name: 'Foreign', revision: 2 })).status).toBe(409);
  });

  // Input: valid rename; mock: the save store's rename throws
  // Expected status code: 500
  // Expected behavior: success is not reported
  // Expected output: { error: "Something went wrong. Please try again." }
  test('Reports a store failure', async () => {
    const { app, auth, ledgerSaves } = await setup();
    await request(app).put('/api/ledger-saves/1').set('Authorization', auth).send(body());
    silenceErrors();
    ledgerSaves.rename = () => Promise.reject(new Error('Database unavailable'));
    expect((await request(app).patch('/api/ledger-saves/1').set('Authorization', auth).send({ name: 'Trip', revision: 1 })).status).toBe(500);
  });
});

// Interface DELETE /api/ledger-saves/:slot
describe('Mocked: DELETE /api/ledger-saves/:slot', () => {
  // Input: delete without a session
  // Expected status code: 401
  // Expected behavior: nothing is deleted
  // Expected output: Bearer challenge
  test('Requires a session', async () => {
    const { app } = await setup();
    expect((await request(app).delete('/api/ledger-saves/1?revision=1')).status).toBe(401);
  });

  // Input: delete at a stale revision, at the current revision, and again
  // Expected status code: 409, 204, 204
  // Expected behavior: only the listed revision is deleted; repeating is harmless
  // Expected output: empty body; a later restore returns 404
  test('Deletes only at the listed revision', async () => {
    const { app, auth } = await setup();
    await request(app).put('/api/ledger-saves/1').set('Authorization', auth).send(body());
    await request(app).put('/api/ledger-saves/1').set('Authorization', auth).send(body({ replaces: 1 }));
    expect((await request(app).delete('/api/ledger-saves/1?revision=1').set('Authorization', auth)).status).toBe(409);
    expect((await request(app).delete('/api/ledger-saves/1?revision=2').set('Authorization', auth)).status).toBe(204);
    expect((await request(app).delete('/api/ledger-saves/1?revision=2').set('Authorization', auth)).status).toBe(204);
    expect((await request(app).get('/api/ledger-saves/1').set('Authorization', auth)).status).toBe(404);
  });

  // Input: delete of the owner's slot from another account
  // Expected status code: 204
  // Expected behavior: the request affects only the caller's own (empty) slot; the owner's save remains
  // Expected output: the owner can still restore the save
  test('Cannot delete another account\'s save', async () => {
    const { app, auth, otherAuth } = await setup();
    await request(app).put('/api/ledger-saves/1').set('Authorization', auth).send(body());
    expect((await request(app).delete('/api/ledger-saves/1?revision=1').set('Authorization', otherAuth)).status).toBe(204);
    expect((await request(app).get('/api/ledger-saves/1').set('Authorization', auth)).status).toBe(200);
  });

  // Input: delete with no, zero or non-numeric revision
  // Expected status code: 400
  // Expected behavior: nothing is deleted
  // Expected output: error message
  test.each(['', '?revision=0', '?revision=abc'])('Rejects a delete without a valid revision (%p)', async query => {
    const { app, auth } = await setup();
    expect((await request(app).delete(`/api/ledger-saves/1${query}`).set('Authorization', auth)).status).toBe(400);
  });

  // Input: valid delete; mock: the save store's delete throws
  // Expected status code: 500
  // Expected behavior: the save remains and deletion is not reported
  // Expected output: { error: "Something went wrong. Please try again." }
  test('Reports a store failure', async () => {
    const { app, auth, ledgerSaves, saves } = await setup();
    await request(app).put('/api/ledger-saves/1').set('Authorization', auth).send(body());
    silenceErrors();
    ledgerSaves.delete = () => Promise.reject(new Error('Database unavailable'));
    expect((await request(app).delete('/api/ledger-saves/1?revision=1').set('Authorization', auth)).status).toBe(500);
    expect(saves.size).toBe(1);
  });
});
