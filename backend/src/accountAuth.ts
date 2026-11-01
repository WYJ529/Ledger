import { Router, type RequestHandler } from 'express';
import { hashPassword, rejectUnknownPassword, verifyPassword } from './password';
import { publicUser, requireSession, startSession, type AuthenticatedLocals } from './session';
import { DuplicateAccountError, type SessionStore, type UserStore } from './userStore';
import { normalizeUsername, passwordProblem } from './validation';

export const USERNAME_TAKEN = 'This username is already taken. Choose another username.';

export function registrationRateLimit(maximum: number): RequestHandler {
  const clients = new Map<string, { count: number; resetAt: number }>();
  return (req, res, next) => {
    const now = Date.now();
    for (const [key, entry] of clients) if (entry.resetAt <= now) clients.delete(key);
    const key = req.ip ?? req.socket.remoteAddress ?? 'unknown';
    let entry = clients.get(key);
    if (!entry) {
      if (clients.size >= 2000) { res.status(503).json({ error: 'Registration is busy. Please try again later.' }); return; }
      entry = { count: 0, resetAt: now + 10 * 60 * 1000 };
      clients.set(key, entry);
    }
    if (entry.count >= maximum) {
      const retryAfterSeconds = Math.ceil((entry.resetAt - now) / 1000);
      res.setHeader('Retry-After', retryAfterSeconds);
      res.status(429).json({ error: 'Too many registration requests. Please try again later.', retryAfterSeconds });
      return;
    }
    entry.count++;
    next();
  };
}

/** Username/password accounts and session logout, mounted at /api/auth. */
export function accountAuthRouter(users: UserStore, sessions: SessionStore): Router {
  const router = Router();
  router.post('/register', registrationRateLimit(30), async (req, res) => {
    const username = normalizeUsername(req.body?.username);
    const problem = passwordProblem(req.body?.password);
    if (!username) { res.status(400).json({ error: 'Use 3-30 letters, numbers, dots, hyphens or underscores for your username.' }); return; }
    if (problem) { res.status(400).json({ error: problem }); return; }
    if (req.body.confirmPassword !== req.body.password) { res.status(400).json({ error: 'Passwords do not match.' }); return; }
    if (await users.findByUsername(username)) { res.status(409).json({ error: USERNAME_TAKEN }); return; }
    try {
      const user = await users.create({ username, displayName: username, passwordHash: await hashPassword(req.body.password) });
      res.status(201).json(await startSession(sessions, user));
    } catch (error) {
      if (!(error instanceof DuplicateAccountError)) throw error;
      res.status(409).json({ error: USERNAME_TAKEN });
    }
  });

  router.post('/login', registrationRateLimit(60), async (req, res) => {
    const username = normalizeUsername(req.body?.username);
    const password: unknown = req.body?.password;
    if (!username || typeof password !== 'string' || !password || password.length > 128) {
      res.status(400).json({ error: 'Enter your username and password.' }); return;
    }
    const user = await users.findByUsername(username);
    const valid = user?.passwordHash ? await verifyPassword(password, user.passwordHash) : await rejectUnknownPassword(password);
    if (!user || !valid) { res.status(401).json({ error: 'Incorrect username or password.' }); return; }
    res.json(await startSession(sessions, user));
  });

  router.post('/logout', requireSession(users, sessions), async (_req, res) => {
    await sessions.delete((res.locals as AuthenticatedLocals).tokenHash);
    res.status(204).end();
  });

  return router;
}

/** Signed-in account information, mounted at /api/users. */
export function userRouter(users: UserStore, sessions: SessionStore): Router {
  const router = Router();
  router.get('/me', requireSession(users, sessions), (_req, res) => {
    res.json({ user: publicUser((res.locals as AuthenticatedLocals).user) });
  });
  return router;
}
