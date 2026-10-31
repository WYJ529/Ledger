import assert from 'node:assert/strict';
import { test } from 'node:test';
import { registerWithEmailCode } from './smoke-registration.mjs';

const account = { email: 'smoke@example.com', displayName: 'Smoke', password: 'test passphrase' };
const challenge = { email: account.email, verificationId: 'a'.repeat(64) };
function fixture(overrides = {}) {
  const calls = [];
  const api = async (method, path, status, body) => {
    calls.push({ method, path, status, body });
    return path.endsWith('/code') ? { ...challenge, ...overrides } : { token: 'test-session' };
  };
  return { calls, api };
}

test('smoke registration proves the account cannot sign in until its emailed code is entered', async () => {
  const { calls, api } = fixture();
  const result = await registerWithEmailCode(api, account, async email => {
    assert.equal(email, account.email);
    assert.deepEqual(calls.map(call => [call.path, call.status]), [
      ['/api/auth/register', 400], ['/api/auth/login', 401],
      ['/api/auth/register/code', 202], ['/api/auth/login', 401],
    ]);
    return '001234';
  });
  assert.equal(result.token, 'test-session');
  assert.deepEqual(calls.at(-1).body, { ...account, verificationId: challenge.verificationId, code: '001234' });
});

test('malformed or cancelled input never completes registration', async () => {
  for (const readCode of [async () => '12345', async () => '１２３４５６', async () => { throw new Error('cancelled'); }]) {
    const { calls, api } = fixture();
    await assert.rejects(registerWithEmailCode(api, account, readCode));
    assert(!calls.some(call => call.status === 201));
  }
});

test('refuses mismatched delivery or an API response exposing the code', async () => {
  for (const overrides of [{ email: 'other@example.com' }, { verificationId: 'invalid' }, { code: '001234' }]) {
    const { calls, api } = fixture(overrides);
    await assert.rejects(registerWithEmailCode(api, account, async () => { assert.fail('Must reject before reading a code'); }));
    assert(!calls.some(call => call.status === 201));
  }
});
