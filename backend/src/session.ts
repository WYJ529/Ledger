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
    username: user.username ?? '',
    email: user.email ?? '',
    displayName: user.displayName,
    signInMethods: [...(user.passwordHash ? ['password'] : []), ...(user.googleId ? ['google'] : [])],
    createdAt: user.createdAt.toISOString(),
    phoneNumber: user.phoneNumber ?? '',
    avatarDataUrl: user.avatarDataUrl ?? '',
  };
}

/** Creates a session and returns the bearer token once; only its hash is stored. */
export async function startSession(sessions: SessionStore, user: UserRecord) {
  const token = randomBytes(32).toString('base64url');
  const expiresAt = new Date(Date.now() + SESSION_TTL_MS);
  await sessions.create(hashToken(token), { userId: user.id, expiresAt, sessionVersion: user.sessionVersion ?? 0 });
  return { token, expiresAt: expiresAt.getTime(), user: publicUser(user) };
}

function unauthorized(res: Response) {
  res.setHeader('WWW-Authenticate', 'Bearer');
  res.status(401).json({ error: 'Your session has expired. Please sign in again.' });
}

export function bearerToken(header: unknown): string | undefined {
  return typeof header === 'string' && header.startsWith('Bearer ') ? header.slice(7) : undefined;
}

export function isSessionToken(token: unknown): token is string {
  return typeof token === 'string' && token.length >= 20 && token.length <= 200 && !/[^A-Za-z0-9_-]/.test(token);
}

/** Re-read on every request/event; never cache a successful session check. */
export async function validSession(sessions: SessionStore, tokenHash: string, userId?: unknown) {
  const session = await sessions.find(tokenHash);
  return session && session.expiresAt.getTime() > Date.now() &&
    (userId === undefined || session.userId === userId) ? session : null;
}

export async function authenticateSession(users: UserStore, sessions: SessionStore, token: unknown): Promise<AuthenticatedLocals | null> {
  if (!isSessionToken(token)) return null;
  const tokenHash = hashToken(token);
  const session = await validSession(sessions, tokenHash);
  if (!session) return null;
  const user = await users.findById(session.userId);
  if (!user) { await sessions.delete(tokenHash); return null; }
  return { user, tokenHash };
}

/** Accepts "Authorization: Bearer <token>" and exposes the user as res.locals.user. */
export function requireSession(users: UserStore, sessions: SessionStore): RequestHandler {
  return async (req, res, next) => {
    const locals = await authenticateSession(users, sessions, bearerToken(req.get('authorization')));
    if (!locals) { unauthorized(res); return; }
    Object.assign(res.locals, locals);
    next();
  };
}
