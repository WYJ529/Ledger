import assert from 'node:assert/strict';

/** Exercise the public registration contract using a code from the real inbox. */
export async function registerWithEmailCode(api, account, readCode) {
  await api('POST', '/api/auth/register', 400, account);
  await api('POST', '/api/auth/login', 401, account);
  const challenge = await api('POST', '/api/auth/register/code', 202, { email: account.email });
  assert.equal(challenge.email, account.email, 'Code delivery email did not match.');
  assert(/^[a-f0-9]{64}$/.test(challenge.verificationId), 'Invalid verification challenge.');
  assert(!Object.hasOwn(challenge, 'code'), 'The API must not expose the verification code.');
  await api('POST', '/api/auth/login', 401, account);
  const code = await readCode(account.email);
  assert(typeof code === 'string' && /^[0-9]{6}$/.test(code), 'Enter exactly six digits from the email.');
  return api('POST', '/api/auth/register', 201, { ...account, verificationId: challenge.verificationId, code });
}
