import type { RequestHandler } from 'express';
import { Types } from 'mongoose';
import { GroupModel } from './groupStore';
import type { AuthenticatedLocals } from './session';

/** Read current membership; callers must not reuse this across requests or broadcasts. */
export async function findMemberGroup(groupId: unknown, userId: string) {
  if (typeof groupId !== 'string' || !Types.ObjectId.isValid(groupId) || !Types.ObjectId.isValid(userId)) return null;
  return GroupModel.findOne().setOptions({ sanitizeFilter: true })
    .where('_id').equals(new Types.ObjectId(groupId))
    .where('memberIds').equals(new Types.ObjectId(userId))
    .where('deletedAt').equals(null).lean();
}

export type GroupLocals = AuthenticatedLocals & { group: NonNullable<Awaited<ReturnType<typeof findMemberGroup>>> };

/** Run after session authentication; nonmembers and deleted groups share the same 404. */
export const requireGroupMember: RequestHandler = async (req, res, next) => {
  const { user } = res.locals as AuthenticatedLocals;
  const group = await findMemberGroup(req.params.groupId, user.id);
  if (!group) { res.status(404).json({ error: 'Group not found.' }); return; }
  res.locals.group = group;
  next();
};
