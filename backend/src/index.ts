import { createApp } from './app';
import { env } from './config/env';
import { attachPixelRelay } from './pixelRelay';
import { attachSplitSessionLive } from './splitSessionLive';
import { mongoSessionStore, mongoUserStore } from './userStore';
import { connectDatabase, disconnectDatabase } from './userStore';

async function main() {
  await connectDatabase(env.mongodbUri);
  const app = createApp();
  const server = app.listen(env.port, env.host, () => {
    console.log(`Server listening on port ${env.port}`);
  });
  const relay = attachPixelRelay(server);
  const splitLive = attachSplitSessionLive(relay.io, mongoUserStore, mongoSessionStore);

  for (const signal of ['SIGINT', 'SIGTERM'] as const) {
    process.once(signal, () => {
      splitLive.close();
      void relay.close().then(disconnectDatabase).then(() => process.exit(0));
    });
  }
}

main().catch(error => {
  console.error('Failed to start server:', error instanceof Error ? error.message : error);
  process.exit(1);
});
