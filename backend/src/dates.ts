export const MIN_TRANSACTION_DATE = '1999-01-04';

export function dateProblem(value: unknown, today = new Date()): string | undefined {
  if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(value)) return 'Date must use YYYY-MM-DD format.';
  const date = new Date(value + 'T00:00:00Z');
  if (!Number.isFinite(date.getTime()) || date.toISOString().slice(0, 10) !== value) return 'Date must be a real calendar date.';
  if (value < MIN_TRANSACTION_DATE || value > today.toISOString().slice(0, 10)) return 'Date must be between ' + MIN_TRANSACTION_DATE + ' and today (UTC).';
  return undefined;
}

export function isHistoricalDate(value: unknown): value is string { return dateProblem(value) === undefined; }
