import request from 'supertest';
import { Types } from 'mongoose';
import { findMemberGroup } from '../../src/groupAccess';
import { GroupModel } from '../../src/groupStore';
import { mongoSessionStore, mongoUserStore } from '../../src/userStore';
import { useGroupApiFixture } from '../helpers/groupApiFixture';

const fixture = useGroupApiFixture();

test.each(['snapshot', 'split-sessions'])('%s requests authenticate and read membership only once', async endpoint => {
  const sessions = jest.spyOn(mongoSessionStore, 'find');
  const users = jest.spyOn(mongoUserStore, 'findById');
  const groups = jest.spyOn(GroupModel, 'findOne');
  const path = `/api/groups/${fixture.groupId}/${endpoint}`;
  const response = endpoint === 'snapshot'
    ? await request(fixture.app).get(path).set('Authorization', fixture.ownerAuth)
    : await request(fixture.app).post(path).set('Authorization', fixture.ownerAuth).send({});
  expect(response.status).toBe(endpoint === 'snapshot' ? 200 : 400);
  expect(sessions).toHaveBeenCalledTimes(1);
  expect(users).toHaveBeenCalledTimes(1);
  expect(groups).toHaveBeenCalledTimes(1);
});

test.each([null, {}, { $ne: null }, ['000000000000000000000001'], 'invalid'])('rejects invalid group IDs without a query: %j', async id => {
  const query = jest.spyOn(GroupModel, 'findOne');
  expect(await findMemberGroup(id, fixture.ownerId)).toBeNull();
  expect(query).not.toHaveBeenCalled();
});

test('membership is read again after removal or group deletion', async () => {
  expect(await findMemberGroup(fixture.groupId, fixture.memberId)).not.toBeNull();
  expect(await findMemberGroup(fixture.groupId, fixture.inviteeId)).toBeNull();
  await GroupModel.updateOne({ _id: new Types.ObjectId(fixture.groupId) },
    { $pull: { memberIds: new Types.ObjectId(fixture.memberId) } });
  expect(await findMemberGroup(fixture.groupId, fixture.memberId)).toBeNull();
  await GroupModel.updateOne({ _id: new Types.ObjectId(fixture.groupId) }, { $set: { deletedAt: new Date() } });
  expect(await findMemberGroup(fixture.groupId, fixture.ownerId)).toBeNull();
});

test.each(['summary', 'snapshot', 'invitations'])('nonmembers cannot discover a group through %s', async endpoint => {
  const path = `/api/groups/${fixture.groupId}/${endpoint}`;
  const response = endpoint === 'invitations'
    ? await request(fixture.app).post(path).set('Authorization', fixture.inviteeAuth)
    : await request(fixture.app).get(path).set('Authorization', fixture.inviteeAuth);
  expect(response.status).toBe(404);
  expect(response.body).toEqual({ error: 'Group not found.' });
});
