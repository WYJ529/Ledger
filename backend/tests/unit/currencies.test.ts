import { CURRENCIES, isSupportedCurrencyCode, minorUnitDigits } from '../../src/currencies';
import { convertMinor, formatMinor, parseMinor, validateInput } from '../../src/ledger/domain';
import { calculateMeal, parseMoney, splitBill } from '../../src/splitting';

describe('Supported currencies', () => {
  test('include the six added currencies alongside the original six', () => {
    expect(CURRENCIES).toEqual(['CAD', 'USD', 'EUR', 'GBP', 'CNY', 'JPY', 'AUD', 'HKD', 'SGD', 'KRW', 'MXN', 'INR']);
    for (const code of ['AUD', 'HKD', 'SGD', 'KRW', 'MXN', 'INR']) expect(isSupportedCurrencyCode(code)).toBe(true);
    expect(isSupportedCurrencyCode('XXX')).toBe(false);
    expect(isSupportedCurrencyCode(42)).toBe(false);
  });

  test('yen and won have no minor unit; every other currency has two decimals', () => {
    expect(CURRENCIES.filter(code => minorUnitDigits(code) === 0)).toEqual(['JPY', 'KRW']);
  });
});

describe('Personal ledger with the added currencies', () => {
  test('parses, formats and converts won as whole units and rupees with two decimals', () => {
    expect(parseMinor('15000', 'KRW')).toBe(15000);
    expect(() => parseMinor('15000.5', 'KRW')).toThrow('0 decimal places');
    expect(formatMinor(15000, 'KRW')).toBe('15000');
    expect(parseMinor('249.99', 'INR')).toBe(24999);
    expect(formatMinor(24999, 'MXN')).toBe('249.99');
    // 15,000 KRW at 0.00098 CAD/KRW = 14.70 CAD; 10.00 AUD at 900 KRW/AUD = 9,000 KRW.
    expect(convertMinor(15000, 'KRW', 'CAD', '0.00098')).toBe(1470);
    expect(convertMinor(1000, 'AUD', 'KRW', '900')).toBe(9000);
  });

  test('accepts transactions in each added currency', () => {
    for (const currency of ['AUD', 'HKD', 'SGD', 'MXN', 'INR']) {
      expect(() => validateInput({ kind: 'expense', amount: '12.30', currency, date: '2026-10-10',
        merchant: 'Shop', category: 'Food', notes: '' }, new Date('2026-10-16T01:00:00Z'))).not.toThrow();
    }
  });
});

describe('Group money in zero-decimal currencies', () => {
  test('whole won amounts parse as minor units and decimals are rejected', () => {
    expect(parseMoney('15000', false, 0)).toBe(15000);
    expect(parseMoney('100000000', false, 0)).toBe(100_000_000);
    expect(() => parseMoney('15000.50', false, 0)).toThrow('whole amount');
    expect(() => parseMoney('100000001', false, 0)).toThrow('100,000,000');
    expect(() => parseMoney('0', false, 0)).toThrow();
  });

  test('custom shares and meals use whole won', () => {
    expect(splitBill(10000, 'custom', [{ userId: 'a', amount: '6000' }, { userId: 'b', amount: '4000' }], ['a', 'b'], 0))
      .toEqual([{ userId: 'a', amountCents: 6000 }, { userId: 'b', amountCents: 4000 }]);
    expect(() => splitBill(10000, 'custom', [{ userId: 'a', amount: '10000.00' }], ['a'], 0)).toThrow();
    const meal = calculateMeal({ tipRate: '10', members: [{ userId: 'a', subtotal: '20000', extraTaxRate: '0' }] }, ['a'], 0);
    // 20,000 won + 5% GST (1,000) + 10% tip (2,000).
    expect(meal).toMatchObject({ subtotalCents: 20000, taxCents: 1000, tipCents: 2000, amountCents: 23000 });
    expect(meal.customCalculation.members[0]?.subtotal).toBe('20000');
    expect(meal.customCalculation.tipRate).toBe('10.00');
  });
});
