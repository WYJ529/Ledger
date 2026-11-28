import type { Response } from 'supertest';

/** MongoDB query operators and an array, each sent where a plain submission ID string is expected. */
export function operatorShapedIds(validId: string): unknown[] {
  return [{ $ne: null }, { $gt: '' }, [validId]];
}

/** Sends each value and expects every request to be rejected as invalid input. */
export async function expectEachRejected(values: unknown[], send: (value: unknown) => Promise<Response>) {
  expect(values.length).toBeGreaterThan(0);
  for (const value of values) expect((await send(value)).status).toBe(400);
}
