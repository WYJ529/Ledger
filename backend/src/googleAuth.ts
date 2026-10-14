import { OAuth2Client, type TokenPayload } from 'google-auth-library';
import { Router, json, type ErrorRequestHandler } from 'express';

const google = new OAuth2Client();
export type TokenVerifier = (token: string, audience: string) => Promise<TokenPayload | undefined>;
export const verifyGoogleToken: TokenVerifier = async (idToken, audience) => {
  const ticket = await google.verifyIdToken({ idToken, audience });
  return ticket.getPayload();
};

export function googleAuthRouter(verify: TokenVerifier = verifyGoogleToken): Router {
  const router = Router();
  router.use(json({ limit: '16kb' }));
  router.post('/google', async (req, res) => {
    res.setHeader('Cache-Control', 'no-store');
    const audience = process.env.GOOGLE_CLIENT_ID?.trim();
    if (!audience || audience.startsWith('your_')) {
      res.status(503).json({ error: 'Google sign-in is not configured on the backend.' });
      return;
    }
    const token: unknown = req.body?.idToken;
    if (typeof token !== 'string' || !token.trim() || token.length > 8192) {
      res.status(400).json({ error: 'A Google ID token is required.' });
      return;
    }
    try {
      // The official verifier checks signature, issuer, expiration and audience.
      const payload = await verify(token, audience);
      if (!payload?.sub || !payload.exp || payload.exp <= Date.now() / 1000) {
        res.status(401).json({ error: 'Invalid or expired Google credential. Please sign in again.' });
        return;
      }
      res.json({
        user: {
          id: payload.sub,
          firstName: payload.given_name ?? '',
          lastName: payload.family_name ?? '',
          displayName: payload.name ?? '',
        },
        expiresAt: payload.exp * 1000,
      });
    } catch {
      res.status(401).json({ error: 'Google credential verification failed. Please sign in again.' });
    }
  });
  const handleJsonError: ErrorRequestHandler = (error, _req, res, next) => {
    if (error?.type === 'entity.too.large') { res.status(413).json({ error: 'Request body too large.' }); return; }
    if (error?.type === 'entity.parse.failed') { res.status(400).json({ error: 'Invalid JSON body.' }); return; }
    next(error);
  };
  router.use(handleJsonError);
  return router;
}
