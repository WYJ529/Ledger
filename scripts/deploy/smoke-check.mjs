// Creates only its own uniquely named test accounts/group; hides the group and logs out at the end.
// Uses generated unique usernames; never prints tokens or passwords.
import assert from 'node:assert/strict';
import { randomBytes, randomUUID } from 'node:crypto';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { mkdirSync, writeFileSync } from 'node:fs';
import { validatedOrigin, validatedInvitation } from './smoke-url.mjs';
import { DEPLOYMENT_ORIGIN, requireDeploymentOrigin, deployedRequest } from './smoke-client.mjs';

requireDeploymentOrigin(process.argv[2]);
const baseUrl = validatedOrigin(DEPLOYMENT_ORIGIN);
/**
 * @param {string} method
 * @param {string} path
 * @param {number} status
 * @param {object} [body]
 * @param {string} [token]
 */
async function api(method, path, status, body, token) {
  const response = await deployedRequest(path, method, { Accept: 'application/json',
    ...(body ? { 'Content-Type': 'application/json' } : {}), ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    body ? JSON.stringify(body) : undefined);
  assert.equal(response.status, status, `${method} ${path.replace(/invitations\/[^/]+/, 'invitations/[token]')}: unexpected status`);
  /** @type {unknown} */
  const payload = response.text ? JSON.parse(response.text) : {};
  assert(payload !== null && typeof payload === 'object', 'Expected a JSON response object or array.');
  return payload;
}
const tag = `deployment-${Date.now()}-${randomBytes(4).toString('hex')}`;
/** @type {{tag: string, usernames: string[], userIds: string[], groupId: string | null, checks: string[]}} */
const report = { tag, usernames: [], userIds: [], groupId: null, checks: [] };
const ownerPassword = randomBytes(24).toString('base64url');
const memberPassword = randomBytes(24).toString('base64url');
/** @type {string[]} */
const sessions = [];
let groupId;
try {
  assert.equal((await api('GET', '/health', 200)).status, 'ok');
  await api('GET', '/api/groups', 401);
  report.checks.push('HTTPS health and authenticated group gate');
  const accounts = [];
  for (const [role, password] of [['owner', ownerPassword], ['member', memberPassword]]) {
    const username = `smoke_${role}_${randomBytes(6).toString('hex')}`;
    const account = await api('POST', '/api/auth/register', 201, { username, password, confirmPassword: password });
    const token = account.token;
    assert(typeof token === 'string', 'Registration must return a session token.');
    const userId = account.user.id;
    assert(typeof userId === 'string', 'Registration must return the user ID.');
    accounts.push({ token, user: { id: userId } });
    sessions.push(token);
    report.usernames.push(username); report.userIds.push(userId);
  }
  const [owner, member] = accounts;
  const login = await api('POST', '/api/auth/login', 200, { username: report.usernames[0], password: ownerPassword });
  const loginToken = login.token;
  assert(typeof loginToken === 'string', 'Login must return a session token.');
  sessions.push(loginToken);
  report.checks.push('Username registration, password login and MongoDB persistence');
  const created = await api('POST', '/api/groups', 201, { name: 'Deployment verification', description: 'Disposable deployment smoke check',
    currency: 'CAD', requestId: randomUUID() }, owner.token);
  groupId = created.group.id; report.groupId = groupId;
  assert.equal(created.group.ownerId, owner.user.id);
  assert(created.invitation.link.startsWith(DEPLOYMENT_ORIGIN + '/invite/'));
  const invitationToken = created.invitation.deepLink.split('/').at(-1);
  const preview = await api('GET', `/api/groups/invitations/${invitationToken}`, 200, undefined, member.token);
  assert.equal(preview.alreadyMember, false);
  assert.equal((await api('GET', '/api/groups', 200, undefined, member.token)).groups.length, 0);
  const link = created.invitation.link;
  assert(typeof link === 'string', 'Expected an invitation URL.');
  const page = await deployedRequest(validatedInvitation(baseUrl, link).pathname);
  assert.equal(page.status, 200);
  assert(page.text.includes('Open in Ledger'));
  await api('POST', `/api/groups/invitations/${invitationToken}/accept`, 200, undefined, member.token);
  await api('POST', `/api/groups/invitations/${invitationToken}/accept`, 200, undefined, member.token);
  const summary = await api('GET', `/api/groups/${groupId}/summary`, 200, undefined, member.token);
  assert.equal(summary.group.members.length, 2);
  assert.equal(summary.group.members.find(item => item.id === owner.user.id).role, 'owner');
  assert.equal(summary.group.members.find(item => item.id === member.user.id).role, 'member');
  await api('PUT', `/api/groups/${groupId}/name`, 200, { name: 'Deployment verification renamed' }, member.token);
  await api('POST', `/api/groups/${groupId}/invitations`, 201, undefined, member.token);
  await api('DELETE', `/api/groups/${groupId}`, 403, undefined, member.token);
  report.checks.push('HTTPS invitations, preview without joining, idempotent acceptance and roles');
  const billPayload = { description: 'Smoke check bill', amount: '10.00', paidBy: owner.user.id, splitMode: 'equal',
    shares: [{ userId: owner.user.id }, { userId: member.user.id }], requestId: randomUUID() };
  const bill = await api('POST', `/api/groups/${groupId}/expenses`, 201, billPayload, owner.token);
  await api('PUT', `/api/groups/${groupId}/expenses/${bill.expense.id}`, 200,
    { ...billPayload, amount: '12.00', revision: bill.expense.revision }, owner.token);
  const updated = await api('GET', `/api/groups/${groupId}/summary`, 200, undefined, member.token);
  assert.equal(updated.totalCents, 1200); assert.equal(updated.expenseCount, 1);
  report.checks.push('Expense creation, editing and shared totals');
  await api('DELETE', `/api/groups/${groupId}`, 204, undefined, owner.token);
  groupId = undefined;
  await api('POST', `/api/groups/invitations/${invitationToken}/accept`, 404, undefined, member.token);
  const associations = await api('GET', '/.well-known/assetlinks.json', 200);
  assert.equal(associations[0].target.package_name, 'com.example.cpen321application');
  assert(associations[0].target.sha256_cert_fingerprints.length > 0);
  report.checks.push('Owner-only deletion, invalidated invites and Android domain association');
} finally {
  if (groupId && sessions[0]) await api('DELETE', `/api/groups/${groupId}`, 204, undefined, sessions[0]).catch(() => undefined);
  for (const token of sessions) await api('POST', '/api/auth/logout', 204, undefined, token).catch(() => undefined);
  const reportDirectory = resolve(dirname(fileURLToPath(import.meta.url)), '../../build/cloud');
  mkdirSync(reportDirectory, { recursive: true });
  writeFileSync(resolve(reportDirectory, 'smoke-result.json'), JSON.stringify(report, null, 2));
}
process.stdout.write(`PASS: ${report.checks.length} deployed checks; test group hidden and sessions logged out.\n`);
for (const check of report.checks) process.stdout.write(`- ${check}\n`);
