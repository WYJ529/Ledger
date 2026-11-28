import { MongoMemoryServer } from 'mongodb-memory-server';
import { connectDatabase, disconnectDatabase } from '../../src/userStore';

interface Clearable { deleteMany: (filter: object) => PromiseLike<unknown> }

/** Connects the suite to its own in-memory mongod and empties `models` after each test. */
export function useIsolatedMongo(databaseName: string, models: Clearable[]) {
  let mongo: MongoMemoryServer | undefined;
  const uri = () => {
    if (!mongo) throw new Error('The test database has not started.');
    return mongo.getUri(databaseName);
  };
  beforeAll(async () => {
    mongo = await MongoMemoryServer.create();
    await connectDatabase(uri());
  }, 120000);
  afterEach(async () => { await Promise.all(models.map(model => model.deleteMany({}))); });
  afterAll(async () => {
    await disconnectDatabase();
    await mongo?.stop();
  }, 30000);
  return { uri };
}
