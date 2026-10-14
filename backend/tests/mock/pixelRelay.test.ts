import { createServer, type Server } from 'node:http';
import { io, type Socket } from 'socket.io-client';
import { attachPixelRelay } from '../../src/pixelRelay';

class CourseSocket extends EventTarget {
  closed = false;
  close() { this.closed = true; this.dispatchEvent(new Event('close')); }
}
async function until(predicate: () => boolean) {
  const deadline = Date.now() + 2000;
  while (!predicate()) {
    if (Date.now() > deadline) throw new Error('Timed out waiting for relay');
    await new Promise(resolve => setTimeout(resolve, 10));
  }
}

// Interface: Socket.IO pixel stream. Mock: course WebSocket, real local Socket.IO connections.
describe('Mocked: course stream relay', () => {
  let server: Server;
  let relay: ReturnType<typeof attachPixelRelay>;
  let courses: CourseSocket[];
  let clients: Socket[];
  let address: string;
  beforeEach(async () => {
    courses = []; clients = [];
    server = createServer();
    relay = attachPixelRelay(server, {
      retryDelayMs: 10,
      connect: () => { const course = new CourseSocket(); courses.push(course); return course; },
    });
    await new Promise<void>(resolve => server.listen(0, '127.0.0.1', resolve));
    const bound = server.address();
    if (!bound || typeof bound === 'string') throw new Error('Missing port');
    address = `http://127.0.0.1:${bound.port}`;
  });
  afterEach(async () => { clients.forEach(client => client.disconnect()); await relay.close(); });
  async function viewer() {
    const client = io(address, { transports: ['websocket'], reconnection: false, forceNew: true });
    clients.push(client);
    await new Promise<void>((resolve, reject) => {
      client.once('connect', () => resolve()); client.once('connect_error', reject);
    });
    return client;
  }

  // Input: pixel JSON with original spacing. Expected: one immediate event with identical text.
  test('Forwards the original payload without parsing, batching, or rewriting', async () => {
    const client = await viewer();
    const message = ' { "x": 2, "y": 7, "color": "#aAbBcC" } ';
    const received = new Promise<string>(resolve => client.once('pixel', resolve));
    courses[0]!.dispatchEvent(new MessageEvent('message', { data: message }));
    await expect(received).resolves.toBe(message);
  });

  // Input: two viewers. Expected: a shared upstream and identical ordered updates at both viewers.
  test('Shares one upstream and preserves message order for multiple viewers', async () => {
    const first = await viewer(); const second = await viewer();
    expect(courses).toHaveLength(1);
    const a: string[] = []; const b: string[] = [];
    first.on('pixel', (value: string) => a.push(value)); second.on('pixel', (value: string) => b.push(value));
    const messages = ['{"x":0,"y":0,"color":"#FFFFFF"}', '{"x":1,"y":0,"color":"#000000"}'];
    messages.forEach(data => courses[0]!.dispatchEvent(new MessageEvent('message', { data })));
    await until(() => a.length === 2 && b.length === 2);
    expect(a).toEqual(messages); expect(b).toEqual(messages);
    first.disconnect();
    await new Promise(resolve => setTimeout(resolve, 30));
    expect(courses[0]!.closed).toBe(false);
    second.disconnect();
    await until(() => courses[0]!.closed);
  });

  // Mock: upstream network error. Expected: retry; old connection cannot deliver stale messages.
  test('Reconnects after upstream failure and ignores stale callbacks', async () => {
    const client = await viewer();
    const first = courses[0]!;
    first.dispatchEvent(new Event('error'));
    await until(() => courses.length === 2);
    const received: string[] = [];
    client.on('pixel', (value: string) => received.push(value));
    first.dispatchEvent(new MessageEvent('message', { data: 'stale' }));
    courses[1]!.dispatchEvent(new MessageEvent('message', { data: 'fresh' }));
    await until(() => received.length > 0);
    expect(received).toEqual(['fresh']);
    expect(first.closed).toBe(true);
  });

  // Input: no viewers. Expected: no connection to the external service.
  test('Does not connect upstream until a viewer arrives', () => { expect(courses).toHaveLength(0); });
});
