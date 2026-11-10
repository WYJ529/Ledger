import { MAX_AMOUNT_MINOR, parseMoney, formatMinorUnits, MoneyInputError } from './money';
export const MAX_PARTICIPANTS = 100;

export type Share = { userId: string; amountCents: number; percentageBasisPoints?: number; adjustmentCents?: number };

/** One basis point is 0.01%; parse decimal text without floating-point rounding. */
export function parsePercentage(value: unknown): number {
  const points = parseMoney(value, true);
  if (points > 10000) throw new MoneyInputError('Each percentage must be between 0 and 100.');
  return points;
}

/** Integer weights, exact products, largest remainder first; ties use ascending member IDs. */
function allocate(total: number, weights: { userId: string; weight: number }[]) {
  const denominator = BigInt(weights.reduce((sum, item) => sum + item.weight, 0));
  const portions = weights.map(item => {
    const product = BigInt(total) * BigInt(item.weight);
    return { userId: item.userId, amountCents: denominator ? Number(product / denominator) : 0,
      remainder: denominator ? product % denominator : 0n };
  });
  const remaining = denominator ? total - portions.reduce((sum, item) => sum + item.amountCents, 0) : 0;
  const ranked = [...portions].sort((a, b) => a.remainder === b.remainder ? (a.userId < b.userId ? -1 : 1) : a.remainder > b.remainder ? -1 : 1);
  const extra = new Set(ranked.slice(0, remaining).map(item => item.userId));
  return portions.map(item => ({ userId: item.userId, amountCents: item.amountCents + (extra.has(item.userId) ? 1 : 0) }));
}

function percentageShares(total: number, input: { userId: string; percentage?: unknown }[]): Share[] {
  const weights = input.map(item => ({ userId: item.userId, weight: parsePercentage(item.percentage) })).sort((a, b) => a.userId < b.userId ? -1 : 1);
  if (weights.reduce((sum, item) => sum + item.weight, 0) !== 10000) throw new MoneyInputError('Percentages must add up exactly to 100%.');
  return allocate(total, weights).map((share, index) => ({ ...share, percentageBasisPoints: weights[index]?.weight ?? 0 }));
}

export function proportionalShares(items: { userId: string; amountCents: number }[], extra: number) {
  return allocate(extra, items.map(item => ({ userId: item.userId, weight: item.amountCents })))
    .map((share, index) => ({ ...share, amountCents: share.amountCents + (items[index]?.amountCents ?? 0) }));
}

/** Signed decimal text such as "8", "+8", "-2.50"; blank or missing means no adjustment. */
export function parseAdjustment(value: unknown, digits = 2): number {
  if (value == null) return 0;
  if (typeof value !== 'string') throw new MoneyInputError('Enter a valid adjustment.');
  const text = value.trim().replace(/−/g, '-');
  if (!text) return 0;
  if (!/^[+-]?\d+(\.\d+)?$/.test(text)) throw new MoneyInputError('Enter a valid adjustment.');
  const cents = parseMoney(text.replace(/^[+-]/, ''), true, digits);
  return text.startsWith('-') && cents > 0 ? -cents : cents;
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
  if (pool < 0) throw new MoneyInputError('The added amounts are larger than the bill total.');
  const allocations = allocate(pool, adjustments.map(item => ({ userId: item.userId, weight: 1 })));
  const shares = adjustments.map((item, index) => ({ ...item,
    amountCents: (allocations[index]?.amountCents ?? 0) + item.adjustmentCents }));
  if (shares.some(share => share.amountCents < 0)) {
    throw new MoneyInputError("A deduction is larger than that member's equal share. Reduce it so every share is at least 0.");
  }
  return shares;
}

/** Round each person's GST, extra tax and pretax tip independently, half up to cents. */
export function calculateMeal(value: unknown, memberIds: readonly string[], digits = 2) {
  const input = value as { tipRate?: unknown; members?: unknown } | null;
  const tipRate = parsePercentage(input?.tipRate);
  if (!Array.isArray(input?.members) || input.members.length < 1 || input.members.length > MAX_PARTICIPANTS) {
    throw new MoneyInputError('Select between 1 and 100 members.');
  }
  const seen = new Set<string>();
  const members = input.members.map((raw: unknown) => {
    const m = raw as { userId?: unknown; subtotal?: unknown; extraTaxRate?: unknown } | null;
    if (!m || typeof m.userId !== 'string' || !memberIds.includes(m.userId) || seen.has(m.userId)) throw new MoneyInputError('Choose unique group members.');
    seen.add(m.userId);
    return { userId: m.userId, subtotal: parseMoney(m.subtotal, true, digits), extraRate: parsePercentage(m.extraTaxRate) };
  }).sort((a, b) => a.userId < b.userId ? -1 : a.userId > b.userId ? 1 : 0);
  let subtotalCents = 0, taxCents = 0, tipCents = 0;
  const shares = members.map(m => {
    const tax = Number((BigInt(m.subtotal) * 500n + 5000n) / 10000n) + Number((BigInt(m.subtotal) * BigInt(m.extraRate) + 5000n) / 10000n);
    const tip = Number((BigInt(m.subtotal) * BigInt(tipRate) + 5000n) / 10000n);
    subtotalCents += m.subtotal; taxCents += tax; tipCents += tip;
    return { userId: m.userId, amountCents: m.subtotal + tax + tip };
  });
  const amountCents = subtotalCents + taxCents + tipCents;
  if (subtotalCents < 1 || amountCents > MAX_AMOUNT_MINOR) {
    throw new MoneyInputError(`Meal total must be positive and at most ${formatMinorUnits(MAX_AMOUNT_MINOR, digits)}.`);
  }
  const rateText = (basisPoints: number) => formatMinorUnits(basisPoints);
  return { subtotalCents, taxCents, tipCents, amountCents, shares,
    customCalculation: { tipRate: rateText(tipRate), members: members.map(m => ({ userId: m.userId, subtotal: formatMinorUnits(m.subtotal, digits), extraTaxRate: rateText(m.extraRate) })) } };
}

export function splitBill(totalCents: number, mode: unknown, input: unknown, memberIds: readonly string[], digits = 2): Share[] {
  if (!Number.isSafeInteger(totalCents) || totalCents < 1 || totalCents > MAX_AMOUNT_MINOR) {
    throw new MoneyInputError('Invalid total amount.');
  }
  if (!Array.isArray(input) || input.length < 1 || input.length > MAX_PARTICIPANTS) {
    throw new MoneyInputError('Select between 1 and 100 members.');
  }
  const ids = input.map((item: unknown) => {
    if (!item || typeof item !== 'object' || !('userId' in item) || typeof item.userId !== 'string') {
      throw new MoneyInputError('Each share needs a member ID.');
    }
    return item.userId;
  });
  if (new Set(ids).size !== ids.length) throw new MoneyInputError('Select each member only once.');
  if (ids.some(id => !memberIds.includes(id))) throw new MoneyInputError('Every participant must belong to this group.');
  // Stable order means the same members receive remainder cents regardless of request ordering.
  const sortedIds = [...ids].sort();
  if (mode === 'equal') return allocate(totalCents, sortedIds.map(userId => ({ userId, weight: 1 })));
  if (mode === 'percentage') return percentageShares(totalCents, input);
  if (mode === 'adjusted') return adjustedShares(totalCents, input, digits);
  if (mode !== 'custom') throw new MoneyInputError('Choose equal, custom, percentage or adjusted splitting.');
  const shares = input.map((item: { userId: string; amount?: unknown }) => ({
    userId: item.userId, amountCents: parseMoney(item.amount, true, digits),
  })).sort((a, b) => a.userId.localeCompare(b.userId));
  if (shares.reduce((sum, share) => sum + share.amountCents, 0) !== totalCents) {
    throw new MoneyInputError('Custom shares must add up exactly to the bill total.');
  }
  return shares;
}
