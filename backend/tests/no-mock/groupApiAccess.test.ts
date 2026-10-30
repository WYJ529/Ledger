import request from 'supertest';
import { ExpenseModel, GroupInvitationModel, GroupModel } from '../../src/groupStore';
import { hashToken } from '../../src/session';
import { SessionModel } from '../../src/userStore';
import { useGroupApiFixture } from '../helpers/groupApiFixture';

const fixture = useGroupApiFixture();

// Real HTTP routing, session verification and MongoDB. No mocked components.
const protectedInterfaces = [
  { name: 'POST /api/groups', send: () => request(fixture.app).post('/api/groups')
    .send({ name: 'Unauthorized group', requestId: 'unauthorized-group-request' }) },
  { name: 'GET /api/groups', send: () => request(fixture.app).get('/api/groups') },
  { name: 'GET /api/groups/invitations/:token', send: () => request(fixture.app)
    .get(`/api/groups/invitations/${fixture.invitationToken}`) },
  { name: 'POST /api/groups/invitations/:token/accept', send: () => request(fixture.app)
    .post(`/api/groups/invitations/${fixture.invitationToken}/accept`) },
  { name: 'POST /api/groups/:groupId/invitations', send: () => request(fixture.app)
    .post(`/api/groups/${fixture.groupId}/invitations`) },
  { name: 'PUT /api/groups/:groupId/name', send: () => request(fixture.app)
    .put(`/api/groups/${fixture.groupId}/name`).send({ name: 'Unauthorized rename' }) },
  { name: 'DELETE /api/groups/:groupId', send: () => request(fixture.app).delete(`/api/groups/${fixture.groupId}`) },
  { name: 'POST /api/groups/:groupId/leave', send: () => request(fixture.app).post(`/api/groups/${fixture.groupId}/leave`) },
  { name: 'DELETE /api/groups/:groupId/members/:memberId', send: () => request(fixture.app)
    .delete(`/api/groups/${fixture.groupId}/members/${fixture.memberId}`) },
  { name: 'GET /api/groups/:groupId/summary', send: () => request(fixture.app).get(`/api/groups/${fixture.groupId}/summary`) },
  { name: 'POST /api/groups/:groupId/expenses', send: () => request(fixture.app)
    .post(`/api/groups/${fixture.groupId}/expenses`).send(expenseInput()) },
  { name: 'PUT /api/groups/:groupId/expenses/:expenseId', send: () => request(fixture.app)
    .put(`/api/groups/${fixture.groupId}/expenses/${fixture.expenseId}`).send({ ...expenseInput(), revision: 0 }) },
];

function expenseInput() {
  return {
    description: 'Unauthorized bill', amount: '20.00', paidBy: fixture.ownerId, splitMode: 'equal',
    shares: [{ userId: fixture.ownerId }], requestId: 'unauthorized-bill-request',
  };
}

describe.each(protectedInterfaces)('Unmocked: $name', ({ send }) => {
  // Input: syntactically valid request with a missing or unknown bearer token.
  // Expected: 401 + Bearer challenge, no private response data and no financial/group changes.
  test.each(['missing', 'unknown'])('rejects a %s session before exposing or changing group data', async kind => {
    const before = await snapshot();
    const attempt = send();
    if (kind === 'unknown') attempt.set('Authorization', 'Bearer unknown-session-token-00000000');
    const response = await attempt;
    expect(response.status).toBe(401);
    expect(response.headers['www-authenticate']).toBe('Bearer');
    expect(response.body).toEqual({ error: 'Your session has expired. Please sign in again.' });
    expect(await snapshot()).toEqual(before);
  });

  // Input: genuine owner session expired in storage, without relying on MongoDB's TTL cleanup.
  // Expected: 401 at request time; membership, invitations and expenses remain unchanged.
  test('rejects an expired session still present in MongoDB', async () => {
    const token = fixture.ownerAuth.slice('Bearer '.length);
    await SessionModel.updateOne({ tokenHash: hashToken(token) }, { expiresAt: new Date(Date.now() - 1000) });
    const before = await snapshot();
    const response = await send().set('Authorization', fixture.ownerAuth);
    expect(response.status).toBe(401);
    expect(response.headers['www-authenticate']).toBe('Bearer');
    expect(response.body).toEqual({ error: 'Your session has expired. Please sign in again.' });
    expect(await snapshot()).toEqual(before);
  });
});

async function snapshot() {
  const [groups, invitations, expenses] = await Promise.all([
    GroupModel.find().sort({ _id: 1 }).lean(),
    GroupInvitationModel.find().sort({ _id: 1 }).lean(),
    ExpenseModel.find().sort({ _id: 1 }).lean(),
  ]);
  return { groups, invitations, expenses };
}
