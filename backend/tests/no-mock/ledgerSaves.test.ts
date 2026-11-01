import { MongoMemoryServer } from 'mongodb-memory-server';
import request from 'supertest';
import { createApp } from '../../src/app';
import { LedgerSaveModel } from '../../src/ledgerSaveStore';
import { startSession } from '../../src/session';
import { connectDatabase, disconnectDatabase, mongoSessionStore, mongoUserStore, SessionModel, UserModel } from '../../src/userStore';
import { savedRecord } from '../helpers/ledgerSaves';

let mongo: MongoMemoryServer | undefined;
const app = createApp();
let auth = '';

beforeAll(async () => {
  mongo = await MongoMemoryServer.create();
  await connectDatabase(mongo.getUri('ledger-save-tests'));
}, 120000);

beforeEach(async () => {
  const user = await mongoUserStore.create({ email: 'saver@example.com', displayName: 'Saver' });
  auth = `Bearer ${(await startSession(mongoSessionStore, user)).token}`;
});

afterEach(async () => {
  await Promise.all([LedgerSaveModel.deleteMany({}), UserModel.deleteMany({}), SessionModel.deleteMany({})]);
});

afterAll(async () => {
  await disconnectDatabase();
  await mongo?.stop();
});

const body = (replaces: number | null, records = [savedRecord()]) => ({ deviceName: 'Pixel 9', baseCurrency: 'CAD', records, replaces });

// Interface PUT/GET/DELETE /api/ledger-saves/:slot against MongoDB
describe('Unmocked: ledger save slots', () => {
  test('stores, lists, restores, overwrites and deletes a slot', async () => {
    const saved = await request(app).put('/api/ledger-saves/3').set('Authorization', auth).send(body(null));
    expect(saved.status).toBe(200);
    expect((await request(app).get('/api/ledger-saves').set('Authorization', auth)).body.slots).toEqual([saved.body.save]);
    expect((await request(app).get('/api/ledger-saves/3').set('Authorization', auth)).body.save.records).toEqual([savedRecord()]);
    const replaced = await request(app).put('/api/ledger-saves/3').set('Authorization', auth).send(body(1, []));
    expect(replaced.body.save).toMatchObject({ revision: 2, recordCount: 0 });
    expect((await request(app).delete('/api/ledger-saves/3?revision=1').set('Authorization', auth)).status).toBe(409);
    expect((await request(app).delete('/api/ledger-saves/3?revision=2').set('Authorization', auth)).status).toBe(204);
    expect(await LedgerSaveModel.countDocuments()).toBe(0);
  });

  test('lets exactly one of two devices fill the same empty slot', async () => {
    const results = await Promise.all([1, 2].map(() => request(app).put('/api/ledger-saves/1').set('Authorization', auth).send(body(null))));
    expect(results.map(res => res.status).sort()).toEqual([200, 409]);
    expect(await LedgerSaveModel.countDocuments({ slot: 1 })).toBe(1);
  });

  test('lets exactly one of two devices overwrite the same revision', async () => {
    await request(app).put('/api/ledger-saves/2').set('Authorization', auth).send(body(null));
    const results = await Promise.all([[], [savedRecord()]].map(records =>
      request(app).put('/api/ledger-saves/2').set('Authorization', auth).send(body(1, records))));
    expect(results.map(res => res.status).sort()).toEqual([200, 409]);
    const listed = await request(app).get('/api/ledger-saves').set('Authorization', auth);
    expect(listed.body.slots).toEqual([expect.objectContaining({ slot: 2, revision: 2 })]);
  });
});
