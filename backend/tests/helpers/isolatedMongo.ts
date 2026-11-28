import { MongoMemoryServer } from 'mongodb-memory-server';
import { connectDatabase, disconnectDatabase } from '../../src/userStore';
import { requiredString } from './required';

interface Clearable { deleteMany: (filter: object) => PromiseLike<unknown> }

/** Start one disposable database and keep its shutdown available even if connecting fails. */
export function testDatabase(databaseName: string) {
  let address: string | undefined;
  let stop: (() => Promise<void>) | undefined;
  const uri = (): string => requiredString(address);
  async function connect() {
    const mongo = await MongoMemoryServer.create();
    stop = async () => { await mongo.stop(); };
    address = requiredString(mongo.getUri(databaseName));
    await connectDatabase(address);
  }
  function disconnect(): Promise<void> {
    return disconnectDatabase().finally(() => stop?.());
  }
  return { uri, connect, disconnect };
}

/** Connects the suite to its own in-memory mongod and empties `models` after each test. */
export function useIsolatedMongo(databaseName: string, models: Clearable[]) {
  const db = testDatabase(databaseName);
  beforeAll(db.connect, 120000);
  afterEach(async () => { await Promise.all(models.map(model => model.deleteMany({}))); });
  afterAll(db.disconnect, 30000);
  return { uri: db.uri };
}
