import { createApp } from './app';
import { env } from './config/env';
import { Server } from 'socket.io';
import { attachSplitSessionLive } from './splitSessionLive';
import { connectDatabase, disconnectDatabase, mongoSessionStore, mongoUserStore } from './userStore';

async function main() {
  await connectDatabase(env.mongodbUri);
  const app = createApp();
  const server = app.listen(env.port, env.host, () => {
    process.stdout.write(`Server listening on port ${env.port}\n`);
  });
  const io = new Server(server, { transports: ['websocket'], serveClient: false });
  const splitLive = attachSplitSessionLive(io, mongoUserStore, mongoSessionStore);

  let stopping = false;
  for (const signal of ['SIGINT', 'SIGTERM'] as const) {
    process.once(signal, () => {
      if (stopping) return;
      stopping = true;
      splitLive.close();
      io.close().then(disconnectDatabase).catch((error: unknown) => {
        console.error('Failed to shut down server:', error);
        process.exitCode = 1;
      });
    });
  }
}

main().catch((error: unknown) => {
  console.error('Failed to start server:', error instanceof Error ? error.message : error);
  process.exitCode = 1;
  return disconnectDatabase();
});
