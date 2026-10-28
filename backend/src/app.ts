import express, { json, type ErrorRequestHandler, type Express } from 'express';
import { formatLocalTime, serverAddress } from './serverInfo';
import { googleAuthRouter, verifyGoogleToken, type TokenVerifier } from './googleAuth';
import { accountAuthRouter, userRouter } from './accountAuth';
import { mongoSessionStore, mongoUserStore, type SessionStore, type UserStore } from './userStore';
import { groupExpensesRouter } from './groupExpenses';
import { invitationWebRouter } from './groupManagement';
import { createLedgerRouter } from './ledger/router';
import { createSplitSessionsRouter } from './splitSessions';

export type AppDependencies = {
  verifyToken: TokenVerifier;
  users: UserStore;
  sessions: SessionStore;
};

export function createApp(dependencies: Partial<AppDependencies> = {}): Express {
  const { verifyToken = verifyGoogleToken, users = mongoUserStore, sessions = mongoSessionStore } = dependencies;
  const app = express();
  const splitSessions = createSplitSessionsRouter(users, sessions);
  // Document routes use their own bounded 7 MiB JSON parser before the small API parser.
  app.use('/api/ledger', createLedgerRouter());
  // Up to 200 reviewed item units can exceed the ordinary small API body limit.
  app.use('/api/groups/:groupId/split-sessions', json({ limit: '128kb' }));
  app.use('/api', json({ limit: '16kb' }));
  app.use(['/api/auth', '/api/users'], (_req, res, next) => { res.setHeader('Cache-Control', 'no-store'); next(); });
  app.use('/api/auth', googleAuthRouter(verifyToken, users, sessions));
  app.use('/api/auth', accountAuthRouter(users, sessions));
  app.use('/api/users', userRouter(users, sessions));
  app.use('/api/groups', groupExpensesRouter(users, sessions));
  // Only Feature 3 route families enter its authenticated router. Preserve public
  // diagnostics and the existing 404 behavior for unrelated API routes.
  app.use('/api', (req, res, next) => {
    if (/^\/(?:groups\/[^/]+\/(?:split-sessions|snapshot)|split-sessions\/[^/]+\/(?:claims|finalize)|operations\/[^/]+)\/?$/.test(req.path)) {
      return splitSessions(req, res, next);
    }
    next();
  });
  app.use(invitationWebRouter(users));
  app.get('/health', (_req, res) => { res.json({ status: 'ok' }); });
  app.get('/api/server/ip', (_req, res) => {
    try { res.json(serverAddress()); }
    catch { res.status(503).json({ error: 'Invalid SERVER_PUBLIC_IP configuration' }); }
  });
  app.get('/api/server/time', (_req, res) => {
    res.setHeader('Cache-Control', 'no-store');
    const now = new Date();
    res.json({ time: formatLocalTime(now), timestamp: now.toISOString() });
  });
  app.get('/api/server/name', (_req, res) => {
    const firstName = process.env.OWNER_FIRST_NAME?.trim() ?? '';
    const lastName = process.env.OWNER_LAST_NAME?.trim() ?? '';
    if (!firstName || !lastName) {
      res.status(503).json({ error: 'Set OWNER_FIRST_NAME and OWNER_LAST_NAME in backend/.env' });
      return;
    }
    res.json({ firstName, lastName });
  });
  app.use((_req, res) => { res.status(404).json({ error: 'Not Found' }); });
  const handleError: ErrorRequestHandler = (error, _req, res, _next) => {
    if (error?.type === 'entity.too.large') { res.status(413).json({ error: 'Request body too large.' }); return; }
    if (error?.type === 'entity.parse.failed') { res.status(400).json({ error: 'Invalid JSON body.' }); return; }
    console.error('Unhandled request error:', error instanceof Error ? error.message : error);
    res.status(500).json({ error: 'Something went wrong. Please try again.' });
  };
  app.use(handleError);
  return app;
}
