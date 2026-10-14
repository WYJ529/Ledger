import request from 'supertest';
import { createApp } from '../../src/app';
import { formatLocalTime } from '../../src/serverInfo';

const keys = ['OWNER_FIRST_NAME', 'OWNER_LAST_NAME', 'SERVER_PUBLIC_IP'] as const;
const original = Object.fromEntries(keys.map(key => [key, process.env[key]]));
afterEach(() => {
  for (const key of keys) {
    const value = original[key];
    if (value === undefined) delete process.env[key]; else process.env[key] = value;
  }
});

// Interface GET /api/server/name. No mocked components.
describe('Unmocked: GET /api/server/name', () => {
  // Input: configured owner. Expected: 200, exact first and last name.
  test('Returns configured developer identity', async () => {
    process.env.OWNER_FIRST_NAME = 'Joy'; process.env.OWNER_LAST_NAME = 'Tao';
    const res = await request(createApp()).get('/api/server/name');
    expect(res.status).toBe(200);
    expect(res.body).toEqual({ firstName: 'Joy', lastName: 'Tao' });
  });
  // Input: missing surname. Expected: 503; no invented identity.
  test('Reports missing identity configuration', async () => {
    delete process.env.OWNER_LAST_NAME;
    const res = await request(createApp()).get('/api/server/name');
    expect(res.status).toBe(503);
    expect(res.body.error).toContain('OWNER_LAST_NAME');
  });
});

// Interface GET /api/server/ip. No mocked components.
describe('Unmocked: GET /api/server/ip', () => {
  // Input: explicit IP. Expected: 200 and configured address.
  test('Uses explicit deployment address', async () => {
    process.env.SERVER_PUBLIC_IP = '203.0.113.7';
    const res = await request(createApp()).get('/api/server/ip');
    expect(res.status).toBe(200);
    expect(res.body).toEqual({ ip: '203.0.113.7', source: 'configured-public' });
  });
  // Input: no public IP. Expected: 200; local address is clearly identified.
  test('Labels local development address', async () => {
    delete process.env.SERVER_PUBLIC_IP;
    const res = await request(createApp()).get('/api/server/ip');
    expect(res.status).toBe(200);
    expect(res.body.source).toBe('local-development');
  });
  // Input: malformed address. Expected: 503 rather than misleading data.
  test('Rejects invalid deployment address', async () => {
    process.env.SERVER_PUBLIC_IP = 'not-an-ip';
    expect((await request(createApp()).get('/api/server/ip')).status).toBe(503);
  });
});

// Interface GET /api/server/time. No mocked components.
describe('Unmocked: GET /api/server/time', () => {
  // Input: current-time request. Expected: 200, fresh timestamp, GMT offset.
  test('Returns current local time without cache reuse', async () => {
    const before = Date.now();
    const res = await request(createApp()).get('/api/server/time');
    expect(res.status).toBe(200);
    expect(Date.parse(res.body.timestamp)).toBeGreaterThanOrEqual(before);
    expect(Date.parse(res.body.timestamp)).toBeLessThanOrEqual(Date.now());
    expect(res.body.time).toMatch(/^\d{2}:\d{2}:\d{2} GMT[+-]\d{2}:\d{2}$/);
    expect(res.headers['cache-control']).toBe('no-store');
  });
  // Input: a local midnight date. Expected: zero-padded 24-hour clock.
  test('Formats midnight in 24-hour format', () => {
    expect(formatLocalTime(new Date(2026, 0, 1, 0, 1, 2))).toMatch(/^00:01:02 GMT[+-]\d{2}:\d{2}$/);
  });
});
