/** Match explicit card repayment labels, never a merchant merely containing "payment". */
export function isCardRepayment(description: string): boolean {
  return /\b(?:payment\s+(?:thank\s*you|received)|(?:mobile|online|autopay|automatic|internet)\s+payment|payment\s*[-–]\s*thank\s*you)\b/i.test(description);
}
