# Feature 3 local acceptance record

Session date: **27 October 2026**, as supplied by the client. The finalized expense was persisted at **2026-10-28T02:02:05.356Z**; the emulator displayed 28 October in its UTC-based clock. Persisted timestamps were not altered.

This records completion of requested local-testing steps **4, 5 and 6**. No cloud backend deployment, Git commit or push was performed.

## 4. Local Android build and installation — passed

- Updated ignored `frontend/local.properties` with `API_BASE_URL=http://10.0.2.2:3000` and `APP_LINK_HOST=ledger.invalid`, preserving the installed Android SDK path.
- Used the existing local backend on port 3000 and MongoDB on port 27017. Existing database and personal-ledger data were preserved.
- Built with JDK 17 and the cached Gradle 8.14.5 distribution, offline. `testDebugUnitTest`, `assembleDebug`, `assembleDebugAndroidTest` and `lintDebug` completed successfully.
- **46 Android unit tests passed**, with zero failures, errors or skipped tests. Lint reported **0 errors, 43 warnings and 8 hints**.
- Installed the new debug app on both API 36 emulators:
  - `emulator-5554`: `Pixel_9_API_36`, visible owner device.
  - `emulator-5556`: `Pixel_9_Pro_-_M1_APK_Test`, temporary read-only member device.
- APK: `frontend/app/build/outputs/apk/debug/app-debug.apk` (13,723,090 bytes).
- APK SHA-256: `48005C6663A44091D18ADB281B5B05D90E2872976C7AB449C29667CAB191732F`.

The local backend currently uses the installed Node 24.18.0; the repository declares Node 22. This run establishes behavior in the local environment, not validation of a different release runtime.

## 5. Android UI regression tests — passed

The selected instrumentation run finished with **OK (20 tests)** in **51.355 seconds**:

| Suite | Passed |
| --- | ---: |
| `GroupLiveSplitUiTest` | 7 |
| `GroupSplitPendingStorageTest` | 1 |
| `GroupExpensesUiTest` | 5 |
| `AppNavigationUiTest` | 7 |

An emulator System UI not-responding dialog initially caused two navigation tests to lose window focus. After dismissing the dialog and recovering System UI, the entire 20-test selection passed. No application or test-code changes were needed for this issue.

Final instrumentation log: `frontend/app/build/local-feature3-instrumentation.log`. Unit XML results and lint reports remain under `frontend/app/build/test-results/` and `frontend/app/build/reports/`.

## 6. Two-account, two-emulator acceptance — passed

Two dedicated local test accounts were registered through the local API, then signed in through the Android login screens. The following business actions were performed through the actual Android UI; authorized backend snapshots were used to verify persisted records.

Group: **Local Dutch Test 2026-10-27**. Receipt: **Local Coffee Receipt**. Input: two Coffee units at CAD 4.50 each plus one Tax unit at CAD 1.00. The owner reviewed the CAD 10.00 receipt before starting the split.

| Check | Observed result |
| --- | --- |
| Group invitation | Owner created the group and invitation; member accepted through Android and appeared in the two-member roster. |
| Live receipt delivery | Both devices displayed the same receipt and authenticated Live status. |
| Competing claims | Concurrent Claim taps targeted the same first Coffee unit. Only the owner acquired it; the member received the stale-session conflict and both displayed the same single assignment at version 1. |
| Release and reclaim | Owner released the unit; both devices showed it available at version 2. Member reclaimed it at version 3. |
| Backend restart | Stopped the repository's running backend and watcher, then restarted `npm run dev`. During downtime both devices showed Reconnecting, retained the committed claim and disabled mutation controls. Both recovered to Live with the same version-3 assignment. |
| Complete allocation | Owner claimed the remaining Coffee and Tax. Assigned total became CAD 10.00, with CAD 0.00 unassigned and all three units claimed. |
| Finalization | Owner finalized through Android. Both devices displayed finalized units. The backend held one linked expense, session version 6 and sequence 7. |
| App cold restart | Force-stopped and relaunched both apps without clearing data. Both showed one expense, two members and CAD 10.00 total group spending. |
| Exact shares and immutable UI | Owner share remained CAD 5.50; member share remained CAD 4.50. Expanded View split showed both values on both devices. The generated expense had no Edit action, including for the owner. |

Persisted identities, recorded for inspecting this local test data:

- Group: `6ae1555e26abec57a11dd357`
- Split session: `6ae1568826abec57a11dd35a`
- Final expense: `6ae1579de11ff6c0193fbbf5`
- Owner: `6ae1282726abec57a11dd352`, allocated **550 cents**
- Member: `6ae1282726abec57a11dd354`, allocated **450 cents**

The final authorized snapshot contains exactly **one expense**, amount **1000 cents**, linked from the finalized session. Its shares sum to the receipt amount.

## Evidence and continuing locally

Local generated evidence is in the ignored `frontend/app/build/` directory:

- `local-feature3-instrumentation.log`: successful 20-test run.
- `local-dutch-race.json`: competing-claim screen observations.
- `local-dutch-snapshot.json`: final persisted session, units, expense and shares.
- `local-dutch-owner-after-restart.png` and `local-dutch-member-after-restart.png`: group totals after app restarts.
- `local-dutch-owner-expense.png` and `local-dutch-member-expense.png`: expanded expense shares after app restarts.
- `local-dutch-tools.cjs`: local ADB/UI and snapshot helper used in this session.

Test credentials and tokens remain only in the ignored local test-state file; they are not reproduced in this report. Build cleanup can remove these temporary artifacts.

The local backend was left running. The final `node scripts/check-backend.mjs http://127.0.0.1:3000` check passed for health, account, group and ledger routes. The visible owner emulator remains signed in; open **Group → Local Dutch Test 2026-10-27** to inspect the result. No fresh APK installation is needed on these two emulators.

## Limits

- This run used two Android emulators and one local backend process. It does not establish the 20-member/200-unit performance targets or multi-instance event delivery.
- Backend restart recovery exercised already committed claims. A deliberately lost mutation response and the Android uncertain-action retry were not injected in this acceptance run; pending storage and backend retry behavior have separate automated coverage.
- The earlier 221-test backend result is retained in the implementation document; the full backend suite was not rerun as part of these Android/local-acceptance steps.
- No production backend or physical-phone validation is claimed.
