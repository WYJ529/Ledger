import { hashPassword, rejectUnknownPassword, verifyPassword } from '../../src/password';
import { normalizeDisplayName, normalizeEmail, passwordProblem } from '../../src/validation';

describe('Password hashing', () => {
  // Expected: salted hashes that verify only the original password.
  test('Verifies the original password only', async () => {
    const first = await hashPassword('correct horse');
    const second = await hashPassword('correct horse');
    expect(first).not.toBe(second);
    expect(first).not.toContain('correct horse');
    expect(await verifyPassword('correct horse', first)).toBe(true);
    expect(await verifyPassword('correct horsE', first)).toBe(false);
  });
  // Input: corrupted stored hashes. Expected: false rather than an exception.
  test.each(['', 'bcrypt$1$2$3$a$b', 'scrypt$x$8$1$c2FsdA==$a2V5', 'scrypt$16384$8$1$$'])('Rejects malformed hash %#', async stored => {
    expect(await verifyPassword('anything', stored)).toBe(false);
  });
  test('Unknown accounts always fail', async () => {
    expect(await rejectUnknownPassword('anything')).toBe(false);
  });
});

describe('Account input validation', () => {
  test('Normalizes email addresses', () => {
    expect(normalizeEmail('  Sam@Example.COM ')).toBe('sam@example.com');
    expect(normalizeEmail('no-at-sign')).toBeUndefined();
    expect(normalizeEmail('a@b')).toBeUndefined();
    expect(normalizeEmail(`${'a'.repeat(250)}@b.co`)).toBeUndefined();
    expect(normalizeEmail(42)).toBeUndefined();
  });
  test('Checks password length', () => {
    expect(passwordProblem('12345678')).toBeUndefined();
    expect(passwordProblem('1234567')).toContain('at least');
    expect(passwordProblem(null)).toContain('required');
  });
  test('Normalizes display names', () => {
    expect(normalizeDisplayName('  Sam   Lee ')).toBe('Sam Lee');
    expect(normalizeDisplayName('Sam\u0000')).toBeUndefined();
    expect(normalizeDisplayName(' ')).toBeUndefined();
    expect(normalizeDisplayName(undefined)).toBeUndefined();
  });
});
