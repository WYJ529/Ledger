import request from 'supertest';
import { GroupInvitationModel, GroupModel } from '../../src/groupStore';
import { databaseFailure, expectDatabaseError, failNextFindOne, useGroupApiFixture } from '../helpers/groupApiFixture';
import { groupMembershipEvents } from '../../src/groupMembershipEvents';

// Mocked component: MongoDB group and invitation model calls are replaced with jest spies that reject or return null.
const fixture = useGroupApiFixture();

beforeEach(() => {
  jest.spyOn(console, 'error').mockImplementation(() => undefined);
});


// Interface POST /api/groups
describe('Mocked: POST /api/groups', () => {
  // Input: valid group; mock: the group insert rejects
  // Expected status code: 500
  // Expected behavior: success is not reported; no group or invitation is added
  // Expected output: { error: "Something went wrong. Please try again." }
  test('Does not report success when group persistence fails', async () => {
    const create = jest.spyOn(GroupModel, 'create').mockRejectedValueOnce(databaseFailure());
    const response = await request(fixture.app).post('/api/groups').set('Authorization', fixture.ownerAuth)
      .send({ name: 'New group', currency: 'CAD', requestId: 'new-group-api-request' });
    expectDatabaseError(response);
    expect(create).toHaveBeenCalledTimes(1);
    expect(await GroupModel.countDocuments()).toBe(1);
    expect(await GroupInvitationModel.countDocuments()).toBe(1);
  });

  // Input: the same creation retried after the invitation insert failed; mock: first invitation insert rejects
  // Expected status code: 500, then 200
  // Expected behavior: the retry completes the partial creation without duplicating the group
  // Expected output: { group, invitation } with the originally saved group ID
  test('Recovers a partially completed creation without duplicating the group', async () => {
    const createInvitation = jest.spyOn(GroupInvitationModel, 'create').mockRejectedValueOnce(databaseFailure());
    const body = { name: 'New group', currency: 'CAD', requestId: 'new-group-api-request' };
    const submit = (): request.Test => request(fixture.app).post('/api/groups').set('Authorization', fixture.ownerAuth).send(body);
    expectDatabaseError(await submit());
    const saved = await GroupModel.findOne({ createRequestId: { $eq: 'new-group-api-request' } }).lean();
    expect(saved).not.toBeNull();
    expect(await GroupInvitationModel.countDocuments()).toBe(1);
    const retry = await submit();
    expect(retry.status).toBe(200);
    expect(retry.body.group.id).toBe(saved?._id.toString());
    expect(retry.body.invitation.deepLink).toMatch(/^ledger:\/\/invite\//);
    expect(createInvitation).toHaveBeenCalledTimes(2);
    expect(await GroupModel.countDocuments()).toBe(2);
    expect(await GroupInvitationModel.countDocuments()).toBe(2);
  });
});

// Interface GET /api/groups/invitations/:token
describe('Mocked: GET /api/groups/invitations/:token', () => {
  // Input: valid invitation and invitee; mock: the invitation lookup rejects
  // Expected status code: 500
  // Expected behavior: a failed lookup is not reported as an expired link; membership is unchanged
  // Expected output: { error: "Something went wrong. Please try again." }
  test('Distinguishes a failed lookup from an unavailable invitation', async () => {
    const find = failNextFindOne(GroupInvitationModel);
    const response = await request(fixture.app).get(`/api/groups/invitations/${fixture.invitationToken}`)
      .set('Authorization', fixture.inviteeAuth);
    expectDatabaseError(response);
    expect(find).toHaveBeenCalledTimes(1);
    expect((await GroupModel.findById(fixture.groupId))?.memberIds.map(String)).toEqual([fixture.ownerId, fixture.memberId]);
  });
});

// Interface POST /api/groups/invitations/:token/accept
describe('Mocked: POST /api/groups/invitations/:token/accept', () => {
  // Input: valid invitation for a new member; mock: the atomic membership write rejects once
  // Expected status code: 500, then 200 on retry
  // Expected behavior: membership is unchanged after the failure; the retry joins once and publishes one event
  // Expected output: { group }; the member appears once
  test('Allows a failed membership write to be retried without duplicates', async () => {
    const publish = jest.spyOn(groupMembershipEvents, 'emit');
    const update = jest.spyOn(GroupModel, 'findOneAndUpdate').mockRejectedValueOnce(databaseFailure());
    const accept = (): request.Test => request(fixture.app).post(`/api/groups/invitations/${fixture.invitationToken}/accept`)
      .set('Authorization', fixture.inviteeAuth);
    expectDatabaseError(await accept());
    expect(publish).not.toHaveBeenCalled();
    expect((await GroupModel.findById(fixture.groupId))?.memberIds.map(String)).toEqual([fixture.ownerId, fixture.memberId]);
    const retry = await accept();
    expect(retry.status).toBe(200);
    expect(publish).toHaveBeenCalledTimes(1);
    expect(publish).toHaveBeenCalledWith('changed', { groupId: fixture.groupId, type: 'joined' });
    expect(update).toHaveBeenCalledTimes(2);
    const members = (await GroupModel.findById(fixture.groupId))?.memberIds.map(String);
    expect(members).toHaveLength(3);
    expect(members?.filter(id => id === fixture.inviteeId)).toHaveLength(1);
  });

  // Input: valid invitation; mock: the conditional write finds no group and the existence check returns null
  // Expected status code: 404
  // Expected behavior: a group deleted mid-request is reported as missing, not full
  // Expected output: { error: "Group not found." }
  test('Does not describe a deleted group as merely full', async () => {
    jest.spyOn(GroupModel, 'findOneAndUpdate').mockResolvedValueOnce(null);
    const exists = jest.spyOn(GroupModel, 'exists').mockResolvedValueOnce(null);
    const response = await request(fixture.app).post(`/api/groups/invitations/${fixture.invitationToken}/accept`)
      .set('Authorization', fixture.inviteeAuth);
    expect(response.status).toBe(404);
    expect(response.body).toEqual({ error: 'Group not found.' });
    expect(exists).toHaveBeenCalledTimes(1);
    expect((await GroupModel.findById(fixture.groupId))?.memberIds).toHaveLength(2);
  });
});

// Interface POST /api/groups/:groupId/invitations
describe('Mocked: POST /api/groups/:groupId/invitations', () => {
  // Input: active member; mock: the invitation insert rejects
  // Expected status code: 500
  // Expected behavior: no link is returned or stored
  // Expected output: { error: "Something went wrong. Please try again." }
  test('Does not return an invitation that could not be stored', async () => {
    const create = jest.spyOn(GroupInvitationModel, 'create').mockRejectedValueOnce(databaseFailure());
    const response = await request(fixture.app).post(`/api/groups/${fixture.groupId}/invitations`)
      .set('Authorization', fixture.memberAuth);
    expectDatabaseError(response);
    expect(create).toHaveBeenCalledTimes(1);
    expect(response.body).not.toHaveProperty('invitation');
    expect(await GroupInvitationModel.countDocuments()).toBe(1);
  });
});

// Interface PUT /api/groups/:groupId/name
describe('Mocked: PUT /api/groups/:groupId/name', () => {
  // Input: valid member and name; mock: the rename write rejects
  // Expected status code: 500
  // Expected behavior: name, currency, owner and members are unchanged
  // Expected output: { error: "Something went wrong. Please try again." }
  test('Preserves the group when renaming fails', async () => {
    const before = await GroupModel.findById(fixture.groupId).lean();
    const update = jest.spyOn(GroupModel, 'findOneAndUpdate').mockRejectedValueOnce(databaseFailure());
    const response = await request(fixture.app).put(`/api/groups/${fixture.groupId}/name`)
      .set('Authorization', fixture.memberAuth).send({ name: 'Changed name' });
    expectDatabaseError(response);
    expect(update).toHaveBeenCalledTimes(1);
    expect(await GroupModel.findById(fixture.groupId).lean()).toEqual(before);
  });

  // Input: valid rename; mock: the conditional write finds no group
  // Expected status code: 404
  // Expected behavior: a group that disappears mid-request is not reported as renamed
  // Expected output: { error: "Group not found." }
  test('Handles a group disappearing before the rename commits', async () => {
    jest.spyOn(GroupModel, 'findOneAndUpdate').mockResolvedValueOnce(null);
    const response = await request(fixture.app).put(`/api/groups/${fixture.groupId}/name`)
      .set('Authorization', fixture.ownerAuth).send({ name: 'Changed name' });
    expect(response.status).toBe(404);
    expect(response.body).toEqual({ error: 'Group not found.' });
    expect((await GroupModel.findById(fixture.groupId))?.name).toBe('API test group');
  });
});

// Interface DELETE /api/groups/:groupId
describe('Mocked: DELETE /api/groups/:groupId', () => {

  // Input: another process finishes deletion after the owner is authorized.
  // Expected status code: 204.
  // Expected behavior: acknowledge the idempotent deletion without republishing it.
  // Expected output: no second membership-change event.
  test('does not announce a concurrent deletion twice', async () => {
    jest.spyOn(GroupModel, 'updateOne').mockResolvedValueOnce({ modifiedCount: 0 } as never);
    const publish = jest.spyOn(groupMembershipEvents, 'emit');
    const response = await request(fixture.app).delete(`/api/groups/${fixture.groupId}`).set('Authorization', fixture.ownerAuth);
    expect(response.status).toBe(204);
    expect(publish).not.toHaveBeenCalled();
  });

  // Input: owner deletes an active group; mock: the soft-delete write rejects
  // Expected status code: 500
  // Expected behavior: the group stays active and its invitation still works
  // Expected output: { error: "Something went wrong. Please try again." }
  test('Keeps the group accessible when deletion fails', async () => {
    const update = jest.spyOn(GroupModel, 'updateOne').mockRejectedValueOnce(databaseFailure());
    const response = await request(fixture.app).delete(`/api/groups/${fixture.groupId}`)
      .set('Authorization', fixture.ownerAuth);
    expectDatabaseError(response);
    expect(update).toHaveBeenCalledTimes(1);
    expect((await GroupModel.findById(fixture.groupId))?.deletedAt).toBeFalsy();
    const preview = await request(fixture.app).get(`/api/groups/invitations/${fixture.invitationToken}`)
      .set('Authorization', fixture.inviteeAuth);
    expect(preview.status).toBe(200);
  });
});

// Interface GET /invite/:token
describe('Mocked: GET /invite/:token', () => {
  // Input: valid invitation token; mock: the invitation lookup rejects
  // Expected status code: 500
  // Expected behavior: no invitation page or app link is served
  // Expected output: { error: "Something went wrong. Please try again." }
  test('Does not serve an invitation page when the lookup fails', async () => {
    failNextFindOne(GroupInvitationModel);
    const page = await request(fixture.app).get(`/invite/${fixture.invitationToken}`);
    expect(page.status).toBe(500);
    expect(page.text).not.toContain('ledger://invite/');
  });

  // Input: valid invitation token; mock: the inviter's group membership lookup rejects
  // Expected status code: 500
  // Expected behavior: the group name is not disclosed
  // Expected output: { error: "Something went wrong. Please try again." }
  test('Does not disclose the group when its lookup fails', async () => {
    failNextFindOne(GroupModel);
    const page = await request(fixture.app).get(`/invite/${fixture.invitationToken}`);
    expect(page.status).toBe(500);
    expect(page.text).not.toContain('API test group');
  });
});
