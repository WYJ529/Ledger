import assert from 'node:assert/strict';
import test from 'node:test';
import { validatedOrigin, validatedPath, validatedInvitation } from './smoke-url.mjs';

test('deployment configuration accepts only HTTPS origins', () => {
  assert.equal(validatedOrigin('https://ledger.example:8443').origin, 'https://ledger.example:8443');
  for (const value of ['http://ledger.example', 'file:///tmp/ledger', 'https://user:pass@ledger.example',
    'https://ledger.example/api', 'https://ledger.example/?redirect=evil', 'https://ledger.example/#fragment']) {
    assert.throws(() => validatedOrigin(value));
  }
});

test('API paths cannot replace the operator-selected origin', () => {
  const origin = validatedOrigin('https://ledger.example');
  assert.equal(validatedPath(origin, '/health').href, 'https://ledger.example/health');
  for (const path of ['https://evil.example', '//evil.example', '/\\evil.example', '/health#fragment']) {
    assert.throws(() => validatedPath(origin, path));
  }
});

test('invitation responses cannot redirect the probe to another host or endpoint', () => {
  const origin = validatedOrigin('https://ledger.example');
  const path = `/invite/${'a'.repeat(43)}`;
  assert.equal(validatedInvitation(origin, origin.origin + path).href, origin.origin + path);
  for (const value of [`https://evil.example${path}`, `https://ledger.example.evil.example${path}`,
    `https://user@ledger.example${path}`, `https://ledger.example:444${path}`,
    `${origin.origin}${path}?next=evil`, `${origin.origin}${path}#fragment`, `${origin.origin}/admin`]) {
    assert.throws(() => validatedInvitation(origin, value));
  }
});
