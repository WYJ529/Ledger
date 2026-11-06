import { MoneyInputError, parseMoney, formatMinorUnits } from '../money';
import { dateProblem } from '../dates';
import { CURRENCIES, minorUnitDigits, isSupportedCurrencyCode, type Currency } from '../currencies';

export { CURRENCIES, type Currency };
export const CATEGORIES = [
  'Food', 'Transport', 'Shopping', 'Housing', 'Health', 'Entertainment', 'Salary', 'Other',
] as const;
export type Category = typeof CATEGORIES[number];
export type TransactionKind = 'expense' | 'income';

export interface TransactionInput {
  kind: TransactionKind;
  amount: string;
  currency: string;
  date: string;
  merchant: string;
  category: string;
  notes: string;
}

export interface NormalizedTransaction extends TransactionInput {
  currency: Currency;
  category: Category;
  amountMinor: number;
}

export class LedgerValidationError extends Error {
  readonly issues: Record<string, string>;

  constructor(issues: Record<string, string>) {
    super(Object.values(issues).join(' '));
    this.name = 'LedgerValidationError';
    this.issues = issues;
  }
}

export function currencyDecimals(currency: string): number {
  if (!isSupportedCurrencyCode(currency)) {
    throw new LedgerValidationError({ currency: `Currency must be one of ${CURRENCIES.join(', ')}.` });
  }
  return minorUnitDigits(currency);
}

export function parseMinor(amount: string, currency: string): number {
  const digits = currencyDecimals(currency);
  try { return parseMoney(amount, false, digits); }
  catch (error) {
    if (!(error instanceof MoneyInputError)) throw error;
    throw new LedgerValidationError({ amount: error.message });
  }
}

export function formatMinor(amountMinor: number, currency: string): string {
  const digits = currencyDecimals(currency);
  try { return formatMinorUnits(amountMinor, digits); }
  catch (error) {
    if (!(error instanceof MoneyInputError)) throw error;
    throw new LedgerValidationError({ amount: error.message });
  }
}

/**
 * Rates are target major units per source major unit, supplied as decimal strings.
 * Integer arithmetic implements round-half-up, including zero-decimal JPY.
 */
export function convertMinor(amountMinor: number, from: string, to: string, rate: string): number {
  const fromDecimals = currencyDecimals(from);
  const toDecimals = currencyDecimals(to);
  if (!Number.isSafeInteger(amountMinor) || amountMinor < 0) {
    throw new LedgerValidationError({ amount: 'Conversion requires a non-negative safe integer amount.' });
  }
  if (rate.length > 32 || !/^\d+(?:\.\d{1,12})?$/.test(rate)) {
    throw new LedgerValidationError({ rate: 'Exchange rate must be a positive decimal with at most 12 decimal places.' });
  }
  const [whole = '', fraction = ''] = rate.split('.');
  const rateNumerator = BigInt(whole + fraction);
  if (rateNumerator <= 0n) {
    throw new LedgerValidationError({ rate: 'Exchange rate must be greater than zero.' });
  }
  const numerator = BigInt(amountMinor) * rateNumerator * (10n ** BigInt(toDecimals));
  const denominator = (10n ** BigInt(fraction.length)) * (10n ** BigInt(fromDecimals));
  const rounded = (numerator + denominator / 2n) / denominator;
  if (rounded > BigInt(Number.MAX_SAFE_INTEGER)) {
    throw new LedgerValidationError({ amount: 'The converted amount exceeds the supported integer range.' });
  }
  return Number(rounded);
}

const INPUT_FIELDS = new Set(['kind', 'amount', 'currency', 'date', 'merchant', 'category', 'notes']);

export function validateInput(value: unknown, today: Date = new Date()): NormalizedTransaction {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) {
    throw new LedgerValidationError({ transaction: 'A transaction must be an object.' });
  }
  const input = value as Record<string, unknown>;
  const issues: Record<string, string> = {};
  const unexpected = Object.keys(input).filter(key => !INPUT_FIELDS.has(key));
  if (unexpected.length > 0) issues.fields = `Unsupported transaction fields: ${unexpected.join(', ')}.`;

  function readString(field: string, optional = false): string {
    const raw = input[field];
    if (raw === undefined && optional) return '';
    if (typeof raw !== 'string') {
      issues[field] = `${field} must be text.`;
      return '';
    }
    return raw.trim();
  }

  const kind = readString('kind').toLowerCase();
  const amount = readString('amount');
  const currency = readString('currency').toUpperCase();
  const date = readString('date');
  const merchant = readString('merchant').replace(/\s+/gu, ' ');
  const rawCategory = readString('category');
  const notes = readString('notes', true);
  const category = CATEGORIES.find(candidate => candidate.toLowerCase() === rawCategory.toLowerCase());

  if (kind !== 'expense' && kind !== 'income') issues.kind = 'Kind must be expense or income.';
  if (!isSupportedCurrencyCode(currency)) issues.currency = `Currency must be one of ${CURRENCIES.join(', ')}.`;
  if (!category) issues.category = `Category must be one of ${CATEGORIES.join(', ')}.`;
  if (merchant.length > 120) issues.merchant = 'Merchant must be at most 120 characters.';
  if (notes.length > 1000) issues.notes = 'Notes must contain at most 1000 characters.';

  const problem = dateProblem(date, today);
  if (problem) issues.date = problem;

  let amountMinor = 0;
  if (isSupportedCurrencyCode(currency)) {
    try {
      amountMinor = parseMinor(amount, currency);
    } catch (error) {
      if (!(error instanceof LedgerValidationError)) throw error;
      Object.assign(issues, error.issues);
    }
  }
  if (Object.keys(issues).length > 0 || !isSupportedCurrencyCode(currency) || !category || (kind !== 'expense' && kind !== 'income')) {
    throw new LedgerValidationError(issues);
  }
  return { kind, amount: formatMinor(amountMinor, currency), amountMinor, currency, date, merchant, category, notes };
}
