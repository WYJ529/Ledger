import { createApp } from './app';
import { env } from './config/env';
import { attachPixelRelay } from './pixelRelay';

const app = createApp();
const server = app.listen(env.port, () => {
  console.log(`Server listening on port ${env.port}`);
});
const relay = attachPixelRelay(server);

for (const signal of ['SIGINT', 'SIGTERM'] as const) {
  process.once(signal, () => {
    void relay.close().then(() => process.exit(0));
  });
}
