import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import https from 'node:https';
import { Readable } from 'node:stream';
import test from 'node:test';
import { DEPLOYMENT_ORIGIN, requireDeploymentOrigin, deployedRequest } from './smoke-client.mjs';

test('the command line can only select the configured deployment', () => {
  requireDeploymentOrigin(DEPLOYMENT_ORIGIN);
  requireDeploymentOrigin(`${DEPLOYMENT_ORIGIN}/`);
  for (const value of [undefined, 'https://evil.example', 'http://136-67-157-106.sslip.io',
    `${DEPLOYMENT_ORIGIN}.evil.example`, `${DEPLOYMENT_ORIGIN}/api`, `${DEPLOYMENT_ORIGIN}?redirect=evil`]) {
    assert.throws(() => requireDeploymentOrigin(value));
  }
});

test('HTTPS requests pin the destination and preserve method, headers and body', async t => {
  const body = JSON.stringify({ name: 'Smoke check' });
  const headers = { 'Content-Type': 'application/json' };
  const requestMock = t.mock.method(https, 'request', (options, callback) => {
    assert.equal(options.protocol, 'https:');
    assert.equal(options.hostname, new URL(DEPLOYMENT_ORIGIN).hostname);
    assert.equal(options.port, 443);
    assert.equal(options.path, '/api/groups?limit=1');
    assert.equal(options.method, 'POST');
    assert.deepEqual(options.headers, headers);
    assert(options.signal instanceof AbortSignal);
    const request = new EventEmitter();
    request.end = sent => {
      assert.equal(sent, body);
      const response = Readable.from([Buffer.from('{"ok":'), Buffer.from('true}')]);
      response.statusCode = 201;
      queueMicrotask(() => callback(response));
    };
    return request;
  });
  assert.deepEqual(await deployedRequest('/api/groups?limit=1', 'POST', headers, body),
    { status: 201, text: '{"ok":true}' });
  assert.equal(requestMock.mock.callCount(), 1);
});

test('invalid paths are rejected before opening a connection', async t => {
  const requestMock = t.mock.method(https, 'request', () => assert.fail('Unexpected network request'));
  for (const path of ['https://evil.example', '//evil.example', '/\\evil.example', '/health#fragment']) {
    await assert.rejects(deployedRequest(path));
  }
  assert.equal(requestMock.mock.callCount(), 0);
});

test('redirects fail without requesting the Location destination', async t => {
  const requestMock = t.mock.method(https, 'request', (_options, callback) => {
    const request = new EventEmitter();
    request.end = () => {
      const response = Readable.from([]);
      response.statusCode = 302;
      response.headers = { location: 'https://evil.example' };
      queueMicrotask(() => callback(response));
    };
    return request;
  });
  await assert.rejects(deployedRequest('/health'), /redirects are not allowed/);
  assert.equal(requestMock.mock.callCount(), 1);
});

test('request errors reject instead of leaving the probe pending', async t => {
  t.mock.method(https, 'request', () => {
    const request = new EventEmitter();
    request.end = () => queueMicrotask(() => request.emit('error', new Error('Connection failed')));
    return request;
  });
  await assert.rejects(deployedRequest('/health'), /Connection failed/);
});

test('response errors reject instead of returning incomplete content', async t => {
  t.mock.method(https, 'request', (_options, callback) => {
    const request = new EventEmitter();
    request.end = () => queueMicrotask(() => {
      const response = new EventEmitter();
      response.statusCode = 200;
      callback(response);
      response.emit('error', new Error('Response interrupted'));
    });
    return request;
  });
  await assert.rejects(deployedRequest('/health'), /Response interrupted/);
});
