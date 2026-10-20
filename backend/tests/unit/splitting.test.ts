import { MAX_AMOUNT_CENTS, parseMoney, splitBill } from '../../src/splitting';

describe('money and splitting', () => {
  test.each([['0.01', 1], ['1', 100], ['1.2', 120], ['1000000.00', MAX_AMOUNT_CENTS]])('parses %s exactly', (text, cents) => {
    expect(parseMoney(text)).toBe(cents);
  });
  test.each(['0', '-1', '1.001', '1e3', 'NaN', ' 2.00 ', '1000000.01', '', 1.1, null])('rejects invalid money %s', value => {
    expect(() => parseMoney(value)).toThrow();
  });
  test('distributes remainder cents deterministically, without losing money', () => {
    const shares = splitBill(1000, 'equal', [{ userId: 'c' }, { userId: 'a' }, { userId: 'b' }], ['a', 'b', 'c']);
    expect(shares).toEqual([{ userId: 'a', amountCents: 334 }, { userId: 'b', amountCents: 333 }, { userId: 'c', amountCents: 333 }]);
    expect(splitBill(1, 'equal', [{ userId: 'b' }, { userId: 'a' }], ['a', 'b'])).toEqual([
      { userId: 'a', amountCents: 1 }, { userId: 'b', amountCents: 0 },
    ]);
  });
  test('preserves exact totals for varying amounts and member counts', () => {
    for (let count = 1; count <= 100; count++) {
      const ids = Array.from({ length: count }, (_, index) => `member-${index}`);
      for (const total of [1, 29, 1000, 1234567, MAX_AMOUNT_CENTS]) {
        const shares = splitBill(total, 'equal', ids.map(userId => ({ userId })), ids);
        expect(shares.reduce((sum, share) => sum + share.amountCents, 0)).toBe(total);
        expect(Math.max(...shares.map(s => s.amountCents)) - Math.min(...shares.map(s => s.amountCents))).toBeLessThanOrEqual(1);
      }
    }
  });
  test('accepts custom zero shares and rejects mismatched totals', () => {
    expect(splitBill(100, 'custom', [{ userId: 'a', amount: '1' }, { userId: 'b', amount: '0' }], ['a', 'b'])).toEqual([
      { userId: 'a', amountCents: 100 }, { userId: 'b', amountCents: 0 },
    ]);
    expect(() => splitBill(101, 'custom', [{ userId: 'a', amount: '1' }], ['a'])).toThrow(/exactly/);
  });
  test.each([
    ['equal', []], ['equal', [{ userId: 'a' }, { userId: 'a' }]],
    ['equal', [{ userId: 'outsider' }]], ['equal', [null]], ['other', [{ userId: 'a' }]],
    ['custom', [{ userId: 'a', amount: '-1' }]], ['custom', [{ userId: 'a' }]],
  ])('rejects invalid %s participants', (mode, shares) => {
    expect(() => splitBill(100, mode, shares, ['a'])).toThrow();
  });
});
