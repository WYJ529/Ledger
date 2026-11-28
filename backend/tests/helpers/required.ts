/** Fails at the fixture lookup when an expected row, event or response field is missing. */
export function required<T>(value: T): NonNullable<T> {
  if (value == null) throw new Error('Expected the test fixture to contain a value.');
  return value;
}

export function requiredString(value: unknown): string {
  if (typeof value !== 'string') throw new Error('Expected a string in the test response.');
  return value;
}
