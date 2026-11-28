import { required } from '../helpers/required';
import { convertMinor, formatMinor, LedgerValidationError } from '../../src/ledger/domain';
import { parseMoney } from '../../src/money';
import { calculateMeal, parseAdjustment, proportionalShares, splitBill } from '../../src/splitting';

// Programmatic interfaces used by the ledger and group routes. Use the real
// monetary, conversion and allocation implementations together.
describe('Unmocked: ledger calculation interfaces', () => {
  // Input: a signed balance and a zero-decimal target, with a two-person meal and a deduction.
  // Expected status code: none (programmatic result).
  // Expected behavior: use the same minor-unit conventions for adjustments, allocations and formatting.
  // Expected output: CAD 9.00 / 11.00, a signed CAD balance and a whole-yen conversion.
  test('uses consistent minor units across splitting, balances and conversion', () => {
    const ids = ['amy', 'ben'];
    const meal = calculateMeal({ tipRate: '0', members: [...ids].reverse().map(userId => ({
      userId, subtotal: '10.00', extraTaxRate: '0',
    })) }, ids);
    expect(meal.amountCents).toBe(2100);
    const deduction = parseAdjustment('-2');
    const shares = splitBill(parseMoney('20.00'), 'adjusted', [
      { userId: 'amy', adjustment: formatMinor(deduction, 'CAD') }, { userId: 'ben' },
    ], ids);
    expect(shares.map(share => formatMinor(share.amountCents, 'CAD'))).toEqual(['9.00', '11.00']);
    expect(formatMinor(-11, 'JPY')).toBe('-11');
    expect(formatMinor(convertMinor(required(shares[0]).amountCents, 'CAD', 'JPY', '100'), 'JPY')).toBe('900');
  });

  // Input: zero-weight allocations, tied remainders in reverse member order, and an invalid total.
  // Expected status code: none (allocation or exception).
  // Expected behavior: keep zero amounts stable and resolve ties by member ID.
  // Expected output: a zero split, one extra cent for amy and an invalid-total diagnostic.
  test('allocates zero and tied weights deterministically', () => {
    const empty = ['ben', 'amy'].map(userId => ({ userId, amountCents: 0 }));
    expect(proportionalShares(empty, 0)).toEqual(empty);
    expect(proportionalShares(empty.map(item => ({ ...item, amountCents: 5 })), 1))
      .toEqual([{ userId: 'ben', amountCents: 5 }, { userId: 'amy', amountCents: 6 }]);
    expect(() => splitBill(0, 'equal', [{ userId: 'amy' }], ['amy'])).toThrow('Invalid total amount');
  });

  // Input: an invalid currency, an unsafe balance and a negative conversion principal.
  // Expected status code: none (LedgerValidationError).
  // Expected behavior: preserve the field diagnostic from the monetary layer.
  // Expected output: validation errors without rounded or substituted amounts.
  test('refuses unsupported or unrepresentable money', () => {
    expect(() => formatMinor(10, 'ZZZ')).toThrow(LedgerValidationError);
    expect(() => formatMinor(2 ** 53, 'CAD')).toThrow('supported integer range');
    expect(() => convertMinor(-1, 'CAD', 'JPY', '100')).toThrow('non-negative');
  });
});
