import { required } from '../helpers/required';
import { setImmediate } from 'node:timers/promises';
import { createApp } from '../../src/app';
import { connectDatabase, disconnectDatabase } from '../../src/userStore';
import { Server } from 'socket.io';
import { attachSplitSessionLive } from '../../src/splitSessionLive';
jest.mock('dotenv/config', () => ({}));
jest.mock('../../src/userStore', () => ({
  connectDatabase: jest.fn(), disconnectDatabase: jest.fn(), mongoUserStore: {}, mongoSessionStore: {},
}));
jest.mock('../../src/app', () => ({ createApp: jest.fn() }));
jest.mock('socket.io', () => ({ Server: jest.fn() }));
jest.mock('../../src/splitSessionLive', () => ({ attachSplitSessionLive: jest.fn() }));


const originalEnv = process.env;
const originalExitCode = process.exitCode;
beforeEach(() => {
  process.env = { ...originalEnv, MONGODB_URI: 'mongodb://localhost/test' };
  delete process.env.PORT;
  delete process.env.HOST;
});
afterEach(() => {
  process.env = originalEnv;
  process.exitCode = originalExitCode;
  jest.restoreAllMocks();
  jest.clearAllMocks();
});

describe('Mocked: server startup and shutdown', () => {

  // Input: the real app factory receives a supported or invalid proxy hop count.
  // Expected status code: startup succeeds or throws a configuration error.
  // Expected behavior: accept exactly the documented proxy configuration.
  // Expected output: trust proxy setting 1 or a diagnostic.
  test('validates proxy hop configuration before serving requests', () => {
    const appModule = jest.requireActual<typeof import('../../src/app')>('../../src/app');
    process.env.TRUST_PROXY_HOPS = '1';
    expect(appModule.createApp().get('trust proxy')).toBe(1);
    process.env.TRUST_PROXY_HOPS = '2';
    expect(() => appModule.createApp()).toThrow('TRUST_PROXY_HOPS');
  });

  // Input: start with valid configuration and signal the running server.
  // Expected status code: process exits with 0.
  // Expected behavior: connect, listen, then close live updates, sockets and MongoDB in order.
  // Expected output: listening port and successful exit.
  test.each(['SIGTERM', 'SIGINT'] as const)('shuts down on %s', async signal => {
    const cleanup: string[] = [];
    const listener = {};
    const listen = jest.fn((_port, _host, ready) => { ready(); return listener; });
    jest.mocked(createApp).mockReturnValue({ listen } as unknown as ReturnType<typeof createApp>);
    const close = jest.fn(() => { cleanup.push('sockets'); return Promise.resolve(); });
    jest.mocked(Server).mockImplementation(() => ({ close } as unknown as Server));
    jest.mocked(attachSplitSessionLive).mockReturnValue({ close: () => { cleanup.push('live'); } });
    jest.mocked(disconnectDatabase).mockImplementation(() => { cleanup.push('database'); return Promise.resolve(); });
    const log = jest.spyOn(process.stdout, 'write').mockReturnValue(true);
    const handlers = jest.spyOn(process, 'once').mockReturnValue(process);
    // Re-execute the command while sharing the same mocked dependencies.
    jest.isolateModules(() => { jest.requireActual<typeof import('../../src/index')>('../../src/index'); });
    await setImmediate();
    expect(connectDatabase).toHaveBeenCalledWith('mongodb://localhost/test');
    expect(listen).toHaveBeenCalledWith(3000, '0.0.0.0', expect.any(Function));
    expect(log).toHaveBeenCalledWith('Server listening on port 3000\n');
    expect(Server).toHaveBeenCalledWith(listener, { transports: ['websocket'], serveClient: false });
    const handler = required(handlers.mock.calls.find(([event]) => event === signal))[1];
    handler();
    await setImmediate();
    expect(cleanup).toEqual(['live', 'sockets', 'database']);
    expect(process.exitCode).toBe(originalExitCode);
  });

  // Input: the database rejects the initial connection.
  // Expected status code: process exits with 1.
  // Expected behavior: report the reason before listening.
  // Expected output: startup error and the underlying diagnostic.
  test.each([new Error('unavailable'), 'unavailable'])('reports connection failure %#', async error => {
    jest.mocked(connectDatabase).mockRejectedValueOnce(error);
    const log = jest.spyOn(console, 'error').mockImplementation(() => undefined);
    jest.isolateModules(() => { jest.requireActual<typeof import('../../src/index')>('../../src/index'); });
    await setImmediate();
    expect(log).toHaveBeenCalledWith('Failed to start server:', 'unavailable');
    expect(process.exitCode).toBe(1);
    expect(createApp).not.toHaveBeenCalled();
  });

  // Input: empty or explicit port, trimmed host and database URI.
  // Expected status code: configuration loads.
  // Expected behavior: use documented defaults and normalize whitespace.
  // Expected output: resolved port, host and connection string.
  test.each([['', 3000], ['65535', 65535]])('reads PORT=%p', (port, expected) => {
    process.env.PORT = String(port);
    process.env.HOST = ' 127.0.0.1 ';
    process.env.MONGODB_URI = ' mongodb://localhost/test ';
    jest.isolateModules(() => {
      expect(jest.requireActual<typeof import('../../src/config/env')>('../../src/config/env').env).toEqual({
        port: expected, host: '127.0.0.1', mongodbUri: 'mongodb://localhost/test',
      });
    });
  });

  // Input: invalid port or absent database URI.
  // Expected status code: configuration loading throws.
  // Expected behavior: stop before connecting to a database.
  // Expected output: diagnostic identifying the invalid setting.
  test.each(['text', '0', '65536', undefined])('rejects invalid configuration %#', port => {
    if (port === undefined) delete process.env.MONGODB_URI;
    else process.env.PORT = port;
    expect(() => jest.isolateModules(() => { jest.requireActual<typeof import('../../src/config/env')>('../../src/config/env'); }))
      .toThrow(port === undefined ? 'MONGODB_URI is required' : 'Invalid PORT');
  });
});
