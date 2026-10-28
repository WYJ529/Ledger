# Feature 3 — Let's Go Dutch

Implementation notes, **21 October 2026**. This document describes the repository implementation of the Feature 3 workflow proposed in [Requirements and Design](Requirements_and_Design.md), including UC-04 (live item claims) and UC-05 (reconnect recovery). The retained CP1 design describes those flows as planned; it is a milestone record. Repository implementation and test results do not establish that the shared cloud backend or an installed APK has been updated.

## Scope

Group members can claim individual receipt item units, see committed allocation updates, recover after a disconnect, and view the resulting immutable group expense. Only the group owner can start or finalize a split session. The workflow is reached through **Group → select a group → Let’s Go Dutch**.

Receipt items are entered and reviewed manually. The existing personal-ledger receipt recognizer still extracts transaction totals; this feature does not add item OCR or reuse a personal transaction. It does not add currency conversion, repayments, net debt balances, settlement, payment transfers, or archival. Group expenses and live split sessions remain backend records separate from the installation-local personal ledger.

## Using a split session

1. Sign in, create or join a group, and open **Let’s Go Dutch** from its expense screen. All participating devices must use the updated backend and a valid Ledger session.
2. As the owner, enter a receipt description, a payer from the current group roster, and the receipt total in the group's currency. Enter each line's description, quantity and **unit price**. A quantity creates separately claimable units: two coffees at 4.50 create two 450-cent units.
3. Include any tax or tip as positive line items, or incorporate reviewed charges into positive unit prices. Every unit must be at least one cent. Zero-price and negative adjustment units are unsupported. The app does not automatically distribute tax, tip, or discounts.
4. Reconcile the sum of all unit amounts to the receipt total and explicitly check the review confirmation. The backend repeats the integer-cent reconciliation before creating a session. The UI permits 1–20 units per line; the session maximum is 200 units. Group currency is CAD, USD, EUR, or CNY, with no FX step. The total maximum is 1,000,000.00 in that currency.
5. Members choose **Claim** on available units or **Release** on units they own. Another member's unit is shown as **Taken**. A unit has at most one owner; a member cannot release another member's claim. The screen shows assigned and unassigned amounts, unit counts and current member allocations.
6. After every unit is assigned and allocated cents equal the total, the owner chooses **Finalize split**. The server posts one group expense, closes the session to claims, and returns its expense ID. The resulting expense appears in group spending and allocated-share totals.

Finalized receipt expenses cannot be changed through the ordinary expense editor, including by the owner. Their summary has `canEdit: false`, and an API edit returns 409. This preserves the relationship between receipt units and the posted allocation. There is no in-place correction, reopening, or session-cancellation workflow in this implementation.

## Live state and recovery

All financial mutations use authenticated HTTP requests. Socket.IO carries committed state through the **`/dutch` namespace**:

- The connection uses a Ledger bearer session, supplied through `auth.token` or the Authorization header. The backend verifies the user and session before connection.
- The client emits `subscribe` with `{ "groupId": "..." }`; the server rechecks session validity and active group membership before joining that group room. `unsubscribe` leaves it.
- After persisting a creation, claim, release or completed finalization, the server emits `onGroupUpdated` with the event metadata and full public session state. It rechecks group membership and session validity before delivery; revoked sessions cannot receive a later committed update.
- Android uses WebSocket transport, connects while the screen's activity is started, disconnects in the background, and reconnects with bounded 1–10 second delays. Production traffic follows the HTTPS backend as WSS.

Each split session has its **own** monotonic `sequence` and mutation `version`. Sequence 1 is the creation event; each accepted claim/release or finalization advances the sequence and version once. A group does not have a global sequence. Event types are `created`, `claim`, `unclaim`, and `finalized`.

The client ignores duplicate or older events. A missed sequence triggers recovery: pause writes, buffer new events, retrieve an authorized snapshot, install its per-session watermarks, then apply contiguous newer events. It asks for another snapshot if a gap remains. A stale HTTP snapshot or reply does not roll back newer known session state. The snapshot response is `{ groupId, expenses, splitSessions }`; it is not a group-wide transaction or a settlement/balance snapshot. There is no separate event-replay endpoint.

The screen labels disconnected or recovering data and disables starting, claiming, releasing, and finalizing until live subscription and state recovery are ready. REST fallback refreshes every 3 seconds while disconnected or resolving a pending result. While live and current, a 15-second snapshot audit catches a missing last event or authorization change. Successful REST polling alone leaves the screen read-only until live subscription returns.

Uncertain requests retain their original key and exact payload in account-and-group-scoped private Android pending storage before transmission. Recovery looks up the operation; an accepted result clears it. **Retry the same action** resends the saved payload and key rather than creating a second financial action. Start-session retries likewise retain the original creation request ID. Unauthorized group responses discard the inaccessible displayed state, and expired sessions require sign-in.

## Financial consistency and persistence

The backend uses the `SplitSession` model alongside existing `Group` and `Expense` models. Startup initializes the indexes before accepting requests. Existing records need no manual migration; the `Expense.splitSessionId` link is optional for older manual expenses.

### Creation and claims

- Creation requires `reviewed: true`, a valid `requestId`, 1–200 uniquely identified positive integer-cent units, an exact total, a current-member payer, and the group owner. The creator and currency come from trusted session/group data.
- The unique `(groupId, createdBy, createRequestId)` index makes identical creation retries return the existing session without a new event. Reusing the same request ID with changed receipt content returns 409.
- A claim/release supplies the expected version and an operation key. One conditional single-document update changes the unit owner and version, and appends the operation result and event. A competing claim or stale version returns 409 with authoritative session state; releasing another member's unit returns 403.
- Operation keys are unique across session documents. An identical retry returns the recorded result, and changing a reused key's payload is rejected. Operation lookup returns only the authenticated actor's own result and still checks current group membership.
- To bound embedded history, a session stops accepting releases after 2,000 successful claim/release mutations. A further release returns 409 explaining that existing allocations can no longer be released. Identical retries still return their saved result; members may claim the remaining units and the owner may finalize. With at most 200 units, this permits at most 2,201 operation records and 2,202 events including creation. The version condition makes concurrent boundary requests obey the same limit.

### Finalization and interrupted posting

This implementation supports standalone MongoDB and does **not** require multi-document transactions. Its sequence is deliberately recoverable:

1. Validate the owner, expected version, complete unit assignment, integer-cent reconciliation, and current membership of the payer and unit owners.
2. Atomically persist an owner-authorized finalization intent and move `open → finalizing`. This state blocks further claims. The original operation key, actor and payload are stored on the session.
3. Insert the shared expense with a deterministic `split-session-<sessionId>` request ID and its session link. Ordinary expense creation reserves the `split-session-` prefix. The existing expense unique index prevents duplicate posting; an existing expense can be reused after an interruption only when its financial fields and `splitSessionId` exactly match. A missing or different session link is a conflict, even if all amounts match.
4. Atomically move `finalizing → finalized`, link the expense, complete the original operation result, advance the version/sequence and append the final event. Publish only after that successful write.

A same-key finalization retry resumes the saved intent. An authorized group snapshot also completes persisted `finalizing` intents before returning fresh state, so recovery does not depend on the original device retaining its key. This handles interruption both before and after expense insertion and preserves one expense and one final event. It is a recovery fence plus idempotent posting, not an atomic transaction spanning the session and expense collections. An unavailable database can delay completion; the server does not report successful completion before the persisted final state.

## HTTP contracts

All routes require `Authorization: Bearer <Ledger session token>` and use `Cache-Control: no-store`. Active membership is checked for each resource; inaccessible groups/sessions and another actor's operation result return 404. The owner restriction is additional to membership. Ordinary invalid input returns 400; stale versions, taken units, conflicting retry keys and closed-session mutations return 409.

| Method and route | Request | Success response |
| --- | --- | --- |
| `POST /api/groups/:groupId/split-sessions` | `reviewed`, `requestId`, `description`, `amountCents`, `paidBy`, `units` | 201 `{ session }`; identical retry 200 `{ session }` |
| `POST /api/split-sessions/:sessionId/claims` | `unitId`, `action` (`claim` or `unclaim`), `version`, `operationKey` | 200 `{ session, operation }` |
| `POST /api/split-sessions/:sessionId/finalize` | `version`, `operationKey` | 200 `{ session, operation, expense }` |
| `GET /api/groups/:groupId/snapshot` | No body | 200 `{ groupId, expenses, splitSessions }` |
| `GET /api/operations/:operationKey` | No body | 200 `{ operation }`, with `expense` when available |

`requestId` and `operationKey` are 16–80 ASCII letters, digits, underscores or hyphens; UUID strings are accepted. Unit IDs are unique within the session and allow 1–80 letters, digits, underscores, hyphens, periods or colons. Creation accepts a bounded 128 KiB JSON body; other API requests retain the ordinary 16 KiB limit.

Example reviewed session request:

```json
{
  "reviewed": true,
  "requestId": "c8d84561-f3af-4b48-92b5-94922d935080",
  "description": "Dinner",
  "amountCents": 2100,
  "paidBy": "<current group member ID>",
  "units": [
    { "unitId": "noodles-1", "description": "Noodles", "amountCents": 900 },
    { "unitId": "noodles-2", "description": "Noodles", "amountCents": 900 },
    { "unitId": "tax-tip-1", "description": "Reviewed tax and tip", "amountCents": 300 }
  ]
}
```

The tax/tip example is a positive claimable unit, not automatic proportional allocation. It must be assigned before finalization like every other unit.

A public session includes `id`, `groupId`, `description`, `amountCents`, `currency`, `paidBy`, `units`, `status`, `version`, `sequence`, `finalizedExpenseId`, `createdAt`, and `updatedAt`. Each returned unit adds `ownerId` (a member ID or `null`). Operation results include key, actor/session/group IDs, action type, original payload, `pending`/`complete` state, resulting version/sequence, and an expense ID when completed. Pending finalization intent is not exposed as a new client-editable command.

## Deployment boundary

Run MongoDB and the updated backend as described in the [root README](../README.md), then rebuild the Android app with the matching `API_BASE_URL`. There is no new API key or external provider requirement. The same HTTP/Socket.IO server hosts the retained pixel relay and the authenticated `/dutch` namespace; the pixel relay is not used for group synchronization.

Live event publishing currently uses an in-process EventEmitter and Socket.IO's local rooms. **Use a single backend process for live event delivery.** Multiple instances sharing MongoDB still have persistent REST state and uniqueness checks, but cannot fan out each other's process-local events. A shared Socket.IO adapter/event transport and corresponding delivery/recovery verification are required before claiming multi-instance live support. No deployment or cloud-update claim is made by these source changes.

The design's target of 95% of updates within 2 seconds and recovery within 5 seconds for 20 members/200 units has not been measured under that load. The implemented limits and polling intervals are not evidence that these NFR targets are met.

## Verification

Codacy follow-up verification (28 October 2026 UTC): database filters use explicit equality
for validated scalar keys and typed ObjectIds, including the duplicate-create recovery path.
Query values also pass through explicit `mongo-sanitize` boundaries recognized by the
security analyzer. Type/format validation runs first, so operator objects, nested operators
and empty objects are rejected rather than converted to permissive filters. Sanitization
applies only to scalar values, preserving server-authored operators and exact retry keys.
Operator-shaped keys/IDs cannot broaden queries. Closing `/dutch` removes its publisher,
rejects reconnects and disconnects only that namespace, preserving a shared pixel transport.

The Android screen now delegates receipt validation, canonical state transitions, pending
request persistence, recovery, mutation submission and socket lifecycle to focused components.
Claim, release, finalize and retry use the same mutation runner. Only API, I/O and JSON
failures become recoverable UI state; cancellation and programming errors propagate.
A confirmed write remains confirmed when a subsequent refresh fails. Recovery retains the
original pending key until its canonical snapshot is successfully installed. Finalized expenses
retain the split session's original `createdBy`; the finalizing actor is recorded separately.

The follow-up checks passed: backend production build/typecheck and **237 tests in 16 suites**;
Android **59 unit tests**, **24 emulator instrumentation tests**, debug and instrumentation APK builds, and lint (**0 errors**, existing
43 warnings and 7 hints). Detekt 1.23.8 with the unchanged repository configuration reported
**0 issues across all 13 changed Kotlin source/test files**. Regression cases cover query
operator inputs, concurrent creation, namespace shutdown with a shared transport, ambiguous
mutation response retries, post-write refresh failure, pending recovery failure, cancellation,
state monotonicity, form restoration and malformed/unrelated live payloads.

Backend verification on 21 October 2026: TypeScript typecheck and production build passed, and the full **221-test suite passed across 16 suites**. The Feature 3 suites use a real standalone `mongod`, the actual Express app, and (for live tests) local HTTP and Socket.IO servers:

Pre-PR verification on **28 October 2026 (UTC)** repeated `npm run typecheck`, `npm run build` and `npm test -- --runInBand` successfully: **221 tests passed across all 16 suites** in 58.916 seconds. Existing ts-jest module-configuration warnings remain.

- `backend/tests/no-mock/splitSessions.test.ts`: review/reconciliation and owner permissions, retry-safe creation, competing claims, stale and reused operations, private lookup, forbidden release, snapshots, complete/incomplete finalization, immutable posted expenses, concurrent finalization, lost-device/process recovery, expense-link collision protection and bounded operation history with remaining claims/finalization preserved.
- `backend/tests/no-mock/splitSessionLive.test.ts`: missing-session connection rejection, outsider subscription rejection, committed state delivery, session revocation, malformed subscription acknowledgement frames, and snapshot recovery followed by the next live sequence.
- `frontend/app/src/test/java/com/example/cpen321application/LiveSplitRulesTest.kt`: receipt/quantity checks, sequence decisions and prevention of state rollback.
- `frontend/app/src/androidTest/java/com/example/cpen321application/e2e/GroupLiveSplitUiTest.kt`: seven Compose cases for canonical totals, claim/release permissions, read-only recovery, finalization readiness/ownership and finalized display.
- `frontend/app/src/androidTest/java/com/example/cpen321application/e2e/GroupSplitPendingStorageTest.kt`: uncertain operation persistence across storage re-opening and isolation by account/group.

Android verification passed: **46 unit tests**, debug APK and instrumentation APK builds, and `lintDebug` (no errors; repository warnings remain). On an API 36 emulator started in temporary read-only mode, **20 instrumentation tests passed**: seven new split UI tests, one new pending-storage test, five existing group expense tests, and seven existing app navigation tests. These checks were repeated successfully during the 27 October 2026 local test session.

The local two-emulator acceptance test also passed: invitation acceptance, reviewed receipt creation, competing claims, release/reclaim, a real backend process restart with read-only recovery, finalization, and exact expense/share persistence after force-stopping and relaunching both apps. One CAD 10.00 expense was posted, allocating CAD 5.50 to the owner and CAD 4.50 to the member. See the [local acceptance record](Feature3-Local-Test-2026-10-27.md) for environment, evidence and limits. The stated load/performance measurements and device-level ambiguous-response retry injection remain separate release checks.

The backend ESLint command could not run because the existing root configuration extends the uninstalled `standard` configuration. TypeScript typecheck, production compilation and the backend test suite provide the completed backend checks; lint is not claimed as passing.

Run backend verification from `backend/`:

```powershell
npm run typecheck
npm test -- --runInBand
```

Run Android unit tests and build from `frontend/`:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
```

With a supported emulator or physical device running, execute the new suites and group/navigation regressions:

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.example.cpen321application.e2e.GroupLiveSplitUiTest,com.example.cpen321application.e2e.GroupSplitPendingStorageTest,com.example.cpen321application.e2e.GroupExpensesUiTest,com.example.cpen321application.AppNavigationUiTest"
```

Before release, repeat the recorded two-device flow against the intended deployment, and explicitly inject an ambiguous response to exercise the device's uncertain-action retry. The local run verified recovery of committed claims through a backend restart; it did not inject a lost mutation response. Measure the stated live/recovery performance targets separately.
