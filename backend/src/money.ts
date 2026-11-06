/** Shared personal/group input limit, measured in the currency's minor unit. */
export const MAX_AMOUNT_MINOR = 1_000_000_000_000;
export class MoneyInputError extends Error {}

/** Trim outside whitespace; zero is explicit, while blank and signed amounts are invalid. */
export function parseMoney(value: unknown, allowZero = false, digits = 2): number {
  const text = typeof value === 'string' ? value.trim() : '';
  const pattern = digits === 0 ? /^\d+$/ : /^\d+(\.\d{1,2})?$/;
  if (text.length > 32 || !pattern.test(text)) {
    throw new MoneyInputError(digits === 0 ? 'Enter a whole amount (0 decimal places).' : 'Enter an amount with at most two decimal places.');
  }
  const [whole = '', fraction = ''] = text.split('.');
  const minor = BigInt(whole) * 10n ** BigInt(digits) + BigInt(fraction.padEnd(digits, '0') || '0');
  if (minor < (allowZero ? 0n : 1n) || minor > BigInt(MAX_AMOUNT_MINOR)) {
    throw new MoneyInputError('Amount is outside the allowed range.');
  }
  return Number(minor);
}

/** Signed balances and adjustments use the same exact formatting as transaction amounts. */
export function formatMinorUnits(minor: number, digits = 2): string {
  if (!Number.isSafeInteger(minor)) throw new MoneyInputError('The amount exceeds the supported integer range.');
  const amount = BigInt(minor), magnitude = amount < 0n ? -amount : amount, scale = 10n ** BigInt(digits);
  return (amount < 0n ? '-' : '') + (digits === 0 ? String(magnitude) : String(magnitude / scale) + '.' + String(magnitude % scale).padStart(digits, '0'));
}
