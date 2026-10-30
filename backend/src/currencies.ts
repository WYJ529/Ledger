/** Currencies supported by the personal ledger, account preferences and groups. */
export const CURRENCIES = ['CAD', 'USD', 'EUR', 'GBP', 'CNY', 'JPY', 'AUD', 'HKD', 'SGD', 'KRW', 'MXN', 'INR'] as const;
export type Currency = typeof CURRENCIES[number];

// ISO 4217 minor units: yen and won have no fractional unit.
const ZERO_DECIMAL_CURRENCIES: ReadonlySet<string> = new Set(['JPY', 'KRW']);

export function isSupportedCurrencyCode(value: unknown): value is Currency {
  return typeof value === 'string' && (CURRENCIES as readonly string[]).includes(value);
}

/** Number of decimal places in the currency's minor unit (0 or 2). */
export function minorUnitDigits(currency: string): number {
  return ZERO_DECIMAL_CURRENCIES.has(currency) ? 0 : 2;
}
