# Test inventory and checkpoint navigation

Inventory date: 2026-11-02. This indexes existing tests after repository organization; it does not claim that all course acceptance scenarios are implemented. [Testing.md](Testing.md) remains the course submission entry point.

## Classification rules

| Location | Meaning | Course submission use |
| --- | --- | --- |
| `backend/tests/mock/` | HTTP/socket interfaces with replaced stores, providers or selected model operations | Mocked interface phase |
| `backend/tests/no-mock/` | Real Express, session checks, MongoDB and/or socket transport | Unmocked interface phase; external success paths are not automatically covered |
| `backend/tests/unit/` | Pure domain/helper checks | Supplemental; excluded from combined interface coverage |
| `backend/tests/nfr/` | Dedicated NFR acceptance suites | Currently empty; not a pass |
| Android `e2e/` | Real application user flows | Currently local personal-ledger flows only; backend-connected acceptance remains a gap |
| Android `ui/` | Screen/component behavior with injected state, callbacks or fake providers | Supplemental; not course E2E evidence |
| Android `integration/` | Android/storage/protocol/API integration, some with replaced network dependencies | Supplemental; two live checks are explicitly opt-in |
| Android `nfr/` | Dedicated frontend NFR acceptance suites | Currently empty; not a pass |
| Android `helpers/` | Shared fixtures | Never selected as test classes |
| `frontend/app/src/test/` | JVM unit tests | Optional supplemental tests |

`mongodb-memory-server` launches a real isolated `mongod`; its name alone does not make a test mocked. Conversely, real MongoDB plus `jest.spyOn(...).mockRejectedValueOnce(...)` belongs in `mock/`. Backend provider-adapter checks already colocated in `mock/ledgerServices.test.ts` are retained and are identified as supplemental inside that suite. No test assertions were removed or rewritten during organization.

## Commands and output locations

Run from the repository root unless stated otherwise. Backend prerequisites: Node 22.13+ in the 22.x line, npm 10+, and the locked development dependencies. MongoDB binaries may need a first-run download; no development/cloud database is used by the automated backend suites. Android prerequisites: the configured JDK 17 toolchain, SDK Platform 35, and an emulator/device for instrumented execution.

| Purpose | Windows PowerShell | macOS/Linux Bash |
| --- | --- | --- |
| All three backend interface phases | `./scripts/run-backend-interface-tests.ps1` | `bash scripts/run-backend-interface-tests.sh` |
| Discover frontend E2E tests | `./scripts/run-frontend-e2e-tests.ps1 -ListOnly` | `bash scripts/run-frontend-e2e-tests.sh --list-only` |
| Run frontend E2E tests | `./scripts/run-frontend-e2e-tests.ps1` | `bash scripts/run-frontend-e2e-tests.sh` |
| Supplemental UI tests | `./scripts/run-frontend-regression-tests.ps1 -Suite ui` | `bash scripts/run-frontend-regression-tests.sh ui` |
| Supplemental Android integration tests | `./scripts/run-frontend-regression-tests.ps1 -Suite integration` | `bash scripts/run-frontend-regression-tests.sh integration` |
| Backend NFR tests | `./scripts/run-backend-nfr-tests.ps1` | `bash scripts/run-backend-nfr-tests.sh` |
| Frontend NFR tests | `./scripts/run-frontend-nfr-tests.ps1` | `bash scripts/run-frontend-nfr-tests.sh` |

Frontend runners support `-ListOnly` / `--list-only` for discovery, `-SkipSetup` / `--skip-setup` when the appropriate backend/emulator is already prepared, and optional `-RequireSignIn` / `--require-sign-in`. By default the retained setup scripts start the backend and frontend. Use disposable test data. The current local-ledger E2E suite does not require account sign-in. Raw Gradle options are passed with PowerShell `-GradleArguments @(...)` or after Bash `--`. Empty NFR runners fail clearly rather than reporting success.

From `backend/`, `npm run typecheck` validates TypeScript; `npm run test:unit` runs optional unit tests; `npm run test:mock`, `test:no-mock`, and `test:interface` run individual interface phases. `npm test -- --runInBand` includes every test category. The course script additionally produces `results.json` in each of `coverage/mock/`, `coverage/no-mock/`, and `coverage/interface/`. The combined interface report includes only mock + no-mock tests, not unit/NFR tests. HTML reports are `index.html` or `lcov-report/index.html` in each coverage directory.

From `frontend/`, `./gradlew.bat :app:testDebugUnitTest :app:assembleDebugAndroidTest` (Bash: `bash ./gradlew ...`) verifies JVM tests and compiles all instrumented suites. `:app:connectedDebugAndroidTest` executes all instrumented categories, including supplemental UI/integration tests; use the course E2E script for E2E-only discovery. Reports are under `frontend/app/build/reports/tests/testDebugUnitTest/` and `frontend/app/build/reports/androidTests/connected/debug/`; XML results are under `frontend/app/build/test-results/` and `frontend/app/build/outputs/androidTest-results/connected/`.

### Opt-in integration checks

- `integration.LedgerLiveFxTest`: add `-Pandroid.testInstrumentationRunnerArguments.ledgerLiveFx=true` and select its full class name. It calls the APK-configured backend and historical FX provider. It does not perform UI acceptance or paid recognition.
- `integration.GroupMembershipIntegrationTest`: from `backend/`, run `npm run build` then `node tests/helpers/membership-test-server.cjs`; select the full class and pass `-Pandroid.testInstrumentationRunnerArguments.membershipServer=http://10.0.2.2:3101`. The fixed emulator-only server uses disposable real MongoDB. The test composes a screen, creates/join accounts through test/API helpers and connects real sockets; it is a focused integration check, not a complete user-driven acceptance flow.
- Both checks skip without their explicit arguments. A skipped live test is not evidence of a passed backend-connected scenario.

## Remaining submission work

The current source index below preserves actual groups and top-level tests. To meet the full testing-document requirements, expand it into one row per API with exact mock and no-mock `describe` links, document each test's inputs/status/output/side effects, and add missing mock or unmocked counterparts. In particular, account profile updates have no dedicated no-mock suite; entry sharing, split sessions and live split sockets have no dedicated mock counterpart; successful Google verification is mocked; some other suites cover only subsets of an interface's scenarios.

The local-ledger E2E suite and historical CP1 manual record do not implement all required automated use-case flows against the actual backend. Add the required complete flows and their scenario-step mapping tables. Dedicated NFR tests, a real CI workflow/run link, final main-branch commit SHA, three coverage screenshots and explanations of uncovered code must be completed as applicable to the checkpoint. Directory organization alone does not supply these results.

## Backend source index

Links point to current source; group titles are preserved. First-test links identify suites without explicit describe groups.

| Area | No-mock suite | Mock suite | Replaced components in mock suite |
| --- | --- | --- | --- |
| accountAuth | [no-mock/accountAuth.test.ts](../backend/tests/no-mock/accountAuth.test.ts) | [mock/accountAuth.test.ts](../backend/tests/mock/accountAuth.test.ts) | User and session stores (in-memory doubles). |
| accountSettings | No dedicated suite | [mock/accountSettings.test.ts](../backend/tests/mock/accountSettings.test.ts) | User/session stores; Google token verification in reauthentication cases; injected persistence errors. |
| entrySharing | [no-mock/entrySharing.test.ts](../backend/tests/no-mock/entrySharing.test.ts) | No dedicated suite | No mock counterpart |
| googleAuth | No dedicated suite | [mock/googleAuth.test.ts](../backend/tests/mock/googleAuth.test.ts) | Google ID-token verifier and user/session stores. |
| groupApiAccess | [no-mock/groupApiAccess.test.ts](../backend/tests/no-mock/groupApiAccess.test.ts) | No dedicated suite | No mock counterpart |
| groupExpenses | [no-mock/groupExpenses.test.ts](../backend/tests/no-mock/groupExpenses.test.ts) | [mock/groupExpenses.test.ts](../backend/tests/mock/groupExpenses.test.ts) | Selected GroupModel/ExpenseModel operations fail or simulate conflicts; other MongoDB operations remain real. |
| groupManagement | [no-mock/groupManagement.test.ts](../backend/tests/no-mock/groupManagement.test.ts) | [mock/groupManagement.test.ts](../backend/tests/mock/groupManagement.test.ts) | Selected group/invitation model operations fail or simulate races; other MongoDB operations remain real. |
| groupMembership | [no-mock/groupMembership.test.ts](../backend/tests/no-mock/groupMembership.test.ts) | [mock/groupMembership.test.ts](../backend/tests/mock/groupMembership.test.ts) | Selected membership writes fail or simulate deletion; other MongoDB operations remain real. |
| health | [no-mock/health.test.ts](../backend/tests/no-mock/health.test.ts) | No dedicated suite | No mock counterpart |
| ledgerSaves | [no-mock/ledgerSaves.test.ts](../backend/tests/no-mock/ledgerSaves.test.ts) | [mock/ledgerSaves.test.ts](../backend/tests/mock/ledgerSaves.test.ts) | User/session/cloud-save stores. |
| ledgerServices | [no-mock/ledgerServices.test.ts](../backend/tests/no-mock/ledgerServices.test.ts) | [mock/ledgerServices.test.ts](../backend/tests/mock/ledgerServices.test.ts) | FX/document extractors and provider HTTP fetches; includes adapter-level checks. |
| pixelRelay | No dedicated suite | [mock/pixelRelay.test.ts](../backend/tests/mock/pixelRelay.test.ts) | Upstream course WebSocket; local HTTP/Socket.IO transport remains real. |
| serverInfo | [no-mock/serverInfo.test.ts](../backend/tests/no-mock/serverInfo.test.ts) | No dedicated suite | No mock counterpart |
| splitSessionLive | [no-mock/splitSessionLive.test.ts](../backend/tests/no-mock/splitSessionLive.test.ts) | No dedicated suite | No mock counterpart |
| splitSessions | [no-mock/splitSessions.test.ts](../backend/tests/no-mock/splitSessions.test.ts) | No dedicated suite | No mock counterpart |

### Existing describe groups and first-test anchors

| Category / file | Existing source groups or first test |
| --- | --- |
| mock/accountAuth.test.ts | [describe('Mocked: POST /api/auth/register', () => {](../backend/tests/mock/accountAuth.test.ts#L17)<br>[describe('Mocked: POST /api/auth/login', () => {](../backend/tests/mock/accountAuth.test.ts#L84)<br>[describe('Mocked: POST /api/auth/logout', () => {](../backend/tests/mock/accountAuth.test.ts#L117)<br>[describe('Mocked: GET /api/users/me', () => {](../backend/tests/mock/accountAuth.test.ts#L137) |
| mock/accountSettings.test.ts | [Top-level tests (no describe group)](../backend/tests/mock/accountSettings.test.ts#L18) |
| mock/googleAuth.test.ts | [describe('Mocked: POST /api/auth/google', () => {](../backend/tests/mock/googleAuth.test.ts#L27)<br>[describe('Google registration and explicit linking', () => {](../backend/tests/mock/googleAuth.test.ts#L117) |
| mock/groupExpenses.test.ts | [describe('Mocked: GET /api/groups/:groupId/summary', () => {](../backend/tests/mock/groupExpenses.test.ts#L18)<br>[describe('Mocked: POST /api/groups/:groupId/expenses', () => {](../backend/tests/mock/groupExpenses.test.ts#L48)<br>[describe('Mocked: PUT /api/groups/:groupId/expenses/:expenseId', () => {](../backend/tests/mock/groupExpenses.test.ts#L93) |
| mock/groupManagement.test.ts | [describe('Mocked: POST /api/groups', () => {](../backend/tests/mock/groupManagement.test.ts#L12)<br>[describe('Mocked: GET /api/groups', () => {](../backend/tests/mock/groupManagement.test.ts#L46)<br>[describe('Mocked: GET /api/groups/invitations/:token', () => {](../backend/tests/mock/groupManagement.test.ts#L61)<br>[describe('Mocked: POST /api/groups/invitations/:token/accept', () => {](../backend/tests/mock/groupManagement.test.ts#L77)<br>[describe('Mocked: POST /api/groups/:groupId/invitations', () => {](../backend/tests/mock/groupManagement.test.ts#L113)<br>[describe('Mocked: PUT /api/groups/:groupId/name', () => {](../backend/tests/mock/groupManagement.test.ts#L127)<br>[describe('Mocked: DELETE /api/groups/:groupId', () => {](../backend/tests/mock/groupManagement.test.ts#L152) |
| mock/groupMembership.test.ts | [describe.each(('leave', 'remove') as const)('Mocked group membership: %s', action => {](../backend/tests/mock/groupMembership.test.ts#L9) |
| mock/ledgerSaves.test.ts | [Top-level tests (no describe group)](../backend/tests/mock/ledgerSaves.test.ts#L22) |
| mock/ledgerServices.test.ts | [describe('Guest service boundary (no financial records are persisted)', () => {](../backend/tests/mock/ledgerServices.test.ts#L12)<br>[describe('Frankfurter historical rates', () => {](../backend/tests/mock/ledgerServices.test.ts#L98)<br>[describe('OpenAI Responses adapter', () => {](../backend/tests/mock/ledgerServices.test.ts#L119) |
| mock/pixelRelay.test.ts | [describe('Mocked: course stream relay', () => {](../backend/tests/mock/pixelRelay.test.ts#L18) |
| no-mock/accountAuth.test.ts | [describe('Unmocked: POST /api/auth/register', () => {](../backend/tests/no-mock/accountAuth.test.ts#L27)<br>[describe('MongoDB username migration and Google linking', () => {](../backend/tests/no-mock/accountAuth.test.ts#L60)<br>[describe('Unmocked: login, account info and logout', () => {](../backend/tests/no-mock/accountAuth.test.ts#L113)<br>[describe('Unmocked: POST /api/auth/google', () => {](../backend/tests/no-mock/accountAuth.test.ts#L197) |
| no-mock/entrySharing.test.ts | [Top-level tests (no describe group)](../backend/tests/no-mock/entrySharing.test.ts#L15) |
| no-mock/groupApiAccess.test.ts | [describe.each(protectedInterfaces)('Unmocked: $name', ({ send }) => {](../backend/tests/no-mock/groupApiAccess.test.ts#L40) |
| no-mock/groupExpenses.test.ts | [describe('Unmocked: POST /api/groups/:groupId/expenses (percentage)', () => {](../backend/tests/no-mock/groupExpenses.test.ts#L274)<br>[describe('Unmocked: PUT /api/groups/:groupId/expenses/:expenseId (percentage)', () => {](../backend/tests/no-mock/groupExpenses.test.ts#L345)<br>[describe('Unmocked: POST/PUT /api/groups/:groupId/expenses (equal split with adjustments)', () => {](../backend/tests/no-mock/groupExpenses.test.ts#L399)<br>[describe('Unmocked: POST /api/groups/:groupId/expenses (zero-decimal currency)', () => {](../backend/tests/no-mock/groupExpenses.test.ts#L455) |
| no-mock/groupManagement.test.ts | [Top-level tests (no describe group)](../backend/tests/no-mock/groupManagement.test.ts#L47) |
| no-mock/groupMembership.test.ts | [describe('Unmocked: POST /api/groups/:groupId/leave', () => {](../backend/tests/no-mock/groupMembership.test.ts#L22)<br>[describe('Unmocked: DELETE /api/groups/:groupId/members/:memberId', () => {](../backend/tests/no-mock/groupMembership.test.ts#L71) |
| no-mock/health.test.ts | [describe('Unmocked: GET /health', () => {](../backend/tests/no-mock/health.test.ts#L6)<br>[describe('Unmocked: GET /does-not-exist', () => {](../backend/tests/no-mock/health.test.ts#L20) |
| no-mock/ledgerSaves.test.ts | [describe('Unmocked: ledger save slots', () => {](../backend/tests/no-mock/ledgerSaves.test.ts#L35) |
| no-mock/ledgerServices.test.ts | [describe('Ledger service public interfaces without provider mocks', () => {](../backend/tests/no-mock/ledgerServices.test.ts#L22) |
| no-mock/serverInfo.test.ts | [describe('Unmocked: GET /api/server/name', () => {](../backend/tests/no-mock/serverInfo.test.ts#L15)<br>[describe('Unmocked: GET /api/server/ip', () => {](../backend/tests/no-mock/serverInfo.test.ts#L33)<br>[describe('Unmocked: GET /api/server/time', () => {](../backend/tests/no-mock/serverInfo.test.ts#L56) |
| no-mock/splitSessionLive.test.ts | [Top-level tests (no describe group)](../backend/tests/no-mock/splitSessionLive.test.ts#L133) |
| no-mock/splitSessions.test.ts | [Top-level tests (no describe group)](../backend/tests/no-mock/splitSessions.test.ts#L69) |
| unit/currencies.test.ts | [describe('Supported currencies', () => {](../backend/tests/unit/currencies.test.ts#L5)<br>[describe('Personal ledger with the added currencies', () => {](../backend/tests/unit/currencies.test.ts#L18)<br>[describe('Group money in zero-decimal currencies', () => {](../backend/tests/unit/currencies.test.ts#L38) |
| unit/ledgerDomain.test.ts | [describe('Ledger monetary arithmetic', () => {](../backend/tests/unit/ledgerDomain.test.ts#L13)<br>[describe('Ledger transaction validation', () => {](../backend/tests/unit/ledgerDomain.test.ts#L55)<br>[describe('Ledger duplicate suggestions', () => {](../backend/tests/unit/ledgerDomain.test.ts#L90)<br>[describe('CSV statement preview', () => {](../backend/tests/unit/ledgerDomain.test.ts#L114) |
| unit/ledgerStatements.test.ts | [describe('Local card statement extraction', () => {](../backend/tests/unit/ledgerStatements.test.ts#L19)<br>[describe('US bank CSV exports', () => {](../backend/tests/unit/ledgerStatements.test.ts#L72) |
| unit/password.test.ts | [describe('Password hashing', () => {](../backend/tests/unit/password.test.ts#L4)<br>[describe('Account input validation', () => {](../backend/tests/unit/password.test.ts#L23) |
| unit/splitting.test.ts | [describe('money and splitting', () => {](../backend/tests/unit/splitting.test.ts#L3)<br>[describe('percentage splitting', () => {](../backend/tests/unit/splitting.test.ts#L42)<br>[describe('equal split with adjustments', () => {](../backend/tests/unit/splitting.test.ts#L102) |

## Android source index

The inventory accounts for 23 instrumented test classes (117 @Test methods) and one helper file. Method counts do not claim device execution. All 13 JVM test files remain in src/test/.

| Category | Class / source | @Test methods | Boundary / caveat |
| --- | --- | --- | --- |
| e2e | [LedgerUiTest](../frontend/app/src/androidTest/java/com/example/cpen321application/e2e/LedgerUiTest.kt) | 7 | Actual MainActivity, UI and local SQLite; no complete backend acceptance flow. |
| ui | [AccountSettingsUiTest](../frontend/app/src/androidTest/java/com/example/cpen321application/ui/AccountSettingsUiTest.kt) | 5 | Isolated UI/component regression; injected state/callbacks/providers; not complete E2E. |
| ui | [AccountTransactionDefaultsTest](../frontend/app/src/androidTest/java/com/example/cpen321application/ui/AccountTransactionDefaultsTest.kt) | 1 | Isolated UI/component regression; injected state/callbacks/providers; not complete E2E. |
| ui | [AppLockTest](../frontend/app/src/androidTest/java/com/example/cpen321application/ui/AppLockTest.kt) | 6 | Isolated UI/component regression; injected state/callbacks/providers; not complete E2E. |
| ui | [AppNavigationUiTest](../frontend/app/src/androidTest/java/com/example/cpen321application/ui/AppNavigationUiTest.kt) | 8 | Isolated UI/component regression; injected state/callbacks/providers; not complete E2E. |
| ui | [AvatarCropUiTest](../frontend/app/src/androidTest/java/com/example/cpen321application/ui/AvatarCropUiTest.kt) | 3 | Isolated UI/component regression; injected state/callbacks/providers; not complete E2E. |
| ui | [CloudSaveNamingUiTest](../frontend/app/src/androidTest/java/com/example/cpen321application/ui/CloudSaveNamingUiTest.kt) | 2 | Isolated UI/component regression; injected state/callbacks/providers; not complete E2E. |
| ui | [EntrySharingUiTest](../frontend/app/src/androidTest/java/com/example/cpen321application/ui/EntrySharingUiTest.kt) | 6 | Isolated UI/component regression; injected state/callbacks/providers; not complete E2E. |
| ui | [GroupExpensesUiTest](../frontend/app/src/androidTest/java/com/example/cpen321application/ui/GroupExpensesUiTest.kt) | 10 | Isolated UI/component regression; injected state/callbacks/providers; not complete E2E. |
| ui | [GroupLiveSplitUiTest](../frontend/app/src/androidTest/java/com/example/cpen321application/ui/GroupLiveSplitUiTest.kt) | 7 | Isolated UI/component regression; injected state/callbacks/providers; not complete E2E. |
| ui | [GroupMembershipUiTest](../frontend/app/src/androidTest/java/com/example/cpen321application/ui/GroupMembershipUiTest.kt) | 11 | Isolated UI/component regression; injected state/callbacks/providers; not complete E2E. |
| ui | [GroupSettingsUiTest](../frontend/app/src/androidTest/java/com/example/cpen321application/ui/GroupSettingsUiTest.kt) | 8 | Isolated UI/component regression; injected state/callbacks/providers; not complete E2E. |
| ui | [RegistrationUiTest](../frontend/app/src/androidTest/java/com/example/cpen321application/ui/RegistrationUiTest.kt) | 4 | Isolated UI/component regression; injected state/callbacks/providers; not complete E2E. |
| integration | [AccountAvatarTest](../frontend/app/src/androidTest/java/com/example/cpen321application/integration/AccountAvatarTest.kt) | 2 | Android avatar processing and validation. |
| integration | [ExampleInstrumentedTest](../frontend/app/src/androidTest/java/com/example/cpen321application/integration/ExampleInstrumentedTest.kt) | 1 | Template application-context smoke check. |
| integration | [GroupMembershipIntegrationTest](../frontend/app/src/androidTest/java/com/example/cpen321application/integration/GroupMembershipIntegrationTest.kt) | 1 | Opt-in real HTTP/Socket.IO/MongoDB, with composed screen and direct fixture/API setup. |
| integration | [GroupSplitPendingStorageTest](../frontend/app/src/androidTest/java/com/example/cpen321application/integration/GroupSplitPendingStorageTest.kt) | 1 | Device storage, isolated account/group records. |
| integration | [GroupSplitProtocolTest](../frontend/app/src/androidTest/java/com/example/cpen321application/integration/GroupSplitProtocolTest.kt) | 5 | Android JSON/protocol/serialization. |
| integration | [LedgerCloudSaveRestoreTest](../frontend/app/src/androidTest/java/com/example/cpen321application/integration/LedgerCloudSaveRestoreTest.kt) | 4 | Real SQLite; offline remote substitute; no cloud round trip. |
| integration | [LedgerLiveFxTest](../frontend/app/src/androidTest/java/com/example/cpen321application/integration/LedgerLiveFxTest.kt) | 1 | Opt-in real HTTP/provider check; no UI; skipped by default. |
| integration | [LedgerPersistenceTest](../frontend/app/src/androidTest/java/com/example/cpen321application/integration/LedgerPersistenceTest.kt) | 20 | Real isolated SQLite with controlled remote provider. |
| integration | [LedgerRecognitionAuthTest](../frontend/app/src/androidTest/java/com/example/cpen321application/integration/LedgerRecognitionAuthTest.kt) | 2 | MockWebServer HTTP boundary; not the real backend. |
| integration | [ReceiptImagesTest](../frontend/app/src/androidTest/java/com/example/cpen321application/integration/ReceiptImagesTest.kt) | 2 | Android image processing and validation. |
| helpers | [TestAppLockProvider](../frontend/app/src/androidTest/java/com/example/cpen321application/helpers/TestAppLockProvider.kt) | 0 | Shared screen-fixture lock provider; not a test. |

### Optional JVM unit suites

| Class | @Test methods |
| --- | --- |
| [AccountValidationTest](../frontend/app/src/test/java/com/example/cpen321application/AccountValidationTest.kt) | 7 |
| [ApiErrorMessagesTest](../frontend/app/src/test/java/com/example/cpen321application/ApiErrorMessagesTest.kt) | 3 |
| [BillSplittingTest](../frontend/app/src/test/java/com/example/cpen321application/BillSplittingTest.kt) | 17 |
| [EntrySharingTest](../frontend/app/src/test/java/com/example/cpen321application/EntrySharingTest.kt) | 2 |
| [ExampleUnitTest](../frontend/app/src/test/java/com/example/cpen321application/ExampleUnitTest.kt) | 1 |
| [GroupSplitRecoveryTest](../frontend/app/src/test/java/com/example/cpen321application/GroupSplitRecoveryTest.kt) | 7 |
| [GroupSplitStateTest](../frontend/app/src/test/java/com/example/cpen321application/GroupSplitStateTest.kt) | 7 |
| [InvitationLinksTest](../frontend/app/src/test/java/com/example/cpen321application/InvitationLinksTest.kt) | 2 |
| [L10nTest](../frontend/app/src/test/java/com/example/cpen321application/L10nTest.kt) | 5 |
| [LedgerCloudSavesTest](../frontend/app/src/test/java/com/example/cpen321application/LedgerCloudSavesTest.kt) | 19 |
| [LedgerDomainTest](../frontend/app/src/test/java/com/example/cpen321application/LedgerDomainTest.kt) | 21 |
| [LiveSplitRulesTest](../frontend/app/src/test/java/com/example/cpen321application/LiveSplitRulesTest.kt) | 5 |
| [MealRecommendationTest](../frontend/app/src/test/java/com/example/cpen321application/MealRecommendationTest.kt) | 6 |
