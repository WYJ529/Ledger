export const PASSWORD_MIN_LENGTH = 8;
export const PASSWORD_MAX_LENGTH = 128;
export const DISPLAY_NAME_MAX_LENGTH = 60;

// Deliberately simple: one @, no spaces, a dot in the domain. Ownership is not verified.
const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
/** True when `value` contains an ASCII control character (U+0000–U+001F or DEL). */
function hasControlCharacter(value: string): boolean {
  for (const character of value) {
    const code = character.charCodeAt(0);
    if (code < 0x20 || code === 0x7f) return true;
  }
  return false;
}

export function normalizeEmail(value: unknown): string | undefined {
  if (typeof value !== 'string') return undefined;
  const email = value.trim().toLowerCase();
  return email.length <= 254 && EMAIL_PATTERN.test(email) ? email : undefined;
}

export function passwordProblem(value: unknown): string | undefined {
  if (typeof value !== 'string') return 'A password is required.';
  if (value.length < PASSWORD_MIN_LENGTH) return `Password must be at least ${PASSWORD_MIN_LENGTH} characters.`;
  if (value.length > PASSWORD_MAX_LENGTH) return `Password must be at most ${PASSWORD_MAX_LENGTH} characters.`;
  return undefined;
}

export function normalizeDisplayName(value: unknown): string | undefined {
  if (typeof value !== 'string') return undefined;
  const name = value.trim().replace(/\s+/g, ' ');
  if (!name || name.length > DISPLAY_NAME_MAX_LENGTH || hasControlCharacter(name)) return undefined;
  return name;
}

/** Login names are case-insensitive and independent of editable display names. */
export function normalizeUsername(value: unknown): string | undefined {
  if (typeof value !== 'string') return undefined;
  const username = value.trim().toLowerCase();
  return /^[a-z0-9][a-z0-9_.-]{2,29}$/.test(username) ? username : undefined;
}
