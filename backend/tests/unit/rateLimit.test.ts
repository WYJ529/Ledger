import type { Request, RequestHandler, Response } from 'express';
import { accountRateLimit, rateLimit } from '../../src/rateLimit';

function attempt(limiter: RequestHandler, ip?: string, remoteAddress?: string) {
  const req = { ip, socket: { remoteAddress } } as Request;
  const res = { status: jest.fn().mockReturnThis(), json: jest.fn().mockReturnThis(), setHeader: jest.fn() };
  const next = jest.fn();
  limiter(req, res as unknown as Response, next);
  return { ...res, next };
}

afterEach(() => jest.restoreAllMocks());

test('fixed windows allow the exact limit and use a ceiling for Retry-After', () => {
  const clock = jest.spyOn(Date, 'now').mockReturnValue(1000);
  const limit = rateLimit(2, 1500);
  expect(attempt(limit, 'client').next).toHaveBeenCalledTimes(1);
  expect(attempt(limit, 'client').next).toHaveBeenCalledTimes(1);
  clock.mockReturnValue(2001);
  const blocked = attempt(limit, 'client');
  expect(blocked.status).toHaveBeenCalledWith(429);
  expect(blocked.setHeader).toHaveBeenCalledWith('Retry-After', 1);
  expect(blocked.json).toHaveBeenCalledWith({ error: 'Too many requests. Please wait a few minutes and retry.' });
  expect(blocked.next).not.toHaveBeenCalled();
  clock.mockReturnValue(2500);
  expect(attempt(limit, 'client').next).toHaveBeenCalledTimes(1);
});

test('client IPs and limiter instances do not share counters', () => {
  const first = rateLimit(1, 60000);
  const second = rateLimit(1, 60000);
  attempt(first, 'one');
  expect(attempt(first, 'one').status).toHaveBeenCalledWith(429);
  expect(attempt(first, 'two').next).toHaveBeenCalledTimes(1);
  expect(attempt(second, 'one').next).toHaveBeenCalledTimes(1);
});

test('falls back to the socket address and finally an unknown-client bucket', () => {
  const limit = rateLimit(1, 60000);
  attempt(limit, undefined, 'socket-one');
  expect(attempt(limit, undefined, 'socket-one').status).toHaveBeenCalledWith(429);
  expect(attempt(limit, undefined, 'socket-two').next).toHaveBeenCalledTimes(1);
  attempt(limit);
  expect(attempt(limit).status).toHaveBeenCalledWith(429);
});

test('bounds stored clients, retains existing buckets, and recovers capacity when windows expire', () => {
  const clock = jest.spyOn(Date, 'now').mockReturnValue(1000);
  const limit = rateLimit(2, 1000);
  for (let index = 0; index < 2000; index++) attempt(limit, `client-${index}`);
  const full = attempt(limit, 'new-client');
  expect(full.status).toHaveBeenCalledWith(503);
  expect(full.json).toHaveBeenCalledWith({ error: 'The service is busy. Please retry later.' });
  expect(full.next).not.toHaveBeenCalled();
  expect(attempt(limit, 'client-0').next).toHaveBeenCalledTimes(1);
  clock.mockReturnValue(2000);
  expect(attempt(limit, 'new-client').next).toHaveBeenCalledTimes(1);
});

test('account limits preserve the ten-minute window and retry metadata', () => {
  jest.spyOn(Date, 'now').mockReturnValue(1000);
  const limit = accountRateLimit(1);
  attempt(limit, 'client');
  const blocked = attempt(limit, 'client');
  expect(blocked.status).toHaveBeenCalledWith(429);
  expect(blocked.setHeader).toHaveBeenCalledWith('Retry-After', 600);
  expect(blocked.json).toHaveBeenCalledWith({
    error: 'Too many registration requests. Please try again later.', retryAfterSeconds: 600,
  });
});
