# Ledger

Ledger is a native Android app for personal accounting and shared group expenses. The app opens **My Expenses**, with bottom navigation for **My Expenses**, **Group**, and **Me**. Personal expenses are available before signing in; opening Group or Me while signed out shows sign-in with a **Use without Login** button. Successful sign-in returns to My Expenses, except when continuing a group invitation. Create groups within Group; Me shows account details and logout. All screens share the existing teal/ivory Material theme.

This stage uses a **local guest ledger**: transactions live in Android SQLite on the current installation, with no login, group sharing, account migration, or cloud synchronization. Manual entries in the reporting currency and CSV imports in that currency work offline. Historical quotes can be reused offline after they have been cached. Clearing app data or uninstalling the app removes the guest ledger; its database is excluded from Android backup and device transfer. See the [Feature 1 design and data behavior](documentation/Feature1-Ledger.md).

## Cloud Backend

The current shared backend is **https://136-67-157-106.sslip.io** (public IP **136.67.157.106**).

To build an APK against it, preserve `sdk.dir` in your ignored `frontend/local.properties` and set:

```properties
API_BASE_URL=https://136-67-157-106.sslip.io
APP_LINK_HOST=136-67-157-106.sslip.io
```

Rebuild the APK after changing these values. Local development uses `http://10.0.2.2:3000` for the emulator instead. HTTPS invitation links and Google sign-in also need the installed APK's signing certificate configured; see the signing instructions below and the [cloud deployment guide](documentation/Google_Cloud_Deployment.md).

The cloud backend supports accounts, groups, expense splitting, historical exchange rates and supported text-PDF statement parsing. Paid OpenAI recognition is not enabled on this deployment. Personal records remain device-local; deploying the backend does not synchronize them to an account.

## Group Expenses

Open **Group** in the bottom navigation and sign in. Create a group, copy an invitation, and let another user paste it into **Join group**. Members can record expenses using equal or custom exact-amount splits, view the saved allocations, and refresh to retrieve other members' changes. Allocated shares are not repayment balances.

- [Group creation, invitations and permissions](documentation/Group_Invitations.md)
- [Group expenses, editing and split calculations](documentation/Group_Expenses.md)

## Local Development

If registration shows **Not Found**, the app may be connected to an outdated backend that still returns a successful `/health`. From the repository root run `node scripts/check-backend.mjs http://localhost:3000` to check the account, Google registration, group and ledger routes without creating accounts. Stop the old process on port 3000, then start the current backend with MongoDB using `npm run dev` from `backend/`, or rebuild Docker with `docker compose up --build -d`. `npm start` now rebuilds TypeScript first; an already running process must still be restarted after merging branches. Keep the backend's `GOOGLE_CLIENT_ID` equal to the frontend Web OAuth client ID. Google account creation uses **Finish creating your account** and asks for a display name; the email/password form is the separate password-registration flow.

For emulator-only development, set `HOST=127.0.0.1` in the ignored `backend/.env`; the emulator reaches this through `10.0.2.2`. Docker Compose overrides the container binding to `0.0.0.0`. Android uses the team's public Web client ID when the local value is missing, blank, or still starts with `your_`; the backend must also use the matching real client ID. No private OAuth secret is needed.

1. Install Node.js 22.13 or newer in the Node.js 22 line, npm 10+, Android Studio, JDK 17, and Android SDK Platform 35. The Android minimum version is API 26. Open `frontend/` in Android Studio and let Gradle sync.
2. Preserve Android Studio's `sdk.dir` in `frontend/local.properties` and set `API_BASE_URL=http://10.0.2.2:3000` for an Android emulator. This address reaches the host computer; do not use emulator `localhost`. A physical device needs the host's reachable LAN address instead. The URL is baked into the APK, so rebuild after changing it. Release builds require an HTTPS backend URL. `GOOGLE_CLIENT_ID` is needed for Google account sign-in and must match the backend.
3. For historical exchange rates or document recognition, start the backend. From the repository root in PowerShell, copy the environment template **only if `backend/.env` does not already exist**, then run:

   ```powershell
   if (-not (Test-Path backend/.env)) { Copy-Item backend/.env.example backend/.env }
   Set-Location backend
   npm ci
   npm run dev
   ```

   Keep `PORT=3000` and `NODE_ENV=development`. The combined backend requires a running MongoDB and a valid `MONGODB_URI` for accounts and groups (see Backend Setup below); no JWT secret is used. The ledger routes remain stateless guest services. Historical rates use Frankfurter/ECB and need no API key. Image/PDF recognition is optional: set `OPENAI_API_KEY` in **backend/.env only** and leave `OPENAI_MODEL=gpt-4.1-mini` or choose a compatible image/PDF-capable model. Restart the backend after changing these values. No credential belongs in Android config or source control. This guest proxy is for local development; shared deployment requires the account/session integration before exposing the paid recognition endpoint.
4. Run the debug app from Android Studio, or build from a second terminal:

   ```powershell
   Set-Location frontend
   .\gradlew.bat :app:assembleDebug
   ```

   Use **Add transaction** for a manual entry and **Import** for a CSV, JPEG, PNG, WebP, or PDF (maximum 5 MiB and 200 transactions). CSV parsing stays on the device. A text PDF matching the supported US Chase or American Express statement layouts can be previewed locally; this validates statement totals and skips explicit credit-card repayments. Scanned statements, receipts, and unsupported PDFs have a separate explicit **Send to OpenAI & extract** action. Every candidate must be reviewed before saving. Possible duplicates require skipping the row or explicitly choosing to keep it.

The backend exposes `GET /api/ledger/meta`, `GET /api/ledger/exchange?from=USD&to=CAD&date=2026-10-01`, `POST /api/ledger/imports/preview` for the explicit OpenAI path, and `POST /api/ledger/imports/statement-preview` for local Chase/Amex PDF parsing. It does not store guest transactions or uploaded documents.

## Try a CSV import

Use the synthetic [ledger-demo.csv](documentation/examples/ledger-demo.csv). Copy it to the emulator's Downloads directory with Android Studio Device Explorer or:

```powershell
adb push documentation/examples/ledger-demo.csv /sdcard/Download/ledger-demo.csv
```

Open **Import → Choose a file → Preview CSV**, review the four rows, then confirm. In a new CAD ledger, the sample contributes CAD 1,200.00 income, CAD 46.45 expenses, and CAD 1,153.55 net movement. Reimporting it should show possible duplicates. The import opens **All time** so sample dates remain visible.

Required CSV columns are `date`, `amount`, and `merchant` (aliases such as `description` are accepted). Optional columns are `currency`, `category`, `kind`, and `notes`:

```csv
date,merchant,amount,currency,category,kind,notes
2026-10-01,Coffee Corner,4.50,CAD,Food,expense,Synthetic example
2026-10-02,Example Employer,1200.00,CAD,Salary,income,Synthetic example
```

Use `YYYY-MM-DD`, positive decimal amounts without thousands separators, and `expense` or `income`. Supported currencies are CAD, USD, EUR, GBP, CNY, and JPY; JPY uses whole yen. Missing currency defaults to the current reporting currency and missing kind defaults to expense, both with review warnings. The Amex export shape (`Date, Description, Card Member, Account #, Amount`) and Chase export shape (`Transaction Date, Post Date, Description, Type, Amount`) also accept US `MM/DD/YYYY` dates. Choose the statement currency when these bank exports omit it; sensitive card-member and account columns are ignored. Repayments are skipped with a warning, while refunds/credits remain selected for review. Invalid values remain editable in review.

For document recognition, try the synthetic receipt as a [PNG](documentation/examples/synthetic-receipt-cad.png) or [PDF](documentation/examples/synthetic-receipt-cad.pdf). Both should produce one expense of CAD 14.00 dated 2025-01-05 at CEDAR TEST CAFE. These examples contain no personal data. Extracting them uses the configured OpenAI API and may incur API charges; inspect the returned fields before confirming the import.

The [synthetic bank statement](documentation/examples/synthetic-statement-cad.pdf) should yield three transactions: CAD 25.00 and 42.35 expenses, and CAD 1,200.00 income. Its opening/closing balances and summary totals should not become transactions. For a supported US statement, the local preview uses billed USD for reconciliation and keeps readable foreign-spend details in notes; it rejects a partial or unreconciled parse rather than silently importing it. The dashboard opens to **All time**, so historical imports remain visible after a cold restart. Use **Select transactions** to select visible rows and delete them in one confirmed batch; the operation is atomic and stale rows abort the whole batch.

## Testing

From `backend/`:

```powershell
npm run typecheck
npm test -- --runInBand
```

From `frontend/`:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
.\gradlew.bat :app:connectedDebugAndroidTest
```

The last command requires a running emulator or connected device. Unit tests cover exact money, CSV parsing, validation, duplicates, and summaries. The ledger instrumentation suites include real SQLite persistence/rollback, bulk deletion, cold ViewModel visibility, manual editing and rotation, and a real FileProvider content URI through CSV review, duplicate acknowledgement, and atomic import. Persistence tests replace external providers. The live FX test skips unless explicitly enabled; no automated test uploads documents to OpenAI.

With the local backend running and the APK configured for `http://10.0.2.2:3000`, run the optional emulator-to-backend FX check from `frontend/`:

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.example.cpen321application.e2e.LedgerLiveFxTest" "-Pandroid.testInstrumentationRunnerArguments.ledgerLiveFx=true"
```

This check requests the historical USD-to-CAD quote for 2025-01-05 and expects the previous business day's rate. It makes no paid AI call. See [Feature 1 documentation](documentation/Feature1-Ledger.md#validation) for test locations and results.

---

## Requirements

Install the following before the frontend or backend setup steps:

- [git](https://git-scm.com/install/)


--- 

## Frontend Setup

### Requirements

- [Android Studio](https://developer.android.com/studio) (latest version)
- [Java 17](https://adoptium.net/temurin/releases/?version=17)
- [Android SDK](https://developer.android.com/studio#command-tools) Platform 35 for compilation, plus a Pixel 9 Android Baklava/API 36 emulator image

### Setup

1. **Open project**: Open the `frontend/` directory in Android Studio
2. **Sync Gradle**: Android Studio will automatically prompt you to sync the project. Click "Sync Now". You can also manually run `cd frontend && ./gradlew build` to trigger the sync and download the necessary dependencies.
3. **Configure Android SDK**: Install SDK Platform 35 and the Build Tools requested by Gradle. Compile/target SDK: 35; minimum SDK: 26. The test emulator runs API 36.
4. **Set up emulator/device**:
   - Create a new AVD (Android Virtual Device) by selecting Pixel 9 as the device and Android Baklava (API level 36) as the system image.
5. **Setup app config**: Copy the example file, then fill in local values:
   ```bash
   cp frontend/local.properties.example frontend/local.properties
   ```
   If Android Studio has already created `local.properties`, preserve its `sdk.dir` rather than overwriting it. Set:
   - `sdk.dir`: path to your Android SDK. Android Studio usually writes this the first time you open `frontend/`. On Mac it is often `sdk.dir=/Users/<username>/Library/Android/sdk`.
   - `API_BASE_URL`: backend URL baked into the APK. For the submitted cloud setup, use `https://136-67-157-106.sslip.io`. For local debug development, use `http://10.0.2.2:3000` for the emulator (`10.0.2.2` is the host machine). For a physical device on the same Wi-Fi, use `http://<your-lan-ip>:3000`.
   - `GOOGLE_CLIENT_ID`: `543187156899-8i4lr8gsegf849ckevgjt1v3osipnei9.apps.googleusercontent.com` (Web OAuth client ID; see signing setup below). If the entry is missing or blank, the build uses this value automatically.


### Build and Run

- **Debug build**: Click the green play button in the toolbar, to compile the code, package a debug APK, and install it on the connected device or running emulator. Alternatively, from the project root, run `./scripts/run-frontend.sh`.
- **Release build**: Go to Build -> Generate Signed App Bundle or APK -> APK. Follow the on-screen instructions to create a key, and select the "release" build variant. You will then have to manually install the generated APK on your device or the running emulator.


### Backend Configuration

Ensure the backend server is running and update the base URL in the app configuration if needed.

---
## Backend Setup

You can run the backend in one of two ways:
* Locally via Node.js 
* Via Docker Compose

Both ways use the same `backend/.env` file (see below).

### Environment configuration

From the project root:

```bash
cp backend/.env.example backend/.env
```

Set at least:
- `PORT`: `3000`.
- `GOOGLE_CLIENT_ID`: the same Web client ID as the frontend (shown above).
- `SERVER_PUBLIC_IP`: optional server-information setting; leave empty for local development.
- `NODE_ENV`: `production` on the cloud or `development` locally.
- `MONGODB_URI`: MongoDB connection string for user accounts and sessions, e.g. `mongodb://localhost:27017/ledger`. Required; the server refuses to start without it. Docker Compose overrides it to use its own `mongo` service.

No JWT secret or OAuth client secret is needed: sessions are random bearer tokens whose SHA-256 hashes are stored in MongoDB.


### Option 1: Run locally

**Requirements:** 
- [Node.js](https://nodejs.org/en/download/) 22.13+ within the 22.x line
- [npm](https://docs.npmjs.com/downloading-and-installing-node-js-and-npm) 10+
- [MongoDB](https://www.mongodb.com/try/download/community) 7+ running locally, reachable at `MONGODB_URI`

**Setup:** 
1. Install dependencies:

   ```bash
   cd backend
   npm ci --include=dev
   ```

2. **Development** (TypeScript with auto-reload):

   ```bash
   npm run dev
   ```

3. **Production build** (optional):

   ```bash
   npm run build
   npm start
   ```

### Option 2: Run with Docker Compose (optional template tooling)

The Compose stack starts the backend together with the MongoDB database used for user accounts.

**Requirements:** 
- [Docker](https://docs.docker.com/desktop/setup/install) and [Docker Compose](https://docs.docker.com/desktop/setup/install) v2.24+
- [curl](https://curl.se/download.html)

**Setup**
1. **Start** (from the project root):

   ```bash
   ./scripts/run-backend.sh
   ```

   Or run Compose directly:

   ```bash
   docker compose up --build -d
   ```

2. **Stop**:

   ```bash
   docker compose down
   ```

### Google sign-in setup

Use the same Google Cloud project for both OAuth clients. Configure Google Auth Platform branding/audience first (add permitted test users if the console requires them).

1. Create an Android OAuth client with package com.example.cpen321application and the SHA-1 of the key signing the APK you will run.
2. Register the SHA-1 of the actual debug or release key used to build the APK. Run `gradlew signingReport` in `frontend/` to inspect signing information; a teammate's debug key or a new release key requires its own Android OAuth client entry. A certificate used for an older milestone does not automatically authorize a new APK.
3. Create a Web application OAuth client in the same project. Its Client ID (not Client Secret, and not the Android client ID) is GOOGLE_CLIENT_ID in both frontend/local.properties and backend/.env.
4. Restart the backend, sync Gradle and rebuild the APK. Run on an emulator with Google Play services and a Google account. **Sign in with Google** on the sign-in screen launches Google sign-in.
5. Credential Manager returns an ID token. POST /api/auth/google validates it using google-auth-library (signature, audience, issuer and expiry) and requires a verified Google email. The Google token is not saved or logged.
6. Local HTTP credential transport is restricted to debug builds. Use HTTPS for the final cloud APK. No Firebase or OAuth client secret is required.

### Accounts and sign-in

- **Sign in** (issue #22): opening Group or Me while signed out shows a screen accepting an email and password, or **Sign in with Google**, and linking to sign-up with "New user? Sign up here!". **Use without Login** returns to My Expenses. A successful sign-in opens My Expenses; an active invitation continues to its preview.
- **Sign up**: name, email, password (8-128 characters) and confirmation. Passwords are hashed with scrypt and a per-user salt; emails are stored lower-case and must be unique.
- **Google accounts** (issues #1, #21): after Google verifies the user, the backend looks up the Google ID. An existing account is signed in directly; otherwise the app shows a "Finish creating your account" step prefilled with the Google name, and `POST /api/auth/google/register` creates the account. A Google email that already belongs to a password account is refused (409) rather than linked automatically, because sign-up does not verify email ownership.
- **Sessions** (issue #25): every sign-in returns a random bearer token valid for 7 days. Only its SHA-256 hash is stored in the `sessions` collection, which has a TTL index. The app keeps the token in private storage excluded from backup and checks it with `GET /api/users/me` on launch. If the server rejects it, Group and Me require sign-in again; My Expenses remains available.
- **Account and logout** (issues #23, #25): **Me** shows the name, email, sign-in method, member-since date and session expiry from `GET /api/users/me`, with automatic loading and no manual Refresh or Create group button. **Log out** asks for confirmation, revokes the session with `POST /api/auth/logout`, clears the stored token and the Google account choice, and returns to My Expenses.
- **Database** (issue #24): MongoDB through Mongoose. `users` holds email (unique), display name, optional password hash and optional Google ID (unique, sparse); `sessions` holds token hash, user ID and expiry.
- **Tests**: `tests/mock/` replaces Google verification and the user database; `tests/no-mock/accountAuth.test.ts` runs against a real `mongod` started by mongodb-memory-server (downloaded on first run); `tests/unit/` covers hashing and validation. Android validation rules are unit-tested in `AccountValidationTest`.

## Project Documentation

- [Requirements and design](documentation/Requirements_and_Design.md)
- [Testing specification](documentation/Testing.md)
- [Code review](documentation/Code_Review.md)
- [Personal-ledger behavior and limitations](documentation/Feature1-Ledger.md)
- [Cloud deployment and maintenance](documentation/Google_Cloud_Deployment.md)
- [Development backlog](documentation/Ledger_Backlog.md)
- [M1 historical documentation](documentation/M1_History.md)
