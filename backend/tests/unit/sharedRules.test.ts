import { parseMinor, formatMinor, validateInput } from '../../src/ledger/domain';
import { parseAdjustment, proportionalShares, splitBill, calculateMeal } from '../../src/splitting';
import { MAX_AMOUNT_MINOR, parseMoney } from '../../src/money';
import { dateProblem, isHistoricalDate } from '../../src/dates';
import { isRequestId } from '../../src/validation';
import { authenticateSession, bearerToken, hashToken, startSession, validSession } from '../../src/session';
import { googleCredential } from '../../src/googleAuth';
import { memoryStores } from '../helpers/memoryStores';

test.each(['CAD', 'JPY'])('personal and group amounts share boundaries and whitespace rules for %s', currency => {
  const digits = currency === 'JPY' ? 0 : 2;
  for (const amount of [MAX_AMOUNT_MINOR, 1, 29]) {
    const text = ' \t' + formatMinor(amount, currency) + ' \n';
    expect(parseMoney(text, false, digits)).toBe(parseMinor(text, currency));
  }
  for (const text of ['', '   ', '0', '-1', '+1', '1e2', '1,000', '1.001', formatMinor(MAX_AMOUNT_MINOR + 1, currency)]) {
    expect(() => parseMoney(text, false, digits)).toThrow();
    expect(() => parseMinor(text, currency)).toThrow();
  }
  expect(parseMoney(' 0 ', true, digits)).toBe(0);
  expect(parseAdjustment('   ', digits)).toBe(0);
  expect(parseAdjustment(' −1 ', digits)).toBe(digits === 0 ? -1 : -100);
  expect(() => parseAdjustment('- 1', digits)).toThrow();
});

test('large weighted splits remain exact and deterministic beyond floating-point products', () => {
  const items = [{ userId: 'b', amountCents: 333333333333 }, { userId: 'a', amountCents: 333333333333 }];
  expect(proportionalShares(items, 333333333334)).toEqual([
    { userId: 'b', amountCents: 500000000000 }, { userId: 'a', amountCents: 500000000000 },
  ]);
  const shares = splitBill(MAX_AMOUNT_MINOR, 'percentage', [
    { userId: 'b', percentage: '33.33' }, { userId: 'a', percentage: '66.67' },
  ], ['a', 'b']);
  expect(shares.map(item => item.amountCents)).toEqual([666700000000, 333300000000]);
  const meal = calculateMeal({ tipRate: '33.33', members: [{ userId: 'a', subtotal: '5000000000.01', extraTaxRate: '0' }] }, ['a']);
  expect(meal).toMatchObject({ subtotalCents: 500000000001, taxCents: 25000000000, tipCents: 166650000000, amountCents: 691650000001 });
});

test('transaction and exchange dates use the same calendar and UTC bounds', () => {
  const today = new Date('2026-11-06T12:00:00Z');
  jest.useFakeTimers().setSystemTime(today);
  try {
    for (const date of ['2024-02-29', '2025-02-29', '2026-04-31', '1999-01-04', '1999-01-03', '2026-11-06', '2026-11-07', '2026-1-01']) {
      const valid = dateProblem(date, today) === undefined;
      expect(isHistoricalDate(date)).toBe(valid);
      const validate = () => validateInput({ kind: 'expense', amount: '1', currency: 'CAD', date, merchant: '', category: 'Other' }, today);
      if (valid) expect(validate).not.toThrow(); else expect(validate).toThrow();
    }
  } finally { jest.useRealTimers(); }
});

test('request keys have one bounded ASCII contract', () => {
  for (const key of ['a'.repeat(16), 'Z_9-'.repeat(20)]) expect(isRequestId(key)).toBe(true);
  for (const key of [null, {}, ['a'.repeat(16)], '', 'a'.repeat(15), 'a'.repeat(81), 'a'.repeat(15) + '.', 'a'.repeat(16) + '\n']) {
    expect(isRequestId(key)).toBe(false);
  }
});

test('shared session checks reject expired, revoked, mismatched and malformed identities', async () => {
  const stores = memoryStores();
  const user = await stores.users.create({ username: 'member', displayName: 'Member' });
  const first = await startSession(stores.sessions, user);
  const second = await startSession(stores.sessions, user);
  const tokenHash = hashToken(first.token);
  expect(await authenticateSession(stores.users, stores.sessions, bearerToken('Bearer ' + first.token))).toMatchObject({ tokenHash, user: { id: user.id } });
  expect(await validSession(stores.sessions, tokenHash, 'other-user')).toBeNull();
  expect(await authenticateSession(stores.users, stores.sessions, 'short')).toBeNull();
  await stores.users.update(user.id, { displayName: 'Updated' }, tokenHash);
  expect(await authenticateSession(stores.users, stores.sessions, second.token)).toBeNull();
  const saved = stores.sessionMap.get(tokenHash);
  if (!saved) throw new Error('Missing fixture session');
  saved.expiresAt = new Date(0);
  expect(await validSession(stores.sessions, tokenHash)).toBeNull();
});

test('Google credential validation rejects bad setup, blank and expired credentials before callers decide status', async () => {
  const previous = process.env.GOOGLE_CLIENT_ID;
  const verify = jest.fn().mockResolvedValue({ sub: 'google-user', exp: Date.now() / 1000 + 100 });
  try {
    process.env.GOOGLE_CLIENT_ID = 'your_client_id';
    expect((await googleCredential('valid', verify)).status).toBe(503);
    process.env.GOOGLE_CLIENT_ID = 'client.apps.googleusercontent.com';
    expect((await googleCredential(' ', verify)).status).toBe(400);
    expect((await googleCredential('x'.repeat(8193), verify)).status).toBe(400);
    expect(verify).not.toHaveBeenCalled();
    expect((await googleCredential('valid', verify)).payload?.sub).toBe('google-user');
    verify.mockResolvedValue({ sub: 'google-user', exp: 1 });
    expect((await googleCredential('expired', verify)).status).toBe(401);
  } finally {
    if (previous === undefined) delete process.env.GOOGLE_CLIENT_ID; else process.env.GOOGLE_CLIENT_ID = previous;
  }
});
