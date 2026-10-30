import request from 'supertest';
import { ExpenseModel, GroupInvitationModel, GroupModel } from '../../src/groupStore';
import { hashToken } from '../../src/session';
import { useGroupApiFixture } from '../helpers/groupApiFixture';

const fixture = useGroupApiFixture();
const leave = (auth: string) => request(fixture.app).post(`/api/groups/${fixture.groupId}/leave`).set('Authorization', auth);
const remove = (auth: string, memberId = fixture.memberId) => request(fixture.app)
  .delete(`/api/groups/${fixture.groupId}/members/${memberId}`).set('Authorization', auth);

async function expectNoAccess(auth: string) {
  expect((await request(fixture.app).get('/api/groups').set('Authorization', auth)).body.groups).toEqual([]);
  for (const path of ['summary', 'snapshot']) {
    expect((await request(fixture.app).get(`/api/groups/${fixture.groupId}/${path}`).set('Authorization', auth)).status).toBe(404);
  }
  expect((await request(fixture.app).post(`/api/groups/${fixture.groupId}/invitations`).set('Authorization', auth)).status).toBe(404);
  expect((await request(fixture.app).put(`/api/groups/${fixture.groupId}/name`).set('Authorization', auth).send({ name: 'Not allowed' })).status).toBe(404);
  expect((await request(fixture.app).post(`/api/groups/${fixture.groupId}/expenses`).set('Authorization', auth).send({})).status).toBe(404);
  expect((await remove(auth)).status).toBe(404);
}

describe('Unmocked: POST /api/groups/:groupId/leave', () => {
  test('a member leaves without changing ownership, historical expenses or another group', async () => {
    const before = await ExpenseModel.findById(fixture.expenseId).lean();
    const other = await GroupModel.create({ name: 'Other group', currency: 'CAD', ownerId: fixture.ownerId,
      memberIds: [fixture.ownerId, fixture.memberId] });
    const result = await leave(fixture.memberAuth);
    expect(result.status).toBe(200);
    expect(result.body).toEqual({ deleted: false });
    const stored = await GroupModel.findById(fixture.groupId).lean();
    expect(stored?.memberIds.map(String)).toEqual([fixture.ownerId]);
    expect(stored?.ownerId.toString()).toBe(fixture.ownerId);
    expect(stored?.deletedAt).toBeUndefined();
    expect(await ExpenseModel.findById(fixture.expenseId).lean()).toEqual(before);
    expect((await GroupModel.findById(other.id))?.memberIds.map(String)).toContain(fixture.memberId);
    await GroupModel.deleteOne({ _id: other._id });
    await expectNoAccess(fixture.memberAuth);
    const summary = await request(fixture.app).get(`/api/groups/${fixture.groupId}/summary`).set('Authorization', fixture.ownerAuth);
    expect(summary.body.totalCents).toBe(1000);
    expect(summary.body.memberShares).toEqual(expect.arrayContaining([{ userId: fixture.memberId, amountCents: 500 }]));
    expect((await leave(fixture.memberAuth)).status).toBe(404);
  });

  test('an owner leaving deletes the group for every member and disables all invitation paths', async () => {
    expect((await leave(fixture.ownerAuth)).body).toEqual({ deleted: true });
    expect((await GroupModel.findById(fixture.groupId))?.deletedAt).toBeTruthy();
    await expectNoAccess(fixture.ownerAuth);
    await expectNoAccess(fixture.memberAuth);
    const invitation = `/api/groups/invitations/${fixture.invitationToken}`;
    expect((await request(fixture.app).get(invitation).set('Authorization', fixture.inviteeAuth)).status).toBe(404);
    expect((await request(fixture.app).post(`${invitation}/accept`).set('Authorization', fixture.inviteeAuth)).status).toBe(404);
    expect((await request(fixture.app).get(`/invite/${fixture.invitationToken}`)).status).toBe(404);
    expect(await ExpenseModel.countDocuments({ groupId: fixture.groupId })).toBe(1);
  });

  test('a nonmember cannot leave or delete the group', async () => {
    expect((await leave(fixture.inviteeAuth)).status).toBe(404);
    expect((await GroupModel.findById(fixture.groupId))?.memberIds).toHaveLength(2);
    expect((await GroupModel.findById(fixture.groupId))?.deletedAt).toBeUndefined();
  });

  test('a former member can join again through an active current-member invitation', async () => {
    await leave(fixture.memberAuth);
    const joined = await request(fixture.app).post(`/api/groups/invitations/${fixture.invitationToken}/accept`)
      .set('Authorization', fixture.memberAuth);
    expect(joined.status).toBe(200);
    expect(joined.body.group.memberCount).toBe(2);
  });
});

describe('Unmocked: DELETE /api/groups/:groupId/members/:memberId', () => {
  test('only the owner removes a member; retries do not affect expenses or the owner', async () => {
    const before = await ExpenseModel.findById(fixture.expenseId).lean();
    expect((await remove(fixture.ownerAuth)).status).toBe(204);
    expect((await remove(fixture.ownerAuth)).status).toBe(204);
    expect((await GroupModel.findById(fixture.groupId))?.memberIds.map(String)).toEqual([fixture.ownerId]);
    expect(await ExpenseModel.findById(fixture.expenseId).lean()).toEqual(before);
    await expectNoAccess(fixture.memberAuth);
  });

  test('a regular member cannot remove anyone, including themselves or the owner', async () => {
    for (const id of [fixture.ownerId, fixture.memberId]) expect((await remove(fixture.memberAuth, id)).status).toBe(403);
    expect((await GroupModel.findById(fixture.groupId))?.memberIds).toHaveLength(2);
  });

  test('a nonmember cannot probe or modify the roster', async () => {
    expect((await remove(fixture.inviteeAuth)).status).toBe(404);
    expect((await GroupModel.findById(fixture.groupId))?.memberIds).toHaveLength(2);
  });

  test.each(['invalid', '00000000000000000000000g'])('rejects malformed member ID %s', async id => {
    expect((await remove(fixture.ownerAuth, id)).status).toBe(400);
    expect((await GroupModel.findById(fixture.groupId))?.memberIds).toHaveLength(2);
  });

  test('the owner cannot remove themselves through the member endpoint', async () => {
    expect((await remove(fixture.ownerAuth, fixture.ownerId)).status).toBe(400);
    expect((await GroupModel.findById(fixture.groupId))?.deletedAt).toBeUndefined();
  });

  test('removing an inviter invalidates their invitations without disabling the owner invitation', async () => {
    const token = 'm'.repeat(43);
    await GroupInvitationModel.create({ groupId: fixture.groupId, inviterId: fixture.memberId,
      tokenHash: hashToken(token), expiresAt: new Date(Date.now() + 86400000) });
    await remove(fixture.ownerAuth);
    expect((await request(fixture.app).get(`/api/groups/invitations/${token}`).set('Authorization', fixture.inviteeAuth)).status).toBe(404);
    expect((await request(fixture.app).post(`/api/groups/invitations/${token}/accept`).set('Authorization', fixture.inviteeAuth)).status).toBe(404);
    expect((await request(fixture.app).get(`/api/groups/invitations/${fixture.invitationToken}`).set('Authorization', fixture.inviteeAuth)).status).toBe(200);
  });

  test('concurrent removal and voluntary departure never remove the owner or restore membership', async () => {
    const responses = await Promise.all([remove(fixture.ownerAuth), leave(fixture.memberAuth)]);
    expect(responses[0]?.status).toBe(204);
    expect([200, 404]).toContain(responses[1]?.status);
    expect((await GroupModel.findById(fixture.groupId))?.memberIds.map(String)).toEqual([fixture.ownerId]);
    expect((await GroupModel.findById(fixture.groupId))?.deletedAt).toBeUndefined();
  });
});
