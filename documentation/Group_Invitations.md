# Group creation and invitations

## User flow

1. Sign in as Joy. Open **Create group** from Home, Account or Group expenses.
2. Enter a required group name (up to 60 characters), optional description (240)
   and currency (CAD/USD/EUR/CNY). Joy becomes the owner automatically.
3. The group expense page opens and an invitation dialog appears automatically.
   **Copy link** copies the URL; **Share** opens Android's share chooser.
4. Another user opens the invitation in Ledger. If signed out, they first sign
   in/register; the invitation is retained. The confirmation page says who invited
   them and shows the group name, description and currency.
5. **Accept** joins as a member and opens that group's expense page. Repeated
   acceptance never duplicates membership. **Reject — return home** goes home
   without joining or invalidating the invitation for other people.
6. **Members** shows names and Owner/Member roles. The plus button there or beside
   Members generates another invitation attributed to the person inviting.

Every member can invite and rename the group. Only the owner sees **Delete group**;
the API also enforces ownership. Delete asks for confirmation, hides the group for
all members and stops its invitation links. Stored group/expense records are kept
behind a deletion marker rather than physically removed. The original expense
edit rule remains: expense creator or group owner can edit.

Invitation links are reusable for 7 days and groups are limited to 100 members.
The database stores a hash of the random invitation token. Creating a group uses
a submission ID, so retries after an uncertain network result do not create a
second group. Currency cannot be changed through rename.

## Local Android Studio test

From the repository root, update the backend first:

```text
docker compose up --build -d backend
```

Then stop and run the app in Android Studio. Both test accounts/devices must use
the same backend/database. An emulator on the backend PC can use
`API_BASE_URL=http://10.0.2.2:3000` in `frontend/local.properties`.

1. Joy creates a group. Confirm the invitation dialog opens automatically; copy
   the link before logging out. Check Members lists Joy as Owner.
2. Log out and sign in as Alex (or use a second emulator). Open **Group expenses →
   Join group**, paste the link, and Continue. This avoids messaging apps that do
   not make custom-scheme URLs clickable.
3. Check the inviter/group name. Reject and confirm the group is absent from
   Alex's group list. Open the same link again and Accept: the group expense page
   should open and Members should show Alex as Member.
4. As Alex, generate a link using plus. Its preview should say Alex invites you.
   Rename the group and check Joy sees the new name after Refresh. Alex should
   have no Delete group action.
5. Accept the invitation again: the membership count must stay unchanged. Add an
   expense with the new member selected and check the saved split/summary.
6. Return as Joy, delete a disposable test group and confirm it disappears for
   Alex after Refresh. Its previous link should report unavailable.
7. Test a signed-out deep-link launch: save a copied link, log out, open it through
   an Android app that supports `ledger://` links, sign in, and confirm the
   invitation appears before joining. For an emulator, Android Studio's terminal
   can launch it without altering account data:

   ```text
   adb shell am start -W -a android.intent.action.VIEW -d "ledger://invite/PASTE_TOKEN_HERE" com.example.cpen321application
   ```

## Clickable links across phones

Default links use `ledger://invite/<token>` and require Ledger to be installed.
Link handling varies between messaging apps; pasting into Join group always uses
the same authenticated preview/accept flow. Backend requests use the configured
API origin, never the host from a pasted invitation URL.

For normal HTTPS sharing, deploy the updated backend at a reachable HTTPS origin:

- In `backend/.env`, set `PUBLIC_APP_URL=https://your-real-domain.example`.
  Future links use `/invite/<token>` at that origin. The public fallback page
  shows inviter/group name and **Open in Ledger**; it never joins automatically.
- In `frontend/local.properties`, set `APP_LINK_HOST=your-real-domain.example`
  (host only) and `API_BASE_URL=https://your-real-domain.example`, then rebuild.
- Configure `ANDROID_SHA256_FINGERPRINTS` on the backend with the installed APK's
  SHA-256 signing certificate fingerprint (comma-separated if more than one).
  `gradlew signingReport` shows the debug certificate. Release builds need their
  actual release/Play signing certificate.
- Serve `/.well-known/assetlinks.json` over HTTPS at the same domain without
  redirects. The backend supplies that route once fingerprints are configured.
  Android must verify the domain before HTTPS links open the app directly.

No real public domain or signing fingerprint is preconfigured. The default host
`ledger.invalid` is a placeholder. A local emulator address is not reachable by
other phones on the internet. A second physical device needs a backend address
it can reach; both clients must point to that same backend.

See Android's official [deep link documentation](https://developer.android.com/training/app-links/create-deeplinks)
and [App Links verification documentation](https://developer.android.com/training/app-links/verify-applinks).

## API

All `/api/groups` routes require a bearer session and return `Cache-Control: no-store`.

| Method | Route | Purpose |
| --- | --- | --- |
| POST | `/api/groups` | Create with name, optional description, currency and requestId; returns group and invitation. |
| GET | `/api/groups/invitations/:token` | Preview without joining. |
| POST | `/api/groups/invitations/:token/accept` | Add current user as member, idempotently; return group. |
| POST | `/api/groups/:groupId/invitations` | Any member generates a fresh invitation. |
| PUT | `/api/groups/:groupId/name` | Any member renames using `{ "name": "..." }`. |
| DELETE | `/api/groups/:groupId` | Owner only; mark deleted, return 204. |

Malformed input returns 400, missing sessions 401, a member deleting as nonowner
403, inaccessible/deleted groups or expired invites 404, and a full group or
conflicting submission-ID reuse 409. Invalid public URL configuration returns 503
before group creation. Public GET `/invite/:token` provides the HTML fallback.

Automated verification covers real MongoDB persistence, role enforcement,
concurrent create/accept retries, atomic member limits, invalid/expired links,
deletion, escaped HTML and deployment settings. Android JVM tests cover invitation
URL parsing; debug and instrumentation APK compilation are checked. The new
multi-account/deep-link flow still needs the manual Android Studio walkthrough.

Verification for this change: backend typecheck/build and all 115 tests passed;
Android debug APK, all 19 JVM unit tests and instrumentation APK compilation
passed. No emulator installation or multi-account device run was performed in
this change, preserving the currently running emulator's test data.
