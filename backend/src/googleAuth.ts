import { OAuth2Client, type TokenPayload } from 'google-auth-library';
import { Router, type Request, type Response } from 'express';
import { startSession } from './session';
import { DuplicateAccountError, type SessionStore, type UserStore } from './userStore';
import { normalizeDisplayName, normalizeEmail, normalizeUsername, passwordProblem } from './validation';
import { hashPassword, rejectUnknownPassword, verifyPassword } from './password';
import { registrationRateLimit, USERNAME_TAKEN } from './accountAuth';

const google = new OAuth2Client();
export type TokenVerifier = (token: string, audience: string) => Promise<TokenPayload | undefined>;
export const verifyGoogleToken: TokenVerifier = async (idToken, audience) => {
  const ticket = await google.verifyIdToken({ idToken, audience });
  return ticket.getPayload();
};

type GoogleIdentity = { googleId: string; email: string; displayName: string };
const LINK_CONFLICT = 'This Ledger account or Google account is already linked to another account.';

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
  const fullName = [payload.given_name, payload.family_name].filter(Boolean).join(' ');
  const displayName = normalizeDisplayName(payload.name) ?? normalizeDisplayName(fullName) ?? '';
  return { googleId: payload.sub, email: email ?? '', displayName };
}

/** Google sign-in and sign-up, mounted at /api/auth. */
export function googleAuthRouter(verify: TokenVerifier, users: UserStore, sessions: SessionStore): Router {
  const router = Router();
  router.use(['/google', '/google/register', '/google/link'], registrationRateLimit(60));

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
    const username = normalizeUsername(req.body?.username);
    const problem = passwordProblem(req.body?.password);
    if (!username) { res.status(400).json({ error: 'Use 3-30 letters, numbers, dots, hyphens or underscores for your username.' }); return; }
    if (problem) { res.status(400).json({ error: problem }); return; }
    if (req.body.confirmPassword !== req.body.password) { res.status(400).json({ error: 'Passwords do not match.' }); return; }
    if (await users.findByUsername(username)) { res.status(409).json({ error: USERNAME_TAKEN }); return; }
    try {
      const user = await users.create({ username, displayName: username, passwordHash: await hashPassword(req.body.password), googleId: identity.googleId });
      res.status(201).json({ status: 'signed-in', ...await startSession(sessions, user) });
    } catch (error) {
      if (!(error instanceof DuplicateAccountError)) throw error;
      res.status(409).json({ error: USERNAME_TAKEN });
    }
  });

  // Both the Google token and Ledger password must be verified before linking.
  router.post('/google/link', async (req, res) => {
    const identity = await verifiedIdentity(req, res, verify);
    if (!identity) return;
    const username = normalizeUsername(req.body?.username);
    const password: unknown = req.body?.password;
    if (!username || typeof password !== 'string' || !password || password.length > 128) {
      res.status(400).json({ error: 'Enter your username and password.' }); return;
    }
    const user = await users.findByUsername(username);
    const valid = user?.passwordHash ? await verifyPassword(password, user.passwordHash) : await rejectUnknownPassword(password);
    if (!user || !valid || !user.passwordHash) { res.status(401).json({ error: 'Incorrect username or password.' }); return; }
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
