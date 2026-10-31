// Probe route availability without creating users or sending Google credentials.
const origin = process.argv[2] ?? 'http://localhost:3000';
const base = origin.replace(/\/+$/, '');
const checks = [
  ['GET', '/health', [200]],
  ['GET', '/api/users/me', [401]],
  ['POST', '/api/auth/register', [400]],
  ['POST', '/api/auth/register/code', [400]],
  ['POST', '/api/auth/google/register', [400, 503]],
  ['GET', '/api/groups', [401]],
  ['GET', '/api/ledger/meta', [200]],
];

try {
  for (const [method, path, expected] of checks) {
    const response = await fetch(base + path, {
      method,
      headers: { Accept: 'application/json', ...(method === 'POST' ? { 'Content-Type': 'application/json' } : {}) },
      ...(method === 'POST' ? { body: '{}' } : {}),
      signal: AbortSignal.timeout(5000),
    });
    if (!expected.includes(response.status)) {
      throw new Error(`${method} ${path} returned HTTP ${response.status}. ` +
        'Check the API address and restart the integrated backend; an old M1/guest-only process may still own this port.');
    }
    console.log(`OK ${method} ${path} (HTTP ${response.status})`);
    if (response.status === 503) {
      throw new Error('Google account creation is not configured. Set backend GOOGLE_CLIENT_ID to the Android app\'s Web OAuth client ID and restart the backend.');
    }
  }
  console.log('The integrated account, group and ledger routes are available. No account was created.');
} catch (error) {
  console.error(`Backend check failed: ${error instanceof Error ? error.message : 'Connection failed'}`);
  process.exitCode = 1;
}
