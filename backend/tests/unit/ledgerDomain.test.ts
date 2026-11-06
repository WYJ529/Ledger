import {
  convertMinor, formatMinor, LedgerValidationError, parseMinor,
  TransactionInput, validateInput,
} from '../../src/ledger/domain';

const today = new Date('2026-10-16T01:00:00.000Z');
const transaction: TransactionInput = {
  kind: 'expense', amount: '12.30', currency: 'CAD', date: '2026-10-10',
  merchant: 'Cafe Artigiano', category: 'Food', notes: '',
};

describe('Ledger monetary arithmetic', () => {
  test('keeps decimal amounts exact and formats currencies with their own precision', () => {
    expect(parseMinor('00012.30', 'CAD')).toBe(1230);
    expect(parseMinor('0.29', 'USD')).toBe(29);
    expect(parseMinor('1200', 'JPY')).toBe(1200);
    expect(formatMinor(29, 'EUR')).toBe('0.29');
    expect(formatMinor(-1, 'CAD')).toBe('-0.01');
    expect(formatMinor(1200, 'JPY')).toBe('1200');
    expect(formatMinor(0, 'CAD')).toBe('0.00');
    expect(parseMinor('10000000000.00', 'CAD')).toBe(1_000_000_000_000);
  });

  test.each(['0', '-1', '1e3', '1,000', 'NaN', 'Infinity', '0.001', '10000000000.01'])('rejects invalid CAD amount %s', amount => {
    expect(() => parseMinor(amount, 'CAD')).toThrow(LedgerValidationError);
  });

  test('rejects fractional yen and unsafe values', () => {
    expect(() => parseMinor('1.0', 'JPY')).toThrow('0 decimal places');
    expect(() => formatMinor(Number.MAX_SAFE_INTEGER + 1, 'CAD')).toThrow('integer range');
    expect(() => parseMinor('1', 'BTC')).toThrow('Currency');
  });

  test('rounds at exact half boundaries and converts across currency scales', () => {
    expect(convertMinor(1, 'USD', 'CAD', '1.5')).toBe(2);
    expect(convertMinor(1, 'USD', 'CAD', '1.499999999999')).toBe(1);
    expect(convertMinor(100, 'CAD', 'JPY', '109.5')).toBe(110);
    expect(convertMinor(1, 'JPY', 'CAD', '0.005')).toBe(1);
    expect(convertMinor(1000, 'JPY', 'CAD', '0.0091')).toBe(910);
    expect(convertMinor(101, 'CAD', 'CAD', '1')).toBe(101);
    expect(convertMinor(0, 'JPY', 'USD', '0.01')).toBe(0);
  });

  test.each(['0', '-1', '1e-2', '0.1234567890123', 'NaN'])('rejects invalid rate %s', rate => {
    expect(() => convertMinor(100, 'USD', 'CAD', rate)).toThrow(LedgerValidationError);
  });

  test('rejects unsafe conversion results and fractional minor-unit inputs', () => {
    expect(() => convertMinor(Number.MAX_SAFE_INTEGER, 'CAD', 'USD', '2')).toThrow('integer range');
    expect(() => convertMinor(1.5, 'CAD', 'USD', '1')).toThrow('safe integer');
  });
});

describe('Ledger transaction validation', () => {
  test('normalizes user fields and omits storage-controlled fields', () => {
    expect(validateInput({ ...transaction, amount: '0012.3', currency: ' cad ', merchant: '  Café   Artigiano  ', category: 'food', notes: '  breakfast  ' }, today))
      .toEqual({ ...transaction, amountMinor: 1230, merchant: 'Café Artigiano', notes: 'breakfast' });
    const { notes: _notes, ...withoutNotes } = transaction;
    expect(validateInput(withoutNotes, today).notes).toBe('');
    expect(() => validateInput({ ...transaction, userId: 'somebody-else' }, today)).toThrow('Unsupported transaction fields');
  });

  test.each(['2025-02-29', '2026-04-31', '2026-13-01', '1999-01-03', '2026-10-17', '10/10/2026'])('rejects invalid or unsupported date %s', date => {
    expect(() => validateInput({ ...transaction, date }, today)).toThrow(LedgerValidationError);
  });

  test('accepts real leap days and inclusive date limits', () => {
    for (const date of ['2024-02-29', '1999-01-04', '2026-10-16']) {
      expect(validateInput({ ...transaction, date }, today).date).toBe(date);
    }
  });

  test('reports all editable problems together', () => {
    try {
      validateInput({ ...transaction, amount: 12, merchant: 'x'.repeat(121), kind: 'refund', category: 'Invalid', notes: 'x'.repeat(1001) }, today);
      throw new Error('Expected validation to fail');
    } catch (error) {
      expect(error).toBeInstanceOf(LedgerValidationError);
      expect((error as LedgerValidationError).issues).toEqual(expect.objectContaining({
        amount: expect.any(String), merchant: expect.any(String), kind: expect.any(String),
        category: expect.any(String), notes: expect.any(String),
      }));
    }
    expect(() => validateInput(null, today)).toThrow('object');
    expect(() => validateInput([], today)).toThrow('object');
  });
});

test('blank merchants normalize and validate; overlong merchants remain invalid', () => {
  expect(validateInput({ ...transaction, merchant: '   ' }, today).merchant).toBe('');
  expect(() => validateInput({ ...transaction, merchant: 'x'.repeat(121) }, today)).toThrow(LedgerValidationError);
});
