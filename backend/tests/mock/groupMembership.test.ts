import request from 'supertest';
import { GroupModel } from '../../src/groupStore';
import { groupMembershipEvents } from '../../src/groupMembershipEvents';
import { databaseFailure, expectDatabaseError, useGroupApiFixture } from '../helpers/groupApiFixture';

// Mocked component: the MongoDB group membership write (GroupModel.findOneAndUpdate) rejects or returns null.
const fixture = useGroupApiFixture();
beforeEach(() => { jest.spyOn(console, 'error').mockImplementation(() => undefined); });

const leave = (): request.Test => request(fixture.app).post(`/api/groups/${fixture.groupId}/leave`).set('Authorization', fixture.memberAuth);
const remove = (): request.Test => request(fixture.app).delete(`/api/groups/${fixture.groupId}/members/${fixture.memberId}`).set('Authorization', fixture.ownerAuth);

async function expectFailedWriteLeavesMembership(send: () => request.Test) {
  const publish = jest.spyOn(groupMembershipEvents, 'emit');
  jest.spyOn(GroupModel, 'findOneAndUpdate').mockRejectedValueOnce(databaseFailure());
  expectDatabaseError(await send());
  expect((await GroupModel.findById(fixture.groupId))?.memberIds).toHaveLength(2);
  expect(publish).not.toHaveBeenCalled();
}

// Interface POST /api/groups/:groupId/leave
describe('Mocked: POST /api/groups/:groupId/leave', () => {
  // Input: a member leaves; mock: the membership write rejects
  // Expected status code: 500
  // Expected behavior: success is not claimed, membership is unchanged and no event is published
  // Expected output: { error: "Something went wrong. Please try again." }
  test('Reports a persistence failure without changing membership', async () => {
    await expectFailedWriteLeavesMembership(leave);
  });

  // Input: a member leaves; mock: the conditional write finds no group (deleted concurrently)
  // Expected status code: 404
  // Expected behavior: the departure is reported as unavailable
  // Expected output: { error: "Group not found." }
  test('Reports a concurrently deleted group as unavailable', async () => {
    jest.spyOn(GroupModel, 'findOneAndUpdate').mockResolvedValueOnce(null);
    expect((await leave()).status).toBe(404);
  });
});

// Interface DELETE /api/groups/:groupId/members/:memberId
describe('Mocked: DELETE /api/groups/:groupId/members/:memberId', () => {
  // Input: the owner removes a member; mock: the membership write rejects
  // Expected status code: 500
  // Expected behavior: success is not claimed, membership is unchanged and no event is published
  // Expected output: { error: "Something went wrong. Please try again." }
  test('Reports a persistence failure without changing membership', async () => {
    await expectFailedWriteLeavesMembership(remove);
  });

  // Input: the owner removes a member; mock: the conditional write finds no group (deleted concurrently)
  // Expected status code: 404
  // Expected behavior: the removal is reported as unavailable
  // Expected output: { error: "Group not found." }
  test('Reports a concurrently deleted group as unavailable', async () => {
    jest.spyOn(GroupModel, 'findOneAndUpdate').mockResolvedValueOnce(null);
    expect((await remove()).status).toBe(404);
  });
});
