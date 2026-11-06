import type { Response } from 'express';
import { hashPassword, rejectUnknownPassword, verifyPassword } from './password';
import { DuplicateAccountError, type UserStore } from './userStore';
import { normalizeUsername, passwordProblem } from './validation';

type Credentials = { username?: unknown; password?: unknown; confirmPassword?: unknown } | undefined;
const USERNAME_TAKEN = 'This username is already taken. Choose another username.';

export async function registerAccount(body: Credentials, users: UserStore, res: Response, googleId?: string) {
  const username = normalizeUsername(body?.username);
  const password = body?.password;
  const problem = passwordProblem(password);
  if (!username) { res.status(400).json({ error: 'Use 3-30 letters, numbers, dots, hyphens or underscores for your username.' }); return; }
  if (problem || typeof password !== 'string') { res.status(400).json({ error: problem }); return; }
  if (body?.confirmPassword !== password) { res.status(400).json({ error: 'Passwords do not match.' }); return; }
  if (await users.findByUsername(username)) { res.status(409).json({ error: USERNAME_TAKEN }); return; }
  try {
    return await users.create({ username, displayName: username, passwordHash: await hashPassword(password), ...(googleId ? { googleId } : {}) });
  } catch (error) {
    if (!(error instanceof DuplicateAccountError)) throw error;
    res.status(409).json({ error: USERNAME_TAKEN });
    return;
  }
}

export async function passwordAccount(body: Credentials, users: UserStore, res: Response) {
  const username = normalizeUsername(body?.username);
  const password = body?.password;
  if (!username || typeof password !== 'string' || !password || password.length > 128) {
    res.status(400).json({ error: 'Enter your username and password.' }); return;
  }
  const user = await users.findByUsername(username);
  const valid = user?.passwordHash ? await verifyPassword(password, user.passwordHash) : await rejectUnknownPassword(password);
  if (!user?.passwordHash || !valid) { res.status(401).json({ error: 'Incorrect username or password.' }); return; }
  return { ...user, passwordHash: user.passwordHash };
}
