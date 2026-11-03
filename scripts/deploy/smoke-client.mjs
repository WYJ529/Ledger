import assert from 'node:assert/strict';
import https from 'node:https';
import { validatedOrigin, validatedPath } from './smoke-url.mjs';

// Update this host together with the deployment record when moving the service.
const DEPLOYMENT_HOST = '136-67-157-106.sslip.io';
export const DEPLOYMENT_ORIGIN = `https://${DEPLOYMENT_HOST}`;
const baseUrl = validatedOrigin(DEPLOYMENT_ORIGIN);

export function requireDeploymentOrigin(value) {
  assert.equal(value?.replace(/\/$/, ''), DEPLOYMENT_ORIGIN,
    `Use the configured deployment origin: ${DEPLOYMENT_ORIGIN}`);
}

export async function deployedRequest(path, method = 'GET', headers = {}, body) {
  const target = validatedPath(baseUrl, path);
  return new Promise((resolve, reject) => {
    // Only the path varies; a response or CLI argument can never supply a host.
    const request = https.request({ protocol: 'https:', hostname: DEPLOYMENT_HOST, port: 443,
      path: target.pathname + target.search, method, headers, signal: AbortSignal.timeout(20000) }, response => {
      response.on('error', reject);
      // Native HTTPS does not follow redirects. Reject them before reading a body.
      if (response.statusCode >= 300 && response.statusCode < 400) {
        response.resume();
        reject(new Error('Deployment probe redirects are not allowed.'));
        return;
      }
      const chunks = [];
      response.on('data', chunk => chunks.push(chunk));
      response.on('end', () => resolve({ status: response.statusCode, text: Buffer.concat(chunks).toString('utf8') }));
    });
    request.on('error', reject);
    request.end(body);
  });
}
