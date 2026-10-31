// Creates only its own uniquely named test accounts/group; hides the group and logs out at the end.
// Uses two fresh inbox aliases you control; never prints codes, tokens or passwords.
import assert from 'node:assert/strict';
import { randomBytes, randomUUID } from 'node:crypto';
import { mkdirSync, writeFileSync } from 'node:fs';
import { createInterface } from 'node:readline/promises';
import { Writable } from 'node:stream';
import { registerWithEmailCode } from './smoke-registration.mjs';

const base = process.argv[2]?.replace(/\/$/, '');
assert(base?.startsWith('https://'), 'Provide the deployed HTTPS origin.');
const emails = [process.env.SMOKE_OWNER_EMAIL, process.env.SMOKE_MEMBER_EMAIL].map(value => value?.trim().toLowerCase());
assert(emails.every(email => email && /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)),
  'Set SMOKE_OWNER_EMAIL and SMOKE_MEMBER_EMAIL to fresh inbox aliases you control.');
assert.notEqual(emails[0], emails[1], 'Use two different email addresses.');
assert(process.stdin.isTTY, 'Run interactively to enter the codes delivered to your inboxes.');
async function readCode(email) {
  // Suppress terminal echo so one-time codes do not enter captured console logs.
  const output = new Writable({ write(_chunk, _encoding, done) { done(); } });
  const input = createInterface({ input: process.stdin, output, terminal: true });
  const controller = new AbortController();
  input.once('SIGINT', () => controller.abort());
  console.log(`Enter the 6-digit code sent to ${email} (input hidden):`);
  try {
    return (await input.question('', { signal: AbortSignal.any([controller.signal, AbortSignal.timeout(10 * 60 * 1000)]) })).trim();
  } finally { input.close(); output.end(); }
}
async function api(method, path, status, body, token) {
  const response = await fetch(base + path, { method, headers: { Accept: 'application/json',
    ...(body ? { 'Content-Type': 'application/json' } : {}), ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    ...(body ? { body: JSON.stringify(body) } : {}), signal: AbortSignal.timeout(20000) });
  assert.equal(response.status, status, `${method} ${path.replace(/invitations\/[^/]+/, 'invitations/[token]')}: unexpected status`);
  const text = await response.text();
  return text ? JSON.parse(text) : {};
}
const tag = `deployment-${Date.now()}-${randomBytes(4).toString('hex')}`;
const report = { tag, emails: [], userIds: [], groupId: null, checks: [] };
const ownerPassword = randomBytes(24).toString('base64url');
const memberPassword = randomBytes(24).toString('base64url');
const sessions = [];
let groupId;
try {
  assert.equal((await api('GET', '/health', 200)).status, 'ok');
  await api('GET', '/api/groups', 401);
  report.checks.push('HTTPS health and authenticated group gate');
  const accounts = [];
  for (const [role, password, email] of [['owner', ownerPassword, emails[0]], ['member', memberPassword, emails[1]]]) {
    const account = await registerWithEmailCode(api, { email, displayName: `Deployment check ${role}`, password }, readCode);
    accounts.push(account); sessions.push(account.token);
    report.emails.push(email); report.userIds.push(account.user.id);
  }
  const [owner, member] = accounts;
  const login = await api('POST', '/api/auth/login', 200, { email: report.emails[0], password: ownerPassword });
  sessions.push(login.token);
  report.checks.push('Email delivery and verification, password login and MongoDB persistence');
  const created = await api('POST', '/api/groups', 201, { name: 'Deployment verification', description: 'Disposable deployment smoke check',
    currency: 'CAD', requestId: randomUUID() }, owner.token);
  groupId = created.group.id; report.groupId = groupId;
  assert.equal(created.group.ownerId, owner.user.id);
  assert(created.invitation.link.startsWith(base + '/invite/'));
  const invitationToken = created.invitation.deepLink.split('/').at(-1);
  const preview = await api('GET', `/api/groups/invitations/${invitationToken}`, 200, undefined, member.token);
  assert.equal(preview.alreadyMember, false);
  assert.equal((await api('GET', '/api/groups', 200, undefined, member.token)).groups.length, 0);
  const page = await fetch(created.invitation.link, { signal: AbortSignal.timeout(20000) });
  assert.equal(page.status, 200);
  assert((await page.text()).includes('Open in Ledger'));
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
  if (groupId && sessions[0]) await api('DELETE', `/api/groups/${groupId}`, 204, undefined, sessions[0]).catch(() => {});
  for (const token of sessions) await api('POST', '/api/auth/logout', 204, undefined, token).catch(() => {});
  mkdirSync(new URL('../build/cloud/', import.meta.url), { recursive: true });
  writeFileSync(new URL('../build/cloud/smoke-result.json', import.meta.url), JSON.stringify(report, null, 2));
}
console.log(`PASS: ${report.checks.length} deployed checks; test group hidden and sessions logged out.`);
for (const check of report.checks) console.log(`- ${check}`);
