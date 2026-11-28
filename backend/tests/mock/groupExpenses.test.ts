import request from 'supertest';
import { ExpenseModel, GroupModel } from '../../src/groupStore';
import { databaseFailure, expectDatabaseError, useGroupApiFixture } from '../helpers/groupApiFixture';

// Mocked component: MongoDB group and expense model calls are replaced with jest spies that reject or return null.
const fixture = useGroupApiFixture();

beforeEach(() => {
  jest.spyOn(console, 'error').mockImplementation(() => undefined);
});

function bill() {
  return {
    description: 'New dinner', amount: '12.00', paidBy: fixture.ownerId, splitMode: 'equal',
    shares: [{ userId: fixture.ownerId }, { userId: fixture.memberId }], requestId: 'new-expense-api-request',
  };
}
const editUrl = () => `/api/groups/${fixture.groupId}/expenses/${fixture.expenseId}`;

// Interface GET /api/groups
describe('Mocked: GET /api/groups', () => {
  // Input: valid session; mock: the group list query rejects
  // Expected status code: 500
  // Expected behavior: a failure is not reported as an empty list
  // Expected output: { error: "Something went wrong. Please try again." } without a groups field
  test('Reports a database failure instead of an empty list', async () => {
    const query = GroupModel.find();
    jest.spyOn(query, 'exec').mockRejectedValueOnce(databaseFailure());
    const find = jest.spyOn(GroupModel, 'find').mockReturnValueOnce(query);
    const response = await request(fixture.app).get('/api/groups').set('Authorization', fixture.ownerAuth);
    expectDatabaseError(response);
    expect(find).toHaveBeenCalledTimes(1);
    expect(response.body).not.toHaveProperty('groups');
  });
});

// Interface GET /api/groups/:groupId/summary
describe('Mocked: GET /api/groups/:groupId/summary', () => {

  // Input: MongoDB returns an empty facet result or an unrepresentable total.
  // Expected status code: 200 or 422.
  // Expected behavior: provide empty collections for an empty group and reject unsafe totals.
  // Expected output: a zero summary or a range diagnostic.
  test.each([false, true])('validates the aggregate result; overflow=%s', async overflow => {
    const query = ExpenseModel.aggregate();
    jest.spyOn(query, 'exec').mockResolvedValueOnce(overflow ? [{ totals: [{ totalCents: 2 ** 53 }] }] : []);
    jest.spyOn(ExpenseModel, 'aggregate').mockReturnValueOnce(query);
    const response = await request(fixture.app).get(`/api/groups/${fixture.groupId}/summary`).set('Authorization', fixture.ownerAuth);
    expect(response.status).toBe(overflow ? 422 : 200);
    if (overflow) expect(response.body.error).toContain('integer range');
    else expect(response.body).toMatchObject({ totalCents: 0, memberShares: [], expenses: [] });
  });

  // Input: a group member; mock: the membership lookup rejects
  // Expected status code: 500
  // Expected behavior: no aggregation is attempted and no summary is disclosed
  // Expected output: { error: "Something went wrong. Please try again." }
  test('Does not read expenses when membership cannot be established', async () => {
    const query = GroupModel.findOne();
    jest.spyOn(query, 'exec').mockRejectedValueOnce(databaseFailure());
    jest.spyOn(GroupModel, 'findOne').mockReturnValueOnce(query);
    const aggregate = jest.spyOn(ExpenseModel, 'aggregate');
    const response = await request(fixture.app).get(`/api/groups/${fixture.groupId}/summary`).set('Authorization', fixture.ownerAuth);
    expectDatabaseError(response);
    expect(aggregate).not.toHaveBeenCalled();
    expect(response.body).not.toHaveProperty('expenses');
  });

  // Input: a group member; mock: the totals aggregation rejects
  // Expected status code: 500
  // Expected behavior: zero totals are never substituted; the stored bill is unchanged
  // Expected output: { error: "Something went wrong. Please try again." } without totalCents
  test('Does not substitute zero totals for a failed aggregation', async () => {
    const query = ExpenseModel.aggregate();
    jest.spyOn(query, 'exec').mockRejectedValueOnce(databaseFailure());
    const aggregate = jest.spyOn(ExpenseModel, 'aggregate').mockReturnValueOnce(query);
    const response = await request(fixture.app).get(`/api/groups/${fixture.groupId}/summary`).set('Authorization', fixture.memberAuth);
    expectDatabaseError(response);
    expect(aggregate).toHaveBeenCalledTimes(1);
    expect(response.body).not.toHaveProperty('totalCents');
    expect((await ExpenseModel.findById(fixture.expenseId))?.amountCents).toBe(1000);
  });

  // Input: malformed or operator-shaped group IDs; mock: a spy on the group lookup
  // Expected status code: 404
  // Expected behavior: invalid IDs are rejected without any database query
  // Expected output: { error: "Group not found." }
  test.each(['invalid', encodeURIComponent('{"$ne":null}'), '000000000000000000000000x'])('Rejects group ID %s without a query', async id => {
    const query = jest.spyOn(GroupModel, 'findOne');
    const response = await request(fixture.app).get(`/api/groups/${id}/summary`).set('Authorization', fixture.ownerAuth);
    expect(response.status).toBe(404);
    expect(query).not.toHaveBeenCalled();
  });
});

// Interface POST /api/groups/:groupId/expenses
describe('Mocked: POST /api/groups/:groupId/expenses', () => {
  // Input: valid equal-split bill submitted twice; mock: the first insert rejects
  // Expected status code: 500, then 201
  // Expected behavior: the failed attempt saves nothing; the retry saves one bill
  // Expected output: { expense } with amountCents 1200 split 600/600
  test('Persists one bill when a failed save is retried', async () => {
    const create = jest.spyOn(ExpenseModel, 'create').mockRejectedValueOnce(databaseFailure());
    const submit = (): request.Test => request(fixture.app).post(`/api/groups/${fixture.groupId}/expenses`).set('Authorization', fixture.ownerAuth).send(bill());
    expectDatabaseError(await submit());
    expect(await ExpenseModel.countDocuments()).toBe(1);
    const retry = await submit();
    expect(retry.status).toBe(201);
    expect(retry.body.expense).toMatchObject({ amountCents: 1200, currency: 'CAD', createdBy: fixture.ownerId });
    expect(retry.body.expense.shares).toEqual(expect.arrayContaining([
      { userId: fixture.ownerId, amountCents: 600 }, { userId: fixture.memberId, amountCents: 600 },
    ]));
    expect(create).toHaveBeenCalledTimes(2);
    expect(await ExpenseModel.countDocuments()).toBe(2);
  });

  // Input: valid bill; mock: the insert reports a duplicate key but the replay lookup finds nothing
  // Expected status code: 500
  // Expected behavior: no successful replay is invented and nothing is saved
  // Expected output: { error: "Something went wrong. Please try again." } without an expense
  test('Does not acknowledge a duplicate without a stored result', async () => {
    jest.spyOn(ExpenseModel, 'create').mockRejectedValueOnce(Object.assign(databaseFailure(), { code: 11000 }));
    const response = await request(fixture.app).post(`/api/groups/${fixture.groupId}/expenses`).set('Authorization', fixture.ownerAuth).send(bill());
    expectDatabaseError(response);
    expect(response.body).not.toHaveProperty('expense');
    expect(await ExpenseModel.countDocuments()).toBe(1);
  });

  // Input: custom split that does not add up; mock: the insert would reject if reached
  // Expected status code: 400
  // Expected behavior: validation happens before any write
  // Expected output: error mentioning "add up exactly"
  test('Validates allocations before attempting a write', async () => {
    const create = jest.spyOn(ExpenseModel, 'create').mockRejectedValueOnce(databaseFailure());
    const response = await request(fixture.app).post(`/api/groups/${fixture.groupId}/expenses`).set('Authorization', fixture.ownerAuth)
      .send({ ...bill(), splitMode: 'custom', shares: [{ userId: fixture.ownerId, amount: '11.99' }] });
    expect(response.status).toBe(400);
    expect(response.body.error).toContain('add up exactly');
    expect(create).not.toHaveBeenCalled();
    expect(await ExpenseModel.countDocuments()).toBe(1);
  });
});

// Interface PUT /api/groups/:groupId/expenses/:expenseId
describe('Mocked: PUT /api/groups/:groupId/expenses/:expenseId', () => {
  // Input: owner edits the stored bill at revision 0; mock: the conditional write rejects
  // Expected status code: 500
  // Expected behavior: amount, shares, creator, revision and timestamps are unchanged
  // Expected output: { error: "Something went wrong. Please try again." }
  test('Preserves the entire expense when an edit fails', async () => {
    const before = await ExpenseModel.findById(fixture.expenseId).lean();
    const update = jest.spyOn(ExpenseModel, 'findOneAndUpdate').mockRejectedValueOnce(databaseFailure());
    const response = await request(fixture.app).put(editUrl()).set('Authorization', fixture.ownerAuth).send({ ...bill(), revision: 0 });
    expectDatabaseError(response);
    expect(update).toHaveBeenCalledTimes(1);
    expect(await ExpenseModel.findById(fixture.expenseId).lean()).toEqual(before);
  });

  // Input: authorized edit at revision 0; mock: the conditional write matches no current revision
  // Expected status code: 409
  // Expected behavior: a concurrent change is reported instead of being overwritten
  // Expected output: error mentioning "changed since you opened" without an expense
  test('Reports a concurrent modification instead of overwriting it', async () => {
    jest.spyOn(ExpenseModel, 'findOneAndUpdate').mockResolvedValueOnce(null);
    const response = await request(fixture.app).put(editUrl()).set('Authorization', fixture.ownerAuth).send({ ...bill(), revision: 0 });
    expect(response.status).toBe(409);
    expect(response.body.error).toContain('changed since you opened');
    expect(response.body).not.toHaveProperty('expense');
    const stored = await ExpenseModel.findById(fixture.expenseId);
    expect(stored?.amountCents).toBe(1000);
    expect(stored?.__v).toBe(0);
  });

  // Input: a member edits the owner's bill; mock: the write would reject if reached
  // Expected status code: 403
  // Expected behavior: permission is checked before any write
  // Expected output: error mentioning "creator or group owner"
  test('Checks edit permission before attempting a write', async () => {
    const update = jest.spyOn(ExpenseModel, 'findOneAndUpdate').mockRejectedValueOnce(databaseFailure());
    const response = await request(fixture.app).put(editUrl()).set('Authorization', fixture.memberAuth).send({ ...bill(), revision: 0 });
    expect(response.status).toBe(403);
    expect(response.body.error).toContain('creator or group owner');
    expect(update).not.toHaveBeenCalled();
    expect((await ExpenseModel.findById(fixture.expenseId))?.amountCents).toBe(1000);
  });
});

// Interface DELETE /api/groups/:groupId/expenses/:expenseId
describe('Mocked: DELETE /api/groups/:groupId/expenses/:expenseId', () => {
  // Input: owner deletes at revision 0; mock: the delete write rejects
  // Expected status code: 500
  // Expected behavior: the bill remains and deletion is not reported
  // Expected output: { error: "Something went wrong. Please try again." }
  test('Keeps the expense when deletion fails', async () => {
    jest.spyOn(ExpenseModel, 'deleteOne').mockRejectedValueOnce(databaseFailure());
    const response = await request(fixture.app).delete(`${editUrl()}?revision=0`).set('Authorization', fixture.ownerAuth);
    expectDatabaseError(response);
    expect(await ExpenseModel.findById(fixture.expenseId)).not.toBeNull();
  });

  // Input: owner deletes at revision 0; mock: the conditional delete removes nothing (changed concurrently)
  // Expected status code: 409
  // Expected behavior: a concurrent change is reported instead of a silent success
  // Expected output: error mentioning "changed"
  test('Reports a concurrent change instead of a deletion', async () => {
    jest.spyOn(ExpenseModel, 'deleteOne').mockResolvedValueOnce({ acknowledged: true, deletedCount: 0 });
    const response = await request(fixture.app).delete(`${editUrl()}?revision=0`).set('Authorization', fixture.ownerAuth);
    expect(response.status).toBe(409);
    expect(response.body.error).toContain('changed');
    expect(await ExpenseModel.findById(fixture.expenseId)).not.toBeNull();
  });

  // Input: owner deletes at revision 0; mock: the expense lookup rejects
  // Expected status code: 500
  // Expected behavior: no delete is attempted
  // Expected output: { error: "Something went wrong. Please try again." }
  test('Does not delete when the expense cannot be read', async () => {
    const query = ExpenseModel.findOne();
    jest.spyOn(query, 'exec').mockRejectedValueOnce(databaseFailure());
    jest.spyOn(ExpenseModel, 'findOne').mockReturnValueOnce(query);
    const remove = jest.spyOn(ExpenseModel, 'deleteOne');
    expectDatabaseError(await request(fixture.app).delete(`${editUrl()}?revision=0`).set('Authorization', fixture.ownerAuth));
    expect(remove).not.toHaveBeenCalled();
  });
});
