import request from 'supertest';
import { ExpenseModel, GroupModel } from '../../src/groupStore';
import { databaseFailure, expectDatabaseError, useGroupApiFixture } from '../helpers/groupApiFixture';

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

describe('Mocked: GET /api/groups/:groupId/summary', () => {
  // Input: authorized group member. Mock: membership lookup fails.
  // Expected: 500; no financial aggregation is attempted or summary disclosed.
  test('does not read expenses when membership cannot be established', async () => {
    const query = GroupModel.findOne();
    jest.spyOn(query, 'exec').mockRejectedValueOnce(databaseFailure());
    jest.spyOn(GroupModel, 'findOne').mockReturnValueOnce(query);
    const aggregate = jest.spyOn(ExpenseModel, 'aggregate');
    const response = await request(fixture.app).get(`/api/groups/${fixture.groupId}/summary`)
      .set('Authorization', fixture.ownerAuth);
    expectDatabaseError(response);
    expect(aggregate).not.toHaveBeenCalled();
    expect(response.body).not.toHaveProperty('expenses');
  });

  // Input: authorized member. Mock: totals aggregation rejects.
  // Expected: 500 rather than false zero totals; the persisted bill remains unchanged.
  test('does not substitute zero totals for a failed aggregation', async () => {
    const query = ExpenseModel.aggregate();
    jest.spyOn(query, 'exec').mockRejectedValueOnce(databaseFailure());
    const aggregate = jest.spyOn(ExpenseModel, 'aggregate').mockReturnValueOnce(query);
    const response = await request(fixture.app).get(`/api/groups/${fixture.groupId}/summary`)
      .set('Authorization', fixture.memberAuth);
    expectDatabaseError(response);
    expect(aggregate).toHaveBeenCalledTimes(1);
    expect(response.body).not.toHaveProperty('totalCents');
    expect((await ExpenseModel.findById(fixture.expenseId))?.amountCents).toBe(1000);
  });
});

describe('Mocked: POST /api/groups/:groupId/expenses', () => {
  // Input: valid equal-split bill. Mock: first insertion fails before committing.
  // Expected: 500 with no new expense; same request can then succeed once with 201.
  test('persists one bill when a failed save is retried', async () => {
    const create = jest.spyOn(ExpenseModel, 'create').mockRejectedValueOnce(databaseFailure());
    const submit = () => request(fixture.app).post(`/api/groups/${fixture.groupId}/expenses`)
      .set('Authorization', fixture.ownerAuth).send(bill());
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

  // Input: valid bill. Mock: insert reports duplicate key but replay lookup finds no saved bill.
  // Expected: 500, no invented successful replay, and no new expense.
  test('does not acknowledge a duplicate without a persisted result', async () => {
    jest.spyOn(ExpenseModel, 'create').mockRejectedValueOnce(Object.assign(databaseFailure(), { code: 11000 }));
    const response = await request(fixture.app).post(`/api/groups/${fixture.groupId}/expenses`)
      .set('Authorization', fixture.ownerAuth).send(bill());
    expectDatabaseError(response);
    expect(response.body).not.toHaveProperty('expense');
    expect(await ExpenseModel.countDocuments()).toBe(1);
  });

  // Input: invalid custom split. Mock: expense insertion would fail if reached.
  // Expected: 400 before persistence; the failing insert is never invoked.
  test('validates allocations before attempting a database write', async () => {
    const create = jest.spyOn(ExpenseModel, 'create').mockRejectedValueOnce(databaseFailure());
    const response = await request(fixture.app).post(`/api/groups/${fixture.groupId}/expenses`)
      .set('Authorization', fixture.ownerAuth).send({
        ...bill(), splitMode: 'custom', shares: [{ userId: fixture.ownerId, amount: '11.99' }],
      });
    expect(response.status).toBe(400);
    expect(response.body.error).toContain('add up exactly');
    expect(create).not.toHaveBeenCalled();
    expect(await ExpenseModel.countDocuments()).toBe(1);
  });
});

describe('Mocked: PUT /api/groups/:groupId/expenses/:expenseId', () => {
  // Input: owner edits the stored expense at revision 0. Mock: conditional write rejects.
  // Expected: 500; amount, shares, creator, revision and timestamps remain unchanged.
  test('preserves the entire expense when an edit fails', async () => {
    const before = await ExpenseModel.findById(fixture.expenseId).lean();
    const update = jest.spyOn(ExpenseModel, 'findOneAndUpdate').mockRejectedValueOnce(databaseFailure());
    const response = await request(fixture.app).put(`/api/groups/${fixture.groupId}/expenses/${fixture.expenseId}`)
      .set('Authorization', fixture.ownerAuth).send({ ...bill(), revision: 0 });
    expectDatabaseError(response);
    expect(update).toHaveBeenCalledTimes(1);
    expect(await ExpenseModel.findById(fixture.expenseId).lean()).toEqual(before);
  });

  // Input: authorized edit at revision 0. Mock: conditional update matches no current revision.
  // Expected: 409, no successful expense returned, original financial data unchanged.
  test('reports a concurrent modification instead of overwriting it', async () => {
    jest.spyOn(ExpenseModel, 'findOneAndUpdate').mockResolvedValueOnce(null);
    const response = await request(fixture.app).put(`/api/groups/${fixture.groupId}/expenses/${fixture.expenseId}`)
      .set('Authorization', fixture.ownerAuth).send({ ...bill(), revision: 0 });
    expect(response.status).toBe(409);
    expect(response.body.error).toContain('changed since you opened');
    expect(response.body).not.toHaveProperty('expense');
    const stored = await ExpenseModel.findById(fixture.expenseId);
    expect(stored?.amountCents).toBe(1000);
    expect(stored?.__v).toBe(0);
  });

  // Input: a member edits another member's expense. Mock: update would fail if reached.
  // Expected: 403 without calling the write; original expense remains intact.
  test('checks edit ownership before attempting persistence', async () => {
    const update = jest.spyOn(ExpenseModel, 'findOneAndUpdate').mockRejectedValueOnce(databaseFailure());
    const response = await request(fixture.app).put(`/api/groups/${fixture.groupId}/expenses/${fixture.expenseId}`)
      .set('Authorization', fixture.memberAuth).send({ ...bill(), revision: 0 });
    expect(response.status).toBe(403);
    expect(response.body.error).toContain('creator or group owner');
    expect(update).not.toHaveBeenCalled();
    expect((await ExpenseModel.findById(fixture.expenseId))?.amountCents).toBe(1000);
  });
});
