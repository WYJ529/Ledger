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

export type Share = { userId: string; amountCents: number; percentageBasisPoints?: number };

/** One basis point is 0.01%; parse decimal text without floating-point rounding. */
export function parsePercentage(value: unknown): number {
  if (typeof value !== 'string' || !/^\d{1,3}(\.\d{1,2})?$/.test(value)) {
    throw new SplitInputError('Enter a percentage from 0 to 100 with at most two decimal places.');
  }
  const [whole, fraction = ''] = value.split('.');
  const basisPoints = Number(whole) * 100 + Number(fraction.padEnd(2, '0'));
  if (basisPoints > 10000) throw new SplitInputError('Each percentage must be between 0 and 100.');
  return basisPoints;
}

function percentageShares(totalCents: number, input: { userId: string; percentage?: unknown }[]): Share[] {
  const portions = input.map(item => {
    const percentageBasisPoints = parsePercentage(item.percentage);
    // At most 100,000,000 * 10,000: all arithmetic stays in the safe integer range.
    const product = totalCents * percentageBasisPoints;
    return { userId: item.userId, percentageBasisPoints,
      amountCents: Math.floor(product / 10000), remainder: product % 10000 };
  });
  if (portions.reduce((sum, share) => sum + share.percentageBasisPoints, 0) !== 10000) {
    throw new SplitInputError('Percentages must add up exactly to 100%.');
  }
  const remaining = totalCents - portions.reduce((sum, share) => sum + share.amountCents, 0);
  // Largest fractional remainders receive the extra cents; ties use ascending member IDs.
  const ranked = [...portions].sort((a, b) => b.remainder - a.remainder || (a.userId < b.userId ? -1 : 1));
  const extraCentIds = new Set(ranked.slice(0, remaining).map(share => share.userId));
  return portions.sort((a, b) => a.userId < b.userId ? -1 : 1).map(share => ({
    userId: share.userId, percentageBasisPoints: share.percentageBasisPoints,
    amountCents: share.amountCents + (extraCentIds.has(share.userId) ? 1 : 0),
  }));
}

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
  if (mode === 'percentage') return percentageShares(totalCents, input);
  if (mode !== 'custom') throw new SplitInputError('Choose equal, custom or percentage splitting.');
  const shares = input.map((item: { userId: string; amount?: unknown }) => ({
    userId: item.userId, amountCents: parseMoney(item.amount, true),
  })).sort((a, b) => a.userId.localeCompare(b.userId));
  if (shares.reduce((sum, share) => sum + share.amountCents, 0) !== totalCents) {
    throw new SplitInputError('Custom shares must add up exactly to the bill total.');
  }
  return shares;
}
