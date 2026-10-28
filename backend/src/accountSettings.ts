import { Router } from 'express';
import type { TokenVerifier } from './googleAuth';
import { hashPassword, verifyPassword } from './password';
import { publicUser, requireSession, type AuthenticatedLocals } from './session';
import { DuplicateAccountError, type AccountUpdate, type SessionStore, type UserRecord, type UserStore } from './userStore';
import { normalizeDisplayName, normalizeEmail, passwordProblem } from './validation';

const CURRENCIES = new Set(['CAD', 'USD', 'EUR', 'GBP', 'CNY', 'JPY']);
const FIELDS = new Set(['displayName', 'email', 'phoneNumber', 'timeZone', 'defaultCurrency', 'avatarDataUrl', 'newPassword', 'currentPassword', 'googleIdToken']);

function profileChanges(body: Record<string, unknown>): AccountUpdate {
  const changes: AccountUpdate = {};
  if ('displayName' in body) {
    const name = normalizeDisplayName(body.displayName);
    if (!name) throw new Error('Enter a name of 1-60 characters.');
    changes.displayName = name;
  }
  if ('email' in body) {
    const email = normalizeEmail(body.email);
    if (!email) throw new Error('Enter a valid email address.');
    changes.email = email;
  }
  if ('phoneNumber' in body) {
    if (typeof body.phoneNumber !== 'string') throw new Error('Enter a valid phone number.');
    const phone = body.phoneNumber.trim();
    const digits = phone.replace(/[^0-9]/g, '');
    if (phone && (phone.length > 32 || !/^\+?[0-9 ()-]+$/.test(phone) || digits.length < 7 || digits.length > 15)) {
      throw new Error('Use a phone number with 7-15 digits, including the country code.');
    }
    changes.phoneNumber = phone;
  }
  if ('timeZone' in body) {
    if (typeof body.timeZone !== 'string' || !body.timeZone || body.timeZone.length > 100) throw new Error('Choose a valid time zone.');
    try { new Intl.DateTimeFormat('en', { timeZone: body.timeZone }).format(); }
    catch { throw new Error('Choose a valid time zone.'); }
    changes.timeZone = body.timeZone;
  }
  if ('defaultCurrency' in body) {
    if (typeof body.defaultCurrency !== 'string' || !CURRENCIES.has(body.defaultCurrency)) throw new Error('Choose a supported currency.');
    changes.defaultCurrency = body.defaultCurrency as NonNullable<AccountUpdate['defaultCurrency']>;
  }
  if ('avatarDataUrl' in body) {
    const value = body.avatarDataUrl;
    if (typeof value !== 'string' || value.length > 180000) throw new Error('Choose a smaller profile photo.');
    if (value) {
      const match = /^data:image\/jpeg;base64,([A-Za-z0-9+/]+={0,2})$/.exec(value);
      if (!match?.[1]) throw new Error('Choose a JPEG profile photo.');
      const bytes = Buffer.from(match[1], 'base64');
      if (bytes.length < 4 || bytes[0] !== 0xff || bytes[1] !== 0xd8 || bytes.at(-2) !== 0xff || bytes.at(-1) !== 0xd9 || bytes.toString('base64') !== match[1]) {
        throw new Error('Choose a valid JPEG profile photo.');
      }
    }
    changes.avatarDataUrl = value;
  }
  return changes;
}

async function confirmIdentity(user: UserRecord, body: Record<string, unknown>, verify: TokenVerifier): Promise<boolean> {
  if (user.passwordHash) {
    return typeof body.currentPassword === 'string' && body.currentPassword.length <= 128 &&
      await verifyPassword(body.currentPassword, user.passwordHash);
  }
  const audience = process.env.GOOGLE_CLIENT_ID?.trim();
  if (!user.googleId || !audience || typeof body.googleIdToken !== 'string' || body.googleIdToken.length > 8192) return false;
  try {
    const identity = await verify(body.googleIdToken, audience);
    return identity?.sub === user.googleId && identity.email_verified === true && (identity.exp ?? 0) > Date.now() / 1000;
  } catch { return false; }
}

/** Updates only the authenticated user's editable profile; credential changes require reauthentication. */
export function accountSettingsRouter(users: UserStore, sessions: SessionStore, verify: TokenVerifier): Router {
  const router = Router();
  router.patch('/me', requireSession(users, sessions), async (req, res) => {
    const body: unknown = req.body;
    if (!body || typeof body !== 'object' || Array.isArray(body) || !Object.keys(body).length || Object.keys(body).some(key => !FIELDS.has(key))) {
      res.status(400).json({ error: 'Provide valid account settings.' }); return;
    }
    const input = body as Record<string, unknown>;
    const { user, tokenHash } = res.locals as AuthenticatedLocals;
    let changes: AccountUpdate;
    try { changes = profileChanges(input); }
    catch (error) { res.status(400).json({ error: (error as Error).message }); return; }
    if ('newPassword' in input) {
      const problem = passwordProblem(input.newPassword);
      if (problem) { res.status(400).json({ error: problem }); return; }
    }
    const sensitive = ('email' in changes && changes.email !== user.email) || 'newPassword' in input;
    if (sensitive && !await confirmIdentity(user, input, verify)) {
      res.status(403).json({ error: user.passwordHash ? 'Your current password is incorrect.' : 'Confirm your identity with the Google account linked to Ledger.' }); return;
    }
    if (changes.email && changes.email !== user.email) {
      const existing = await users.findByEmail(changes.email);
      if (existing && existing.id !== user.id) { res.status(409).json({ error: 'This email address is already in use.' }); return; }
    }
    if (typeof input.newPassword === 'string') changes.passwordHash = await hashPassword(input.newPassword);
    if (!Object.keys(changes).length) { res.status(400).json({ error: 'Provide settings to update.' }); return; }
    try {
      const updated = await users.update(user.id, changes);
      if (!updated) { res.status(401).json({ error: 'Your session has expired. Please sign in again.' }); return; }
      if (sensitive) await sessions.deleteForUser(user.id, tokenHash);
      res.json({ user: publicUser(updated) });
    } catch (error) {
      if (!(error instanceof DuplicateAccountError)) throw error;
      res.status(409).json({ error: 'This email address is already in use.' });
    }
  });
  return router;
}
