# Live group membership (#61)

When someone joins, leaves or is removed, existing members viewing the group receive an authenticated Socket.IO notification. Android reloads the canonical group summary in the background. The open Members dialog, group settings roster, member count, payer choices and participant choices update without pressing Refresh. Owner departure broadcasts deletion to all remaining members.

An open expense editor keeps its description, split mode, final amount, payer, selected participants, percentages, pretax amounts, individual extra tax, tip rate, submission ID and originally opened expense revision. Member-specific inputs are keyed by member ID, so a reordered roster does not move one person's amounts to another. New members are available but remain unselected until explicitly included. Creating a form after a roster update uses the updated membership.

Money inputs retain the group's currency precision, including whole-unit JPY/KRW amounts. Percentage, tip and extra-tax rates retain their separate two-decimal precision. The device regression suite includes reopening a saved JPY meal and updating its roster without changing amounts or selecting the newcomer.

## Delivery and recovery

- Reuse the authenticated `/dutch` namespace and its `subscribe`/`unsubscribe` group rooms.
- `onGroupMembershipChanged` carries only `{ groupId, type: "joined" | "left" | "removed" | "deleted" }`; it does not carry a roster, member IDs, email addresses or invitation tokens. The affected-user delivery hint remains server-internal.
- Publish only after the membership write commits. The atomic preimage identifies the one new join when identical accepts race. Repeated accepts do not publish additional successful-join notifications.
- Recheck the group membership and Ledger session before delivery. Outsiders, other groups and revoked/expired sessions cannot receive the group's updates.
- The removed/departing account receives only its own minimal access invalidation on all connected authenticated devices, then leaves the group room. Server-assigned private user rooms allow this invalidation even if a concurrent broadcast already released the group room. It cannot receive later receipt or membership data. Reconnection with a rejected group subscription also triggers a canonical access check.
- Read the authoritative REST summary after successful subscription/reconnection, screen re-entry, and join notifications. Cancelling older refreshes prevents late responses from regressing the roster. Duplicate/out-of-order notifications trigger fresh reads rather than applying stale event data.
- Keep the editor composed during background reads. A temporary read failure retains the existing draft. Reconnecting reads the current roster again.
- If a selected participant leaves, retain the draft and pause saving until the user explicitly removes former participants from the draft and reviews the recalculated shares. Do not silently redistribute their amount. A removed payer must be replaced with a current member before saving.
- Socket listeners are disposed on group/account navigation and logout, and disconnected while the activity is stopped. Deleting a group invalidates its clients and releases their room membership.
- Let's Go Dutch also refreshes its summary on membership notifications.

The live adapter is process-local, like the existing receipt-claim adapter. A multi-process deployment needs coordinated fan-out; the current deployment uses a single backend process. Group settings implement owner-only removal, member departure and owner-departure deletion; see [membership behavior](Group_Invitations.md#leaving-and-removing-members). Existing expense allocations are not live-edited or erased by departure.

## Deployment

Deploy the backend and rebuild/install the Android APK. All users must target the same backend/database. For the shared cloud deployment, preserve the local SDK configuration and use:

```properties
API_BASE_URL=https://136-67-157-106.sslip.io
APP_LINK_HOST=136-67-157-106.sslip.io
```

A local invitation does not exist in the cloud database. Deploying application code does not migrate local accounts, groups or invitations.

## Verification

Backend integration tests use real HTTP, Socket.IO clients and an isolated MongoDB. They cover delivery after persistence, concurrent/repeated acceptance, failed writes, invalid invitations, authorization isolation, session revocation, offline recovery and deletion cleanup. `GroupMembershipUiTest` exercises the actual Compose screen with deterministic notifications, including open dialogs, reordered rosters and preserved Custom/Percentage drafts.

For an end-to-end device check, first run in `backend/`:

```powershell
npm run build
node scripts/membership-test-server.cjs
```

This starts a disposable database/backend on host loopback port 3101 and stops after 15 minutes. It never uses the normal development or cloud database. With an emulator running, execute in `frontend/`:

```powershell
./gradlew.bat :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.example.cpen321application.e2e.GroupMembershipIntegrationTest -Pandroid.testInstrumentationRunnerArguments.membershipServer=http://10.0.2.2:3101
```

The test drives the group UI over a real WebSocket while separate authenticated HTTP clients accept invitations. It verifies an open member dialog updates and a later join preserves an unfinished percentage bill, payer and existing selections. The test is skipped unless `membershipServer` is explicitly provided. Stop the isolated backend with Ctrl+C when finished.

### Integration with group settings (2026-10-30)

Merged `main` at `78a7fd0` with the local settings/leave/remove implementation.
The resolution preserves atomic join notifications, inviter-membership guards,
the navy/outlined controls, all 12 currencies and member-keyed draft precision.
Leave/removal now publish after persistence; owner departure publishes deletion.
Repeated removal does not publish another successful membership change.

Verification: backend typecheck and all 433 tests in 23 suites passed; all 74
Android JVM tests passed; debug and instrumentation APK builds passed. On Pixel 9
/ Android 16, all 28 selected component UI tests passed (7 GroupSettingsUiTest,
11 GroupMembershipUiTest, 10 GroupExpensesUiTest). These cover settings removal
visibility, revocation returning to the list, draft review after removal and the
existing join/precision/draft regressions. The backend real-socket tests verify
post-commit notifications, multiple devices and exclusion from later group data.
This merge did not deploy the cloud backend or run the opt-in real-backend Android
acceptance test above.
