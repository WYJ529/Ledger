/** Money crosses the API as decimal text, and is stored/calculated as integer cents. */
export const MAX_AMOUNT_CENTS = 100_000_000;
export const MAX_PARTICIPANTS = 100;

export class SplitInputError extends Error {}

export function parseMoney(value: unknown, allowZero = false): number {
  if (typeof value !== 'string' || !/^\d{1,7}(\.\d{1,2})?$/.test(value)) {
    throw new SplitInputError('Enter an amount with at most two decimal places.');
  }
  const [whole, fraction = ''] = value.split('.');
  const cents = Number(whole) * 100 + Number(fraction.padEnd(2, '0'));
  if (!Number.isSafeInteger(cents) || cents > MAX_AMOUNT_CENTS || cents < (allowZero ? 0 : 1)) {
    throw new SplitInputError('Amount must be between 0.01 and 1,000,000.00.');
  }
  return cents;
}

export type Share = { userId: string; amountCents: number };

export function splitBill(totalCents: number, mode: unknown, input: unknown, memberIds: readonly string[]): Share[] {
  if (!Number.isSafeInteger(totalCents) || totalCents < 1 || totalCents > MAX_AMOUNT_CENTS) {
    throw new SplitInputError('Invalid total amount.');
  }
  if (!Array.isArray(input) || input.length < 1 || input.length > MAX_PARTICIPANTS) {
    throw new SplitInputError('Select between 1 and 100 members.');
  }
  const ids = input.map((item: unknown) => {
    if (!item || typeof item !== 'object' || !('userId' in item) || typeof item.userId !== 'string') {
      throw new SplitInputError('Each share needs a member ID.');
    }
    return item.userId;
  });
  if (new Set(ids).size !== ids.length) throw new SplitInputError('Select each member only once.');
  if (ids.some(id => !memberIds.includes(id))) throw new SplitInputError('Every participant must belong to this group.');
  // Stable order means the same members receive remainder cents regardless of request ordering.
  const sortedIds = [...ids].sort();
  if (mode === 'equal') {
    const base = Math.floor(totalCents / ids.length);
    const remainder = totalCents % ids.length;
    return sortedIds.map((userId, index) => ({ userId, amountCents: base + (index < remainder ? 1 : 0) }));
  }
  if (mode !== 'custom') throw new SplitInputError('Choose equal or custom splitting.');
  const shares = input.map((item: { userId: string; amount?: unknown }) => ({
    userId: item.userId, amountCents: parseMoney(item.amount, true),
  })).sort((a, b) => a.userId.localeCompare(b.userId));
  if (shares.reduce((sum, share) => sum + share.amountCents, 0) !== totalCents) {
    throw new SplitInputError('Custom shares must add up exactly to the bill total.');
  }
  return shares;
}
