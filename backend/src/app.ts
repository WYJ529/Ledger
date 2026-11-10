import express, { json, type ErrorRequestHandler, type Express } from 'express';
import { googleAuthRouter, verifyGoogleToken, type TokenVerifier } from './googleAuth';
import { accountAuthRouter, userRouter } from './accountAuth';
import { accountSettingsRouter } from './accountSettings';
import { mongoSessionStore, mongoUserStore, type SessionStore, type UserStore } from './userStore';
import { groupExpensesRouter } from './groupExpenses';
import { invitationWebRouter } from './groupManagement';
import { createLedgerRouter } from './ledger/router';
import { ledgerSavesRouter } from './ledgerSaves';
import { mongoLedgerSaveStore, type LedgerSaveStore } from './ledgerSaveStore';
import { createSplitSessionsRouter } from './splitSessions';
import { requireSession } from './session';

export type AppDependencies = {
  verifyToken: TokenVerifier;
  users: UserStore;
  sessions: SessionStore;
  ledgerSaves: LedgerSaveStore;
};

export function createApp(dependencies: Partial<AppDependencies> = {}): Express {
  const { verifyToken = verifyGoogleToken, users = mongoUserStore, sessions = mongoSessionStore } = dependencies;
  const app = express();
  // Cloud Compose exposes only Caddy; local/direct deployments must not trust forwarded IPs.
  const proxyHops = process.env.TRUST_PROXY_HOPS ?? '0';
  if (proxyHops !== '0' && proxyHops !== '1') throw new Error('TRUST_PROXY_HOPS must be 0 or 1.');
  app.set('trust proxy', Number(proxyHops));
  const splitSessions = createSplitSessionsRouter(users, sessions);
  // Document routes use a bounded parser that accommodates a 20 MiB PDF/CSV in base64.
  // Authenticate before accepting an upload or calling the paid recognition service.
  app.post('/api/ledger/imports/preview', requireSession(users, sessions));
  app.use('/api/ledger', createLedgerRouter());
  // Up to 200 reviewed item units can exceed the ordinary small API body limit.
  app.use('/api/groups/:groupId/split-sessions', json({ limit: '128kb' }));
  // Profile photos are resized JPEGs, bounded independently of other API requests.
  app.use('/api/users/me', json({ limit: '192kb' }));
  // A cloud save is a whole-ledger snapshot of up to 5,000 transactions.
  app.use('/api/ledger-saves', json({ limit: '8mb' }));
  app.use('/api', json({ limit: '16kb' }));
  app.use(['/api/auth', '/api/users'], (_req, res, next) => { res.setHeader('Cache-Control', 'no-store'); next(); });
  app.use('/api/auth', googleAuthRouter(verifyToken, users, sessions));
  app.use('/api/auth', accountAuthRouter(users, sessions));
  app.use('/api/users', userRouter(users, sessions));
  app.use('/api/users', accountSettingsRouter(users, sessions, verifyToken));
  app.use('/api/ledger-saves', ledgerSavesRouter(users, sessions, dependencies.ledgerSaves ?? mongoLedgerSaveStore));
  // Match split routes before the general groups router so each authenticates once.
  app.use('/api', (req, res, next) => {
    if (/^\/(?:groups\/[^/]+\/(?:split-sessions|snapshot)|split-sessions\/[^/]+\/(?:claims|finalize)|operations\/[^/]+)\/?$/.test(req.path)) {
      return splitSessions(req, res, next);
    }
    next();
  });
  app.use('/api/groups', groupExpensesRouter(users, sessions));
  app.use(invitationWebRouter(users));
  app.get('/health', (_req, res) => { res.json({ status: 'ok' }); });
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
