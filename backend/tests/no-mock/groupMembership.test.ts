import request from 'supertest';
import { ExpenseModel, GroupInvitationModel, GroupModel } from '../../src/groupStore';
import { hashToken } from '../../src/session';
import { useGroupApiFixture } from '../helpers/groupApiFixture';
import { testsRejectInvalidSessions } from '../helpers/sessionRejection';

// Real HTTP routing, session verification and MongoDB. No mocked components.
const fixture = useGroupApiFixture();
const leave = (auth: string) => request(fixture.app).post(`/api/groups/${fixture.groupId}/leave`).set('Authorization', auth);
const remove = (auth: string, memberId = fixture.memberId) => request(fixture.app)
  .delete(`/api/groups/${fixture.groupId}/members/${memberId}`).set('Authorization', auth);
const groupState = () => Promise.all([GroupModel.find().lean(), GroupInvitationModel.find().lean(), ExpenseModel.find().lean()]);

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

// Interface POST /api/groups/:groupId/leave
describe('Unmocked: POST /api/groups/:groupId/leave', () => {
  testsRejectInvalidSessions(() => request(fixture.app).post(`/api/groups/${fixture.groupId}/leave`), () => fixture.ownerAuth, groupState);

  // Input: a regular member leaves; a second group also contains that member
  // Expected status code: 200, then 404 on a second attempt
  // Expected behavior: only this membership is removed; owner, history and the other group are unchanged
  // Expected output: { deleted: false }
  test('A member leaves without changing ownership, history or another group', async () => {
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

  // Input: the owner leaves
  // Expected status code: 200
  // Expected behavior: the group is deleted for every member; all invitation paths stop working; expenses are kept
  // Expected output: { deleted: true }
  test('An owner leaving deletes the group and disables its invitations', async () => {
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

  // Input: a nonmember tries to leave
  // Expected status code: 404
  // Expected behavior: membership and the group are unchanged
  // Expected output: { error: "Group not found." }
  test('A nonmember cannot leave or delete the group', async () => {
    expect((await leave(fixture.inviteeAuth)).status).toBe(404);
    expect((await GroupModel.findById(fixture.groupId))?.memberIds).toHaveLength(2);
    expect((await GroupModel.findById(fixture.groupId))?.deletedAt).toBeUndefined();
  });

  // Input: a former member accepts an invitation that is still active
  // Expected status code: 200
  // Expected behavior: the former member joins again
  // Expected output: { group } with memberCount 2
  test('A former member can join again through an active invitation', async () => {
    await leave(fixture.memberAuth);
    const joined = await request(fixture.app).post(`/api/groups/invitations/${fixture.invitationToken}/accept`)
      .set('Authorization', fixture.memberAuth);
    expect(joined.status).toBe(200);
    expect(joined.body.group.memberCount).toBe(2);
  });
});

// Interface DELETE /api/groups/:groupId/members/:memberId
describe('Unmocked: DELETE /api/groups/:groupId/members/:memberId', () => {
  testsRejectInvalidSessions(() => request(fixture.app).delete(`/api/groups/${fixture.groupId}/members/${fixture.memberId}`),
    () => fixture.ownerAuth, groupState);

  // Input: the owner removes a member, then retries
  // Expected status code: 204 for both
  // Expected behavior: the member loses access; expenses and the owner are unchanged
  // Expected output: empty body
  test('Only the owner removes a member and retries are harmless', async () => {
    const before = await ExpenseModel.findById(fixture.expenseId).lean();
    expect((await remove(fixture.ownerAuth)).status).toBe(204);
    expect((await remove(fixture.ownerAuth)).status).toBe(204);
    expect((await GroupModel.findById(fixture.groupId))?.memberIds.map(String)).toEqual([fixture.ownerId]);
    expect(await ExpenseModel.findById(fixture.expenseId).lean()).toEqual(before);
    await expectNoAccess(fixture.memberAuth);
  });

  // Input: a regular member tries to remove the owner and themselves
  // Expected status code: 403
  // Expected behavior: the roster is unchanged
  // Expected output: error message
  test('A regular member cannot remove anyone', async () => {
    for (const id of [fixture.ownerId, fixture.memberId]) expect((await remove(fixture.memberAuth, id)).status).toBe(403);
    expect((await GroupModel.findById(fixture.groupId))?.memberIds).toHaveLength(2);
  });

  // Input: a nonmember tries to remove a member
  // Expected status code: 404
  // Expected behavior: the roster is not disclosed or changed
  // Expected output: { error: "Group not found." }
  test('A nonmember cannot probe or modify the roster', async () => {
    expect((await remove(fixture.inviteeAuth)).status).toBe(404);
    expect((await GroupModel.findById(fixture.groupId))?.memberIds).toHaveLength(2);
  });

  // Input: malformed member ID
  // Expected status code: 400
  // Expected behavior: the roster is unchanged
  // Expected output: error message
  test.each(['invalid', '00000000000000000000000g'])('Rejects malformed member ID %s', async id => {
    expect((await remove(fixture.ownerAuth, id)).status).toBe(400);
    expect((await GroupModel.findById(fixture.groupId))?.memberIds).toHaveLength(2);
  });

  // Input: the owner names their own ID
  // Expected status code: 400
  // Expected behavior: the owner cannot leave through this endpoint; the group stays active
  // Expected output: error message
  test('The owner cannot remove themselves', async () => {
    expect((await remove(fixture.ownerAuth, fixture.ownerId)).status).toBe(400);
    expect((await GroupModel.findById(fixture.groupId))?.deletedAt).toBeUndefined();
  });

  // Input: remove a member who created an invitation
  // Expected status code: 204
  // Expected behavior: that member's invitations stop working; the owner's invitation still works
  // Expected output: preview of the member's token returns 404, owner's token returns 200
  test('Removing an inviter invalidates only their invitations', async () => {
    const token = 'm'.repeat(43);
    await GroupInvitationModel.create({ groupId: fixture.groupId, inviterId: fixture.memberId,
      tokenHash: hashToken(token), expiresAt: new Date(Date.now() + 86400000) });
    expect((await remove(fixture.ownerAuth)).status).toBe(204);
    expect((await request(fixture.app).get(`/api/groups/invitations/${token}`).set('Authorization', fixture.inviteeAuth)).status).toBe(404);
    expect((await request(fixture.app).post(`/api/groups/invitations/${token}/accept`).set('Authorization', fixture.inviteeAuth)).status).toBe(404);
    expect((await request(fixture.app).get(`/api/groups/invitations/${fixture.invitationToken}`).set('Authorization', fixture.inviteeAuth)).status).toBe(200);
  });

  // Input: owner removes a member while that member leaves at the same time
  // Expected status code: 204 for removal; 200 or 404 for the departure
  // Expected behavior: the owner is never removed and the member is not restored
  // Expected output: the group keeps only the owner
  test('Concurrent removal and departure never remove the owner', async () => {
    const responses = await Promise.all([remove(fixture.ownerAuth), leave(fixture.memberAuth)]);
    expect(responses[0].status).toBe(204);
    expect([200, 404]).toContain(responses[1].status);
    expect((await GroupModel.findById(fixture.groupId))?.memberIds.map(String)).toEqual([fixture.ownerId]);
    expect((await GroupModel.findById(fixture.groupId))?.deletedAt).toBeUndefined();
  });
});
