import type { Express } from 'express';
import request from 'supertest';

export function registerAccount(app: Express, account: { username: string; password: string }) {
  return request(app).post('/api/auth/register').send({ ...account, confirmPassword: account.password });
}
