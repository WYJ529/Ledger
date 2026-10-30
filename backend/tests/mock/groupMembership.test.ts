import request from 'supertest';
import { GroupModel } from '../../src/groupStore';
import { databaseFailure, expectDatabaseError, useGroupApiFixture } from '../helpers/groupApiFixture';

const fixture = useGroupApiFixture();
beforeEach(() => { jest.spyOn(console, 'error').mockImplementation(() => undefined); });

describe.each(['leave', 'remove'] as const)('Mocked group membership: %s', action => {
  const send = () => action === 'leave'
    ? request(fixture.app).post(`/api/groups/${fixture.groupId}/leave`).set('Authorization', fixture.memberAuth)
    : request(fixture.app).delete(`/api/groups/${fixture.groupId}/members/${fixture.memberId}`).set('Authorization', fixture.ownerAuth);

  test('a persistence failure is reported without claiming success or altering membership', async () => {
    jest.spyOn(GroupModel, 'findOneAndUpdate').mockRejectedValueOnce(databaseFailure());
    expectDatabaseError(await send());
    expect((await GroupModel.findById(fixture.groupId))?.memberIds).toHaveLength(2);
  });

  test('a concurrent group deletion is reported as unavailable', async () => {
    jest.spyOn(GroupModel, 'findOneAndUpdate').mockResolvedValueOnce(null);
    expect((await send()).status).toBe(404);
  });
});
