// Isolated, disposable backend for emulator tests that need the real Node backend:
// GroupMembershipIntegrationTest (default port 3101) and the full-stack E2E tests
// (LEDGER_TEST_PORT=3000, the API_BASE_URL a local debug build uses).
// Run `npm run build` first. Never connects to a development or cloud database.
async function main() {
  const { createServer } = await import('node:http');
  const { randomUUID } = await import('node:crypto');
  const { default: express } = await import('express');
  const { MongoMemoryServer } = await import('mongodb-memory-server');
  const { Server } = await import('socket.io');
  const { createApp } = await import('../../dist/app.js');
  const { connectDatabase, disconnectDatabase, mongoUserStore, mongoSessionStore } = await import('../../dist/userStore.js');
  const { attachSplitSessionLive } = await import('../../dist/splitSessionLive.js');
  const { startSession } = await import('../../dist/session.js');
  const mongo = await MongoMemoryServer.create();
  await connectDatabase(mongo.getUri('membership-device-acceptance'));
  const app = express();
  // Test fixtures exist only in this loopback-only runner with its disposable database.
  // Group acceptance does not exercise registration or require an external inbox.
  app.post('/test/accounts', express.json({ limit: '1kb' }), async (req, res) => {
    const displayName = req.body?.displayName;
    if (typeof displayName !== 'string' || !['Live owner', 'First joiner', 'Second joiner'].includes(displayName)) {
      res.status(400).end(); return;
    }
    const user = await mongoUserStore.create({ email: `membership-${randomUUID()}@example.com`, displayName });
    res.status(201).json(await startSession(mongoSessionStore, user));
  });
  app.use(createApp());
  const server = createServer(app);
  const io = new Server(server, { transports: ['websocket'], serveClient: false });
  const live = attachSplitSessionLive(io, mongoUserStore, mongoSessionStore);
  let stopping = false;
  async function stop() {
    if (stopping) return;
    stopping = true;
    live.close();
    await io.close();
    await disconnectDatabase();
    await mongo.stop();
  }
  const requestStop = () => { stop().catch(reportFailure); };
  process.once('SIGINT', requestStop);
  process.once('SIGTERM', requestStop);
  setTimeout(requestStop, 15 * 60 * 1000).unref();
  const port = Number(process.env.LEDGER_TEST_PORT?.trim() ? process.env.LEDGER_TEST_PORT : 3101);
  if (![3000, 3101].includes(port)) throw new Error('LEDGER_TEST_PORT must be 3000 or 3101.');
  server.listen(port, '127.0.0.1', () => { process.stdout.write(`Isolated test backend ready on 127.0.0.1:${port} (in-memory MongoDB).\n`); });
}

/** @param {unknown} error */
function reportFailure(error) {
  console.error(error instanceof Error ? error.message : 'Test server failed.');
  process.exitCode = 1;
}

main().catch(reportFailure);
