# Feature 1: Personal Ledger

## Integration baseline (2026-10-20)

The feature checkpoint is `e07bb0d` on `feature1-dev`. The `codex/feature1-integration` branch merges main at `f4b999c`, retaining authentication, invitations and group splitting. The app now opens **My Expenses**, with **My Expenses**, **Group**, and **Me** in bottom navigation. My Expenses works before sign-in; Group and Me require authentication, with **Use without Login** returning to My Expenses. Signing in normally returns to My Expenses; a pending group invitation continues to its preview. Existing records keep their original device-only ownership and are not assigned to whichever account signs in. This layout change does not add account-linked storage or cloud synchronization.

The combined server starts with MongoDB, as required by main. Ledger routes retain their own 7 MiB JSON parser ahead of the 16 KiB parser used by other API routes. Both session preferences and the guest database remain excluded from backup and device transfer. Guest recognition is still a local-development service; session-protected recognition and per-user storage/migration remain follow-up work before shared deployment.

Validation of this merge: backend typecheck/build passed, all 203 Jest tests across 14 suites passed (including real temporary MongoDB account/group tests and document-body parser regression checks), and all 38 Android JVM tests passed. Both debug and instrumentation APKs build successfully. Backend verification used host Node.js 24.18.0; the declared deployment requirement remains Node.js 22.13+ within the 22.x line. No Android device/emulator was connected, so the updated ledger navigation UI test and other device suites were compiled but not run. No live Google, FX, or paid document-recognition calls were made for this merge.

## Registration environment repair (2026-10-20)

The emulator's port 3000 was served by an old, still-running `node dist/index.js`: `/health` and guest ledger routes worked, while `/api/auth/register`, `/api/auth/google/register` and `/api/users/me` returned 404. Restarting the current integrated backend with persistent local MongoDB restored these routes. Both local Google client settings were template placeholders and were aligned to the repository's existing public Web client ID. No user credentials or guest records were used or changed.

Regression prevention: `npm start` compiles current sources first; `scripts/check-backend.mjs` checks route availability without creating accounts; Android gives an actionable message for missing account routes while preserving group-resource 404 errors. The backend supports explicit `HOST` binding for loopback-only development.

Validation: backend typecheck/build, 47 Google/password account tests and all 41 Android JVM tests passed. The rebuilt APK was installed without clearing app data. A unique synthetic account completed registration, login, account lookup, logout and revoked-session checks against the running backend, then its account/session records were removed. The emulator reached the repaired backend through `10.0.2.2`. Google account creation is covered with a mocked token verifier plus backend route/configuration checks; a real Google account-picker/consent flow must be completed by the user.

## Codacy remediation (2026-10-20)

Addressed PR #45's 29 new findings without disabling checks or increasing their thresholds. Statement extraction now uses literal regular expressions. CSV parsing, request validation, HTTP response handling, account navigation and ledger screen sections have focused components. The activity-scoped ledger retains its drafts and operation state while filter, selection, document and import actions live in dedicated controllers. Expected ledger, document and network failures are handled specifically; coroutine cancellation continues to propagate. Fingerprint encoding, retry receipts, revision checks and atomic database transactions retain their existing behavior.

Validation: all 203 backend tests, 41 Android JVM tests and 24 emulator tests (ledger UI, SQLite persistence and group UI) passed. Android debug/test APK builds and lint passed; lint has no errors. Detekt with the existing configuration reports no new findings relative to the pre-fix tree, with the 28 reported Kotlin issues removed; remaining findings belong to unchanged baseline code. The ESLint non-literal-RegExp check passes for statement extraction. Emulator tests used isolated databases or unique synthetic records and did not clear existing app data.

## Scope and data ownership

The working ledger is local to one Android installation and works without signing in. Its SQLite database is `ledger_guest.db`; it is not shared with a group or synchronized automatically. A signed-in user can copy it to, or replace it from, one of the account's three [cloud saves](#cloud-saves). Android cloud backup and device transfer exclude this database. Clearing app data or uninstalling the app removes the device records; cloud saves stay in the account.

Each transaction preserves its original amount and currency as decimal text plus integer minor units, along with its kind, ISO date, merchant, category, and notes. A reporting-currency snapshot stores the decimal exchange rate, rate date and source, converted minor-unit amount, and reporting currency. Records also carry a source label, optional source filename, revision, and timestamps. Receipt and statement bytes are held only in memory while being reviewed and are not persisted.

## Cloud saves

Cloud saves work like game save slots. Each account has three fixed slots, and each slot holds one complete copy of a device ledger: every transaction, the reporting currency, the device model, the record count and the server's save time. Saving and loading are always manual. There is no automatic synchronization or merging of edits made on different devices: two devices diverge until one of them loads the other's save.

- **Save** (Me → Cloud saves → Save here): an empty slot is written at once. An occupied slot is overwritten only after a confirmation showing its time and record count.
- **Load**: replaces the whole device ledger after a warning that unsaved changes will be lost. The warning also offers **Save current ledger first**, which saves to another slot and then continues the load. The save is validated before anything is deleted: amounts must match their minor units, every conversion must match its rate, and every record must use the save's reporting currency. The replacement is one SQLite transaction, so a damaged save or a storage error leaves the current ledger unchanged. Open editors, imports and selections are closed. Retry receipts are cleared; cached exchange rates are kept. Shares imported from groups are saved like other records. Their "already imported" marks survive a load on the same device for every record the save contains. A device that never imported a share has no such mark, so it can import that share again after loading a save made elsewhere.
- **Delete**: removes only the cloud slot; the device ledger is not changed.
- **After sign-in**: if the device has transactions, Ledger offers to save them to this account. If the device is empty and the account has saves, it offers to load one. **Not now** dismisses the offer, and if the server can't be reached the offer is skipped.
- **Log out**: when the device has transactions, the user chooses **Save to the cloud, then log out** (the device keeps its copy), **Delete from this device, then log out** (a second confirmation follows; cloud saves stay), or **Cancel**. A failed save does not log out. With an empty ledger, the ordinary log-out confirmation appears. Switching accounts means logging out and signing in, so both choices apply.

Each slot has a revision number. A save or delete sends the revision the device last listed, and the server answers `409` when another device has changed the slot since then. The app then re-lists the slots instead of overwriting a newer save. A save holds at most 5,000 transactions in an 8 MiB request. The backend validates every record again before storing it.

| Route (bearer session required) | Behavior |
| --- | --- |
| `GET /api/ledger-saves` | Lists occupied slots: `slot`, `revision`, `savedAt`, `deviceName`, `baseCurrency`, `recordCount`. Transactions are not included. |
| `GET /api/ledger-saves/:slot` | Returns one save with its `records`; `404` for an empty slot. |
| `PUT /api/ledger-saves/:slot` | Body `{ deviceName, baseCurrency, records, replaces }`, where `replaces` is the listed revision or `null` for an empty slot. Returns the new summary, `400` for an invalid ledger, or `409` for a changed slot. |
| `DELETE /api/ledger-saves/:slot?revision=N` | `204` when deleted or already empty, `409` when the slot holds a different revision. |

Saves are stored in the MongoDB `ledgersaves` collection, one document per account and slot. Uploaded receipts and statements are never part of a save.

## Currency and summary behavior

CAD is the initial reporting currency. The supported currencies are CAD, USD, EUR, GBP, CNY, JPY, AUD, HKD, SGD, KRW, MXN, and INR; JPY and KRW use zero fractional digits, while the others use two. Amounts and conversions use decimal/integer arithmetic with half-up rounding rather than floating-point calculations. Foreign-currency entries use a historical ECB reference rate returned by the backend. The quote date cannot be after the transaction date or more than seven days earlier. Same-currency entries need no network request.

Quotes are cached locally by source currency, reporting currency, and requested transaction date, so historical entries can be viewed and revalued offline after their quote is cached. A quote fetched on its requested date expires after five minutes because that day's rate may not yet have been published. Changing the reporting currency preserves every original transaction, obtains all missing quotes first, verifies that the new totals are safe, then updates the reporting snapshots and preference in one SQLite transaction. A failed lookup or local write leaves the selected currency and existing records unchanged.

Dashboard totals are calculated from the currently visible records after month, kind, and search filters. Net movement is income minus expenses; category totals include expenses only. Every entry stays in the selected reporting currency, with the actual rate date available on converted records.

## Create, edit, delete, and import

Manual entries are validated before saving. Edits and deletes compare the revision read by the editor with the stored revision, preventing a stale screen from silently overwriting a newer change. An edit keeps the saved exchange rate when the original currency, transaction date, and reporting currency are unchanged; an amount correction is converted with that same saved rate. Editing notes or a merchant therefore works offline and cannot unexpectedly change the transaction's conversion. Each save request has a UUID and content fingerprint; the result receipt and changed record commit together, so retrying after an uncertain response does not add a duplicate.

CSV files are parsed locally, including quoted fields, escaped quotes, embedded newlines, and a byte-order mark. Date, amount, and merchant columns are required. Unsupported currencies, amounts, dates, kinds, and other invalid values remain visible for correction. Missing currency and kind receive an explicit warning. The Amex and Chase export headers are recognized separately: their US dates are normalized, their bank-specific amount signs are interpreted, sensitive member/account columns are ignored, repayments are skipped with a warning, and refunds/credits remain selected for review. Generic CSV files retain the conservative ISO-date and signed-expense behavior.

JPEG, PNG, WebP, and PDF files are size- and signature-checked on the device. Supported US Chase and American Express text PDFs have a local backend preview using PDF.js: positioned text, billing-period years, continuation pages, billed/foreign amount columns, posted fees and interest, and detail-to-summary reconciliation. Repayments are skipped with a count warning. Scanned PDFs, receipts and unsupported layouts have a separate explicit OpenAI action; local-preview failure never sends the document to AI automatically. The server returns editable candidates without storing documents or transactions. Network or recognition failures leave saved entries unchanged.

US card statement entries use billed USD so totals match the card statement; readable foreign purchase amounts/currencies remain in notes. Historical conversion translates billed USD to the reporting currency. Merchant refunds/credits are represented as income in the current two-kind model, with a review warning; they do not reduce category expense totals. Local parsing is bounded to 20 pages, 500,000 extracted characters and 200 output rows. An unsupported or unreconciled PDF produces an actionable error instead of a partial import.

Imports are limited to 5 MiB and 200 rows. Duplicate suggestions require the same direction, original amount and currency, dates within three days, and an exact or sufficiently similar identifying merchant name. These are suggestions only: the user can skip the row or explicitly keep a suspected duplicate. Selected rows are revalidated against the current ledger at commit time; either all selected, valid, acknowledged rows and the retry receipt are written in one SQLite transaction, or no rows are saved. Raw source documents are discarded after preview.

The dashboard starts with an **All time** view so a process restart does not hide historical entries behind the current-month filter. In selection mode, only visible rows can be selected; filters clear selection, and a confirmed batch delete uses revision compare-and-swap inside one SQLite transaction. A stale row or SQLite failure rolls back every deletion.

## Validation

- Pure ledger rules, decimal arithmetic, summaries, duplicate matching, and local CSV parsing: `frontend/app/src/test/java/com/example/cpen321application/LedgerDomainTest.kt`.
- Android SQLite persistence, reopen/offline behavior, failed FX rollback, idempotent retry, revision conflicts, duplicate review, and atomic imports: `frontend/app/src/androidTest/java/com/example/cpen321application/integration/LedgerPersistenceTest.kt`.
- Compose manual create/edit/delete, unsaved draft retention across activity recreation, and actual FileProvider content-URI CSV reading through preview, duplicate acknowledgement, and atomic import: `frontend/app/src/androidTest/java/com/example/cpen321application/e2e/LedgerUiTest.kt`. Tests use unique synthetic merchants and clean up only their own records and fixture files.
- Optional real emulator HTTP integration: `frontend/app/src/androidTest/java/com/example/cpen321application/integration/LedgerLiveFxTest.kt`. It skips unless the runner argument `ledgerLiveFx=true` is set; it makes no document-recognition or paid AI call.

Run JVM tests with `cd frontend && .\gradlew.bat :app:testDebugUnitTest`. The SQLite and Compose suites require an Android emulator or connected device and run with `cd frontend && .\gradlew.bat :app:connectedDebugAndroidTest`.

To exercise the actual emulator-to-backend FX connection, start the local backend, rebuild the APK with `API_BASE_URL=http://10.0.2.2:3000`, and run from `frontend/`:

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.example.cpen321application.integration.LedgerLiveFxTest" "-Pandroid.testInstrumentationRunnerArguments.ledgerLiveFx=true"
```

To run only the content-URI import UI flow without external services:

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.example.cpen321application.e2e.LedgerUiTest#csvContentUriRequiresDuplicateReviewBeforeAtomicImport"
```

### Verified results (2026-10-19; earlier live checks dated 2026-10-16)

| Check | Result | What was exercised |
| --- | --- | --- |
| Backend Jest | 107 tests passed across 8 suites | Existing backend behavior, ledger domain and CSV rules, HTTP validation, and simulated OpenAI/FX responses. These tests make no paid recognition calls. |
| Backend TypeScript and build | Passed | `npm run typecheck` and `npm run build`. |
| Android JVM | 26 tests passed | Local validation, exact money conversion, CSV parsing, duplicate suggestions, and summaries. |
| Android instrumentation | 19 tests passed, 1 opt-in test skipped | 13 persistence tests against real Android SQLite, 5 Compose flow tests including filtered bulk deletion and a fresh ViewModel, and 1 existing example test on a Pixel 9 API 36 emulator. |
| Android live FX integration | 1 opt-in test passed | Actual `LedgerHttpRemote` request from the emulator through the local backend returned USD/CAD 1.4411 for 2025-01-05, with effective rate date 2025-01-03. |
| Android builds | Passed | `assembleDebug` and `assembleDebugAndroidTest`. |
| Live image recognition | Passed | A synthetic PNG receipt was sent through the actual backend/OpenAI path; it returned one receipt total of CAD 14.00 dated 2025-01-05 for CEDAR TEST CAFE. |
| Live PDF recognition | Passed | The same synthetic receipt embedded in a one-page PDF was sent through the actual backend/OpenAI PDF path and returned the same single transaction: CAD 14.00, 2025-01-05, CEDAR TEST CAFE. |
| Live bank statement recognition | Passed | A synthetic PDF returned exactly three posted transactions with correct dates, merchants, currencies, and directions: CAD 25.00 expense, CAD 1,200.00 income, and CAD 42.35 expense. Opening/closing balances and summary totals were excluded. |
| Live historical FX | Passed | The actual backend/Frankfurter path returned a historical exchange-rate quote. |

The earlier 92-test baseline was also verified on Node.js 22.23.3. The current 107-test suite, typecheck and build pass on the host Node.js 24.18 runtime. The declared deployment line remains Node.js 22, now with minimum 22.13 because of PDF.js. Live external-service checks above used synthetic data on October 16; the October 19 real-file checks ran locally without OpenAI calls. Android lint also passes after fixing the local SDK property escaping; existing dependency-update/style warnings remain.

Local real-file validation: Chase PDF produced 40 candidates with 1 repayment skipped. Amex PDF and CSV each produced 26 candidates with 2 repayments skipped and 2 merchant credits retained. Each PDF reconciles purchases, payments/credits, fees and interest against its summary. The Amex date/amount/direction/currency multisets match across PDF and CSV. Private files are not committed as fixtures and were not sent to OpenAI.

The SQLite tests replace only the remote provider with a controlled fake. They verify real persistence across closing/reopening, offline edits retaining the transaction's saved FX snapshot, stale-revision conflicts, explicit duplicate decisions, retry idempotency, and rollback after injected SQL or quote failures. They do not establish live external-service availability. The separate live checks above verify those external integration paths using synthetic data; they do not establish recognition accuracy for arbitrary receipts or bank statements.
