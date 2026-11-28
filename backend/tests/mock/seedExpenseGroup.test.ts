import { MongoMemoryServer } from 'mongodb-memory-server';
import mongoose from 'mongoose';
import { GroupModel } from '../../src/groupStore';
import * as stores from '../../src/userStore';

jest.mock('dotenv/config', () => ({}));
jest.mock('mongoose', () => jest.requireActual<typeof import('mongoose')>('mongoose'));
// Share the suite's Mongoose models when reloading the CLI entry point.
jest.mock('../../src/userStore', () => jest.requireActual<typeof import('../../src/userStore')>('../../src/userStore'));
jest.mock('../../src/groupStore', () => jest.requireActual<typeof import('../../src/groupStore')>('../../src/groupStore'));

let mongo: MongoMemoryServer;
let uri: string;
const argv = process.argv;
const env = process.env;
const exitCode = process.exitCode;
beforeAll(async () => {
  mongo = await MongoMemoryServer.create();
  uri = mongo.getUri('seed-command');
  await stores.connectDatabase(uri);
  await stores.mongoUserStore.create({ username: 'owner', displayName: 'Owner' });
  await stores.mongoUserStore.create({ username: 'guest', displayName: 'Guest' });
  await stores.disconnectDatabase();
}, 120000);
beforeEach(() => { process.env = { ...env, MONGODB_URI: uri }; });
afterEach(() => {
  process.env = env;
  process.argv = argv;
  process.exitCode = exitCode;
  jest.restoreAllMocks();
});
afterAll(async () => { await stores.disconnectDatabase(); await mongo.stop(); });

async function seed(args: string[], success = false) {
  process.argv = [...argv.slice(0, 2), ...args];
  const output = new Promise<string>(resolve => {
    jest.spyOn(console, 'error').mockImplementationOnce(message => { resolve(String(message)); });
    jest.spyOn(process.stdout, 'write').mockImplementationOnce(message => {
      resolve(String(message).trimEnd());
      return true;
    });
  });
  // On success the message precedes the asynchronous disconnect in finally.
  const disconnected = new Promise<void>(resolve => {
    const disconnect = stores.disconnectDatabase;
    jest.spyOn(stores, 'disconnectDatabase').mockImplementationOnce(async () => {
      await disconnect();
      resolve();
    });
  });
  jest.isolateModules(() => { jest.requireActual<typeof import('../../src/seedExpenseGroup')>('../../src/seedExpenseGroup'); });
  const message = await output;
  if (success) await disconnected;
  jest.restoreAllMocks();
  return message;
}

describe('Mocked: seed:expense-group CLI', () => {
  // Input: a legacy account requires migration and the username write fails during startup.
  // Expected status code: command exits with 1.
  // Expected behavior: stop seeding and close the database connection.
  // Expected output: the storage diagnostic and no new group.
  test('reports a failed legacy account migration', async () => {
    await stores.connectDatabase(uri);
    const legacy = await stores.UserModel.create({ displayName: 'Legacy' });
    await stores.disconnectDatabase();
    jest.spyOn(stores.UserModel, 'updateOne').mockRejectedValueOnce(new Error('migration write failed'));
    expect(await seed(['Migration', 'CAD', 'owner'])).toBe('migration write failed');
    expect(process.exitCode).toBe(1);
    expect(mongoose.connection.readyState).toBe(0);
    await stores.connectDatabase(uri);
    try {
      expect(await GroupModel.exists({ name: 'Migration' })).toBeNull();
      await stores.UserModel.deleteOne({ _id: legacy._id });
    } finally { await stores.disconnectDatabase(); }
  });

  // Input: two existing accounts and the same group twice, with output captured.
  // Expected status code: command succeeds both times.
  // Expected behavior: preserve the owner and reuse the group on the second run.
  // Expected output: created and existing messages with the same group ID.
  test('seeds an idempotent group in the isolated database', async () => {
    const args = [' Trip ', 'CAD', ' OWNER ', 'guest'];
    const created = await seed(args, true);
    expect(created).toMatch(/^Created group: Trip/);
    expect(await seed(args, true)).toBe(created.replace('Created', 'Existing'));
    await stores.connectDatabase(uri);
    try {
      const groups = await GroupModel.find().lean();
      expect(groups).toHaveLength(1);
      expect(groups[0]?.memberIds.map(String)).toEqual([
        (await stores.mongoUserStore.findByUsername('owner'))?.id,
        (await stores.mongoUserStore.findByUsername('guest'))?.id,
      ]);
      expect(String(groups[0]?.ownerId)).toBe(String(groups[0]?.memberIds[0]));
    } finally { await stores.disconnectDatabase(); }
  });

  // Input: invalid CLI arguments or an unknown member.
  // Expected status code: command exits with 1.
  // Expected behavior: reject the request without creating a group.
  // Expected output: usage instructions or the absent account name.
  test.each([
    { args: [], message: 'Usage:' },
    { args: ['g'.repeat(61), 'CAD', 'owner'], message: 'Usage:' },
    { args: ['Group'], message: 'Usage:' },
    { args: ['Group', 'XXX', 'owner'], message: 'Usage:' },
    { args: ['Group', 'USD'], message: 'Usage:' },
    { args: ['Group', 'USD', ...Array<string>(101).fill('owner')], message: 'Usage:' },
    { args: ['Group', 'USD', '!'], message: 'Usage:' },
    { args: ['Group', 'USD', 'owner', 'Owner'], message: 'Usage:' },
    { args: ['Group', 'USD', 'missing'], message: 'Register this account in the app first: missing' },
  ])('rejects invalid seed request %#', async ({ args, message }) => {
    expect(await seed(args)).toContain(message);
    expect(process.exitCode).toBe(1);
    expect(mongoose.connection.readyState).toBe(0);
  });

  // Input: valid arguments with a missing URI or a rejected database connection.
  // Expected status code: command exits with 1.
  // Expected behavior: surface the configuration or database error.
  // Expected output: an actionable diagnostic.
  test.each([true, false])('reports a startup error, missing URI=%s', async missing => {
    if (missing) delete process.env.MONGODB_URI;
    else jest.spyOn(stores, 'connectDatabase').mockRejectedValueOnce('offline');
    expect(await seed(['Group', 'CAD', 'owner'])).toContain(missing ? 'Set MONGODB_URI' : 'offline');
    expect(process.exitCode).toBe(1);
  });
});
