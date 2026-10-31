import type { Express } from 'express';
import request from 'supertest';
import type { RegistrationEmailSender } from '../../src/registrationEmail';
import { MAX_CODE_ATTEMPTS, type RegistrationVerificationStore, type VerificationRecord } from '../../src/registrationVerification';

export function memoryRegistration() {
  const records = new Map<string, VerificationRecord>();
  const codes = new Map<string, string>();
  const registrationEmail: RegistrationEmailSender = { async sendCode(email, code) { codes.set(email, code); } };
  const registrationVerifications: RegistrationVerificationStore = {
    async reserve(record, now) {
      const existing = [...records.values()].find(value => value.email === record.email);
      if (existing && existing.resendAt > now) return false;
      if (existing) records.delete(existing.verificationId);
      records.set(record.verificationId, { ...record });
      return true;
    },
    async activate(id) { const record = records.get(id); if (record) record.ready = true; },
    async invalidate(id) {
      const record = records.get(id);
      if (record) { record.ready = false; record.attempts = MAX_CODE_ATTEMPTS; }
    },
    async takeAttempt(email, id, now) {
      const record = records.get(id);
      if (!record || record.email !== email || !record.ready || record.expiresAt <= now || record.attempts >= MAX_CODE_ATTEMPTS) return null;
      record.attempts++;
      return { ...record };
    },
    async consume(id, codeHash, now) {
      const record = records.get(id);
      if (!record || record.codeHash !== codeHash || !record.ready || record.expiresAt <= now) return false;
      return records.delete(id);
    },
  };
  return { records, codes, registrationEmail, registrationVerifications };
}

export async function registrationProof(app: Express, codes: Map<string, string>, email: string) {
  const sent = await request(app).post('/api/auth/register/code').send({ email });
  expect(sent.status).toBe(202);
  const code = codes.get(email.trim().toLowerCase());
  expect(code).toMatch(/^[0-9]{6}$/);
  return { verificationId: sent.body.verificationId as string, code: code! };
}

export async function registerVerified(app: Express, codes: Map<string, string>, account: { email: string; password: string; displayName: string }) {
  const proof = await registrationProof(app, codes, account.email);
  return request(app).post('/api/auth/register').send({ ...account, ...proof });
}
