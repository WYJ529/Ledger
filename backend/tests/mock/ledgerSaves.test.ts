import request from 'supertest';
import { createApp } from '../../src/app';
import { startSession } from '../../src/session';
import { MAX_SAVE_RECORDS } from '../../src/ledgerSaves';
import { memoryStores } from '../helpers/memoryStores';
import { memoryLedgerSaves, savedRecord } from '../helpers/ledgerSaves';

async function setup() {
  const stores = memoryStores();
  const { ledgerSaves, saves } = memoryLedgerSaves();
  const app = createApp({ ...stores, ledgerSaves });
  const owner = await stores.users.create({ email: 'saver@example.com', displayName: 'Saver' });
  const other = await stores.users.create({ email: 'other@example.com', displayName: 'Other' });
  const auth = `Bearer ${(await startSession(stores.sessions, owner)).token}`;
  const otherAuth = `Bearer ${(await startSession(stores.sessions, other)).token}`;
  return { app, auth, otherAuth, saves };
}

const body = (overrides: Record<string, unknown> = {}) =>
  ({ deviceName: '  Pixel   9 ', baseCurrency: 'CAD', records: [savedRecord()], replaces: null, ...overrides });

test('requires a session for every save slot route', async () => {
  const { app } = await setup();
  for (const call of [request(app).get('/api/ledger-saves'), request(app).get('/api/ledger-saves/1'),
    request(app).put('/api/ledger-saves/1').send(body()), request(app).delete('/api/ledger-saves/1?revision=1')]) {
    expect((await call).status).toBe(401);
  }
});

test('saves a complete ledger to an empty slot and restores it unchanged', async () => {
  const { app, auth } = await setup();
  expect((await request(app).get('/api/ledger-saves').set('Authorization', auth)).body).toEqual({ slots: [] });
  const saved = await request(app).put('/api/ledger-saves/2').set('Authorization', auth).send(body());
  expect(saved.status).toBe(200);
  expect(saved.headers['cache-control']).toBe('no-store');
  expect(saved.body.save).toMatchObject({ slot: 2, revision: 1, deviceName: 'Pixel 9', baseCurrency: 'CAD', recordCount: 1 });
  const listed = await request(app).get('/api/ledger-saves').set('Authorization', auth);
  expect(listed.body.slots).toEqual([saved.body.save]);
  expect(JSON.stringify(listed.body)).not.toContain('Corner Cafe');
  const restored = await request(app).get('/api/ledger-saves/2').set('Authorization', auth);
  expect(restored.status).toBe(200);
  expect(restored.body.save).toEqual({ ...saved.body.save, records: [savedRecord()] });
});

test('saves an empty ledger and normalizes transaction text', async () => {
  const { app, auth } = await setup();
  expect((await request(app).put('/api/ledger-saves/1').set('Authorization', auth).send(body({ records: [] }))).body.save.recordCount).toBe(0);
  const messy = savedRecord({ input: { ...savedRecord().input, merchant: '  Corner   Cafe ', category: 'food' } });
  const saved = await request(app).put('/api/ledger-saves/3').set('Authorization', auth).send(body({ records: [messy], deviceName: '' }));
  expect(saved.body.save.deviceName).toBe('Android device');
  const restored = await request(app).get('/api/ledger-saves/3').set('Authorization', auth);
  expect(restored.body.save.records[0].input).toMatchObject({ merchant: 'Corner Cafe', category: 'Food' });
});

test('keeps shares imported from groups in a save', async () => {
  const { app, auth } = await setup();
  const imported = savedRecord({ source: 'group', sourceFileName: 'Ski trip' });
  expect((await request(app).put('/api/ledger-saves/1').set('Authorization', auth).send(body({ records: [imported] }))).status).toBe(200);
  expect((await request(app).get('/api/ledger-saves/1').set('Authorization', auth)).body.save.records).toEqual([imported]);
});

test('overwrites a slot only when the device saw its current revision', async () => {
  const { app, auth } = await setup();
  await request(app).put('/api/ledger-saves/1').set('Authorization', auth).send(body());
  // A second device listed the slot as empty, then the first device filled it.
  expect((await request(app).put('/api/ledger-saves/1').set('Authorization', auth).send(body())).status).toBe(409);
  const replaced = await request(app).put('/api/ledger-saves/1').set('Authorization', auth).send(body({ records: [], replaces: 1 }));
  expect(replaced.body.save).toMatchObject({ revision: 2, recordCount: 0 });
  expect((await request(app).put('/api/ledger-saves/1').set('Authorization', auth).send(body({ replaces: 1 }))).status).toBe(409);
  expect((await request(app).get('/api/ledger-saves/1').set('Authorization', auth)).body.save.records).toEqual([]);
});

test('deletes a save only at the listed revision', async () => {
  const { app, auth } = await setup();
  await request(app).put('/api/ledger-saves/1').set('Authorization', auth).send(body());
  await request(app).put('/api/ledger-saves/1').set('Authorization', auth).send(body({ replaces: 1 }));
  expect((await request(app).delete('/api/ledger-saves/1?revision=1').set('Authorization', auth)).status).toBe(409);
  expect((await request(app).delete('/api/ledger-saves/1?revision=2').set('Authorization', auth)).status).toBe(204);
  expect((await request(app).delete('/api/ledger-saves/1?revision=2').set('Authorization', auth)).status).toBe(204);
  expect((await request(app).get('/api/ledger-saves/1').set('Authorization', auth)).status).toBe(404);
});

test('keeps every account\'s save slots private', async () => {
  const { app, auth, otherAuth } = await setup();
  await request(app).put('/api/ledger-saves/1').set('Authorization', auth).send(body());
  expect((await request(app).get('/api/ledger-saves').set('Authorization', otherAuth)).body).toEqual({ slots: [] });
  expect((await request(app).get('/api/ledger-saves/1').set('Authorization', otherAuth)).status).toBe(404);
  expect((await request(app).delete('/api/ledger-saves/1?revision=1').set('Authorization', otherAuth)).status).toBe(204);
  expect((await request(app).get('/api/ledger-saves/1').set('Authorization', auth)).status).toBe(200);
});

test.each(['0', '4', 'one', '1.0', '11'])('rejects save slot %s', async slot => {
  const { app, auth } = await setup();
  expect((await request(app).put(`/api/ledger-saves/${slot}`).set('Authorization', auth).send(body())).status).toBe(400);
  expect((await request(app).get(`/api/ledger-saves/${slot}`).set('Authorization', auth)).status).toBe(400);
});

test('rejects a delete without the revision being removed', async () => {
  const { app, auth } = await setup();
  for (const query of ['', '?revision=0', '?revision=abc']) {
    expect((await request(app).delete(`/api/ledger-saves/1${query}`).set('Authorization', auth)).status).toBe(400);
  }
});

const record = savedRecord();
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
])('rejects a save with %s and leaves the slot empty', async (_case, payload) => {
  const { app, auth, saves } = await setup();
  const res = await request(app).put('/api/ledger-saves/1').set('Authorization', auth).send(payload);
  expect(res.status).toBe(400);
  expect(typeof res.body.error).toBe('string');
  expect(saves.size).toBe(0);
});
