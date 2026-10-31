import { OAuth2Client, type TokenPayload } from 'google-auth-library';
import { Router, type Request, type Response } from 'express';
import { startSession } from './session';
import { DuplicateAccountError, type SessionStore, type UserStore } from './userStore';
import { normalizeDisplayName, normalizeEmail } from './validation';

const google = new OAuth2Client();
export type TokenVerifier = (token: string, audience: string) => Promise<TokenPayload | undefined>;
export const verifyGoogleToken: TokenVerifier = async (idToken, audience) => {
  const ticket = await google.verifyIdToken({ idToken, audience });
  return ticket.getPayload();
};

type GoogleIdentity = { googleId: string; email: string; displayName: string };
const EMAIL_TAKEN = 'An account with this email already exists. Sign in with your email and password.';

/** Verifies the ID token in the body; on failure responds and returns undefined. */
async function verifiedIdentity(req: Request, res: Response, verify: TokenVerifier): Promise<GoogleIdentity | undefined> {
  const audience = process.env.GOOGLE_CLIENT_ID?.trim();
  if (!audience || audience.startsWith('your_')) {
    res.status(503).json({ error: 'Google sign-in is not configured on the backend.' });
    return undefined;
  }
  const token: unknown = req.body?.idToken;
  if (typeof token !== 'string' || !token.trim() || token.length > 8192) {
    res.status(400).json({ error: 'A Google ID token is required.' });
    return undefined;
  }
  let payload: TokenPayload | undefined;
  try {
    // The official verifier checks signature, issuer, expiration and audience.
    payload = await verify(token, audience);
  } catch {
    res.status(401).json({ error: 'Google credential verification failed. Please sign in again.' });
    return undefined;
  }
  if (!payload?.sub || !payload.exp || payload.exp <= Date.now() / 1000) {
    res.status(401).json({ error: 'Invalid or expired Google credential. Please sign in again.' });
    return undefined;
  }
  const email = normalizeEmail(payload.email);
  if (!email || payload.email_verified !== true) {
    res.status(403).json({ error: 'Your Google account needs a verified email address.' });
    return undefined;
  }
  const fullName = [payload.given_name, payload.family_name].filter(Boolean).join(' ');
  const displayName = normalizeDisplayName(payload.name) ?? normalizeDisplayName(fullName) ?? email.split('@')[0]!.slice(0, 60);
  return { googleId: payload.sub, email, displayName };
}

/** Google sign-in and sign-up, mounted at /api/auth. */
export function googleAuthRouter(verify: TokenVerifier, users: UserStore, sessions: SessionStore): Router {
  const router = Router();

  // Signs in an existing Google account, or tells the app to show the create-account step.
  router.post('/google', async (req, res) => {
    const identity = await verifiedIdentity(req, res, verify);
    if (!identity) return;
    const user = await users.findByGoogleId(identity.googleId);
    if (user) { res.json({ status: 'signed-in', ...await startSession(sessions, user) }); return; }
    // Existing password accounts are never linked automatically, including legacy unverified accounts.
    if (await users.findByEmail(identity.email)) { res.status(409).json({ error: EMAIL_TAKEN }); return; }
    res.json({ status: 'registration-required', profile: { email: identity.email, displayName: identity.displayName } });
  });

  // Creates the account after the user confirms their profile. The ID token is verified again.
  router.post('/google/register', async (req, res) => {
    const identity = await verifiedIdentity(req, res, verify);
    if (!identity) return;
    const displayName = req.body?.displayName === undefined ? identity.displayName : normalizeDisplayName(req.body.displayName);
    if (!displayName) { res.status(400).json({ error: 'Enter a name of 1-60 characters.' }); return; }
    const existing = await users.findByGoogleId(identity.googleId);
    if (existing) { res.json({ status: 'signed-in', ...await startSession(sessions, existing) }); return; }
    if (await users.findByEmail(identity.email)) { res.status(409).json({ error: EMAIL_TAKEN }); return; }
    try {
      const user = await users.create({ email: identity.email, displayName, googleId: identity.googleId });
      res.status(201).json({ status: 'signed-in', ...await startSession(sessions, user) });
    } catch (error) {
      if (!(error instanceof DuplicateAccountError)) throw error;
      res.status(409).json({ error: EMAIL_TAKEN });
    }
  });

  return router;
}
