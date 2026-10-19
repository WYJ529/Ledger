import { Router } from 'express';
import { hashPassword, rejectUnknownPassword, verifyPassword } from './password';
import { publicUser, requireSession, startSession, type AuthenticatedLocals } from './session';
import { DuplicateAccountError, type SessionStore, type UserStore } from './userStore';
import { normalizeDisplayName, normalizeEmail, passwordProblem } from './validation';

const EMAIL_TAKEN = 'An account with this email already exists. Sign in instead.';

/** Email + password accounts and session logout, mounted at /api/auth. */
export function accountAuthRouter(users: UserStore, sessions: SessionStore): Router {
  const router = Router();

  router.post('/register', async (req, res) => {
    const email = normalizeEmail(req.body?.email);
    const displayName = normalizeDisplayName(req.body?.displayName);
    const problem = passwordProblem(req.body?.password);
    if (!email) { res.status(400).json({ error: 'Enter a valid email address.' }); return; }
    if (!displayName) { res.status(400).json({ error: 'Enter a name of 1-60 characters.' }); return; }
    if (problem) { res.status(400).json({ error: problem }); return; }
    if (await users.findByEmail(email)) { res.status(409).json({ error: EMAIL_TAKEN }); return; }
    try {
      const user = await users.create({ email, displayName, passwordHash: await hashPassword(req.body.password) });
      res.status(201).json(await startSession(sessions, user));
    } catch (error) {
      if (!(error instanceof DuplicateAccountError)) throw error;
      res.status(409).json({ error: EMAIL_TAKEN });
    }
  });

  router.post('/login', async (req, res) => {
    const email = normalizeEmail(req.body?.email);
    const password: unknown = req.body?.password;
    if (!email || typeof password !== 'string' || !password || password.length > 1024) {
      res.status(400).json({ error: 'Enter your email and password.' });
      return;
    }
    const user = await users.findByEmail(email);
    const valid = user?.passwordHash
      ? await verifyPassword(password, user.passwordHash)
      : await rejectUnknownPassword(password);
    if (!user || !valid) {
      // One message for unknown email, wrong password and Google-only accounts.
      res.status(401).json({ error: 'Incorrect email or password.' });
      return;
    }
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
