import { Router } from 'express';
import { accountRateLimit } from './rateLimit';
import { registerAccount, passwordAccount } from './accountCredentials';
import { publicUser, requireSession, startSession, type AuthenticatedLocals } from './session';
import type { SessionStore, UserStore } from './userStore';

/** Username/password accounts and session logout, mounted at /api/auth. */
export function accountAuthRouter(users: UserStore, sessions: SessionStore): Router {
  const router = Router();
  router.post('/register', accountRateLimit(30), async (req, res) => {
    const user = await registerAccount(req.body, users, res);
    if (user) res.status(201).json(await startSession(sessions, user));
  });

  router.post('/login', accountRateLimit(60), async (req, res) => {
    const user = await passwordAccount(req.body, users, res);
    if (user) res.json(await startSession(sessions, user));
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
