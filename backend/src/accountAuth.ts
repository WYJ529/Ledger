import { Router, type RequestHandler } from 'express';
import { hashPassword, rejectUnknownPassword, verifyPassword } from './password';
import { publicUser, requireSession, startSession, type AuthenticatedLocals } from './session';
import { DuplicateAccountError, type SessionStore, type UserStore } from './userStore';
import { normalizeDisplayName, normalizeEmail, passwordProblem } from './validation';
import { consumeVerification, createVerification, RESEND_DELAY_MS, type RegistrationVerificationStore } from './registrationVerification';
import type { RegistrationEmailSender } from './registrationEmail';

const EMAIL_TAKEN = 'An account with this email already exists. Sign in instead.';

function registrationRateLimit(maximum: number): RequestHandler {
  const clients = new Map<string, { count: number; resetAt: number }>();
  return (req, res, next) => {
    const now = Date.now();
    for (const [key, entry] of clients) if (entry.resetAt <= now) clients.delete(key);
    const key = req.ip ?? req.socket.remoteAddress ?? 'unknown';
    let entry = clients.get(key);
    if (!entry) {
      if (clients.size >= 2000) { res.status(503).json({ error: 'Registration is busy. Please try again later.' }); return; }
      entry = { count: 0, resetAt: now + 10 * 60 * 1000 };
      clients.set(key, entry);
    }
    if (entry.count >= maximum) {
      const retryAfterSeconds = Math.ceil((entry.resetAt - now) / 1000);
      res.setHeader('Retry-After', retryAfterSeconds);
      res.status(429).json({ error: 'Too many registration requests. Please try again later.', retryAfterSeconds });
      return;
    }
    entry.count++;
    next();
  };
}

/** Email + password accounts and session logout, mounted at /api/auth. */
export function accountAuthRouter(users: UserStore, sessions: SessionStore,
  verifications: RegistrationVerificationStore, emailSender: RegistrationEmailSender): Router {
  const router = Router();

  router.post('/register/code', registrationRateLimit(10), async (req, res) => {
    const email = normalizeEmail(req.body?.email);
    if (!email) { res.status(400).json({ error: 'Enter a valid email address.' }); return; }
    if (await users.findByEmail(email)) { res.status(409).json({ error: EMAIL_TAKEN }); return; }
    const { code, record } = await createVerification(email);
    if (!await verifications.reserve(record, new Date())) {
      const retryAfterSeconds = RESEND_DELAY_MS / 1000;
      res.setHeader('Retry-After', retryAfterSeconds);
      res.status(429).json({ error: 'Please wait before requesting another code.', retryAfterSeconds });
      return;
    }
    try {
      await emailSender.sendCode(email, code);
    } catch {
      await verifications.invalidate(record.verificationId);
      res.status(503).json({ error: 'Unable to send the verification email. Please try again later.' });
      return;
    }
    await verifications.activate(record.verificationId);
    res.status(202).json({
      verificationId: record.verificationId, email,
      expiresAt: record.expiresAt.getTime(), retryAfterSeconds: RESEND_DELAY_MS / 1000,
    });
  });

  router.post('/register', registrationRateLimit(30), async (req, res) => {
    const email = normalizeEmail(req.body?.email);
    const displayName = normalizeDisplayName(req.body?.displayName);
    const problem = passwordProblem(req.body?.password);
    if (!email) { res.status(400).json({ error: 'Enter a valid email address.' }); return; }
    if (!displayName) { res.status(400).json({ error: 'Enter a name of 1-60 characters.' }); return; }
    if (problem) { res.status(400).json({ error: problem }); return; }
    if (await users.findByEmail(email)) { res.status(409).json({ error: EMAIL_TAKEN }); return; }
    const verificationId: unknown = req.body?.verificationId;
    const code: unknown = req.body?.code;
    if (typeof verificationId !== 'string' || !/^[a-f0-9]{64}$/.test(verificationId) ||
        typeof code !== 'string' || !/^[0-9]{6}$/.test(code)) {
      res.status(400).json({ error: 'Request an email verification code and enter the 6 digits.' }); return;
    }
    if (!await consumeVerification(verifications, email, verificationId, code)) {
      res.status(400).json({ error: 'The verification code is incorrect, expired or no longer valid. Try again or request a new code.' }); return;
    }
    try {
      const user = await users.create({ email, displayName, passwordHash: await hashPassword(req.body.password) });
      res.status(201).json(await startSession(sessions, user));
    } catch (error) {
      if (!(error instanceof DuplicateAccountError)) throw error;
      res.status(409).json({ error: EMAIL_TAKEN });
    }
  });

  router.post('/login', async (req, res) => {
    const email = normalizeEmail(req.body?.email);
    const password: unknown = req.body?.password;
    if (!email || typeof password !== 'string' || !password || password.length > 1024) {
      res.status(400).json({ error: 'Enter your email and password.' });
      return;
    }
    const user = await users.findByEmail(email);
    const valid = user?.passwordHash
      ? await verifyPassword(password, user.passwordHash)
      : await rejectUnknownPassword(password);
    if (!user || !valid) {
      // One message for unknown email, wrong password and Google-only accounts.
      res.status(401).json({ error: 'Incorrect email or password.' });
      return;
    }
    res.json(await startSession(sessions, user));
  });

  router.post('/logout', requireSession(users, sessions), async (_req, res) => {
    await sessions.delete((res.locals as AuthenticatedLocals).tokenHash);
    res.status(204).end();
  });

  return router;
}

/** Signed-in account information, mounted at /api/users. */
export function userRouter(users: UserStore, sessions: SessionStore): Router {
  const router = Router();
  router.get('/me', requireSession(users, sessions), (_req, res) => {
    res.json({ user: publicUser((res.locals as AuthenticatedLocals).user) });
  });
  return router;
}
