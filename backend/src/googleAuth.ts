import { OAuth2Client, type TokenPayload } from 'google-auth-library';
import { Router, type Request, type Response } from 'express';
import { startSession } from './session';
import { DuplicateAccountError, type SessionStore, type UserStore } from './userStore';
import { normalizeDisplayName, normalizeEmail } from './validation';
import { registerAccount, passwordAccount } from './accountCredentials';
import { accountRateLimit } from './rateLimit';

const google = new OAuth2Client();
export type TokenVerifier = (token: string, audience: string) => Promise<TokenPayload | undefined>;
export const verifyGoogleToken: TokenVerifier = async (idToken, audience) => {
  const ticket = await google.verifyIdToken({ idToken, audience });
  return ticket.getPayload();
};

type GoogleIdentity = { googleId: string; email: string; displayName: string };
const LINK_CONFLICT = 'This Ledger account or Google account is already linked to another account.';

/** Shared Google credential checks; callers retain their own HTTP policy. */
export async function googleCredential(token: unknown, verify: TokenVerifier) {
  const audience = process.env.GOOGLE_CLIENT_ID?.trim();
  if (!audience || audience.startsWith('your_')) return { status: 503, error: 'Google sign-in is not configured on the backend.' };
  if (typeof token !== 'string' || !token.trim() || token.length > 8192) return { status: 400, error: 'A Google ID token is required.' };
  let payload: TokenPayload | undefined;
  try { payload = await verify(token, audience); }
  catch { return { status: 401, error: 'Google credential verification failed. Please sign in again.' }; }
  if (!payload?.sub || !payload.exp || payload.exp <= Date.now() / 1000) {
    return { status: 401, error: 'Invalid or expired Google credential. Please sign in again.' };
  }
  return { payload };
}

async function verifiedIdentity(req: Request, res: Response, verify: TokenVerifier): Promise<GoogleIdentity | undefined> {
  const result = await googleCredential(req.body?.idToken, verify);
  if (!result.payload) { res.status(result.status).json({ error: result.error }); return; }
  const payload = result.payload;
  const email = normalizeEmail(payload.email);
  const fullName = [payload.given_name, payload.family_name].filter(Boolean).join(' ');
  const displayName = normalizeDisplayName(payload.name) ?? normalizeDisplayName(fullName) ?? '';
  return { googleId: payload.sub, email: email ?? '', displayName };
}

/** Google sign-in and sign-up, mounted at /api/auth. */
export function googleAuthRouter(verify: TokenVerifier, users: UserStore, sessions: SessionStore): Router {
  const router = Router();
  router.use(['/google', '/google/register', '/google/link'], accountRateLimit(60));

  // Signs in an existing Google account, or tells the app to show the create-account step.
  router.post('/google', async (req, res) => {
    const identity = await verifiedIdentity(req, res, verify);
    if (!identity) return;
    const user = await users.findByGoogleId(identity.googleId);
    if (user) { res.json({ status: 'signed-in', ...await startSession(sessions, user) }); return; }
    res.json({ status: 'registration-required', profile: { email: identity.email, displayName: identity.displayName } });
  });

  // Creates the account after the user confirms their profile. The ID token is verified again.
  router.post('/google/register', async (req, res) => {
    const identity = await verifiedIdentity(req, res, verify);
    if (!identity) return;
    const existing = await users.findByGoogleId(identity.googleId);
    if (existing) { res.json({ status: 'signed-in', ...await startSession(sessions, existing) }); return; }
    const user = await registerAccount(req.body, users, res, identity.googleId);
    if (user) res.status(201).json({ status: 'signed-in', ...await startSession(sessions, user) });
  });

  // Both the Google token and Ledger password must be verified before linking.
  router.post('/google/link', async (req, res) => {
    const identity = await verifiedIdentity(req, res, verify);
    if (!identity) return;
    const user = await passwordAccount(req.body, users, res);
    if (!user) return;
    const linked = await users.findByGoogleId(identity.googleId);
    if ((linked && linked.id !== user.id) || (user.googleId && user.googleId !== identity.googleId)) {
      res.status(409).json({ error: LINK_CONFLICT }); return;
    }
    try {
      const updated = await users.linkGoogle(user.id, identity.googleId, user.passwordHash);
      if (!updated) { res.status(409).json({ error: 'Account details changed. Please sign in again to link your account.' }); return; }
      res.json({ status: 'signed-in', ...await startSession(sessions, updated) });
    } catch (error) {
      if (!(error instanceof DuplicateAccountError)) throw error;
      res.status(409).json({ error: LINK_CONFLICT });
    }
  });

  return router;
}
