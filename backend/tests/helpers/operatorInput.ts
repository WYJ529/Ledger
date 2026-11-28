/** MongoDB query operators and an array, each sent where a plain submission ID string is expected. */
export function operatorShapedIds(validId: string) {
  return [{ requestId: { $ne: null } }, { requestId: { $gt: '' } }, { requestId: [validId] }];
}
