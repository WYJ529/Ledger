import { randomBytes } from 'node:crypto';
import { MongoMemoryServer } from 'mongodb-memory-server';
import type { Response } from 'supertest';
import { createApp } from '../../src/app';
import { ExpenseModel, GroupInvitationModel, GroupModel } from '../../src/groupStore';
import { hashToken, startSession } from '../../src/session';
import {
  connectDatabase, disconnectDatabase, mongoSessionStore, mongoUserStore, SessionModel, UserModel,
} from '../../src/userStore';

/** Each suite gets its own local mongod. No development or cloud database is used. */
export function useGroupApiFixture() {
  let mongo: MongoMemoryServer | undefined;
  let databaseConnected = false;
  const originalPublicUrl = process.env.PUBLIC_APP_URL;
  const fixture = {
    app: createApp(), groupId: '', expenseId: '', invitationToken: '',
    ownerId: '', memberId: '', inviteeId: '', ownerAuth: '', memberAuth: '', inviteeAuth: '',
  };

  beforeAll(async () => {
    mongo = await MongoMemoryServer.create();
    await connectDatabase(mongo.getUri('group-api-tests'));
    databaseConnected = true;
  }, 120000);

  beforeEach(async () => {
    delete process.env.PUBLIC_APP_URL;
    const accounts = await UserModel.insertMany([
      { email: 'owner@example.com', displayName: 'Owner' },
      { email: 'member@example.com', displayName: 'Member' },
      { email: 'invitee@example.com', displayName: 'Invitee' },
    ]);
    const [owner, member, invitee] = accounts;
    if (!owner || !member || !invitee) throw new Error('Missing test accounts.');
    fixture.ownerId = owner.id;
    fixture.memberId = member.id;
    fixture.inviteeId = invitee.id;
    const authorizations = await Promise.all(accounts.map(async account => {
      const user = await mongoUserStore.findById(account.id);
      if (!user) throw new Error('Missing persisted test account.');
      return `Bearer ${(await startSession(mongoSessionStore, user)).token}`;
    }));
    const [ownerAuth, memberAuth, inviteeAuth] = authorizations;
    if (!ownerAuth || !memberAuth || !inviteeAuth) throw new Error('Missing test sessions.');
    fixture.ownerAuth = ownerAuth;
    fixture.memberAuth = memberAuth;
    fixture.inviteeAuth = inviteeAuth;
    const group = await GroupModel.create({
      name: 'API test group', description: 'Isolated test data', currency: 'CAD',
      ownerId: owner._id, memberIds: [owner._id, member._id], createRequestId: 'fixture-group-request',
    });
    fixture.groupId = group.id;
    fixture.invitationToken = randomBytes(32).toString('base64url');
    await GroupInvitationModel.create({
      groupId: group._id, inviterId: owner._id, tokenHash: hashToken(fixture.invitationToken),
      expiresAt: new Date(Date.now() + 86400000),
    });
    const expense = await ExpenseModel.create({
      groupId: group._id, description: 'Original dinner', amountCents: 1000, currency: 'CAD',
      paidBy: owner._id, createdBy: owner._id, splitMode: 'equal', requestId: 'fixture-expense-request',
      shares: [{ userId: owner._id, amountCents: 500 }, { userId: member._id, amountCents: 500 }],
    });
    fixture.expenseId = expense.id;
  });

  afterEach(async () => {
    // Restore spies before querying actual storage for cleanup.
    jest.restoreAllMocks();
    if (databaseConnected) {
      await Promise.all([
        ExpenseModel.deleteMany({}), GroupInvitationModel.deleteMany({}), GroupModel.deleteMany({}),
        SessionModel.deleteMany({}), UserModel.deleteMany({}),
      ]);
    }
    if (originalPublicUrl === undefined) delete process.env.PUBLIC_APP_URL;
    else process.env.PUBLIC_APP_URL = originalPublicUrl;
  });

  afterAll(async () => {
    await disconnectDatabase();
    await mongo?.stop();
  }, 30000);

  return fixture;
}

/** Assert the public error contract rather than leaking database diagnostics to clients. */
export function expectDatabaseError(response: Response) {
  expect(response.status).toBe(500);
  expect(response.body).toEqual({ error: 'Something went wrong. Please try again.' });
}

export function databaseFailure() {
  return new Error('Injected database failure: private connection details');
}
