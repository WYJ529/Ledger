import request from 'supertest';
import { GroupInvitationModel, GroupModel } from '../../src/groupStore';
import { databaseFailure, expectDatabaseError, useGroupApiFixture } from '../helpers/groupApiFixture';

const fixture = useGroupApiFixture();

beforeEach(() => {
  jest.spyOn(console, 'error').mockImplementation(() => undefined);
});

describe('Mocked: POST /api/groups', () => {
  // Input: valid group. Mock: group insert rejects before writing.
  // Expected: 500 with a generic error; no group or invitation is added.
  test('does not report success when group persistence fails', async () => {
    const create = jest.spyOn(GroupModel, 'create').mockRejectedValueOnce(databaseFailure());
    const response = await request(fixture.app).post('/api/groups').set('Authorization', fixture.ownerAuth)
      .send({ name: 'New group', currency: 'CAD', requestId: 'new-group-api-request' });
    expectDatabaseError(response);
    expect(create).toHaveBeenCalledTimes(1);
    expect(await GroupModel.countDocuments()).toBe(1);
    expect(await GroupInvitationModel.countDocuments()).toBe(1);
  });

  // Input: same confirmed creation retried after invitation persistence failed.
  // Mock: first invitation insert rejects. Expected: 500, then 200; one new group, one new invite.
  test('recovers a partially completed creation without duplicating the group', async () => {
    const createInvitation = jest.spyOn(GroupInvitationModel, 'create').mockRejectedValueOnce(databaseFailure());
    const body = { name: 'New group', currency: 'CAD', requestId: 'new-group-api-request' };
    const submit = () => request(fixture.app).post('/api/groups').set('Authorization', fixture.ownerAuth).send(body);
    expectDatabaseError(await submit());
    const saved = await GroupModel.findOne({ createRequestId: body.requestId }).lean();
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

describe('Mocked: GET /api/groups', () => {
  // Input: valid session. Mock: list query rejects.
  // Expected: 500 rather than a misleading successful empty group list.
  test('reports a database failure instead of returning an empty list', async () => {
    const query = GroupModel.find();
    jest.spyOn(query, 'exec').mockRejectedValueOnce(databaseFailure());
    const find = jest.spyOn(GroupModel, 'find').mockReturnValueOnce(query);
    const response = await request(fixture.app).get('/api/groups').set('Authorization', fixture.ownerAuth);
    expectDatabaseError(response);
    expect(find).toHaveBeenCalledTimes(1);
    expect(response.body).not.toHaveProperty('groups');
    expect(await GroupModel.countDocuments()).toBe(1);
  });
});

describe('Mocked: GET /api/groups/invitations/:token', () => {
  // Input: valid invitation and invitee. Mock: invitation lookup rejects.
  // Expected: 500, not an expired-link 404; membership is unchanged.
  test('distinguishes failed lookup from an unavailable invitation', async () => {
    const query = GroupInvitationModel.findOne();
    jest.spyOn(query, 'exec').mockRejectedValueOnce(databaseFailure());
    const find = jest.spyOn(GroupInvitationModel, 'findOne').mockReturnValueOnce(query);
    const response = await request(fixture.app).get(`/api/groups/invitations/${fixture.invitationToken}`)
      .set('Authorization', fixture.inviteeAuth);
    expectDatabaseError(response);
    expect(find).toHaveBeenCalledTimes(1);
    expect((await GroupModel.findById(fixture.groupId))?.memberIds.map(String))
      .toEqual([fixture.ownerId, fixture.memberId]);
  });
});

describe('Mocked: POST /api/groups/invitations/:token/accept', () => {
  // Input: valid invitation, invitee not yet a member. Mock: atomic membership write rejects.
  // Expected: 500 with unchanged membership; retry succeeds once with 200.
  test('allows a failed membership write to be retried without duplicate members', async () => {
    const update = jest.spyOn(GroupModel, 'findOneAndUpdate').mockRejectedValueOnce(databaseFailure());
    const accept = () => request(fixture.app).post(`/api/groups/invitations/${fixture.invitationToken}/accept`)
      .set('Authorization', fixture.inviteeAuth);
    expectDatabaseError(await accept());
    expect((await GroupModel.findById(fixture.groupId))?.memberIds.map(String))
      .toEqual([fixture.ownerId, fixture.memberId]);
    const retry = await accept();
    expect(retry.status).toBe(200);
    expect(update).toHaveBeenCalledTimes(2);
    const members = (await GroupModel.findById(fixture.groupId))?.memberIds.map(String);
    expect(members).toHaveLength(3);
    expect(members?.filter(id => id === fixture.inviteeId)).toHaveLength(1);
  });

  // Input: valid preview followed by deletion before the conditional membership write.
  // Mock: atomic update finds no group; existence lookup returns null. Expected: 404; no join.
  test('does not describe a deleted group as merely full', async () => {
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

describe('Mocked: POST /api/groups/:groupId/invitations', () => {
  // Input: active member and group. Mock: invitation insert rejects.
  // Expected: 500; no new link is returned or invitation persisted.
  test('does not return an invitation that could not be stored', async () => {
    const create = jest.spyOn(GroupInvitationModel, 'create').mockRejectedValueOnce(databaseFailure());
    const response = await request(fixture.app).post(`/api/groups/${fixture.groupId}/invitations`)
      .set('Authorization', fixture.memberAuth);
    expectDatabaseError(response);
    expect(create).toHaveBeenCalledTimes(1);
    expect(response.body).not.toHaveProperty('invitation');
    expect(await GroupInvitationModel.countDocuments()).toBe(1);
  });
});

describe('Mocked: PUT /api/groups/:groupId/name', () => {
  // Input: valid member/name. Mock: rename write rejects.
  // Expected: 500; stored name, currency, owner and membership remain unchanged.
  test('preserves the original group when renaming fails', async () => {
    const before = await GroupModel.findById(fixture.groupId).lean();
    const update = jest.spyOn(GroupModel, 'findOneAndUpdate').mockRejectedValueOnce(databaseFailure());
    const response = await request(fixture.app).put(`/api/groups/${fixture.groupId}/name`)
      .set('Authorization', fixture.memberAuth).send({ name: 'Changed name' });
    expectDatabaseError(response);
    expect(update).toHaveBeenCalledTimes(1);
    expect(await GroupModel.findById(fixture.groupId).lean()).toEqual(before);
  });

  // Input: valid rename after an earlier membership read. Mock: conditional write finds no group.
  // Expected: 404 rather than success with a null group.
  test('handles a group disappearing before the rename commits', async () => {
    jest.spyOn(GroupModel, 'findOneAndUpdate').mockResolvedValueOnce(null);
    const response = await request(fixture.app).put(`/api/groups/${fixture.groupId}/name`)
      .set('Authorization', fixture.ownerAuth).send({ name: 'Changed name' });
    expect(response.status).toBe(404);
    expect(response.body).toEqual({ error: 'Group not found.' });
    expect((await GroupModel.findById(fixture.groupId))?.name).toBe('API test group');
  });
});

describe('Mocked: DELETE /api/groups/:groupId', () => {
  // Input: owner deletes an active group. Mock: soft-delete write rejects.
  // Expected: 500, not 204; the group stays active and its invitation remains usable.
  test('keeps the group accessible when deletion fails', async () => {
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
