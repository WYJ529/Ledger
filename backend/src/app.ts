import express, { type Express } from 'express';
import { formatLocalTime, serverAddress } from './serverInfo';
import { googleAuthRouter, type TokenVerifier } from './googleAuth';

export function createApp(verifyToken?: TokenVerifier): Express {
  const app = express();
  app.use('/api/auth', googleAuthRouter(verifyToken));
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
  return app;
}
