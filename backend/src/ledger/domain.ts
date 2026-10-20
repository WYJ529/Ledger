export const CURRENCIES = ['CAD', 'USD', 'EUR', 'GBP', 'CNY', 'JPY'] as const;
export type Currency = typeof CURRENCIES[number];
export const CATEGORIES = [
  'Food', 'Transport', 'Shopping', 'Housing', 'Health', 'Entertainment', 'Salary', 'Other',
] as const;
export type Category = typeof CATEGORIES[number];
export type TransactionKind = 'expense' | 'income';
export const MAX_AMOUNT_MINOR = 1_000_000_000_000;
export const MIN_TRANSACTION_DATE = '1999-01-04';

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

export function isCurrency(value: string): value is Currency {
  return CURRENCIES.some(currency => currency === value);
}

export function currencyDecimals(currency: string): number {
  if (!isCurrency(currency)) {
    throw new LedgerValidationError({ currency: `Currency must be one of ${CURRENCIES.join(', ')}.` });
  }
  return currency === 'JPY' ? 0 : 2;
}

/** Parse a positive major-unit amount without passing money through floating point. */
export function parseMinor(amount: string, currency: string): number {
  const decimals = currencyDecimals(currency);
  const trimmed = amount.trim();
  if (trimmed.length > 32 || !/^\d+(?:\.\d+)?$/.test(trimmed)) {
    throw new LedgerValidationError({ amount: 'Amount must be a positive decimal number without separators.' });
  }
  const [whole = '', fraction = ''] = trimmed.split('.');
  if (fraction.length > decimals) {
    throw new LedgerValidationError({ amount: `${currency} amounts allow at most ${decimals} decimal places.` });
  }
  const minor = BigInt(whole) * (10n ** BigInt(decimals)) + BigInt(fraction.padEnd(decimals, '0') || '0');
  if (minor <= 0n || minor > BigInt(MAX_AMOUNT_MINOR)) {
    throw new LedgerValidationError({ amount: `Amount must be greater than zero and at most ${formatMinor(MAX_AMOUNT_MINOR, currency)} ${currency}.` });
  }
  return Number(minor);
}

/** Negative amounts are supported here for balances, but never as transaction input. */
export function formatMinor(amountMinor: number, currency: string): string {
  const decimals = currencyDecimals(currency);
  if (!Number.isSafeInteger(amountMinor)) {
    throw new LedgerValidationError({ amount: 'The amount exceeds the supported integer range.' });
  }
  const amount = BigInt(amountMinor);
  const magnitude = amount < 0n ? -amount : amount;
  const sign = amount < 0n ? '-' : '';
  if (decimals === 0) return `${sign}${magnitude.toString()}`;
  const scale = 10n ** BigInt(decimals);
  return `${sign}${(magnitude / scale).toString()}.${(magnitude % scale).toString().padStart(decimals, '0')}`;
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
  if (!isCurrency(currency)) issues.currency = `Currency must be one of ${CURRENCIES.join(', ')}.`;
  if (!category) issues.category = `Category must be one of ${CATEGORIES.join(', ')}.`;
  if (merchant.length === 0 || merchant.length > 120) issues.merchant = 'Merchant must contain 1 to 120 characters.';
  if (notes.length > 1000) issues.notes = 'Notes must contain at most 1000 characters.';

  if (!/^\d{4}-\d{2}-\d{2}$/.test(date)) {
    issues.date = 'Date must use YYYY-MM-DD format.';
  } else {
    const timestamp = new Date(`${date}T00:00:00.000Z`);
    if (!Number.isFinite(timestamp.getTime()) || timestamp.toISOString().slice(0, 10) !== date) {
      issues.date = 'Date must be a real calendar date.';
    } else if (date < MIN_TRANSACTION_DATE || date > today.toISOString().slice(0, 10)) {
      issues.date = `Date must be between ${MIN_TRANSACTION_DATE} and today (UTC).`;
    }
  }

  let amountMinor = 0;
  if (isCurrency(currency)) {
    try {
      amountMinor = parseMinor(amount, currency);
    } catch (error) {
      if (!(error instanceof LedgerValidationError)) throw error;
      Object.assign(issues, error.issues);
    }
  }
  if (Object.keys(issues).length > 0 || !isCurrency(currency) || !category || (kind !== 'expense' && kind !== 'income')) {
    throw new LedgerValidationError(issues);
  }
  return { kind, amount: formatMinor(amountMinor, currency), amountMinor, currency, date, merchant, category, notes };
}

export function normalizeMerchant(merchant: string): string {
  return merchant.normalize('NFKD').replace(/\p{M}/gu, '').toLowerCase()
    .replace(/[^\p{L}\p{N}]+/gu, ' ').trim().replace(/\s+/gu, ' ');
}

export interface DuplicateScore {
  score: number;
  reason: string;
}

// Bank descriptors and generic business words cannot establish a merchant match.
const NON_IDENTIFYING_TOKENS = new Set([
  'the', 'inc', 'ltd', 'llc', 'co', 'corp', 'corporation', 'pos', 'debit',
  'credit', 'visa', 'mastercard', 'purchase', 'payment', 'store', 'shop', 'restaurant', 'cafe',
]);

function identifyingTokens(merchant: string): Set<string> {
  return new Set(merchant.split(' ').filter(token => token.length > 0
    && !NON_IDENTIFYING_TOKENS.has(token) && !/^\d+$/.test(token)));
}

/**
 * Candidates must have the same original amount, currency and direction, dates at
 * most three days apart, and an exact merchant or >= 50% identifying-token Jaccard
 * overlap. Scores explain suggestions only: callers must ask the user to keep/skip.
 */
export function scoreDuplicate(a: NormalizedTransaction, b: NormalizedTransaction): DuplicateScore | null {
  if (a.amountMinor !== b.amountMinor || a.currency !== b.currency || a.kind !== b.kind) return null;
  const days = Math.abs(Date.parse(a.date) - Date.parse(b.date)) / 86_400_000;
  if (!Number.isFinite(days) || days > 3) return null;
  const first = normalizeMerchant(a.merchant);
  const second = normalizeMerchant(b.merchant);
  if (!first || !second) return null;
  let similarity = 1;
  if (first !== second) {
    const firstTokens = identifyingTokens(first);
    const secondTokens = identifyingTokens(second);
    const overlap = [...firstTokens].filter(token => secondTokens.has(token)).length;
    const union = new Set([...firstTokens, ...secondTokens]).size;
    if (union === 0 || overlap === 0) return null;
    similarity = overlap / union;
    if (similarity < 0.5) return null;
  }
  const merchantReason = first === second ? 'same merchant' : 'overlapping merchant name';
  const dateReason = days === 0 ? 'same date' : `dates ${days} day${days === 1 ? '' : 's'} apart`;
  return {
    score: 50 + Math.round(35 * similarity) + (15 - days * 5),
    reason: `Same ${a.kind} amount and currency; ${merchantReason}; ${dateReason}.`,
  };
}
