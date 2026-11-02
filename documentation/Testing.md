# Testing

## 1. Change History

| Change Date | Modified Sections | Rationale |
| --- | --- | --- |
| 2026-11-02 | Sections 2-3; submission navigation; evidence paths | Organize existing tests by actual scope, document course runners and outstanding submission evidence. Preserve the historical CP1 record rather than relabeling it as a new automated run. |
| 2026-10-19 | Sections 2-4 | Replace unrelated course-template examples with Ledger's CP1 manual end-to-end test specification for recording and viewing a shared expense. Scope CP1 to E2E testing following the instructor's clarification. |

## 2. Back-end Test Specification: APIs

The current suite locations, source-group links and mocked components are indexed in [Test_Inventory.md](Test_Inventory.md). Backend interface tests are in `backend/tests/mock/` and `backend/tests/no-mock/`; optional unit tests remain in `backend/tests/unit/`. Shared fixtures are in `backend/tests/helpers/`. A temporary MongoDB started by `mongodb-memory-server` is a real `mongod`, not a mocked database. Some mock suites also use it while replacing selected database operations.

From the repository root, run `./scripts/run-backend-interface-tests.ps1` on Windows or `bash scripts/run-backend-interface-tests.sh` on macOS/Linux. Install Node 22.13+ within 22.x and npm 10+; the script installs locked development dependencies if needed and executes mock, no-mock and combined interface phases serially. Each phase writes coverage to `backend/coverage/mock/`, `backend/coverage/no-mock/` or `backend/coverage/interface/`, including HTML, LCOV and `results.json`. Optional unit tests run with `cd backend` then `npm run test:unit`; they are deliberately excluded from the combined **interface** coverage.

### Submission evidence status

- The source baseline for this organization is `f092d8f65bf121b4ecc6b84e6809d40d89699188`, with local changes on `refactor/repo-organize`. This is not a claimed tested submission commit on `main`; enter the final main-branch SHA after review and merge, and rerun on that SHA.
- `.github/workflows/` currently contains only `.gitkeep`. An actual backend-test workflow and its run link still need to be supplied if required by the checkpoint.
- Use the [organization record](Repository_Organization_2026-11-02.md) and [actual console excerpts](res/testing/repository-organization-2026-11-02.txt) for verification performed during this change. Generated reports remain ignored; publish selected sanitized logs under `documentation/res/testing/` and report screenshots under `documentation/images/testing/`.
- Before submission, complete a row for **each API** with its mock/no-mock `describe` group links, fill missing test counterparts and per-test input/status/output/side-effect comments, capture separate and combined coverage screenshots, and explain uncovered code. The source-group inventory explicitly identifies suites with top-level tests; those are not falsely presented as per-API `describe` groups.

## 3. Tests of Non-Functional Requirements

`backend/tests/nfr/` and `frontend/app/src/androidTest/java/com/example/cpen321application/nfr/` preserve the required template locations but currently contain **no dedicated automated NFR test suites**. Their normal runners fail on an empty suite; frontend `-ListOnly` / `--list-only` reports the gap without executing anything.

Existing financial-integrity, access-control and persistence regression tests remain in their actual interface/unit/integration categories. They are useful supporting checks, but have not been duplicated or relabeled as two independently verified NFRs. For each selected NFR, add a dedicated test if appropriate, state the measured acceptance criteria, and include a paragraph describing verification plus real logs. NFR-3 latency/load targets are not established by these regression tests.

## 4. Front-end Test Specification: End-to-End Tests

**Current automated inventory:** see [Test_Inventory.md](Test_Inventory.md). `LedgerUiTest` now resides in the `e2e` package and is discovered by the course E2E runner. It covers local personal-ledger user flows and is not evidence of the complete backend-connected group workflow. Injected-screen tests are in `ui/`; API, socket, SQLite and device-component checks are in `integration/`; fixtures are in `helpers/`. Standard JVM unit tests remain under `app/src/test/`.

**Historical record:** Sections 4.1-4.7 below preserve the team's 2026-10-19 CP1 manual execution and evidence. They are not a new automated checkpoint result. For a new automated submission, add use-case step-to-test-step tables and actual passed/failed logs for the required complete flows against the real backend. Do not use component tests with fake providers as replacements for those flows.

### 4.1. Scope and Test Method

**Feature under test:** Record a shared group expense using equal splitting and view the saved expense as another group member.

**Objective:** Verify the complete user flow from Android UI actions through backend processing and database persistence: sign in, create a group, accept an invitation, save an expense, and retrieve consistent expense information across accounts and app restarts.

These are manual E2E tests performed through the installed Ledger app against a real running backend and MongoDB database. Do not replace network responses or database storage with mocks. Refresh actions are explicit; automatic live synchronization is not assumed.

Allocated shares represent each member's portion of recorded expenses. They are not outstanding debts or repayment balances.

### 4.2. Environment and Preconditions

1. Install the APK being evaluated on a Pixel 9 emulator running Android Baklava (API 36). A second device/emulator is optional; the same flow can be performed by switching accounts on one emulator.
2. Ensure the APK connects to the intended Ledger backend, that MongoDB is available, and that all clients use the same backend/database. Record the actual backend URL in Section 4.6.
3. Prepare two distinct username/password test accounts, User A and User B, using Ledger's sign-up flow if necessary. Record their usernames and display names in the execution record; do not include passwords in this document.
4. Use disposable CAD test groups and record their starting state. Do not use production or personal expense data. The reported run used group ABC for all three cases.
5. E2E-02 and E2E-03 use existing group ABC after E2E-01. Check against its existing saved-expense baseline rather than expecting an empty group.

### 4.3. E2E-01: Create, Share, and Persist an Equal-Split Expense

**Test data:** Group name `ABC` (use a fresh, uniquely named group for a rerun); currency CAD; description `CP1 dinner`; total `100.00`; payer A; participants A and B; split mode Equal split.

| Step | User Action | Expected Result |
| --- | --- | --- |
| 1 | Open Ledger and sign in as A using the prepared username/password account. | Sign-in succeeds and the home screen appears. |
| 2 | Select **Create group**, enter the unique group name, choose CAD, and create the group. | The group expense screen opens and an invitation dialog appears. |
| 3 | Copy the invitation link and close the dialog. Open **Members**. | A is listed as Owner. The new group has no expenses and total spending is CAD 0.00. |
| 4 | Log out through Account, confirm logout, and sign in as B. Open **Group expenses**, select **Join group**, paste the copied link, and select **Continue**. | The invitation preview identifies A as the inviter and shows the correct group name and CAD currency. Previewing alone does not join the group. |
| 5 | Select **Accept**, then open **Members**. | The group expense screen opens. Exactly two members appear: A as Owner and B as Member. |
| 6 | Log out, sign in as A, open **Group expenses**, refresh the group list if needed, and open the test group. Select **Add expense**. | The expense form opens for the correct group and offers both A and B as participants. |
| 7 | Enter `CP1 dinner` and total `100.00`. Set **Who paid?** to A, select **Equal split**, and ensure exactly A and B are selected under **Split between**. | The preview allocates CAD 50.00 to each participant, and **Save expense** is enabled. |
| 8 | Select **Save expense** once and wait for the group summary. | Exactly one expense appears with description `CP1 dinner`, amount CAD 100.00, payer A, and Equal split. Total group spending is CAD 100.00. A and B each have an allocated share of CAD 50.00. |
| 9 | Select **View split** on the saved expense, then **Refresh expenses**. | The saved per-expense shares are A: CAD 50.00 and B: CAD 50.00. Refresh preserves one expense, total CAD 100.00, and both allocated shares. |
| 10 | Log out, sign in as B, open the same group, and select **Refresh expenses**. Open **View split**. | B sees the same expense, payer, total, and per-member shares. B's **Your allocated share** is CAD 50.00. |
| 11 | Force-stop Ledger through Android settings and reopen it without clearing app storage or reinstalling. If sign-in is requested, sign in as B. Open the group and refresh expenses. | The group still contains exactly one `CP1 dinner` expense. Total spending remains CAD 100.00 and each member's allocated share remains CAD 50.00. |

**Pass criteria:** Every expected result is observed, including retrieval by B and after app restart. A UI preview alone is insufficient to pass this test.

**Actual result:** The team member preparing this document confirmed that the complete create-group, join-as-another-account, add/split-expense, switch-account, and reopen-app flow was run on a Pixel 9 emulator without issues. During the documented follow-up, Joy Tao reported no errors through saving and viewing the split. The supplied screenshot shows `CP1 dinner`, CAD 100.00, paid by joy, Equal split, joy: CAD 50.00, and joy1: CAD 50.00. Joy Tao subsequently confirmed that viewing the expense as joy1 and reopening after force-stop both preserved the same result in group ABC, and supplied a second matching expense-detail screenshot. The screenshots show the expense details; the active account, group name, restart action, and group-wide expense count are supported by the tester report rather than directly visible in these cropped images.

**Status:** Pass (manual run confirmed by Joy Tao, with expense-detail screenshots). Group-wide total and expense count are not independently visible in the supplied screenshots.

### 4.4. E2E-02: Reject an Invalid Expense Amount

**Preconditions:** Signed in to the existing CAD group ABC. The previously saved `CP1 dinner` expense is retained; this test does not require an empty group.

| Step | User Action | Expected Result | Actual Result |
| --- | --- | --- | --- |
| 1 | Open **Add expense** and leave the amount empty. | **Save expense** is disabled. | Joy Tao confirmed that the button was greyed out and saving was unavailable. |
| 2 | Enter `0` as the amount. | The amount is invalid and cannot be saved. | Joy Tao confirmed that the amount field turned red and saving remained unavailable. |

**Status:** Pass for the invalid-amount checks above (tester-reported). No screenshot was supplied for this case. A correction-and-save sequence and cross-account verification were not reported and are not included in this result.

### 4.5. E2E-03: Cancel an Unsaved Expense

**Preconditions:** Signed in to the existing CAD group ABC. Existing saved expenses form the baseline; cancellation must not alter them.

| Step | User Action | Expected Result | Actual Result |
| --- | --- | --- | --- |
| 1 | Open **Add expense**, enter draft expense details, and return without selecting **Save expense**. | The draft is not saved, no new expense is added, and existing saved expenses remain unchanged. | Joy Tao confirmed that nothing changed and the draft was not saved. |

**Status:** Pass for cancellation without saving (tester-reported). No screenshot was supplied for this case. Separate refresh, restart, and cross-account checks for this cancellation case were not reported.

### 4.6. Execution Record and Evidence

The execution record below combines the manual test report from Joy Tao, supplied screenshots, and locally verified build metadata. This run used the Android Studio debug build. Verification of the final release APK is a separate submission check.

| Field | Recorded Value |
| --- | --- |
| Tester | Joy Tao |
| Execution date and time | 2026-10-19; expense card displays 20:30 (expense timestamp, not a separately recorded test start time) |
| APK filename / build identifier | Android Studio Run; local debug build `app-debug.apk`, version 1.0 (versionCode 1), application ID `com.example.cpen321application`; verified from generated BuildConfig and APK output metadata |
| Source commit SHA corresponding to APK | `733548ced94887412468465f14c8072c5f644fac` on `main`; local HEAD matches origin/main, and the tester confirmed testing the synchronized version. Current uncommitted changes are documentation and evidence only. |
| Device / emulator and Android API | Pixel 9 emulator; Android 16 (Baklava), API 36, x86_64 (confirmed by Device Manager screenshot) |
| Backend URL | `https://136-67-157-106.sslip.io` (verified in local configuration and generated debug BuildConfig) |
| Test account display names | A: joy; B: joy1 |
| Test group names | ABC for E2E-01, E2E-02, and E2E-03 |

| Test ID | Status | Actual Result / Failure Details | Evidence |
| --- | --- | --- | --- |
| E2E-01 | Pass (team-reported workflow run) | Complete main workflow ran on a Pixel 9 emulator without issues. Follow-up screenshot confirms a CAD 100.00 equal-split expense, paid by joy, with CAD 50.00 allocated to joy and joy1 each. Joy Tao confirmed the same result as joy1 and after force-stop/reopen in group ABC, with no errors reported. | Tester confirmation, [saved split](images/evidence/CP1-E2E-01-saved-split.png), and [follow-up split](images/evidence/CP1-E2E-01-followup-split.png). Cropped screenshots do not show active account or restart action. |
| E2E-02 | Pass (tester-reported) | Empty amount: save button greyed out. Amount 0: amount field red and saving unavailable. | Joy Tao confirmation; no screenshot supplied |
| E2E-03 | Pass (tester-reported) | Returning without saving did not save the draft or change existing data in ABC. | Joy Tao confirmation; no screenshot supplied |

For E2E-01, capture the two-member roster, A's saved summary and expanded split, B's matching summary, and the summary after restart. For additional evidence of E2E-02, capture the disabled save action with an empty amount and the red amount field with 0 entered. For E2E-03, capture the unchanged summary after cancellation. Name evidence files with the test ID and step number. Record any mismatch with the failing step and observed values.

### 4.7. Related Automated Tests and Coverage Limits

The repository contains Compose UI tests at `frontend/app/src/androidTest/java/com/example/cpen321application/ui/GroupExpensesUiTest.kt`. They cover isolated form and summary behavior using supplied data and callbacks; they do not execute the complete app-to-backend-to-database flow above. API integration tests are under `backend/tests/no-mock/`. Neither suite is evidence that these manual E2E scenarios have passed.

This CP1 specification covers one primary equal-split feature and its invalid-input and cancellation paths. Custom splits, rounding with unequal remainders, expense editing, network failures, and other features are outside this document's current E2E coverage.
