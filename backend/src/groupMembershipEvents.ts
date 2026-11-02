import { EventEmitter } from 'node:events';

export type GroupMembershipChange = {
  groupId: string; type: 'joined' | 'left' | 'removed' | 'deleted';
  /** Internal delivery hint: never included in a client notification. */
  affectedUserId?: string;
};

/** Invalidate clients' rosters after persistence; REST remains the source of truth. */
export const groupMembershipEvents = new EventEmitter();

export function publishMembershipChange(change: GroupMembershipChange): void {
  try { groupMembershipEvents.emit('changed', change); }
  catch {
    // A transport failure must not turn a committed join into a failed HTTP response.
  }
}

export type GroupChange = { groupId: string; type: 'expense-added' | 'expense-updated' | 'expense-deleted' | 'renamed' | 'split-finalized' };
export function publishGroupChange(groupId: string, type: GroupChange['type']): void {
  try { groupMembershipEvents.emit('groupChanged', { groupId, type } satisfies GroupChange); }
  catch { /* A committed write must not fail because a transport listener failed. */ }
}
