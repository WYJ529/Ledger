import { EventEmitter } from 'node:events';

export type GroupMembershipChange = { groupId: string; type: 'joined' | 'deleted' };

/** Invalidate clients' rosters after persistence; REST remains the source of truth. */
export const groupMembershipEvents = new EventEmitter();

export function publishMembershipChange(change: GroupMembershipChange): void {
  try { groupMembershipEvents.emit('changed', change); }
  catch {
    // A transport failure must not turn a committed join into a failed HTTP response.
  }
}
