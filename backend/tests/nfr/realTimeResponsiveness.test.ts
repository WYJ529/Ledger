import { required } from '../helpers/required';
import { performance } from 'node:perf_hooks';
import request from 'supertest';
import type { Socket } from 'socket.io-client';
import { GroupModel } from '../../src/groupStore';
import { startSession } from '../../src/session';
import { mongoSessionStore, mongoUserStore } from '../../src/userStore';
import { harness, subscribed, type TestAccount, useLiveHarness } from '../helpers/liveHarness';

// NFR-2 Real-Time Responsiveness, measured at the backend's limits: 100 group members and 200 item units.
// Real HTTP, Socket.IO and MongoDB on one machine; nothing is mocked. Mobile network delay is not included.
useLiveHarness('nfr-real-time');

const MEMBERS = 100;
const UNITS = 200;
const RELEASES = 100;
const DELIVERY_TARGET_MS = 1000;
// The app polls every 3 seconds after the network returns, leaving 2 seconds of the 5-second recovery target for the request.
const SNAPSHOT_BUDGET_MS = 2000;

interface GroupUpdate { event: { operationKey?: string; actorId: string }; session: { version: number } }

let members: TestAccount[] = [];
let sockets: Socket[] = [];
let splitId = '';

function percentile(values: number[], fraction: number): number {
  const sorted = [...values].sort((a, b) => a - b);
  return sorted[Math.min(sorted.length - 1, Math.ceil(fraction * sorted.length) - 1)] ?? Number.POSITIVE_INFINITY;
}

function summary(name: string, values: number[]) {
  const round = (value: number) => Math.round(value * 10) / 10;
  return `${name}: n=${values.length} median=${round(percentile(values, 0.5))}ms p95=${round(percentile(values, 0.95))}ms max=${round(Math.max(...values))}ms`;
}

async function fillGroup() {
  const extra = await Promise.all(Array.from({ length: MEMBERS - 2 }, async (_, index) => {
    const user = await mongoUserStore.create({ displayName: `Member ${index + 3}`, email: `member${index + 3}@example.com` });
    return { id: user.id, token: (await startSession(mongoSessionStore, user)).token };
  }));
  members = [harness.owner, harness.member, ...extra];
  await GroupModel.updateOne({ _id: harness.groupId }, { $set: { memberIds: members.map(member => member.id) } });
}

async function startLargeSplit() {
  const response = await request(harness.server).post(`/api/groups/${harness.groupId}/split-sessions`)
    .set('Authorization', `Bearer ${harness.owner.token}`).send({
      reviewed: true, requestId: 'nfr-large-split-0001', description: 'Banquet', amountCents: UNITS * 100, paidBy: harness.owner.id,
      units: Array.from({ length: UNITS }, (_, index) => ({ unitId: `dish-${index}`, description: `Dish ${index}`, amountCents: 100 })),
    });
  expect(response.status).toBe(201);
  return response.body.session.id as string;
}

beforeEach(async () => {
  await fillGroup();
  splitId = await startLargeSplit();
  sockets = [];
  for (let start = 0; start < MEMBERS; start += 20) {
    const batch = await Promise.all(members.slice(start, start + 20).map(member => subscribed(member)));
    sockets = sockets.concat(batch);
  }
}, 120000);

/** Sends one claim or release and returns the time it was sent. */
async function change(step: number): Promise<[string, number]> {
  const releasing = step >= UNITS;
  const unit = releasing ? step - UNITS : step;
  const actor = required(members[unit % MEMBERS]);
  const operationKey = `nfr-step-${String(step).padStart(8, '0')}`;
  const sentAt = performance.now();
  const response = await request(harness.server).post(`/api/split-sessions/${splitId}/claims`)
    .set('Authorization', `Bearer ${actor.token}`)
    .send({ unitId: `dish-${unit}`, action: releasing ? 'unclaim' : 'claim', version: step, operationKey });
  expect({ step, status: response.status }).toEqual({ step, status: 200 });
  return [operationKey, sentAt];
}

describe('NFR-2 Real-Time Responsiveness', () => {
  // Input: 100 subscribed members; members claim all 200 units and then release 100 of them, one accepted change at a time
  // Expected behavior: every other member receives every change over /dutch
  // Expected output: 300 x 99 = 29,700 deliveries, and at least 95% arrive within 1 second of the change being sent
  test('95% of claims and releases reach the other members within 1 second at full load', async () => {
    const received = new Map<string, number[]>();
    sockets.forEach((socket, index) => {
      const member = required(members.at(index));
      socket.on('onGroupUpdated', (update: GroupUpdate) => {
        if (update.event.actorId === member.id || !update.event.operationKey) return;
        const times = received.get(update.event.operationKey) ?? [];
        times.push(performance.now());
        received.set(update.event.operationKey, times);
      });
    });

    const sent = new Map<string, number>();
    for (let step = 0; step < UNITS + RELEASES; step += 1) {
      const [operationKey, sentAt] = await change(step);
      sent.set(operationKey, sentAt);
    }
    const expected = (UNITS + RELEASES) * (MEMBERS - 1);
    const deadline = performance.now() + 10000;
    const deliveries = () => [...received.values()].reduce((total, times) => total + times.length, 0);
    while (deliveries() < expected && performance.now() < deadline) await new Promise(resolve => setTimeout(resolve, 50));

    const latencies = [...received].flatMap(([key, times]) => times.map(time => time - required(sent.get(key))));
    process.stdout.write(summary('NFR-2 delivery latency', latencies) + '\n');
    expect(latencies).toHaveLength(expected);
    expect(percentile(latencies, 0.95)).toBeLessThanOrEqual(DELIVERY_TARGET_MS);
  }, 180000);

  // Input: after 200 claims, all 100 members request the group snapshot at the same moment, as after a shared network drop
  // Expected status code: 200 for every request
  // Expected behavior: each snapshot shows the latest version of the split session
  // Expected output: at least 95% of snapshots return within 2 seconds, so the app's 3-second poll meets the 5-second recovery target
  test('Every member recovers the current state within the recovery budget at full load', async () => {
    for (let step = 0; step < UNITS; step += 1) await change(step);
    const timings = await Promise.all(members.map(async member => {
      const startedAt = performance.now();
      const response = await request(harness.server).get(`/api/groups/${harness.groupId}/snapshot`)
        .set('Authorization', `Bearer ${member.token}`);
      const elapsed = performance.now() - startedAt;
      expect(response.status).toBe(200);
      expect(response.body.splitSessions[0].version).toBe(UNITS);
      return elapsed;
    }));
    process.stdout.write(summary('NFR-2 snapshot recovery', timings) + '\n');
    expect(percentile(timings, 0.95)).toBeLessThanOrEqual(SNAPSHOT_BUDGET_MS);
  }, 180000);
});
