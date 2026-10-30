/** Money crosses the API as decimal text, and is stored/calculated as integer cents. */
export const MAX_AMOUNT_CENTS = 100_000_000;
export const MAX_PARTICIPANTS = 100;

export class SplitInputError extends Error {}

/** Integer minor units as decimal text: "1234.50" for two-digit currencies, "1234" for yen and won. */
export function formatMinorUnits(minor: number, digits = 2): string {
  if (digits === 0) return String(minor);
  return `${Math.floor(minor / 100)}.${String(minor % 100).padStart(2, '0')}`;
}

/**
 * Decimal text to integer minor units. [digits] is the currency's decimal places (2, or 0 for JPY and
 * KRW); percentages also use 2. The limit is MAX_AMOUNT_CENTS minor units in every currency.
 */
export function parseMoney(value: unknown, allowZero = false, digits = 2): number {
  const pattern = digits === 0 ? /^\d{1,9}$/ : /^\d{1,9}(\.\d{1,2})?$/;
  if (typeof value !== 'string' || !pattern.test(value)) {
    throw new SplitInputError(digits === 0 ? 'Enter a whole amount without decimals.' : 'Enter an amount with at most two decimal places.');
  }
  const [whole, fraction = ''] = value.split('.');
  const minor = digits === 0 ? Number(whole) : Number(whole) * 100 + Number(fraction.padEnd(2, '0'));
  if (!Number.isSafeInteger(minor) || minor > MAX_AMOUNT_CENTS || minor < (allowZero ? 0 : 1)) {
    throw new SplitInputError(digits === 0 ? 'Amount must be between 1 and 100,000,000.' : 'Amount must be between 0.01 and 1,000,000.00.');
  }
  return minor;
}

export type Share = { userId: string; amountCents: number; percentageBasisPoints?: number; adjustmentCents?: number };

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

/** Signed decimal text such as "8", "+8", "-2.50"; blank or missing means no adjustment. */
export function parseAdjustment(value: unknown, digits = 2): number {
  if (value === undefined || value === null || value === '') return 0;
  if (typeof value !== 'string' || !/^[+-]?\d{1,7}(\.\d{1,2})?$/.test(value)) {
    throw new SplitInputError('Enter each adjustment as an amount such as 8, -2.50 or 0, with at most two decimal places.');
  }
  const cents = parseMoney(value.replace(/^[+-]/, ''), true, digits);
  return value.startsWith('-') && cents > 0 ? -cents : cents;
}

/**
 * Equal split plus per-member adjustments: the adjustments are set aside, the rest of the bill is split
 * equally (extra cents by ascending member ID, as for an equal split), then each adjustment is added back.
 * E.g. CAD 40.00 with +8.00 for one of four members: the others pay 8.00 each and that member pays 16.00.
 */
function adjustedShares(totalCents: number, input: { userId: string; adjustment?: unknown }[], digits: number): Share[] {
  const adjustments = input.map(item => ({ userId: item.userId, adjustmentCents: parseAdjustment(item.adjustment, digits) }))
    .sort((a, b) => a.userId < b.userId ? -1 : 1);
  const pool = totalCents - adjustments.reduce((sum, item) => sum + item.adjustmentCents, 0);
  if (pool < 0) throw new SplitInputError('The added amounts are larger than the bill total.');
  const base = Math.floor(pool / adjustments.length);
  const remainder = pool % adjustments.length;
  const shares = adjustments.map((item, index) => ({ userId: item.userId, adjustmentCents: item.adjustmentCents,
    amountCents: base + (index < remainder ? 1 : 0) + item.adjustmentCents }));
  if (shares.some(share => share.amountCents < 0)) {
    throw new SplitInputError("A deduction is larger than that member's equal share. Reduce it so every share is at least 0.");
  }
  return shares;
}

/** Round each person's GST, extra tax and pretax tip independently, half up to cents. */
export function calculateMeal(value: unknown, memberIds: readonly string[], digits = 2) {
  const input = value as { tipRate?: unknown; members?: unknown } | null;
  function rate(raw: unknown): number {
    const parsed = parseMoney(raw, true);
    if (parsed > 10000) throw new SplitInputError('Percentages must be between 0 and 100.');
    return parsed;
  }
  const tipRate = rate(input?.tipRate);
  if (!Array.isArray(input?.members) || input.members.length < 1 || input.members.length > MAX_PARTICIPANTS) {
    throw new SplitInputError('Select between 1 and 100 members.');
  }
  const seen = new Set<string>();
  const members = input.members.map((raw: unknown) => {
    const m = raw as { userId?: unknown; subtotal?: unknown; extraTaxRate?: unknown } | null;
    if (!m || typeof m.userId !== 'string' || !memberIds.includes(m.userId) || seen.has(m.userId)) throw new SplitInputError('Choose unique group members.');
    seen.add(m.userId);
    return { userId: m.userId, subtotal: parseMoney(m.subtotal, true, digits), extraRate: rate(m.extraTaxRate) };
  }).sort((a, b) => a.userId < b.userId ? -1 : a.userId > b.userId ? 1 : 0);
  let subtotalCents = 0, taxCents = 0, tipCents = 0;
  const shares = members.map(m => {
    const tax = Math.floor((m.subtotal * 500 + 5000) / 10000) + Math.floor((m.subtotal * m.extraRate + 5000) / 10000);
    const tip = Math.floor((m.subtotal * tipRate + 5000) / 10000);
    subtotalCents += m.subtotal; taxCents += tax; tipCents += tip;
    return { userId: m.userId, amountCents: m.subtotal + tax + tip };
  });
  const amountCents = subtotalCents + taxCents + tipCents;
  if (subtotalCents < 1 || amountCents > MAX_AMOUNT_CENTS) {
    throw new SplitInputError(`Meal total must be positive and at most ${digits === 0 ? '100,000,000' : '1,000,000.00'}.`);
  }
  const rateText = (basisPoints: number) => formatMinorUnits(basisPoints);
  return { subtotalCents, taxCents, tipCents, amountCents, shares,
    customCalculation: { tipRate: rateText(tipRate), members: members.map(m => ({ userId: m.userId, subtotal: formatMinorUnits(m.subtotal, digits), extraTaxRate: rateText(m.extraRate) })) } };
}

export function splitBill(totalCents: number, mode: unknown, input: unknown, memberIds: readonly string[], digits = 2): Share[] {
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
  if (mode === 'adjusted') return adjustedShares(totalCents, input, digits);
  if (mode !== 'custom') throw new SplitInputError('Choose equal, custom, percentage or adjusted splitting.');
  const shares = input.map((item: { userId: string; amount?: unknown }) => ({
    userId: item.userId, amountCents: parseMoney(item.amount, true, digits),
  })).sort((a, b) => a.userId.localeCompare(b.userId));
  if (shares.reduce((sum, share) => sum + share.amountCents, 0) !== totalCents) {
    throw new SplitInputError('Custom shares must add up exactly to the bill total.');
  }
  return shares;
}
