import { required, requiredString } from '../helpers/required';
import request from 'supertest';
import { DuplicateAccountError, mongoUserStore, UserModel } from '../../src/userStore';
import { hashToken } from '../../src/session';
import { registerAccount } from '../helpers/registration';
import { patAccount as account, useAccountDatabase } from '../helpers/accountDatabase';

// Store-level checks for the MongoDB user store. These call store functions directly, not an HTTP interface.
const db = useAccountDatabase('user-store-test');

describe('mongoUserStore.linkGoogle', () => {
  test('links atomically, rejects stale credentials, and keeps the unique Google identity', async () => {
    const first = await registerAccount(db.app, account);
    const other = await registerAccount(db.app, { ...account, username: 'another' });
    const user = required(await mongoUserStore.findById(requiredString(first.body.user.id)));
    const results = await Promise.all(['google-one', 'google-two'].map(googleId => mongoUserStore.linkGoogle(user.id, googleId, required(user.passwordHash))));
    expect(results.filter(Boolean)).toHaveLength(1);
    const linked = required(await mongoUserStore.findById(user.id));
    expect(linked.passwordHash).toBe(user.passwordHash);
    expect(await mongoUserStore.linkGoogle(user.id, required(linked.googleId), 'stale-password-hash')).toBeNull();
    const otherUser = required(await mongoUserStore.findById(requiredString(other.body.user.id)));
    await expect(mongoUserStore.linkGoogle(otherUser.id, required(linked.googleId), required(otherUser.passwordHash))).rejects.toBeInstanceOf(DuplicateAccountError);
    expect((await mongoUserStore.findById(otherUser.id))?.googleId).toBeUndefined();
    expect((await request(db.app).get('/api/users/me').set('Authorization', `Bearer ${first.body.token}`)).status).toBe(200);
  });
});

describe('mongoUserStore.update', () => {
  test('a duplicate-key failure rolls back credential changes and session invalidation together', async () => {
    const first = await registerAccount(db.app, account);
    const other = await request(db.app).post('/api/auth/login').send(account);
    await UserModel.create({ username: 'taken', email: 'taken@example.com', displayName: 'Taken' });
    const userId = first.body.user.id as string;
    const before = await mongoUserStore.findById(userId);
    await expect(mongoUserStore.update(userId, { email: 'taken@example.com', displayName: 'Should not save' }, hashToken(requiredString(first.body.token))))
      .rejects.toBeInstanceOf(DuplicateAccountError);
    expect(await mongoUserStore.findById(userId)).toEqual(before);
    for (const token of [first.body.token, other.body.token]) {
      expect((await request(db.app).get('/api/users/me').set('Authorization', `Bearer ${token}`)).status).toBe(200);
    }
  });
});
