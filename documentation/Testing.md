# Testing

## Change History

| Date | Description |
| --- | --- |
| 11/27/2026 | Added backend NFR tests for NFR-1 Access Control and Privacy and NFR-2 Real-Time Responsiveness, with how each was verified and the execution log. |
| 11/20/2026 | Verified 100% combined backend coverage for all four Jest metrics and refreshed the per-suite results and screenshots. |
| 11/02/2026 | Creation of this file. Reporting E2E testing results for Feature 1 and backend testing results for Bill Import component. |
| 11/19/2026 | Reconciled paths, test inventory, execution prerequisites and evidence status with the repository; marked missing historical artifacts explicitly. |
| 11/19/2026 | Checked the CP2 backend structure against Project Description section 9 and Testing II slides 58-61; moved registration/login input-validation, password-confirmation and rate-limit cases to real MongoDB tests; added mocked dependency-isolation checks for public endpoints. |
| 11/18/2026 | Backend tests restructured to one unmocked and one mocked `describe` per interface with Input/Expected comments; added mocked tests for every interface with a mockable component; added full-stack E2E tests that run the app against the Node backend. |


## Locations of Backend Tests

For the current local results and screenshots, see [Jest Coverage](#jest-coverage). The [November 19 console summary](res/testing/backend-interface-summary.txt) records the earlier structure check.

Backend test conventions (Project Description, section 9):

- Use one `Unmocked: METHOD /path` and one `Mocked: METHOD /path` group for each exposed interface. Socket.IO groups name the namespace and event.
- Exercise normal behavior and controllable errors against real components in `tests/no-mock/`. `mongodb-memory-server` launches a real isolated MongoDB process; an injected JavaScript store is a test double and belongs in `tests/mock/`.
- Use the mocked group to force dependency failures and races. Existing extra tests with in-memory stores remain mock tests; they do not replace the real-database cases.
- Immediately precede each test or parameterized table with `Input`, `Expected status code`, `Expected behavior` and `Expected output`. Describe the injected behavior for mock cases. For sockets, specify the acknowledgement/event instead of inventing an HTTP status.
- Assert the response and relevant persistent effects, including unchanged state after rejected writes. Restore mocks and isolate stored data between cases.
- Public infrastructure endpoints have mocked authentication-store failures to verify they remain independent of those stores; these are availability/isolation checks, not invented error responses. Live Google/OpenAI success paths are not established by forged-token/configuration tests.


The table links to the mocked and unmocked HTTP and Socket.IO tests. Authenticated HTTP routes share checks for missing, unknown and expired sessions. The unmocked socket tests use a real server and an isolated MongoDB process. [Shared session checks](../backend/tests/helpers/sessionRejection.ts).

| Interfaces | Describe group without mocks | Describe group with mocks | Components mocked |
| --- | --- | --- | --- |
| `POST /api/auth/register` | [accountAuth.test.ts:14](../backend/tests/no-mock/accountAuth.test.ts#L14) | [accountAuth.test.ts:25](../backend/tests/mock/accountAuth.test.ts#L25) | MongoDB user and session stores (in-memory replacements whose methods fail or race) |
| `POST /api/auth/login` | [accountAuth.test.ts:127](../backend/tests/no-mock/accountAuth.test.ts#L127) | [accountAuth.test.ts:185](../backend/tests/mock/accountAuth.test.ts#L185) | MongoDB user and session stores (in-memory replacements whose methods fail or race) |
| `GET /api/users/me` | [accountAuth.test.ts:222](../backend/tests/no-mock/accountAuth.test.ts#L222) | [accountAuth.test.ts:285](../backend/tests/mock/accountAuth.test.ts#L285) | MongoDB user and session stores (in-memory replacements whose methods fail or race) |
| `POST /api/auth/logout` | [accountAuth.test.ts:263](../backend/tests/no-mock/accountAuth.test.ts#L263) | [accountAuth.test.ts:247](../backend/tests/mock/accountAuth.test.ts#L247) | MongoDB user and session stores (in-memory replacements whose methods fail or race) |
| `PATCH /api/users/me` | [accountSettings.test.ts:12](../backend/tests/no-mock/accountSettings.test.ts#L12) | [accountSettings.test.ts:23](../backend/tests/mock/accountSettings.test.ts#L23) | MongoDB user and session stores (in-memory replacements whose methods fail or race); Google ID-token verifier |
| `POST /api/groups/:groupId/shared-entries` | [entrySharing.test.ts:27](../backend/tests/no-mock/entrySharing.test.ts#L27) | [entrySharing.test.ts:19](../backend/tests/mock/entrySharing.test.ts#L19) | Mongoose `ExpenseModel` and `GroupModel` via `jest.spyOn`: rejected reads/writes, duplicate-key race |
| `GET /api/groups/:groupId/expenses/:expenseId/personal-entry` | [entrySharing.test.ts:124](../backend/tests/no-mock/entrySharing.test.ts#L124) | [entrySharing.test.ts:73](../backend/tests/mock/entrySharing.test.ts#L73) | Mongoose `ExpenseModel` and `GroupModel` via `jest.spyOn`: rejected reads/writes, duplicate-key race |
| `POST /api/groups/:groupId/expenses/:expenseId/personal-entry/imported` | [entrySharing.test.ts:191](../backend/tests/no-mock/entrySharing.test.ts#L191) | [entrySharing.test.ts:98](../backend/tests/mock/entrySharing.test.ts#L98) | Mongoose `ExpenseModel` and `GroupModel` via `jest.spyOn`: rejected reads/writes, duplicate-key race |
| `POST /api/auth/google` | [googleAuth.test.ts:20](../backend/tests/no-mock/googleAuth.test.ts#L20) | [googleAuth.test.ts:34](../backend/tests/mock/googleAuth.test.ts#L34) | Google ID-token verifier; MongoDB user and session stores (in-memory replacements whose methods fail or race) |
| `POST /api/auth/google/register` | [googleAuth.test.ts:43](../backend/tests/no-mock/googleAuth.test.ts#L43) | [googleAuth.test.ts:194](../backend/tests/mock/googleAuth.test.ts#L194) | Google ID-token verifier; MongoDB user and session stores (in-memory replacements whose methods fail or race) |
| `POST /api/auth/google/link` | [googleAuth.test.ts:56](../backend/tests/no-mock/googleAuth.test.ts#L56) | [googleAuth.test.ts:282](../backend/tests/mock/googleAuth.test.ts#L282) | Google ID-token verifier; MongoDB user and session stores (in-memory replacements whose methods fail or race) |
| `GET /api/groups` | [groupExpenses.test.ts:72](../backend/tests/no-mock/groupExpenses.test.ts#L72) | [groupExpenses.test.ts:21](../backend/tests/mock/groupExpenses.test.ts#L21) | Mongoose models (`GroupModel`, `GroupInvitationModel`, `ExpenseModel`) via `jest.spyOn`: rejected writes/reads, or no matching document |
| `GET /api/groups/:groupId/summary` | [groupExpenses.test.ts:88](../backend/tests/no-mock/groupExpenses.test.ts#L88) | [groupExpenses.test.ts:38](../backend/tests/mock/groupExpenses.test.ts#L38) | Mongoose models (`GroupModel`, `GroupInvitationModel`, `ExpenseModel`) via `jest.spyOn`: rejected writes/reads, or no matching document |
| `POST /api/groups/:groupId/expenses` | [groupExpenses.test.ts:162](../backend/tests/no-mock/groupExpenses.test.ts#L162) | [groupExpenses.test.ts:97](../backend/tests/mock/groupExpenses.test.ts#L97) | Mongoose models (`GroupModel`, `GroupInvitationModel`, `ExpenseModel`) via `jest.spyOn`: rejected writes/reads, or no matching document |
| `PUT /api/groups/:groupId/expenses/:expenseId` | [groupExpenses.test.ts:496](../backend/tests/no-mock/groupExpenses.test.ts#L496) | [groupExpenses.test.ts:145](../backend/tests/mock/groupExpenses.test.ts#L145) | Mongoose models (`GroupModel`, `GroupInvitationModel`, `ExpenseModel`) via `jest.spyOn`: rejected writes/reads, or no matching document |
| `DELETE /api/groups/:groupId/expenses/:expenseId` | [groupExpenses.test.ts:738](../backend/tests/no-mock/groupExpenses.test.ts#L738) | [groupExpenses.test.ts:189](../backend/tests/mock/groupExpenses.test.ts#L189) | Mongoose models (`GroupModel`, `GroupInvitationModel`, `ExpenseModel`) via `jest.spyOn`: rejected writes/reads, or no matching document |
| `POST /api/groups` | [groupManagement.test.ts:75](../backend/tests/no-mock/groupManagement.test.ts#L75) | [groupManagement.test.ts:15](../backend/tests/mock/groupManagement.test.ts#L15) | Mongoose models (`GroupModel`, `GroupInvitationModel`, `ExpenseModel`) via `jest.spyOn`: rejected writes/reads, or no matching document |
| `GET /api/groups/invitations/:token` | [groupManagement.test.ts:171](../backend/tests/no-mock/groupManagement.test.ts#L171) | [groupManagement.test.ts:53](../backend/tests/mock/groupManagement.test.ts#L53) | Mongoose models (`GroupModel`, `GroupInvitationModel`, `ExpenseModel`) via `jest.spyOn`: rejected writes/reads, or no matching document |
| `POST /api/groups/invitations/:token/accept` | [groupManagement.test.ts:223](../backend/tests/no-mock/groupManagement.test.ts#L223) | [groupManagement.test.ts:69](../backend/tests/mock/groupManagement.test.ts#L69) | Mongoose models (`GroupModel`, `GroupInvitationModel`, `ExpenseModel`) via `jest.spyOn`: rejected writes/reads, or no matching document |
| `POST /api/groups/:groupId/invitations` | [groupManagement.test.ts:268](../backend/tests/no-mock/groupManagement.test.ts#L268) | [groupManagement.test.ts:109](../backend/tests/mock/groupManagement.test.ts#L109) | Mongoose models (`GroupModel`, `GroupInvitationModel`, `ExpenseModel`) via `jest.spyOn`: rejected writes/reads, or no matching document |
| `PUT /api/groups/:groupId/name` | [groupManagement.test.ts:303](../backend/tests/no-mock/groupManagement.test.ts#L303) | [groupManagement.test.ts:126](../backend/tests/mock/groupManagement.test.ts#L126) | Mongoose models (`GroupModel`, `GroupInvitationModel`, `ExpenseModel`) via `jest.spyOn`: rejected writes/reads, or no matching document |
| `DELETE /api/groups/:groupId` | [groupManagement.test.ts:331](../backend/tests/no-mock/groupManagement.test.ts#L331) | [groupManagement.test.ts:156](../backend/tests/mock/groupManagement.test.ts#L156) | Mongoose models (`GroupModel`, `GroupInvitationModel`, `ExpenseModel`) via `jest.spyOn`: rejected writes/reads, or no matching document |
| `GET /invite/:token` | [groupManagement.test.ts:366](../backend/tests/no-mock/groupManagement.test.ts#L366) | [groupManagement.test.ts:188](../backend/tests/mock/groupManagement.test.ts#L188) | Mongoose models (`GroupModel`, `GroupInvitationModel`, `ExpenseModel`) via `jest.spyOn`: rejected writes/reads, or no matching document |
| `GET /.well-known/assetlinks.json` | [groupManagement.test.ts:400](../backend/tests/no-mock/groupManagement.test.ts#L400) | [publicEndpoints.test.ts:30](../backend/tests/mock/publicEndpoints.test.ts#L30) | Authentication-store reads reject if called; verify neither store is consulted |
| `POST /api/groups/:groupId/leave` | [groupMembership.test.ts:26](../backend/tests/no-mock/groupMembership.test.ts#L26) | [groupMembership.test.ts:22](../backend/tests/mock/groupMembership.test.ts#L22) | Mongoose `GroupModel.findOneAndUpdate`: rejects or finds no group |
| `DELETE /api/groups/:groupId/members/:memberId` | [groupMembership.test.ts:94](../backend/tests/no-mock/groupMembership.test.ts#L94) | [groupMembership.test.ts:42](../backend/tests/mock/groupMembership.test.ts#L42) | Mongoose `GroupModel.findOneAndUpdate`: rejects or finds no group |
| `GET /health` | [health.test.ts:6](../backend/tests/no-mock/health.test.ts#L6) | [publicEndpoints.test.ts:15](../backend/tests/mock/publicEndpoints.test.ts#L15) | Authentication-store reads reject if called; verify neither store is consulted |
| `GET /api/ledger-saves` | [ledgerSaves.test.ts:44](../backend/tests/no-mock/ledgerSaves.test.ts#L44) | [ledgerSaves.test.ts:31](../backend/tests/mock/ledgerSaves.test.ts#L31) | Ledger save store and user/session stores (in-memory; `list`/`find`/`put`/`rename`/`delete` fail) |
| `GET /api/ledger-saves/:slot` | [ledgerSaves.test.ts:62](../backend/tests/no-mock/ledgerSaves.test.ts#L62) | [ledgerSaves.test.ts:107](../backend/tests/mock/ledgerSaves.test.ts#L107) | Ledger save store and user/session stores (in-memory; `list`/`find`/`put`/`rename`/`delete` fail) |
| `PUT /api/ledger-saves/:slot` | [ledgerSaves.test.ts:94](../backend/tests/no-mock/ledgerSaves.test.ts#L94) | [ledgerSaves.test.ts:152](../backend/tests/mock/ledgerSaves.test.ts#L152) | Ledger save store and user/session stores (in-memory; `list`/`find`/`put`/`rename`/`delete` fail) |
| `PATCH /api/ledger-saves/:slot` | [ledgerSaves.test.ts:184](../backend/tests/no-mock/ledgerSaves.test.ts#L184) | [ledgerSaves.test.ts:303](../backend/tests/mock/ledgerSaves.test.ts#L303) | Ledger save store and user/session stores (in-memory; `list`/`find`/`put`/`rename`/`delete` fail) |
| `DELETE /api/ledger-saves/:slot` | [ledgerSaves.test.ts:216](../backend/tests/no-mock/ledgerSaves.test.ts#L216) | [ledgerSaves.test.ts:370](../backend/tests/mock/ledgerSaves.test.ts#L370) | Ledger save store and user/session stores (in-memory; `list`/`find`/`put`/`rename`/`delete` fail) |
| `GET /api/ledger/meta` | [ledgerServices.test.ts:30](../backend/tests/no-mock/ledgerServices.test.ts#L30) | [ledgerServices.test.ts:45](../backend/tests/mock/ledgerServices.test.ts#L45) | Document extractor and FX provider; `OPENAI_API_KEY` placeholders |
| `GET /api/ledger/exchange` | [ledgerServices.test.ts:46](../backend/tests/no-mock/ledgerServices.test.ts#L46) | [ledgerServices.test.ts:68](../backend/tests/mock/ledgerServices.test.ts#L68) | Frankfurter HTTP `fetch` (real FX provider) and the FX provider `quote` |
| `POST /api/ledger/imports/preview` | [ledgerServices.test.ts:70](../backend/tests/no-mock/ledgerServices.test.ts#L70) | [ledgerServices.test.ts:166](../backend/tests/mock/ledgerServices.test.ts#L166) | OpenAI HTTP `fetch` (real recognition adapter) and the document extractor `extract` |
| `POST /api/ledger/imports/statement-preview` | [ledgerServices.test.ts:115](../backend/tests/no-mock/ledgerServices.test.ts#L115) | [ledgerServices.test.ts:457](../backend/tests/mock/ledgerServices.test.ts#L457) | Local statement extractor and AI extractor `extract` (spy that must not be called) |
| `Socket.IO /dutch connection` | [splitSessionLive.test.ts:43](../backend/tests/no-mock/splitSessionLive.test.ts#L43) | [splitSessionLive.test.ts:39](../backend/tests/mock/splitSessionLive.test.ts#L39) | Session store `find` and the live publisher's `GroupModel.findOne` reject |
| `Socket.IO /dutch unsubscribe` | [splitSessionLive.test.ts:115](../backend/tests/no-mock/splitSessionLive.test.ts#L115) | — | Real socket room membership |
| `Socket.IO /dutch subscribe` | [splitSessionLive.test.ts:135](../backend/tests/no-mock/splitSessionLive.test.ts#L135) | [splitSessionLive.test.ts:81](../backend/tests/mock/splitSessionLive.test.ts#L81) | Session store `find` and the live publisher's `GroupModel.findOne` reject |
| `Socket.IO /dutch onGroupUpdated` | [splitSessionLive.test.ts:189](../backend/tests/no-mock/splitSessionLive.test.ts#L189) | [splitSessionLive.test.ts:128](../backend/tests/mock/splitSessionLive.test.ts#L128) | Session store `find` and the live publisher's `GroupModel.findOne` reject |
| `Socket.IO /dutch onGroupChanged` | [splitSessionLive.test.ts:298](../backend/tests/no-mock/splitSessionLive.test.ts#L298) | [splitSessionLive.test.ts:149](../backend/tests/mock/splitSessionLive.test.ts#L149) | Session store `find` and the live publisher's `GroupModel.findOne` reject |
| `Socket.IO /dutch onGroupMembershipChanged` | [splitSessionLive.test.ts:334](../backend/tests/no-mock/splitSessionLive.test.ts#L334) | [splitSessionLive.test.ts:199](../backend/tests/mock/splitSessionLive.test.ts#L199) | Session store `find` and the live publisher's `GroupModel.findOne` reject |
| `POST /api/groups/:groupId/split-sessions` | [splitSessions.test.ts:98](../backend/tests/no-mock/splitSessions.test.ts#L98) | [splitSessions.test.ts:52](../backend/tests/mock/splitSessions.test.ts#L52) | Mongoose models (`SplitSessionModel`, `ExpenseModel`, `GroupModel`) and session/user store spies: rejected reads/writes, lookup counts |
| `GET /api/groups/:groupId/snapshot` | [splitSessions.test.ts:226](../backend/tests/no-mock/splitSessions.test.ts#L226) | [splitSessions.test.ts:95](../backend/tests/mock/splitSessions.test.ts#L95) | Mongoose models (`SplitSessionModel`, `ExpenseModel`, `GroupModel`) and session/user store spies: rejected reads/writes, lookup counts |
| `POST /api/split-sessions/:sessionId/claims` | [splitSessions.test.ts:288](../backend/tests/no-mock/splitSessions.test.ts#L288) | [splitSessions.test.ts:140](../backend/tests/mock/splitSessions.test.ts#L140) | Mongoose models (`SplitSessionModel`, `ExpenseModel`, `GroupModel`) and session/user store spies: rejected reads/writes, lookup counts |
| `POST /api/split-sessions/:sessionId/finalize` | [splitSessions.test.ts:437](../backend/tests/no-mock/splitSessions.test.ts#L437) | [splitSessions.test.ts:214](../backend/tests/mock/splitSessions.test.ts#L214) | Mongoose models (`SplitSessionModel`, `ExpenseModel`, `GroupModel`) and session/user store spies: rejected reads/writes, lookup counts |
| `GET /api/operations/:operationKey` | [splitSessions.test.ts:663](../backend/tests/no-mock/splitSessions.test.ts#L663) | [splitSessions.test.ts:332](../backend/tests/mock/splitSessions.test.ts#L332) | Mongoose models (`SplitSessionModel`, `ExpenseModel`, `GroupModel`) and session/user store spies: rejected reads/writes, lookup counts |

Unknown routes are covered by `Unmocked: GET /does-not-exist` in [health.test.ts](../backend/tests/no-mock/health.test.ts#L20) and `Mocked: GET /does-not-exist` in [publicEndpoints.test.ts](../backend/tests/mock/publicEndpoints.test.ts#L56) (404 fallback).

## Location of .yml files

Workflow: [`.github/workflows/backend-tests.yml`](../.github/workflows/backend-tests.yml). It runs on every push to `main`, pull requests targeting `main`, and manual dispatch. There is no path filter, so documentation-only pushes to main also run the tests as required by the course.

The Ubuntu 24.04 / Node 22 matrix installs locked dependencies with `npm ci`, typechecks and builds the backend, and runs four jobs:

- `all`: unfiltered `npm test -- --ci --runInBand --coverageDirectory=coverage/all`, including all discovered interface/unit tests and the NFR suites.
- `no-mock`: `npm run test:no-mock -- --ci`.
- `mock`: `npm run test:mock -- --ci`.
- `interface`: `npm run test:interface -- --ci`, for the combined interface coverage report.

Tests create disposable MongoDB instances; no production database, `.env`, or API secrets are supplied. MongoDB binary downloads and Google certificate retrieval require network access. Each job uploads its console output, Jest JSON results, source SHA/runtime versions, and HTML/LCOV coverage as `backend-<suite>-<SHA>` artifacts (30-day retention), including available results from failing tests. A failing test fails its job; the other matrix jobs still run. Download the artifacts before expiry for submission evidence.

Verified on 2026-11-20: [GitHub Actions run 44835505893](https://github.com/WYJ529/Ledger/actions/runs/44835505893), source commit `f3dfa900e507eb333eacc7755b08786902dd8598`, passed all four jobs on Node 22.23.5 / npm 10.9.9. Full suite: **32 suites / 707 tests passed**; no mocks: **12 / 334**; mocks: **12 / 233**; combined interfaces: **24 / 567**. These counts overlap and must not be added together. All four report artifacts were uploaded successfully. See the [saved CI summary](res/testing/backend-ci-2026-11-20.txt); full logs and downloadable reports are on the run page. The Android evidence below is a separate run.


Follow-up: [run 44835937095](https://github.com/WYJ529/Ledger/actions/runs/44835937095) on the documentation-only commit `7d4de21162078f0553375bfe729c717807895f7c` exposed an intermittent concurrent-finalization failure in the all/no-mock jobs (one identical retry received 409 instead of 200). The workflow correctly failed those jobs and retained their artifacts. A deterministic mocked stale-read regression reproduces it: authorization reads an old session, then the operation-key lookup observes the same request already persisted by another caller. The router now recognizes the same session/actor/payload as a replay; other collisions remain errors. A second regression checks that changed parameters still receive 409. Local repair verification: typecheck passed, and both split-session suites passed all 84 tests. The workflow reruns the complete backend on the repair commit; consult the [latest main-branch run](https://github.com/WYJ529/Ledger/actions/workflows/backend-tests.yml?query=branch%3Amain) for its CI status. The first-run counts above remain historical evidence and predate the two added regression tests.

## Front-end test suite location

### Course requirements and existing implementation

Project Description section 9 requires GUI end-to-end tests for the three main features, their formal-use-case success/failure scenarios, independent test setup, and real backend interactions where applicable. Section 12 (Testing document) requires test locations, use-case-step-to-test-step mappings, and execution logs showing passed/failed status. Submission Guidelines scope CP2 to one end-to-end feature and all interfaces of one backend component; the full project scope is broader. Neither document mandates particular test-class names or TXT/XML filenames.

| Feature | Existing implementation | What remains to verify |
| --- | --- | --- |
| Personal ledger | LedgerUiTest: 6 UI-only tests for navigation, manual entry/edit/delete, draft recreation, CSV review and filtered deletion | Existing local flows are implemented. Live receipt/PDF recognition, FX and cloud-save GUI flows are not demonstrated by this class. Historic import restoration is covered separately by `LedgerHistoricImportsIntegrationTest`. |
| Group expenses | GroupBillFullStackE2eTest: 1 app + Node + MongoDB flow | Existing create/join/add-bill success flow is implemented. The success flow passed again on 2026-11-20; remaining formal-use-case failure scenarios still need coverage review. |
| Let's Go Dutch | LetsGoDutchFullStackE2eTest: 1 app + Node + MongoDB + Socket.IO flow | Existing claim/live-update/finalize success flow is implemented. The repaired 2026-11-20 run passed the full flow; remaining failure/recovery scenarios still need coverage review. |

The full-stack harness creates disposable accounts and signs in through the UI; the second user acts through real backend requests, not a second automated GUI. The course default assumes a tester already signed in and allows manual second-user steps. Document this setup difference for review rather than claiming that API-side actions test the second user's GUI.

**Located historical execution evidence:** the [Android runner excerpt](res/testing/android-historical-summary.txt) records **18 tests passed** for GroupMembershipUiTest, GroupMembershipIntegrationTest and GroupExpensesUiTest. These were historically under the e2e package and are now categorized as UI/integration tests. The log does not establish its commit/date and is not evidence of a fresh run of the three current E2E classes.

### Initial Android execution record (before repairs)

- Date: 2026-11-19; source commit `2e9fcdd6cb5dd9149226f44068046f8766d64226`. Only documentation/evidence changes were uncommitted.
- Environment: Pixel 9 emulator, Android 16/API 36, x86_64, fresh isolated userdata; real local backend and disposable MongoDB. No online backend was used.
- Build: `:app:assembleDebug :app:assembleDebugAndroidTest` succeeded. The test APK used `http://10.0.2.2:3000`; original local configuration was restored afterwards.
- Runner: all three existing E2E classes selected with `ledgerFullStack=true`. Exact command and original output: [execution log](res/testing/android-e2e-2026-11-19.txt).
- Result: **9 executed, 3 passed, 6 failed, 0 skipped**. Five LedgerUiTest failures report `RootViewWithoutFocusException` during Espresso back/keyboard actions. LetsGoDutchFullStackE2eTest failed to reach its expected sign-in state before its core flow. These are the initial observed failures; see the repaired run below for subsequent diagnosis and verification.
- Passed: filtered bulk deletion, loading historical personal records, and the group-bill full-stack flow. This run is evidence of execution, not a passing suite or complete course coverage.
- Backend ran on Node 24.11.1; the project-supported Node 22.x environment remains unverified for this run.

### Manual follow-up (2026-11-20)

Joy Tao reported that all six manual checks corresponding to the failed automated scenarios behaved normally on an emulator using the "latest version": dismissing the currency menu, returning from Me/Groups, leaving the editor through tabs, creating/editing/deleting a personal entry, checking an unsaved draft, and signing out then signing in again. This is a tester-reported manual result. The exact APK/source commit, emulator model/API level, and activity-recreation mechanism were not independently verified; no screenshots or execution logs were supplied for this follow-up.

This did not reproduce the reported symptoms manually, but does not establish equivalence to the automated run at `2e9fcdd6cb5dd9149226f44068046f8766d64226`. At the time of this manual follow-up, the recorded automated result was **3 passed / 6 failed**. The later repaired run is recorded below. In particular, the manual sign-in check does not establish that the full Let's Go Dutch flow passed.

### Repaired Android execution record (2026-11-20)

- **9 executed, 9 passed, 0 failed, 0 skipped**, in 205.496 seconds: [final execution log](res/testing/android-e2e-2026-11-20.txt). All three existing E2E classes ran together with `ledgerFullStack=true`, including both real-backend flows.
- Source base: latest fetched main `1626ab9f0ac44f74645e29de3a35957c15d0ea66`, with uncommitted repairs in `LedgerUiTest.kt` and `FullStackHarness.kt`. The log records SHA-256 hashes of those files; application source was unchanged.
- During diagnosis, a **System UI isn't responding** dialog owned window focus. It was closed before the final run. Tests now check initial Activity focus, wait for navigation/BackHandler setup before sending system Back, and await IME dismissal through window insets instead of Espresso's focused-root lookup. The currency-menu test additionally asserts dismissal.
- Full-stack tests now clear only their stored authentication before Activity launch and after teardown, so the next test does not depend on logging out the previous test's account. Each test still signs in through the real UI. This establishes test isolation; it is not a separate automated verification of logout behavior.
- [Intermediate diagnostic logs](res/testing/android-e2e-diagnostics-2026-11-20.txt) retain the six-test run (4 pass / 2 fail), the focus-restored recheck (1 pass / 1 fail), and the final navigation-only recheck (1 pass). The initial 2026-11-19 failure log is also retained above.
- Environment: separate Pixel 9 userdata, Android 16/API 36 x86_64, local Node backend and disposable MongoDB. Build succeeded; original API configuration was restored. Node 24.11.1 was used; project-supported Node 22.x remains unverified here.
- These nine passing tests establish the existing scenarios only; they do not close the remaining coverage gaps noted in the feature table.

### Current suites and scenario mappings

Suite: [frontend/app/src/androidTest/java/com/example/cpen321application/e2e/](../frontend/app/src/androidTest/java/com/example/cpen321application/e2e/).

Use-case steps below refer to the formal specifications in Requirements_and_Design.md:
- UC-01: receipt recognition;
- UC-02: duplicate review/commit;
- UC-F1-03: statement import;
- UC-F1-04: manual entry;
- UC-F1-05: review/edit/delete;
- UC-F1-06: currency conversion;
- UC-F1-07: cloud saves.

**Evidence status:** `res/testing/cp2/android-e2e.txt` and `res/testing/cp2/android-e2e.xml` are absent from this repository. Current per-test results below refer to the 2026-11-19 run at `2e9fcdd6cb5dd9149226f44068046f8766d64226`, linked in the execution record; the old filenames are not required.

Current tracked Android E2E source contains 6 tests in `LedgerUiTest`, 1 in `GroupBillFullStackE2eTest`, and 1 in `LetsGoDutchFullStackE2eTest`. `FullStackHarness` is a helper, not a test suite. The historic-import ViewModel lifecycle check is in `integration/LedgerHistoricImportsIntegrationTest.kt`, outside the E2E suite.

**[LedgerUiTest.kt](../frontend/app/src/androidTest/java/com/example/cpen321application/e2e/LedgerUiTest.kt) — 6 existing local UI tests**

These exercise local UI/SQLite behavior through the rendered app. Each test uses its own merchant for assertions and cleanup. They do not establish the complete app-to-Node-backend flow.

| Test location | Formal use-case steps → test steps and expected behavior | Recorded status |
| --- | --- | --- |
| myExpensesIsHomeAndTabsCanBeReopened | **UC-F1-04 precondition; UC-F1-05 1:** Verify My Expenses is selected → open Me → Back → open Groups → wait for sign-in page → Back; My Expenses/dashboard is available again. | PASS (2026-11-27) |
| expensesTabReturnsToDashboardAndReselectionResetsEditor | **UC-F1-04 1; UC-F1-05 1:** Add → enter 42 → Me → Expenses; the dashboard returns. Open Add again → reselect Expenses; the dashboard returns again. | PASS (2026-11-27) |
| createEditAndDeleteTransactionThroughTheUi | **UC-F1-04 1–3:** Enter amount/merchant → Save; the dashboard shows the saved amount.<br>**UC-F1-05 2:** Open the row → change amount → Save; the dashboard shows the updated amount.<br>**UC-F1-05 3–4:** Open Delete → confirm; the deletion notice appears and the record is absent. | PASS (2026-11-27) |
| draftSurvivesActivityRecreationWithoutSaving | **UC-F1-04 1, 1a:** Enter amount 42 and merchant → recreate the activity; both draft fields survive and no record is saved.<br>**UC-F1-04 1a:** Back → “Discard your changes?” → Discard; the dashboard returns. | PASS (2026-11-27) |
| csvFilePickerRequiresDuplicateReviewBeforeAtomicImport | **UC-F1-03 1–3; UC-02 1–2:** Create a CSV fixture in Downloads → open the system document picker through “Choose a file” → select the file in the picker → preview two rows and verify duplicate confirmation is disabled.<br>**UC-02 3–8:** Acknowledge the duplicate through its visible checkbox → Confirm; two CSV records are imported, including the explicitly retained duplicate. | PASS (2026-11-27) |
| bulkDeleteCanCancelAndDeletesOnlyFilteredSelectedRecords | **UC-F1-05 1:** Create two expenses and one income through the manual-entry UI → enter the merchant in Search → select Expenses; two rows are visible.<br>**UC-F1-05 3, 3a:** Select all shown → Delete → Cancel; the rows remain visible through the UI.<br>**UC-F1-05 3b:** Change the filter through the Expenses/All chips; selection mode clears visibly.<br>**UC-F1-05 3–4:** Filter expenses again → Select all shown → confirm deletion; the expense rows disappear and the income row remains visible. | PASS (2026-11-27) |

`LedgerHistoricImportsIntegrationTest` covers persisted PDF/receipt import reload by constructing a fresh ViewModel directly. It is categorized as integration coverage rather than E2E because the scenario is a state-restoration check without a user-visible flow.

**Full-stack E2E (app + Node backend + MongoDB) — 2 tests**

Both accounts use the app on the same emulator against the isolated Node and MongoDB backend. Set `ledgerFullStack=true` to run the two scenarios. The integration suite also exercises cross-client live updates while the owner stays on screen.

| Test | User actions and expected result | Recorded status |
| --- | --- | --- |
| GroupBillFullStackE2eTest | The owner creates a group and the member joins using the invitation link. The owner saves a CAD 30.00 expense. The member opens the bill and sees the owner as payer and CAD 15.00 allocated to each person. | PASS (2026-11-27) |
| LetsGoDutchFullStackE2eTest | The owner starts a split for Burger and Fries. The member claims Burger, then the owner sees the claim, claims Fries and finalizes. The member opens the posted bill and sees shares of CAD 12.00 and CAD 6.00. | PASS (2026-11-27) |

## NFR location

Both suites are in [backend/tests/nfr/](../backend/tests/nfr/) and run with `scripts/run-backend-nfr-tests.ps1` (or `.sh`). They use the real HTTP app, Socket.IO server and an isolated in-memory MongoDB; nothing is mocked. The `all` GitHub Actions job also runs them. [Execution log](res/testing/backend-nfr-2026-11-27.txt) (commit `c032fd1`).

| NFR | Test file | Result |
| --- | --- | --- |
| NFR-1 — Access Control and Privacy | [accessControl.test.ts](../backend/tests/nfr/accessControl.test.ts) | 9 / 9 passed |
| NFR-2 — Real-Time Responsiveness | [realTimeResponsiveness.test.ts](../backend/tests/nfr/realTimeResponsiveness.test.ts) | 2 / 2 passed |

**NFR-1 — Access Control and Privacy.** The test reads the route table of the real Express app and fails if any route is missing from both the list of 31 protected routes and the list of 11 public ones, so a new route cannot skip the checks. Every protected route is sent with no session, an unknown session, an expired session and a signed-out session; each must return 401 with a Bearer challenge, and a dump of every MongoDB collection must be the same before and after. The 17 routes that expose one group's data are sent with a valid session of a user outside the group and must return 404 without changing any data, and owner-only actions sent by an ordinary member must return 403. On the live channel, connections without a valid session are refused and a non-member cannot subscribe; after the owner removes one member and another member signs out, the owner's socket receives the next claim and neither of theirs does. HTTPS and WSS in release builds are enforced by the Android client, which refuses non-HTTPS backend addresses outside debug builds, so they are not part of this backend suite. Result: all 9 tests passed.

**NFR-2 — Real-Time Responsiveness.** The test builds the largest case the backend accepts: a group of 100 members, each with a session and a subscribed /dutch socket, and a split session with 200 units. Members claim all 200 units and then release 100 of them, one accepted change at a time. Each delivery is timed from when the claim request was sent until another member's socket receives it, so the time includes the HTTP request, the database write and the broadcast. All 300 × 99 = 29,700 deliveries must arrive, and the 95th percentile must be at most 1 second. For recovery, after 200 claims all 100 members request the group snapshot at once, as after a shared network drop. Every snapshot must show the latest version, and the 95th percentile must be at most 2 seconds, which with the app's 3-second poll keeps recovery within 5 seconds (Requirements and Design, Section 3.8). Result: delivery median 258 ms, 95th percentile 307 ms, maximum 349 ms; snapshot median 766 ms, 95th percentile 821 ms, maximum 823 ms. The test runs on one machine, so mobile network delay is not included.

## Jest Coverage

On November 20, 2026, the combined Jest run passed **27 suites and 717 tests** across all 33 TypeScript files in `backend/src`. It covered **2,357/2,357 statements, 1,775/1,775 branches, 332/332 functions and 1,824/1,824 lines**. Jest maps coverage to TypeScript source and imports compiler helpers from `tslib`. The combined command enforces 100% on each metric.

| Local run | Passed suites / tests | Statements | Branches | Functions | Lines |
| --- | ---: | ---: | ---: | ---: | ---: |
| Without mocks | 13 / 359 | 74.41% | 69.74% | 82.53% | 79.33% |
| With mocks | 14 / 358 | 85.65% | 76.45% | 89.15% | 90.29% |
| **Combined** | **27 / 717** | **100%** | **100%** | **100%** | **100%** |

The combined command runs `tests/mock/` and `tests/no-mock/` together. Alongside HTTP and Socket.IO scenarios, it includes server startup and shutdown, the seed command, and three tests that call the exported monetary calculation interfaces directly. The combined run selects only these two directories. The full backend run, including unit tests, passed all 857 tests in 35 suites. [Server lifecycle](../backend/tests/mock/serverCommand.test.ts) · [Seed command](../backend/tests/mock/seedExpenseGroup.test.ts) · [Calculation tests](../backend/tests/no-mock/ledgerCalculationContracts.test.ts).

The screenshots show the Istanbul HTML reports generated locally on Windows with Node 22.23.3, npm 12.0.1 and MongoDB 8.2.6. Each group shows the overall report and the file tables for all three source directories. [Console results and commands](res/testing/coverage-2026-11-20/results.txt).

The previous GitHub Actions evidence for commit `43b5d0a` is archived separately. [Historical CI run](https://github.com/WYJ529/Ledger/actions/runs/44837686701) · [Artifact provenance](res/testing/coverage-43b5d0a/provenance.txt).

### Without mocks

**Whole backend:**

![No-mock Jest coverage: whole-backend totals and directories](res/testing/coverage-2026-11-20/no-mock-overview.png)

**All 27 files in `src`:**

![No-mock Jest coverage: every src file](res/testing/coverage-2026-11-20/no-mock-src.png)

**`src/config`:**

![No-mock Jest coverage: environment configuration](res/testing/coverage-2026-11-20/no-mock-config.png)

**All 5 files in `src/ledger`:**

![No-mock Jest coverage: every ledger file](res/testing/coverage-2026-11-20/no-mock-ledger.png)

### With mocks

**Whole backend:**

![Mocked Jest coverage: whole-backend totals and directories](res/testing/coverage-2026-11-20/mock-overview.png)

**All 27 files in `src`:**

![Mocked Jest coverage: every src file](res/testing/coverage-2026-11-20/mock-src.png)

**`src/config`:**

![Mocked Jest coverage: environment configuration](res/testing/coverage-2026-11-20/mock-config.png)

**All 5 files in `src/ledger`:**

![Mocked Jest coverage: every ledger file](res/testing/coverage-2026-11-20/mock-ledger.png)

### Without and with mocks combined

**Whole backend:**

![Combined-interface Jest coverage: whole-backend totals and directories](res/testing/coverage-2026-11-20/interface-overview.png)

**All 27 files in `src`:**

![Combined-interface Jest coverage: every src file](res/testing/coverage-2026-11-20/interface-src.png)

**`src/config`:**

![Combined-interface Jest coverage: environment configuration](res/testing/coverage-2026-11-20/interface-config.png)

**All 5 files in `src/ledger`:**

![Combined-interface Jest coverage: every ledger file](res/testing/coverage-2026-11-20/interface-ledger.png)

## How to run Tests

**Test running prerequisites:**

- Backend: Node.js 22.13+ within 22.x and npm 10+. Install locked dependencies with `npm ci`. Interface tests use isolated MongoDB processes and do not need a development database or OpenAI key. Network access may be needed for dependency installation, MongoDB binary download and the real Google verifier's certificate retrieval.
- Android: JDK 17, the SDK required by `frontend/`, and one running emulator. Preserve `sdk.dir` in `frontend/local.properties`. Dependencies and emulator images must be installed first.

**Existing local personal-ledger UI tests (PowerShell, repository root):**

```powershell
cd frontend
.\gradlew.bat :app:connectedDebugAndroidTest `
  '-Pandroid.testInstrumentationRunnerArguments.class=com.example.cpen321application.e2e.LedgerUiTest' --console=plain
cd ..
```

The latest Android run uses main `1626ab9f0ac44f74645e29de3a35957c15d0ea66` plus the test repairs described above; the execution log records hashes of the modified test files.

This selects the existing 7-test class only. Open `frontend/app/build/reports/androidTests/connected/debug/index.html` after a run; this generated local report is not committed evidence.

**Full-stack E2E (app + Node backend):** no `.env` or OpenAI key is required. The runner starts a real isolated MongoDB process and stops after 15 minutes. Initial installation and MongoDB binary download may require internet access; the tested group flows use local services. Keep `API_BASE_URL=http://10.0.2.2:3000` in `frontend/local.properties`. In one terminal:

```powershell
cd backend
npm ci
npm run build
$env:LEDGER_TEST_PORT = '3000'
node tests/helpers/membership-test-server.cjs
```

In a second terminal, with only the emulator connected:

```powershell
cd frontend
.\gradlew.bat :app:connectedDebugAndroidTest `
  '-Pandroid.testInstrumentationRunnerArguments.class=com.example.cpen321application.e2e.GroupBillFullStackE2eTest,com.example.cpen321application.e2e.LetsGoDutchFullStackE2eTest' `
  '-Pandroid.testInstrumentationRunnerArguments.ledgerFullStack=true' --console=plain
cd ..
```

Registration is rate-limited per client, so restart the backend if you repeat the run many times within a few minutes.

**Backend interface tests (from the repository root):**

```powershell
cd backend
npm ci
npm run test:no-mock
npm run test:mock
npm run test:interface
cd ..
```

`test:no-mock` and `test:mock` run each folder with coverage (`backend/coverage/no-mock/`, `backend/coverage/mock/`); `test:interface` runs both together (`backend/coverage/interface/`). `npm test` runs every backend suite, including `tests/unit/`.

**Backend NFR tests (from the repository root):**

```powershell
.\scripts\run-backend-nfr-tests.ps1
```

On macOS or Linux, run `bash scripts/run-backend-nfr-tests.sh`. The NFR-2 suite takes about 90 seconds.

### Saving frontend execution evidence

Use any clearly named evidence files; the course does not require the old `android-e2e.txt` or `android-e2e.xml` names. Capture the selected Gradle command output, actual source SHA, environment, selected classes, and each test's passed/failed/skipped status. Preserve the generated Android report or JUnit XML when available; XML is supporting evidence, not a specifically mandated format. A successful Gradle build with skipped full-stack tests is not a passing full-stack run. Review logs for credentials/personal data before committing them.
