import { MAX_AMOUNT_CENTS, parseAdjustment, parseMoney, parsePercentage, splitBill } from '../../src/splitting';

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

describe('percentage splitting', () => {
  test.each([['0', 0], ['0.01', 1], ['33.33', 3333], ['100.00', 10000]])('parses %s%% exactly', (text, basisPoints) => {
    expect(parsePercentage(text)).toBe(basisPoints);
  });

  test.each(['', '-1', '100.01', '1.001', '1e2', 'NaN', ' 50 ', '50%', 50, null, undefined])(
    'rejects invalid percentage %s', value => { expect(() => parsePercentage(value)).toThrow(); },
  );

  test('gives rounding cents to the largest remainders independently of selection order', () => {
    const input = [{ userId: 'a', percentage: '33.33' }, { userId: 'b', percentage: '33.33' }, { userId: 'c', percentage: '33.34' }];
    const expected = [
      { userId: 'a', percentageBasisPoints: 3333, amountCents: 333 },
      { userId: 'b', percentageBasisPoints: 3333, amountCents: 333 },
      { userId: 'c', percentageBasisPoints: 3334, amountCents: 334 },
    ];
    expect(splitBill(1000, 'percentage', input, ['a', 'b', 'c'])).toEqual(expected);
    expect(splitBill(1000, 'percentage', [...input].reverse(), ['a', 'b', 'c'])).toEqual(expected);
  });

  test('breaks remainder ties by member ID and never allocates a cent to a zero percentage', () => {
    expect(splitBill(1, 'percentage', [
      { userId: 'c', percentage: '50' }, { userId: 'a', percentage: '50' }, { userId: 'b', percentage: '0' },
    ], ['a', 'b', 'c'])).toEqual([
      { userId: 'a', percentageBasisPoints: 5000, amountCents: 1 },
      { userId: 'b', percentageBasisPoints: 0, amountCents: 0 },
      { userId: 'c', percentageBasisPoints: 5000, amountCents: 0 },
    ]);
  });

  test('preserves exact totals and sub-cent error bounds for up to 100 participants and maximum bills', () => {
    for (let count = 1; count <= 100; count++) {
      const ids = Array.from({ length: count }, (_, index) => `member-${index}`);
      const input = ids.map((userId, index) => {
        const points = Math.floor(10000 / count) + (index < 10000 % count ? 1 : 0);
        return { userId, percentage: `${Math.floor(points / 100)}.${String(points % 100).padStart(2, '0')}` };
      });
      for (const total of [1, 29, 1000, MAX_AMOUNT_CENTS]) {
        const shares = splitBill(total, 'percentage', input, ids);
        expect(shares.reduce((sum, share) => sum + share.amountCents, 0)).toBe(total);
        shares.forEach(share => {
          expect(Number.isSafeInteger(share.amountCents)).toBe(true);
          expect(Math.abs(share.amountCents * 10000 - total * (share.percentageBasisPoints ?? 0))).toBeLessThan(10000);
        });
      }
    }
  });

  test.each([
    [{ userId: 'a', percentage: '99.99' }],
    [{ userId: 'a', percentage: '0' }],
    [{ userId: 'a', percentage: '50' }, { userId: 'b', percentage: '50.01' }],
    [{ userId: 'a', amount: '1.00' }],
    [{ userId: 'a', percentage: '50' }, { userId: 'a', percentage: '50' }],
    [{ userId: 'outsider', percentage: '100' }],
  ])('rejects incomplete, non-100%% or invalid membership allocations %#', (...input) => {
    expect(() => splitBill(100, 'percentage', input, ['a', 'b'])).toThrow();
  });
});

describe('equal split with adjustments', () => {
  test.each([['8', 800], ['+8', 800], ['-2.5', -250], ['0', 0], ['-0', 0], ['', 0], [undefined, 0], [null, 0]])(
    'parses adjustment %s exactly', (text, cents) => { expect(parseAdjustment(text)).toBe(cents); },
  );

  test.each(['8.001', '1e2', '--1', '+-1', ' 8 ', '8$', 'NaN', 8, '1000000.01'])('rejects invalid adjustment %s', value => {
    expect(() => parseAdjustment(value)).toThrow();
  });

  // The example from the feature request: one member's $8 drink, the rest of the bill split evenly.
  test('adds an extra item to one member and splits the rest evenly', () => {
    const input = [{ userId: 'a' }, { userId: 'b', adjustment: '8' }, { userId: 'c', adjustment: '' }, { userId: 'd' }];
    expect(splitBill(4000, 'adjusted', input, ['a', 'b', 'c', 'd'])).toEqual([
      { userId: 'a', adjustmentCents: 0, amountCents: 800 },
      { userId: 'b', adjustmentCents: 800, amountCents: 1600 },
      { userId: 'c', adjustmentCents: 0, amountCents: 800 },
      { userId: 'd', adjustmentCents: 0, amountCents: 800 },
    ]);
  });

  test('deductions are shared by everyone else and results do not depend on selection order', () => {
    const input = [{ userId: 'c', adjustment: '-1.00' }, { userId: 'a' }, { userId: 'b' }];
    const expected = [
      { userId: 'a', adjustmentCents: 0, amountCents: 367 },
      { userId: 'b', adjustmentCents: 0, amountCents: 367 },
      { userId: 'c', adjustmentCents: -100, amountCents: 266 },
    ];
    expect(splitBill(1000, 'adjusted', input, ['a', 'b', 'c'])).toEqual(expected);
    expect(splitBill(1000, 'adjusted', [...input].reverse(), ['a', 'b', 'c'])).toEqual(expected);
  });

  test('without adjustments it matches an equal split', () => {
    const ids = ['c', 'a', 'b'];
    const adjusted = splitBill(1000, 'adjusted', ids.map(userId => ({ userId })), ids);
    expect(adjusted.map(({ userId, amountCents }) => ({ userId, amountCents })))
      .toEqual(splitBill(1000, 'equal', ids.map(userId => ({ userId })), ids));
  });

  test('preserves exact totals for varying adjustments and member counts', () => {
    for (let count = 1; count <= 100; count += 9) {
      const ids = Array.from({ length: count }, (_, index) => `member-${index}`);
      for (const total of [100000, 1234567, MAX_AMOUNT_CENTS]) {
        const input = ids.map((userId, index) => ({ userId, adjustment: index % 3 === 0 ? '1.99' : index % 3 === 1 ? '-0.01' : '' }));
        const shares = splitBill(total, 'adjusted', input, ids);
        expect(shares.reduce((sum, share) => sum + share.amountCents, 0)).toBe(total);
        expect(shares.every(share => share.amountCents >= 0)).toBe(true);
      }
    }
  });

  test('allows an adjustment equal to the whole bill and a zero-cent share', () => {
    expect(splitBill(800, 'adjusted', [{ userId: 'a', adjustment: '8' }, { userId: 'b' }], ['a', 'b'])).toEqual([
      { userId: 'a', adjustmentCents: 800, amountCents: 800 },
      { userId: 'b', adjustmentCents: 0, amountCents: 0 },
    ]);
  });

  test.each([
    [[{ userId: 'a', adjustment: '10.01' }, { userId: 'b' }], /larger than the bill total/],
    [[{ userId: 'a', adjustment: '-10.50' }, { userId: 'b' }], /at least 0/],
    [[{ userId: 'a', adjustment: '8.123' }], /two decimal places/],
    [[{ userId: 'a' }, { userId: 'a' }], /only once/],
    [[{ userId: 'outsider', adjustment: '1' }], /belong to this group/],
  ])('rejects impossible or invalid adjustments %#', (input, message) => {
    expect(() => splitBill(1000, 'adjusted', input, ['a', 'b'])).toThrow(message);
  });
});
