# Group API regression tests

These tests supplement the existing account, ledger and real-MongoDB group suites.
They exercise the real Express application through Supertest, including routing,
session checks, validation and error handling. They make no Google, OpenAI or FX
requests and do not use the development/cloud database.

## Run

From `backend/`, with the repository dependencies installed:

```powershell
npm run typecheck
npm test -- --runInBand --runTestsByPath tests/mock/groupManagement.test.ts tests/mock/groupExpenses.test.ts tests/no-mock/groupApiAccess.test.ts
```

Run the full regression suite:

```powershell
npm test -- --runInBand
```

Jest generates coverage under `backend/coverage/`. The existing
`scripts/run-backend-interface-tests.ps1` and `.sh` runners also discover these
files in their mock/no-mock phases. A focused run's coverage is not the coverage
of the complete backend suite.

## Scope

| Suite | Interfaces | Coverage added |
| --- | --- | --- |
| `mock/groupManagement.test.ts` | Create/list groups; preview/accept/create invitations; rename/delete groups | Failed database reads/writes; safe errors; retry after failed invitation or membership persistence; simulated deletion races |
| `mock/groupExpenses.test.ts` | Group summary; create/edit expenses | Failed membership lookup/aggregation/insertion/edit; validation and ownership before writes; missing duplicate-replay result; optimistic edit conflict |
| `no-mock/groupApiAccess.test.ts` | Twelve protected group interfaces, including leave/remove | Missing, unknown and expired sessions return 401 and a Bearer challenge without exposing private data or changing groups, invitations or expenses |
| `no-mock/groupMembership.test.ts` | Leave group; owner removes another member | Real persistence, owner-leave deletion, access revocation, invitation invalidation, historical amounts, rejoin, role enforcement and concurrent removal/departure |
| `mock/groupMembership.test.ts` | Leave group; owner removes another member | Failed writes return a safe 500 without changing membership; simulated concurrent deletion returns 404 |

Every new API has a named `Mocked:` group and an `Unmocked:` group. Each test's
comment describes its input, expected status/output, side effects and any injected
failure. Existing no-mock group suites continue to cover successful operations,
member/owner permissions, actual concurrent requests, financial totals and
idempotency.

The mock suites use real temporary MongoDB data, replacing only the specific model
operation that must fail. Assertions query actual persisted state after the HTTP
response. The no-mock suite replaces no application dependency. Shared fixture
hooks restore spies and clean only the temporary database after each test, then
stop its mongod after the suite. The first run may download a MongoDB binary if it
is not already cached.

These tests provide evidence for implemented parts of NFR-1 (financial integrity)
and NFR-2 (access control). They do not cover the planned item-claiming/live
subscription functionality or establish the NFR-3 latency targets. They are backend
interface tests, not Android end-to-end tests.

## Verification on 2026-10-27

Verified on Windows with Node.js 22.23.2 and npm 10.9.8:

- TypeScript checking passed.
- The three new suites passed: 48 tests (10 group-management fault tests,
  8 expense fault tests and 30 real-session access tests).
- The complete backend regression passed: 17 suites, 251 tests.

| Full-suite coverage | Result |
| --- | --- |
| Statements | 86.23% |
| Branches | 82.04% |
| Functions | 84.48% |
| Lines | 87.44% |

The group-expense module reached 100% line coverage and 93.15% branch coverage;
group management reached 97.14% line coverage and 90.72% branch coverage. These
are full-suite measurements, including the existing tests. They do not imply
complete course compliance: separate mock/no-mock reports and explanations for
remaining uncovered code are still needed for the final testing document.

The local installation initially lacked the already-declared `pdfjs-dist`
dependency; it was installed without modifying the manifest or lockfile. The
temporary mongod could not start in the restricted runner, so the passing database
runs used the normal local execution environment. Jest also emitted the existing
ts-jest hybrid-module configuration warning; no checks or diagnostics were disabled.
