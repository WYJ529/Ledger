import assert from 'node:assert/strict';

// The deployment operator chooses the origin. API responses may only supply paths within it.
export function validatedOrigin(value) {
  const url = new URL(value);
  assert(url.protocol === 'https:' && !url.username && !url.password && !url.search && !url.hash && url.pathname === '/', 'Provide a simple HTTPS origin.');
  return url;
}

export function validatedPath(origin, path) {
  assert(typeof path === 'string' && /^\/(?!\/)/.test(path) && !path.includes('\\'), 'Invalid API path.');
  const url = new URL(path, origin);
  assert(url.origin === origin.origin && !url.username && !url.password && !url.hash, 'API path escaped the configured origin.');
  return url;
}

export function validatedInvitation(origin, value) {
  const url = new URL(value);
  assert(url.origin === origin.origin && !url.username && !url.password && !url.search && !url.hash, 'Invitation escaped the configured origin.');
  assert(/^\/invite\/[A-Za-z0-9_-]{43}$/.test(url.pathname), 'Invalid invitation path.');
  return validatedPath(origin, url.pathname);
}
