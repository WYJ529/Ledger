import type { Server as HttpServer } from 'node:http';
import { Server } from 'socket.io';

type Upstream = Pick<WebSocket, 'addEventListener' | 'close'>;
type RelayOptions = {
  url?: string;
  connect?: (url: string) => Upstream;
  retryDelayMs?: number;
};

/** Shares one course connection while viewers exist; never parses or batches pixels. */
export function attachPixelRelay(server: HttpServer, options: RelayOptions = {}) {
  const io = new Server(server, { transports: ['websocket'], serveClient: false });
  const url = options.url ?? process.env.COURSE_WS_URL ?? 'wss://8.229.22.124';
  const connect = options.connect ?? ((address: string) => new WebSocket(address));
  const minimumDelay = options.retryDelayMs ?? 2000;
  let upstream: Upstream | undefined;
  let retry: ReturnType<typeof setTimeout> | undefined;
  let attempt = 0;
  let stopped = false;
  let state = 'Waiting for viewers';

  function report(message: string) { state = message; io.emit('stream-status', message); }
  function release() {
    const old = upstream;
    upstream = undefined;
    try { old?.close(); } catch { /* A failed handshake may already be closed. */ }
  }
  function retryConnection() {
    if (retry || stopped || io.engine.clientsCount === 0) return;
    const delay = Math.min(minimumDelay * 2 ** Math.min(attempt++, 4), 30000);
    retry = setTimeout(() => { retry = undefined; start(); }, delay);
  }
  function start() {
    if (stopped || upstream || io.engine.clientsCount === 0) return;
    report('Connecting to course stream');
    let connection: Upstream;
    try { connection = connect(url); }
    catch { report('Course connection failed; retrying'); retryConnection(); return; }
    upstream = connection;
    connection.addEventListener('open', () => {
      if (upstream !== connection) return;
      attempt = 0;
      report('Connected to course stream');
    });
    connection.addEventListener('message', (event: MessageEvent) => {
      if (upstream !== connection || typeof event.data !== 'string') return;
      // Socket.IO carries the original JSON text as an event argument, unchanged.
      io.emit('pixel', event.data);
    });
    const failed = () => {
      if (upstream !== connection) return;
      release();
      report('Course connection interrupted; retrying');
      retryConnection();
    };
    connection.addEventListener('error', failed);
    connection.addEventListener('close', failed);
  }

  io.on('connection', socket => {
    socket.emit('stream-status', state);
    start();
    socket.on('disconnect', () => {
      if (io.sockets.sockets.size !== 0) return;
      if (retry) clearTimeout(retry);
      retry = undefined;
      attempt = 0;
      release();
      state = 'Waiting for viewers';
    });
  });

  return {
    async close(): Promise<void> {
      stopped = true;
      if (retry) clearTimeout(retry);
      retry = undefined;
      release();
      await new Promise<void>(resolve => io.close(() => resolve()));
    },
  };
}
