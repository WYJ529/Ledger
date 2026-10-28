import { createHash, randomBytes } from 'node:crypto';
import type { RequestHandler, Response } from 'express';
import type { SessionStore, UserRecord, UserStore } from './userStore';

export const SESSION_TTL_MS = 7 * 24 * 60 * 60 * 1000;

export type AuthenticatedLocals = { user: UserRecord; tokenHash: string };

export function hashToken(token: string): string {
  return createHash('sha256').update(token).digest('hex');
}

/** Account fields that are safe to send to the signed-in user. */
export function publicUser(user: UserRecord) {
  return {
    id: user.id,
    email: user.email,
    displayName: user.displayName,
    signInMethods: [...(user.passwordHash ? ['password'] : []), ...(user.googleId ? ['google'] : [])],
    createdAt: user.createdAt.toISOString(),
    phoneNumber: user.phoneNumber ?? '',
    timeZone: user.timeZone ?? 'UTC',
    defaultCurrency: user.defaultCurrency ?? 'CAD',
    avatarDataUrl: user.avatarDataUrl ?? '',
  };
}

/** Creates a session and returns the bearer token once; only its hash is stored. */
export async function startSession(sessions: SessionStore, user: UserRecord) {
  const token = randomBytes(32).toString('base64url');
  const expiresAt = new Date(Date.now() + SESSION_TTL_MS);
  await sessions.create(hashToken(token), { userId: user.id, expiresAt });
  return { token, expiresAt: expiresAt.getTime(), user: publicUser(user) };
}

function unauthorized(res: Response) {
  res.setHeader('WWW-Authenticate', 'Bearer');
  res.status(401).json({ error: 'Your session has expired. Please sign in again.' });
}

/** Accepts "Authorization: Bearer <token>" and exposes the user as res.locals.user. */
export function requireSession(users: UserStore, sessions: SessionStore): RequestHandler {
  return async (req, res, next) => {
    const match = /^Bearer ([A-Za-z0-9_-]{20,200})$/.exec(req.get('authorization') ?? '');
    if (!match?.[1]) { unauthorized(res); return; }
    const tokenHash = hashToken(match[1]);
    const session = await sessions.find(tokenHash);
    if (!session || session.expiresAt.getTime() <= Date.now()) { unauthorized(res); return; }
    const user = await users.findById(session.userId);
    if (!user) { await sessions.delete(tokenHash); unauthorized(res); return; }
    const locals: AuthenticatedLocals = { user, tokenHash };
    Object.assign(res.locals, locals);
    next();
  };
}
