import request from 'supertest';
import express, { type ErrorRequestHandler } from 'express';
import { createApp } from '../../src/app';
import { mongoSessionStore, mongoUserStore } from '../../src/userStore';

// Mocked behavior: account/session reads reject if called. Public infrastructure
// endpoints must remain independent of authentication-store availability.
function unavailableAuthentication() {
  const userRead = jest.spyOn(mongoUserStore, 'findById').mockRejectedValue(new Error('User store unavailable'));
  const sessionRead = jest.spyOn(mongoSessionStore, 'find').mockRejectedValue(new Error('Session store unavailable'));
  return { app: createApp(), userRead, sessionRead };
}

afterEach(() => jest.restoreAllMocks());

test('forwards a ledger response failure after headers are sent', async () => {
  const failure = new Error('stream interrupted');
  const observed = jest.fn();
  const finish: ErrorRequestHandler = (error, _req, res, next) => {
    if (error !== failure) { next(error); return; }
    observed(error);
    res.end();
  };
  const app = express().use((_req, res, next) => {
    res.json = () => { res.write('partial'); throw failure; };
    next();
  }).use(createApp()).use(finish);
  const response = await request(app).get('/api/ledger/meta');
  expect(response.text).toBe('partial');
  expect(observed).toHaveBeenCalledWith(failure);
});

test('rejects a login sent without a body', async () => {
  const response = await request(createApp()).post('/api/auth/login');
  expect(response.status).toBe(400);
  expect(response.body.error).toBe('Enter your username and password.');
});

describe('Mocked: GET /health', () => {
  // Input: anonymous health request; mocked account/session reads reject if called
  // Expected status code: 200
  // Expected behavior: liveness does not query either authentication store
  // Expected output: { status: "ok" }
  test('Reports liveness independently of authentication storage', async () => {
    const { app, userRead, sessionRead } = unavailableAuthentication();
    const res = await request(app).get('/health');
    expect(res.status).toBe(200);
    expect(res.body).toEqual({ status: 'ok' });
    expect(userRead).not.toHaveBeenCalled();
    expect(sessionRead).not.toHaveBeenCalled();
  });
});

describe('Mocked: GET /.well-known/assetlinks.json', () => {
  // Input: configured Android certificate; mocked account/session reads reject if called
  // Expected status code: 200
  // Expected behavior: public app association does not query authentication stores
  // Expected output: Android relation, package name and configured SHA-256 fingerprint
  test('Serves app association independently of authentication storage', async () => {
    const previous = process.env.ANDROID_SHA256_FINGERPRINTS;
    const fingerprint = Array(32).fill('AB').join(':');
    process.env.ANDROID_SHA256_FINGERPRINTS = fingerprint;
    try {
      const { app, userRead, sessionRead } = unavailableAuthentication();
      const res = await request(app).get('/.well-known/assetlinks.json');
      expect(res.status).toBe(200);
      expect(res.body).toEqual([{
        relation: ['delegate_permission/common.handle_all_urls'],
        target: { namespace: 'android_app', package_name: 'com.example.cpen321application', sha256_cert_fingerprints: [fingerprint] },
      }]);
      expect(userRead).not.toHaveBeenCalled();
      expect(sessionRead).not.toHaveBeenCalled();
    } finally {
      if (previous === undefined) delete process.env.ANDROID_SHA256_FINGERPRINTS;
      else process.env.ANDROID_SHA256_FINGERPRINTS = previous;
    }
  });
});

describe('Mocked: GET /does-not-exist', () => {
  // Input: unregistered URL; mocked account/session reads reject if called
  // Expected status code: 404
  // Expected behavior: the fallback does not query authentication stores
  // Expected output: { error: "Not Found" }
  test('Rejects unknown routes independently of authentication storage', async () => {
    const { app, userRead, sessionRead } = unavailableAuthentication();
    const res = await request(app).get('/does-not-exist');
    expect(res.status).toBe(404);
    expect(res.body).toEqual({ error: 'Not Found' });
    expect(userRead).not.toHaveBeenCalled();
    expect(sessionRead).not.toHaveBeenCalled();
  });
});
