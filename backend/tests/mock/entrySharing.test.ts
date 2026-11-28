import request from 'supertest';
import { ExpenseModel, GroupModel } from '../../src/groupStore';
import { databaseFailure, expectDatabaseError, failNextFindOne, useGroupApiFixture } from '../helpers/groupApiFixture';

// Mocked component: MongoDB group and expense model calls are replaced with jest spies that reject.
const f = useGroupApiFixture();
const input = { kind: 'expense', amount: '10.01', currency: 'CAD', date: '2026-01-15', merchant: 'Dinner', category: 'Food', notes: '' };
const sourceId = 'personal-record-00000001';
const share = (): request.Test => request(f.app).post(`/api/groups/${f.groupId}/shared-entries`).set('Authorization', f.memberAuth).send({ sourceId, input });
const mine = (): request.Test => request(f.app).get(`/api/groups/${f.groupId}/expenses/${f.expenseId}/personal-entry`).set('Authorization', f.memberAuth);
const markImported = (): request.Test => request(f.app).post(`/api/groups/${f.groupId}/expenses/${f.expenseId}/personal-entry/imported`)
  .set('Authorization', f.memberAuth).send({});
const sharedCopies = () => ExpenseModel.countDocuments({ 'personalEntry.id': sourceId });

beforeEach(() => { jest.spyOn(console, 'error').mockImplementation(() => undefined); });


// Interface POST /api/groups/:groupId/shared-entries
describe('Mocked: POST /api/groups/:groupId/shared-entries', () => {

  // Input: the insert reports a duplicate but the conflicting document has already disappeared.
  // Expected status code: 500.
  // Expected behavior: fail the request without pretending it was replayed.
  // Expected output: generic error and no shared copy.
  test('reports an unrecoverable duplicate', async () => {
    jest.spyOn(ExpenseModel, 'create').mockRejectedValueOnce(Object.assign(databaseFailure(), { code: 11000 }));
    expectDatabaseError(await share());
    expect(await sharedCopies()).toBe(0);
  });

  // Input: valid personal expense shared twice; mock: the first insert rejects
  // Expected status code: 500, then 201
  // Expected behavior: the failure saves nothing; the retry saves one copy
  // Expected output: { expense, alreadyShared: false } on retry
  test('Saves one copy when a failed share is retried', async () => {
    jest.spyOn(ExpenseModel, 'create').mockRejectedValueOnce(databaseFailure());
    expectDatabaseError(await share());
    expect(await sharedCopies()).toBe(0);
    const retry = await share();
    expect(retry.status).toBe(201);
    expect(retry.body.alreadyShared).toBe(false);
    expect(await sharedCopies()).toBe(1);
  });

  // Input: valid share; mock: the insert stores the copy and then reports a duplicate key, as a concurrent retry would
  // Expected status code: 200
  // Expected behavior: the stored copy is replayed instead of an error or a second copy
  // Expected output: { expense, alreadyShared: true }
  test('Replays the stored copy after a duplicate-key race', async () => {
    const realCreate = ExpenseModel.create.bind(ExpenseModel);
    jest.spyOn(ExpenseModel, 'create').mockImplementationOnce((async (values: object) => {
      await realCreate(values);
      throw Object.assign(databaseFailure(), { code: 11000 });
    }) as never);
    const response = await share();
    expect(response.status).toBe(200);
    expect(response.body.alreadyShared).toBe(true);
    expect(await sharedCopies()).toBe(1);
  });

  // Input: valid share; mock: the group membership lookup rejects
  // Expected status code: 500
  // Expected behavior: nothing is shared
  // Expected output: { error: "Something went wrong. Please try again." }
  test('Shares nothing when membership cannot be read', async () => {
    failNextFindOne(GroupModel);
    expectDatabaseError(await share());
    expect(await sharedCopies()).toBe(0);
  });
});

// Interface GET /api/groups/:groupId/expenses/:expenseId/personal-entry
describe('Mocked: GET /api/groups/:groupId/expenses/:expenseId/personal-entry', () => {
  // Input: a member with a share requests their entry; mock: the expense lookup rejects
  // Expected status code: 500
  // Expected behavior: a failure is not reported as a missing expense
  // Expected output: { error: "Something went wrong. Please try again." } without an input field
  test('Reports a failed expense lookup', async () => {
    failNextFindOne(ExpenseModel);
    const response = await mine();
    expectDatabaseError(response);
    expect(response.body).not.toHaveProperty('input');
  });

  // Input: a member requests their entry; mock: the group membership lookup rejects
  // Expected status code: 500
  // Expected behavior: no expense data is read
  // Expected output: { error: "Something went wrong. Please try again." }
  test('Reads no expense when membership cannot be read', async () => {
    failNextFindOne(GroupModel);
    const lookup = jest.spyOn(ExpenseModel, 'findOne');
    expectDatabaseError(await mine());
    expect(lookup).not.toHaveBeenCalled();
  });
});

// Interface POST /api/groups/:groupId/expenses/:expenseId/personal-entry/imported
describe('Mocked: POST /api/groups/:groupId/expenses/:expenseId/personal-entry/imported', () => {
  // Input: a member with a positive share; mock: the importedBy write rejects
  // Expected status code: 500
  // Expected behavior: the share is not reported as imported
  // Expected output: { error: "Something went wrong. Please try again." }; importedBy stays empty
  test('Does not report an import mark that was not stored', async () => {
    jest.spyOn(ExpenseModel, 'updateOne').mockRejectedValueOnce(databaseFailure());
    const response = await markImported();
    expectDatabaseError(response);
    expect(response.body).not.toHaveProperty('importedByMe');
    expect((await ExpenseModel.findById(f.expenseId).lean())?.importedBy ?? []).toHaveLength(0);
  });

  // Input: a member with a positive share; mock: the expense lookup rejects
  // Expected status code: 500
  // Expected behavior: no write is attempted
  // Expected output: { error: "Something went wrong. Please try again." }
  test('Writes nothing when the expense cannot be read', async () => {
    failNextFindOne(ExpenseModel);
    const write = jest.spyOn(ExpenseModel, 'updateOne');
    expectDatabaseError(await markImported());
    expect(write).not.toHaveBeenCalled();
  });
});
