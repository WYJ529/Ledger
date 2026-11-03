import request from 'supertest';
import { ExpenseModel, GroupModel } from '../../src/groupStore';
import { useGroupApiFixture } from '../helpers/groupApiFixture';

const fixture = useGroupApiFixture();

test('query operators cannot be used as group or expense submission IDs', async () => {
  for (const requestId of [{ $ne: null }, { $gt: '' }, ['fixture-group-request']]) {
    const group = await request(fixture.app).post('/api/groups').set('Authorization', fixture.ownerAuth)
      .send({ name: 'Injected group', currency: 'CAD', requestId });
    expect(group.status).toBe(400);
    const expense = await request(fixture.app).post(`/api/groups/${fixture.groupId}/expenses`)
      .set('Authorization', fixture.ownerAuth).send({ description: 'Injected expense', amount: '10.00',
        paidBy: fixture.ownerId, splitMode: 'equal', shares: [{ userId: fixture.ownerId }], requestId });
    expect(expense.status).toBe(400);
  }
  expect(await GroupModel.countDocuments()).toBe(1);
  expect(await ExpenseModel.countDocuments()).toBe(1);
});

test('operator-shaped identifiers cannot select another group or expense', async () => {
  const operator = encodeURIComponent('{"$ne":null}');
  const group = await request(fixture.app).get(`/api/groups/${operator}/summary`).set('Authorization', fixture.ownerAuth);
  expect(group.status).toBe(404);
  const expense = await request(fixture.app).put(`/api/groups/${fixture.groupId}/expenses/${operator}`)
    .set('Authorization', fixture.ownerAuth).send({});
  expect(expense.status).toBe(404);
  const outsider = await request(fixture.app).get(`/api/groups/${fixture.groupId}/summary`).set('Authorization', fixture.inviteeAuth);
  expect(outsider.status).toBe(404);
});

test('malicious invitation tokens never become HTML attributes', async () => {
  const token = encodeURIComponent('\"><img src=x onerror=alert(1)>');
  const page = await request(fixture.app).get(`/invite/${token}`);
  expect(page.status).toBe(404);
  expect(page.text).not.toContain('<img');
  expect(page.text).not.toContain('href=');
  expect(page.headers['content-security-policy']).toContain("default-src 'none'");
});
