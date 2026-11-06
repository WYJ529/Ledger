import type { RequestHandler } from 'express';

type LimitMessages = { limited: string; busy: string; retryAfterInBody?: boolean };

/** Each middleware instance owns its fixed-window IP buckets. */
export function rateLimit(maximum: number, windowMs: number, messages: LimitMessages = {
  limited: 'Too many requests. Please wait a few minutes and retry.',
  busy: 'The service is busy. Please retry later.',
}): RequestHandler {
  const clients = new Map<string, { count: number; resetAt: number }>();
  return (req, res, next) => {
    const now = Date.now();
    for (const [key, entry] of clients) if (entry.resetAt <= now) clients.delete(key);
    const key = req.ip ?? req.socket.remoteAddress ?? 'unknown';
    let entry = clients.get(key);
    if (!entry) {
      if (clients.size >= 2000) { res.status(503).json({ error: messages.busy }); return; }
      entry = { count: 0, resetAt: now + windowMs };
      clients.set(key, entry);
    }
    if (entry.count >= maximum) {
      const retryAfterSeconds = Math.ceil((entry.resetAt - now) / 1000);
      res.setHeader('Retry-After', retryAfterSeconds);
      res.status(429).json({ error: messages.limited, ...(messages.retryAfterInBody ? { retryAfterSeconds } : {}) });
      return;
    }
    entry.count++;
    next();
  };
}

/** Preserve the account endpoints' retry metadata and existing error contract. */
export function accountRateLimit(maximum: number): RequestHandler {
  return rateLimit(maximum, 10 * 60 * 1000, {
    limited: 'Too many registration requests. Please try again later.',
    busy: 'Registration is busy. Please try again later.',
    retryAfterInBody: true,
  });
}
