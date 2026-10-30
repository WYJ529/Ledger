// Isolated, disposable backend for GroupMembershipIntegrationTest on an emulator.
// Run `npm run build` first. Never connects to a development or cloud database.
const { createServer } = require('node:http');
const { MongoMemoryServer } = require('mongodb-memory-server');
const { Server } = require('socket.io');
const { createApp } = require('../dist/app');
const { connectDatabase, disconnectDatabase, mongoUserStore, mongoSessionStore } = require('../dist/userStore');
const { attachSplitSessionLive } = require('../dist/splitSessionLive');

async function main() {
  const mongo = await MongoMemoryServer.create();
  await connectDatabase(mongo.getUri('membership-device-acceptance'));
  const server = createServer(createApp());
  const io = new Server(server, { transports: ['websocket'], serveClient: false });
  const live = attachSplitSessionLive(io, mongoUserStore, mongoSessionStore);
  let stopping = false;
  async function stop() {
    if (stopping) return;
    stopping = true;
    live.close();
    await new Promise(resolve => io.close(resolve));
    await disconnectDatabase();
    await mongo.stop();
    process.exit(0);
  }
  process.once('SIGINT', stop);
  process.once('SIGTERM', stop);
  setTimeout(stop, 15 * 60 * 1000).unref();
  server.listen(3101, '127.0.0.1', () => console.log('Membership acceptance backend ready on 127.0.0.1:3101 (isolated MongoDB).'));
}

main().catch(error => { console.error(error.message); process.exitCode = 1; });
