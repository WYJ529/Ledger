import { randomBytes } from 'node:crypto';
import { isSupportedCurrencyCode } from './currencies';
import { Router, type Request, type Response } from 'express';
import { Types } from 'mongoose';
import { GroupModel, GroupInvitationModel } from './groupStore';
import { hashToken, type AuthenticatedLocals } from './session';
import type { UserStore } from './userStore';
import { publishMembershipChange } from './groupMembershipEvents';

export const INVITATION_TTL_MS = 7 * 24 * 60 * 60 * 1000;
const TOKEN = /^[A-Za-z0-9_-]{43}$/;
type Group = NonNullable<Awaited<ReturnType<typeof GroupModel.findOne>>>;

export function groupJson(group: Pick<Group, '_id' | 'name' | 'currency' | 'ownerId' | 'memberIds' | 'description'>) {
  return { id: group._id.toString(), name: group.name, description: group.description ?? '', currency: group.currency,
    ownerId: group.ownerId.toString(), memberCount: group.memberIds.length };
}

function publicBase(): string | undefined {
  const raw = process.env.PUBLIC_APP_URL?.trim();
  if (!raw) return undefined;
  try {
    const url = new URL(raw);
    if (!['https:', 'http:'].includes(url.protocol) || (url.protocol === 'http:' && process.env.NODE_ENV === 'production') ||
        url.username || url.password || url.search || url.hash || !['', '/'].includes(url.pathname)) throw new Error();
    return url.origin;
  } catch { throw new Error('Configure PUBLIC_APP_URL as the reachable backend origin (HTTPS in production).'); }
}

async function createInvitation(group: Pick<Group, '_id'>, inviterId: string) {
  const base = publicBase();
  const token = randomBytes(32).toString('base64url');
  const expiresAt = new Date(Date.now() + INVITATION_TTL_MS);
  await GroupInvitationModel.create({ tokenHash: hashToken(token), groupId: group._id, inviterId, expiresAt });
  const deepLink = `ledger://invite/${token}`;
  return { link: base ? `${base}/invite/${token}` : deepLink, deepLink, expiresAt: expiresAt.toISOString() };
}

async function invitationDetails(token: unknown, users: UserStore) {
  if (typeof token !== 'string' || !TOKEN.test(token)) return null;
  const invitation = await GroupInvitationModel.findOne({ tokenHash: hashToken(token), expiresAt: { $gt: new Date() } }).lean();
  if (!invitation) return null;
  const group = await GroupModel.findOne({ _id: invitation.groupId, deletedAt: null, memberIds: invitation.inviterId }).lean();
  if (!group) return null;
  const inviter = await users.findById(invitation.inviterId.toString());
  if (!inviter) return null;
  return { invitation, group, inviter };
}

async function memberGroup(req: Request, res: Response) {
  const { user } = res.locals as AuthenticatedLocals;
  const id = req.params.groupId;
  const group = typeof id === 'string' && /^[a-f\d]{24}$/i.test(id)
    ? await GroupModel.findOne({ _id: id, memberIds: user.id, deletedAt: null }).lean() : null;
  if (!group) res.status(404).json({ error: 'Group not found.' });
  return group;
}

/** Mounted after the parent router's session middleware, before its group-ID routes. */
export function groupManagementRouter(users: UserStore): Router {
  const router = Router();

  router.post('/', async (req, res) => {
    const { user } = res.locals as AuthenticatedLocals;
    const name = typeof req.body?.name === 'string' ? req.body.name.trim() : '';
    const description = req.body?.description === undefined ? '' : req.body.description;
    const currency: unknown = req.body?.currency ?? 'CAD';
    const requestId: unknown = req.body?.requestId;
    if (!name || name.length > 60 || typeof description !== 'string' || description.trim().length > 240 ||
        !isSupportedCurrencyCode(currency) ||
        typeof requestId !== 'string' || !/^[A-Za-z0-9_-]{16,80}$/.test(requestId)) {
      res.status(400).json({ error: 'Enter a name (1-60 characters), description (up to 240), a supported currency and a valid submission ID.' }); return;
    }
    try { publicBase(); } catch (error) { res.status(503).json({ error: (error as Error).message }); return; }
    let group;
    let status = 201;
    try {
      group = await GroupModel.create({ name, description: description.trim(), currency,
        ownerId: user.id, memberIds: [user.id], createRequestId: requestId });
    } catch (error) {
      if ((error as { code?: number }).code !== 11000) throw error;
      group = await GroupModel.findOne({ ownerId: user.id, createRequestId: requestId });
      if (!group || group.deletedAt || group.name !== name || (group.description ?? '') !== description.trim() || group.currency !== currency) {
        res.status(409).json({ error: 'This group submission was already used. Refresh your groups before trying again.' }); return;
      }
      status = 200;
    }
    const invitation = await createInvitation(group, user.id);
    res.status(status).json({ group: groupJson(group), invitation });
  });

  router.get('/invitations/:token', async (req, res) => {
    const details = await invitationDetails(req.params.token, users);
    if (!details) { res.status(404).json({ error: 'This invitation has expired or is no longer available.' }); return; }
    const { user } = res.locals as AuthenticatedLocals;
    res.json({ group: groupJson(details.group), inviterName: details.inviter.displayName,
      expiresAt: details.invitation.expiresAt.toISOString(), alreadyMember: details.group.memberIds.some(id => id.toString() === user.id) });
  });

  router.post('/invitations/:token/accept', async (req, res) => {
    const details = await invitationDetails(req.params.token, users);
    if (!details) { res.status(404).json({ error: 'This invitation has expired or is no longer available.' }); return; }
    const { user } = res.locals as AuthenticatedLocals;
    // Atomic membership insertion: duplicate accepts cannot add the same member twice.
    // Existing members can still open a full group, but new joins stop at 100 members.
    const previous = await GroupModel.findOneAndUpdate({ _id: details.group._id, deletedAt: null,
      memberIds: { $eq: details.invitation.inviterId },
      $or: [{ memberIds: user.id }, { $expr: { $lt: [{ $size: '$memberIds' }, 100] } }],
    }, { $addToSet: { memberIds: user.id } }, { returnDocument: 'before' });
    if (!previous) {
      const active = await GroupModel.exists({ _id: details.group._id, deletedAt: null,
        memberIds: { $eq: details.invitation.inviterId } });
      res.status(active ? 409 : 404).json({ error: active ? 'This group already has 100 members.' : 'Group not found.' }); return;
    }
    // The atomic preimage identifies exactly one new join even when accepts race.
    const alreadyMember = previous.memberIds.some(id => id.toString() === user.id);
    if (!alreadyMember) publishMembershipChange({ groupId: previous.id, type: 'joined' });
    res.json({ group: { ...groupJson(previous), memberCount: previous.memberIds.length + (alreadyMember ? 0 : 1) } });
  });

  router.post('/:groupId/invitations', async (req, res) => {
    const group = await memberGroup(req, res);
    if (!group) return;
    const { user } = res.locals as AuthenticatedLocals;
    try { publicBase(); } catch (error) { res.status(503).json({ error: (error as Error).message }); return; }
    res.status(201).json({ invitation: await createInvitation(group, user.id) });
  });

  router.put('/:groupId/name', async (req, res) => {
    const group = await memberGroup(req, res);
    if (!group) return;
    const name = typeof req.body?.name === 'string' ? req.body.name.trim() : '';
    if (!name || name.length > 60) { res.status(400).json({ error: 'Enter a group name of 1-60 characters.' }); return; }
    const { user } = res.locals as AuthenticatedLocals;
    const updated = await GroupModel.findOneAndUpdate({ _id: group._id, deletedAt: null, memberIds: { $eq: new Types.ObjectId(user.id) } }, { $set: { name } }, { returnDocument: 'after', runValidators: true });
    if (!updated) { res.status(404).json({ error: 'Group not found.' }); return; }
    res.json({ group: groupJson(updated) });
  });

  router.delete('/:groupId', async (req, res) => {
    const group = await memberGroup(req, res);
    if (!group) return;
    const { user } = res.locals as AuthenticatedLocals;
    if (group.ownerId.toString() !== user.id) { res.status(403).json({ error: 'Only the group owner can delete this group.' }); return; }
    // The tombstone immediately blocks joins and all group access, even during concurrent requests.
    const result = await GroupModel.updateOne({ _id: group._id, deletedAt: null, ownerId: user.id }, { $set: { deletedAt: new Date() } });
    if (result.modifiedCount) publishMembershipChange({ groupId: group._id.toString(), type: 'deleted' });
    res.status(204).end();
  });

  router.post('/:groupId/leave', async (req, res) => {
    const group = await memberGroup(req, res);
    if (!group) return;
    const { user } = res.locals as AuthenticatedLocals;
    const userId = new Types.ObjectId(user.id);
    const deleted = group.ownerId.toString() === user.id;
    const updated = await GroupModel.findOneAndUpdate({ _id: group._id, deletedAt: null,
      memberIds: { $eq: userId }, ownerId: deleted ? { $eq: userId } : { $ne: userId },
    }, deleted ? { $set: { deletedAt: new Date() } } : { $pull: { memberIds: userId } }, { returnDocument: 'after' });
    if (!updated) { res.status(404).json({ error: 'Group not found.' }); return; }
    publishMembershipChange({ groupId: group._id.toString(), type: deleted ? 'deleted' : 'left', affectedUserId: user.id });
    res.json({ deleted });
  });

  router.delete('/:groupId/members/:memberId', async (req, res) => {
    const group = await memberGroup(req, res);
    if (!group) return;
    const { user } = res.locals as AuthenticatedLocals;
    if (group.ownerId.toString() !== user.id) {
      res.status(403).json({ error: 'Only the group owner can remove members.' }); return;
    }
    const id = req.params.memberId;
    if (typeof id !== 'string' || !/^[a-f\d]{24}$/i.test(id)) {
      res.status(400).json({ error: 'Choose a valid group member.' }); return;
    }
    const memberId = new Types.ObjectId(id);
    if (memberId.equals(group.ownerId)) {
      res.status(400).json({ error: 'The owner cannot be removed. Leave the group to delete it for everyone.' }); return;
    }
    // A retry is harmless even if the target has already left or been removed.
    const updated = await GroupModel.findOneAndUpdate({ _id: group._id, deletedAt: null,
      ownerId: { $eq: new Types.ObjectId(user.id) }, memberIds: { $eq: new Types.ObjectId(user.id) },
    }, { $pull: { memberIds: memberId } }, { returnDocument: 'before' });
    if (!updated) { res.status(404).json({ error: 'Group not found.' }); return; }
    if (updated.memberIds.some(existing => existing.equals(memberId))) {
      publishMembershipChange({ groupId: group._id.toString(), type: 'removed', affectedUserId: memberId.toString() });
    }
    res.status(204).end();
  });
  return router;
}

function htmlEscape(text: string): string {
  return text.replace(/[&<>"']/g, ch => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[ch]!);
}

/** Public share-page fallback. The token reveals no expenses or roster; joining still requires login and Accept. */
export function invitationWebRouter(users: UserStore): Router {
  const router = Router();
  router.get('/invite/:token', async (req, res) => {
    res.set({ 'Cache-Control': 'no-store', 'Referrer-Policy': 'no-referrer',
      'Content-Security-Policy': "default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; frame-ancestors 'none'" });
    const details = await invitationDetails(req.params.token, users);
    const message = details ? `${details.inviter.displayName} invites you to “${details.group.name}”` : 'This invitation has expired or is no longer available.';
    const action = details ? `<a href="ledger://invite/${req.params.token}">Open in Ledger</a><p>Sign in, then choose Accept or Reject in the app.</p>` : '';
    res.status(details ? 200 : 404).type('html').send(`<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>Ledger invitation</title><style>body{font:18px system-ui;background:#f4f8f7;color:#113c40;max-width:540px;margin:12vh auto;padding:24px}a{display:inline-block;padding:16px 24px;background:#004650;color:white;border-radius:16px;text-decoration:none}p{line-height:1.6}</style><h2>Ledger</h2><h1>${htmlEscape(message)}</h1>${action}<p>Ledger must be installed to open the invitation.</p></html>`);
  });
  router.get('/.well-known/assetlinks.json', (_req, res) => {
    const fingerprints = (process.env.ANDROID_SHA256_FINGERPRINTS ?? '').split(',').map(value => value.trim().toUpperCase()).filter(Boolean);
    if (!fingerprints.length || fingerprints.some(value => !/^(?:[A-F\d]{2}:){31}[A-F\d]{2}$/.test(value))) {
      res.status(503).json({ error: 'Configure ANDROID_SHA256_FINGERPRINTS before verifying Android App Links.' }); return;
    }
    res.json([{ relation: ['delegate_permission/common.handle_all_urls'], target: { namespace: 'android_app',
      package_name: 'com.example.cpen321application', sha256_cert_fingerprints: fingerprints } }]);
  });
  return router;
}
