# Group expenses — issues #16, #17 and #19

Based on `feature/account-auth`. The Android home page now has **Group expenses**.
Signed-in group members can record an expense using equal, custom, percentage or equal-plus-adjustment shares, then
read the group's total spending, allocated shares and the latest 50 expense details.
Totals include all saved expenses. Pulling fresh data uses the Refresh buttons.

## Percentage splitting

The expense form offers **Equal split**, **Custom amounts**, and **Percentage**.
In Percentage mode, enter 0–100% for every selected participant, with at most two
decimal places. The percentages must add up to exactly 100%; the app previews the
resulting money amounts and enables saving only when the inputs are valid.

Both Android and the backend calculate with integer cents and integer basis points
(0.01%). They floor each proportional amount, then allocate remaining cents to the
largest fractional remainders, breaking ties by ascending member ID. For example,
CAD 10.00 at 33.33%, 33.33%, and 33.34% yields CAD 3.33, 3.33, and 3.34. A selected
0% participant remains in the split and receives no rounding cent.

The backend computes canonical amounts rather than trusting submitted amounts.
Stored shares retain `percentageBasisPoints`, and expanded bill details display
both percentages and amounts. Editing pre-fills the original percentages; changing
the total recalculates amounts using those same percentages. Percentage changes
are saved even if a tiny bill happens to round to unchanged cents. Existing equal
and custom expenses require no migration, and switching back to either mode removes
the previous percentage metadata.

Update/restart the backend and rebuild the Android app together before testing this
option. An older backend does not accept `splitMode: "percentage"`.

Validation for this addition: backend typecheck and production build passed; the
full backend suite passed all 289 tests. After simplifying retry comparison to
compare share fields directly, the 34 group-expense API regression tests passed
again. Android passed all 45 JVM tests and built both debug and instrumentation
APKs. Three new Compose tests cover percentage validation/preview, edit prefill,
and bill details; they compiled but were not run because no device was connected.

## Equal split with adjustments

**Equal + adjustments** is for the common case where most of a bill is shared
evenly but someone ordered something extra, or should pay a little less. Each
selected member has an optional signed adjustment, for example `8` for an extra
drink or `-2` to pay less; blank means 0.

The adjustments are set aside, the rest of the bill is split equally (extra cents
go to members in ascending member-ID order, exactly as in an equal split), and then
each member's adjustment is added back. For example, CAD 40.00 among three members
with +8.00 for the second member: the remaining 32.00 is split 10.67 / 10.67 / 10.66,
and that member pays 10.67 + 8.00 = 18.67. A deduction is shared by everyone else.

- The added amounts may not exceed the bill total, and a deduction may not make any
  share negative; the form explains which rule failed and the server returns 400.
- Android and the backend use the same integer-cent calculation; the form previews
  each member's amount, and the backend recomputes it rather than trusting the client.
- Stored shares retain `adjustmentCents`, bill details show "includes +8.00", and
  editing pre-fills the adjustments. Changing the total keeps the adjustments and
  re-splits the remainder. Switching to another mode removes the adjustment metadata.
- Request replay compares adjustments as well as amounts, so reusing a request ID with
  a different adjustment returns 409.

Validation for this addition: backend typecheck/build and all 320 backend tests passed
(unit cases for parsing, rounding, ordering and limits; real-MongoDB create, replay,
rejection and edit cases). Android passed all 49 JVM tests, and the 8 existing group
expense Compose tests passed on a Pixel 3a API 36 emulator. On the emulator, a CAD 40.00
bill with +8.00 and -2.00 adjustments previewed and saved 11.34 / 19.33 / 9.33, showed
the adjustments in bill details, and pre-filled them when edited.

## Correcting an existing expense

- The expense creator and group owner see **Edit expense** on the bill card;
  other members can view its details. The API enforces the same permission.
- Editing pre-fills the description, total, payer, split mode, participants and
  saved shares. **Save changes** updates that record and refreshes the summary.
- Changing a custom bill's total requires reallocating its shares to match.
  Going back before saving discards the form changes.
- Edits retain the original bill ID, creator and creation time. They do not
  increase the expense count. The server records the latest editor separately.
- A version check prevents a stale form from overwriting another user's edit.
  On a conflict, go back, refresh the group and reopen the expense. Retrying an
  already-applied identical edit does not write another version.
- Existing records require no manual migration. When using Docker, rebuild the
  backend to expose the edit endpoint and permissions before installing the new app:

  ```text
  docker compose up --build -d backend
  ```

Manual check: edit a saved CAD 10.00 bill to CAD 12.00; verify the same bill ID and
expense count, a 2.00 increase in group spending, correct new member shares, and
persistence after reopening the app. Also try canceling an edit and submitting
custom shares whose sum does not match the new total.

Edit verification: backend typecheck/build and the full 105-test suite passed,
including creator/owner permissions, invalid edit rejection, recalculated totals,
concurrent edit conflicts and old records without a version field. Android debug
build and all 17 unit tests passed. The added edit-form device test compiles; it
has not been executed on a device during this follow-up.

## Scope and integration

- #16: select participating group members; divide the bill in integer cents. Extra
  cents go to member IDs in ascending order, independent of selection order. A tiny
  bill can give some members a zero-cent share.
- #17: enter each selected member's exact amount (zero is permitted). The server
  requires the shares to add up exactly to the positive total.
- #19: show persisted spending totals, each member's allocated expense share,
  payer, creation date, split mode and expandable per-bill shares.
- Group creation, member roles and invitation links are now implemented; see
  [Group invitations](Group_Invitations.md) for setup and testing. #18 (balances,
  repayments and paid/unpaid status) remains deferred. Allocated expense shares
  are **not debt balances**.
- Each group has one currency: CAD, USD, EUR or CNY. These currencies use two decimal
  places. This module does not convert currencies. Each bill is limited to
  1,000,000.00 and 100 participants.
- Google and password accounts both use the existing bearer session. Identity
  comes from the session, not a caller-supplied creator ID. Only current members
  can list, read or add group expenses; nonmembers receive 404 for a group resource.

## Try it locally

### Android name, icon and navigation

- The launcher name and home branding are now **Ledger**, using the approved
  teal-and-ivory ledger icon for adaptive, round and density-specific launcher resources.
- Sign-in/sign-up fields show `Name *`, `Email *`, `Password *` and
  `Confirm password *`, with a `* Required fields` hint. Password policy is unchanged.
- The group flow has one on-screen back button, using the same route as Android
  back: new expense → group summary → group list → home. The direct home shortcut
  is no longer shown while editing or viewing a group's expenses.
- Stop and run the app again in Android Studio to install the updated name/icon.
  Check both the on-screen back button and the system back gesture from Add expense.
- M1 Server info, Live Updates and Timer entries/routes are now commented out,
  including the countdown effect and recipe navigation. The source is retained.
  Future bottom navigation and email verification plans are recorded in
  [Ledger backlog](Ledger_Backlog.md).

1. Follow the root README to start MongoDB, configure `backend/.env` and run the
   backend. The group/expense collections and indexes initialize at startup.
2. Register the accounts you want to use through the app (email/password or Google).
3. Use **Create group** on the home/account page. The creator is the owner;
   other accounts join as members by accepting an invitation. For optional test
   fixtures, the legacy development CLI remains available in `backend/`:

   ```text
   npm run seed:expense-group -- "Roommates" CAD joy@example.com friend@example.com third@example.com
   ```

   The first account is the owner. All accounts must already exist. Repeating this
   command with the same name, currency, owner and membership reuses the group.
   This is a trusted developer CLI, not an invitation or a public create-group API.
4. In `frontend/local.properties`, point `API_BASE_URL` at this updated backend.
   For an Android emulator and a backend on the same PC, use `http://10.0.2.2:3000`.
   A previously deployed backend will not have the new endpoints until updated.
5. Sign in, open **Group expenses**, refresh, choose the group and tap **Add expense**.
   Enter a description, total, payer, participants and split mode. Save, inspect
   the summary, then reopen the app or sign in as another member to confirm persistence.

The initialization CLI is optional. App/API creation always assigns the current
account as owner, while invitation acceptance adds the signed-in account as a
member. Groups support up to 100 members and retain their original currency.
Deleting a group marks it deleted and blocks access and invitations; stored
expense history is retained. Leaving/removing members is outside this change.

## HTTP interfaces

All routes require `Authorization: Bearer <session token>` and use `Cache-Control: no-store`.

| Method | Route | Behavior |
| --- | --- | --- |
| GET | `/api/groups` | List the caller's groups (`id`, `name`, `currency`). |
| GET | `/api/groups/:groupId/summary` | Group and roster, total cents, expense count, allocated member shares and latest 50 expenses. |
| POST | `/api/groups/:groupId/expenses` | Validate, calculate and persist one expense. |
| PUT | `/api/groups/:groupId/expenses/:expenseId` | Creator/owner updates a bill using its expected `revision`. |

Example equal split submission:

```json
{
  "description": "Dinner",
  "amount": "10.00",
  "paidBy": "<registered group member ID>",
  "splitMode": "equal",
  "shares": [{ "userId": "<member ID>" }, { "userId": "<another member ID>" }],
  "requestId": "<UUID for this submission>"
}
```

For custom splitting, set `splitMode` to `custom` and add a decimal **string**
`amount` to every share. Amounts are validated on the server; client previews are
not trusted. Responses contain integer `amountCents`. Currency is taken from the
group and creator from the session. The payer need not participate in the split.

For percentage splitting, set `splitMode` to `percentage` and supply `percentage`
as decimal text for each selected member. For example:

```json
{
  "description": "Dinner",
  "amount": "10.00",
  "paidBy": "<member A ID>",
  "splitMode": "percentage",
  "shares": [
    { "userId": "<member A ID>", "percentage": "60" },
    { "userId": "<member B ID>", "percentage": "40" }
  ],
  "requestId": "<UUID for this submission>"
}
```

The response shares contain `amountCents` (600/400) and `percentageBasisPoints`
(6000/4000). Request replay compares the percentages as well as the rounded amounts;
reusing a creation request ID with changed percentages returns 409.

For an equal split with adjustments, set `splitMode` to `adjusted` and give each
selected member an optional signed decimal-text `adjustment` (`"8"`, `"+8.00"`,
`"-2.50"`; omitted or `""` means 0). For example, `"amount": "40.00"` with shares
`[{ "userId": "<A>" }, { "userId": "<B>", "adjustment": "8" }, { "userId": "<C>" }]`
returns `amountCents` 1067 / 1867 / 1066 and `adjustmentCents` 0 / 800 / 0.

The unique `(groupId, createdBy, requestId)` index prevents duplicate saves when a
request is retried after a timeout. First save returns 201; an identical retry
returns the saved expense with 200; reuse with different content returns 409.
The Android form retains its request ID across rotation and retry. After a network
failure, retry the same form; if you cancel instead, refresh the group to check
whether the expense was already saved before entering another bill.

Editing uses the same full description/amount/payer/split payload and adds the
`revision` from the summary response. The server whitelists editable fields;
caller-supplied creator, group and currency cannot move the record or change its
ownership. Summary bills expose `canEdit` and `revision`. Invalid edit input
returns 400, insufficient edit rights 403, missing/inaccessible resources 404,
and conflicting concurrent changes 409.

## Verification

```text
cd backend
npm ci
npm run typecheck
npm run build
npm test -- --runInBand
```

The real MongoDB integration tests use `mongodb-memory-server`; the first run may
download a MongoDB binary. They test membership isolation, session revocation,
all three split modes, persistence through a new app instance, malformed input,
concurrent submission retries, and totals beyond the recent-expense display limit.

```text
cd frontend
./gradlew :app:assembleDebug :app:testDebugUnitTest
```

`BillSplittingTest` verifies exact decimal parsing, deterministic remainder handling,
small bills, total preservation and money formatting. Device UI tests cover the
group summary and expense form without depending on Google account availability.

Run those device tests with a booted emulator:

```text
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.example.cpen321application.e2e.GroupExpensesUiTest
```

Initial implementation verification: backend typecheck/build and all 99 tests passed (Node
24.11.1 on this workstation; the repository's deployment target remains Node 22).
Android debug build, all 17 unit tests and all 3 group-expense device UI tests
passed on the Pixel 9 / API 36 emulator. Google sign-in was reused and was not
manually exercised with a live Google account in this change.
The headless emulator displayed a System UI unresponsive dialog during screenshot
capture; the Compose assertions passed, but a clean manual visual walkthrough on
your normal emulator/device is still recommended before merging.

Android branding/navigation follow-up: the updated debug APK, all 17 Android unit
tests and instrumentation test compilation passed. APK metadata reports `Ledger`
and the new adaptive launcher resource. A new navigation regression test checks
the on-screen back button and system back behavior. Its device run on the spare
Pixel 6 / API 37 failed during Espresso initialization (`InputManager.getInstance` not
found), before executing the navigation assertions; rerun it on the supported
Pixel 9 / API 36 image. The repository's pinned test dependencies were preserved.
