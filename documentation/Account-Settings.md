# Me and account settings

Me uses Ledger's existing Material theme and card components. The profile card shows the current name, email and avatar. Tapping the avatar offers **Take a picture** and **Choose from a library**. **Edit** opens the account settings form with **Save changes**.

## Account data

- Editable fields: full name, email address, optional phone number, password, IANA time zone and default currency.
- Profiles and resized avatars persist in MongoDB via authenticated `PATCH /api/users/me`. The endpoint accepts only explicitly editable fields; it never accepts a target user ID. Existing accounts default to UTC and CAD.
- Email or password changes require the current password. Google-only accounts reconfirm the same linked Google identity and may add a Ledger password. A single atomic account write advances the session version and preserves the session making the change. HTTP and live-session lookups reject earlier versions, including a delayed login that verified the old credentials before the change; expired records are removed by MongoDB's existing TTL index.
- Phone numbers are contact information only. This feature does not implement SMS verification or change the existing email ownership verification behavior.
- Default currency and time zone initialize new personal transactions. They do not reconvert existing transactions or change the personal ledger's reporting currency.
- Photos are read with a 20 MiB input limit, oriented using EXIF, center-cropped and resized to at most 384 pixels, then re-encoded as JPEG without original metadata. Encoded avatars are limited to 180,000 characters. Profile request bodies have a 192 KiB limit.
- Cancelling the picker or an unsaved form leaves the saved profile unchanged. Credentials are kept out of saved instance state.

## Notifications (mock)

Preferences persist per account on the current device. No push SDK, notification permission, scheduling or delivery is enabled. The UI labels this as a preview.

| Setting | Initial value | Purpose |
| --- | --- | --- |
| Allow notifications | On | Master preference; disabling it preserves individual choices |
| Expense activity | On | Shared expenses added, edited or deleted |
| Payments & settlements | On | Recorded payments and settlements |
| Group invitations | On | New invitations |
| Balance reminders | Off | Unsettled balance reminders |
| Weekly summary | Off | Spending and shared balance summary |

## Device security

The biometric lock uses AndroidX BiometricPrompt and the fingerprint/face methods supported and enrolled on the device. Successful biometric authentication is required both to enable and to disable it. Cancellation, unavailability and errors do not unlock the app.

The device-level setting protects the entire app, including guest expenses after logout. It is enforced on cold launch and after the Activity is stopped in the background. While locked, app content and dialogs are removed from composition/accessibility. The secure-window flag prevents app content appearing in screenshots or the recents preview while protection is enabled. No PIN fallback is supplied by Ledger. If enrollment is removed, restore biometric enrollment in device settings to regain access. Lock preferences are excluded from backup/device transfer.

## Help, ratings and logout

`LEDGER_SUPPORT_URL` in `frontend/local.properties` defaults to this repository's new-issue page. Contact Us opens it in a browser without submitting any message. Set a dedicated support page when available.

`LEDGER_STORE_URL` is deliberately blank until there is a real HTTPS store listing. Rate Ledger shows an explanatory dialog and a Contact Us option in that case. Configure the actual listing and rebuild to activate the rating destination.

Logout retains the confirmation dialog and clears the saved session and Google account choice. The server session is invalidated when reachable; offline logout still removes the local session. Local personal transactions are retained.

## Running the change

Restart or redeploy the updated backend and rebuild the Android app together. An already deployed older backend will not serve profile writes. No cloud deployment or store publication is performed by this change.

Focused tests: `backend/tests/mock/accountSettings.test.ts`, account authentication tests including real MongoDB persistence, Android `AccountValidationTest`, `AccountSettingsUiTest`, and `AppLockTest`. The latter uses deterministic biometric callbacks to test lock enforcement; hardware face/fingerprint acceptance still needs validation on physical devices.

Verified on 2026-10-28: backend TypeScript checking and 68 focused backend tests passed; all 47 Android unit tests passed; 18 emulator tests covering settings, navigation, avatars and lock enforcement passed. The debug APK build and Android lint passed, and targeted Detekt analysis of the six account/lock implementation files reported no findings.
