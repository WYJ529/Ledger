# Personal and group entry sharing (Issue #66)

Sharing is an explicit, one-time copy. Personal records remain in the installation's SQLite ledger; group expenses are stored in the authenticated backend. Later edits or deletions do not automatically propagate between them.

## Personal ledger to groups

1. Sign in and create or join a group.
2. In **My Expenses**, choose **Share to groups** on an expense, or select several records and use the same action.
3. Select one or more eligible groups, review the entries, and choose **Share selected**.

Each record becomes a separate expense in each selected group. You are the payer and creator, and the amount is split equally among the group's current members using its existing rounding rules. Date, category, merchant, and notes are copied; the confirmation explains that these details become visible to members. The group list/card shows the original date and category, with notes under **View split**.

Only expenses are supported. All selected records must have the same original currency as a selected group, and each amount must be at most 100,000,000 minor units. Income and currency conversion for sharing are not supported. A group that does not meet these rules cannot be selected. If you have no groups, the dialog explains that you must create or join one first.

Progress is shown for every group. If one request fails, completed copies remain saved and retry submits only unfinished targets. The backend also deduplicates by authenticated account, group, and original record ID, including concurrent retries. Re-sharing a changed original to the same group returns a conflict and directs you to edit the saved group expense. Repeating the original snapshot returns the existing expense. Sharing across several groups is not an atomic transaction.

## Group expense to personal ledger

1. Open **Group**, select a group, and choose **Import my share** on an expense with a positive allocation for you.
2. Review the amount and date, then choose **Confirm import**.
3. Return to **My Expenses** to see the updated ledger and totals.

The server uses your authenticated identity to calculate the import. It returns your allocated amount, not the total bill or the amount paid by you. This works for equal, custom, percentage, and finalized receipt splits. An ordinary group expense uses its creation date, category **Other**, and empty notes; an expense shared from the personal ledger retains the original date, category, and notes. The current group description is used if it has been edited.

The dialog re-fetches your allocation before writing. If the preview changes, it asks you to review and confirm again. If this is your own original personal record on this installation, the dialog explicitly warns that it will replace the original amount with your share to avoid counting the same expense twice. An intervening local edit prevents replacement and asks you to reopen the dialog. If the original is absent, import creates a new record. The original can be replaced only once: if it was shared to several groups, importing another group's independent share creates a separate record and retains the first import.

An import is remembered once per account, group, and expense. Its record and retry receipt are committed in one SQLite transaction. Repeated imports are no-ops, including after app restart or deleting the imported local record. A later group edit does not refresh an already imported snapshot. Conversion to the personal reporting currency uses the existing historical quote/cache workflow; a conversion or storage failure leaves the import retryable.

## Scope and access

- Both directions require an active session and current membership in the destination/source group. A deleted group or revoked membership is rejected by the server.
- Group-to-personal requests return only the authenticated member's positive share; callers cannot nominate another member.
- Personal records belong to this installation, not a signed-in account. Switching accounts does not migrate or isolate the local ledger; import deduplication does include the account ID.
- The group screen currently shows its latest 50 expenses. Import actions are available for displayed expenses; this feature does not introduce history pagination.
- English, Simplified Chinese, Traditional Chinese, and French UI strings are provided.

## API and deployment

- `POST /api/groups/:groupId/shared-entries`: `{ sourceId, input }`, where `input` uses the personal ledger transaction schema. Returns `{ expense, alreadyShared }`.
- `GET /api/groups/:groupId/expenses/:expenseId/personal-entry`: returns `{ input, groupName, expenseId, sourcePersonalId }`. Only the expense creator receives the source personal ID through this import endpoint.
- Both routes run behind the existing bearer-session and group-membership guards. The ordinary expense-create endpoint reserves the `personal-entry-` request ID prefix.
- Deploy/restart the updated backend before using the new Android actions. The existing expense request-ID unique index handles sharing deduplication. Optional personal snapshot metadata is backward-compatible with existing MongoDB records; the existing SQLite request table is reused, so no database version bump is needed.

## Verification

Backend tests cover authentication/membership, group isolation, equal allocation, metadata, duplicate and concurrent requests, different groups/accounts, validation, and existing/finalized bills. Android tests cover group selection, partial retries, preview/confirmation, changed allocations, expired sessions, persistence, replacement, transactional rollback, and historical FX failures. Existing ledger and group UI tests provide regression coverage.
