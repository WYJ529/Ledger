import type { Test } from 'supertest';
import { hashToken } from '../../src/session';
import { SessionModel } from '../../src/userStore';

const EXPIRED = { error: 'Your session has expired. Please sign in again.' };

/**
 * Adds the shared session tests to the current interface describe block.
 * `send` builds the request without an Authorization header; `validAuth` is a genuine bearer header;
 * `snapshot` reads the stored state that must stay unchanged; `prepare` creates any data the request targets.
 */
export function testsRejectInvalidSessions(send: () => Test, validAuth: () => string, snapshot: () => Promise<unknown>,
  prepare?: () => Promise<void>) {
  describe('with an invalid session', () => {
    if (prepare) beforeEach(prepare);
    sessionTests(send, validAuth, snapshot);
  });
}

// Sends the request built by `attempt` and checks the 401 contract with stored data unchanged.
async function expectRejectedWithoutChanges(attempt: () => Test, snapshot: () => Promise<unknown>) {
  const before = await snapshot();
  const response = await attempt();
  expect(response.status).toBe(401);
  expect(response.headers['www-authenticate']).toBe('Bearer');
  expect(response.body).toEqual(EXPIRED);
  expect(await snapshot()).toEqual(before);
}

function sessionTests(send: () => Test, validAuth: () => string, snapshot: () => Promise<unknown>) {
  // Input: syntactically valid request with a missing or unknown bearer token
  // Expected status code: 401
  // Expected behavior: request is rejected before any data is read or changed
  // Expected output: Bearer challenge and { error: "Your session has expired. Please sign in again." }
  test.each(['missing', 'unknown'])('Rejects a %s session', async kind => {
    await expectRejectedWithoutChanges(
      (): Test => (kind === 'unknown' ? send().set('Authorization', 'Bearer unknown-session-token-00000000') : send()), snapshot);
  });

  // Input: genuine session whose stored expiry has passed (MongoDB TTL cleanup has not run yet)
  // Expected status code: 401
  // Expected behavior: expiry is enforced at request time; stored data is unchanged
  // Expected output: Bearer challenge and { error: "Your session has expired. Please sign in again." }
  test('Rejects an expired session still present in MongoDB', async () => {
    const auth = validAuth();
    await SessionModel.updateOne({ tokenHash: hashToken(auth.slice('Bearer '.length)) }, { expiresAt: new Date(Date.now() - 1000) });
    await expectRejectedWithoutChanges((): Test => send().set('Authorization', auth), snapshot);
  });
}
