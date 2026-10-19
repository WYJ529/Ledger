import { randomBytes, scrypt, timingSafeEqual } from 'node:crypto';

// OWASP suggests N=2^17; N=2^14 (16 MiB per hash) keeps concurrent logins cheap on a small VM.
const COST = 16384;
const BLOCK_SIZE = 8;
const PARALLELISM = 1;
const KEY_LENGTH = 64;
const SALT_LENGTH = 16;

function derive(password: string, salt: Buffer, cost: number, blockSize: number, parallelism: number): Promise<Buffer> {
  return new Promise((resolve, reject) => {
    scrypt(password.normalize('NFKC'), salt, KEY_LENGTH,
      { N: cost, r: blockSize, p: parallelism, maxmem: 128 * cost * blockSize * 2 },
      (error, key) => { if (error) reject(error); else resolve(key); });
  });
}

/** Returns a self-describing hash: scrypt$N$r$p$salt$key (base64). */
export async function hashPassword(password: string): Promise<string> {
  const salt = randomBytes(SALT_LENGTH);
  const key = await derive(password, salt, COST, BLOCK_SIZE, PARALLELISM);
  return ['scrypt', COST, BLOCK_SIZE, PARALLELISM, salt.toString('base64'), key.toString('base64')].join('$');
}

export async function verifyPassword(password: string, stored: string): Promise<boolean> {
  const [scheme, cost, blockSize, parallelism, salt, key] = stored.split('$');
  if (scheme !== 'scrypt' || !salt || !key) return false;
  const params = [cost, blockSize, parallelism].map(value => Number.parseInt(value ?? '', 10));
  if (params.some(value => !Number.isSafeInteger(value) || value < 1)) return false;
  const expected = Buffer.from(key, 'base64');
  const actual = await derive(password, Buffer.from(salt, 'base64'), params[0]!, params[1]!, params[2]!);
  return expected.length === actual.length && timingSafeEqual(expected, actual);
}

let dummyHash: Promise<string> | undefined;
/** Spends the same work as a real check so unknown emails are not revealed by response time. */
export async function rejectUnknownPassword(password: string): Promise<false> {
  dummyHash ??= hashPassword(randomBytes(16).toString('hex'));
  await verifyPassword(password, await dummyHash);
  return false;
}
