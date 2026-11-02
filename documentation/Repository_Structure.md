# Repository structure

This repository preserves the course template required by Project_Description.pdf section 5 (pages 3-5). In Android, the shorthand `androidTest/e2e` means the package directory below `app/src/androidTest/java/`; it is not a new source root. The backend uses the template's plural `tests/` directory.

```text
Ledger/
|-- frontend/
|   |-- app/src/main/                 Android source and resources
|   |-- app/src/androidTest/java/com/example/cpen321application/
|   |   |-- e2e/                     User flows through the actual app
|   |   |-- nfr/                     Dedicated NFR acceptance tests (currently empty)
|   |   |-- ui/                      Supplemental component/UI regression tests
|   |   |-- integration/             Supplemental Android/API/storage tests
|   |   `-- helpers/                 Shared test fixtures; not runnable tests
|   `-- app/src/test/                Optional local JVM unit tests
|-- backend/
|   |-- src/                         TypeScript application code
|   `-- tests/
|       |-- mock/                    Interface tests with replaced dependencies
|       |-- no-mock/                 Interface tests using real application dependencies
|       |-- nfr/                     Dedicated NFR acceptance tests (currently empty)
|       |-- unit/                    Optional unit tests
|       `-- helpers/                 Shared fixtures and isolated device-test server
|-- documentation/
|   |-- Requirements_and_Design.md
|   |-- Code_Review.md
|   |-- Testing.md                   Submission entry point
|   |-- Test_Inventory.md            Existing suites, boundaries and commands
|   |-- images/                      Documentation screenshots and image examples
|   `-- res/                         CSV/PDF/JSON resources and test evidence
|-- scripts/
|   |-- run-frontend.{sh,ps1}
|   |-- run-backend.{sh,ps1}
|   |-- run-frontend-e2e-tests.{sh,ps1}
|   |-- run-backend-interface-tests.{sh,ps1}
|   |-- run-frontend-nfr-tests.{sh,ps1}
|   |-- run-backend-nfr-tests.{sh,ps1}
|   |-- run-frontend-regression-tests.{sh,ps1}   Supplemental tests
|   |-- lib/                         Common runner code
|   `-- deploy/                      Cloud deployment scripts and Caddy configuration
|-- .github/workflows/               Course CI/CD location; no workflow implemented yet
|-- .gitignore
|-- docker-compose.yml
|-- compose.cloud.yaml               Additional production Compose configuration
|-- detekt.yml
|-- .eslintrc.json
`-- README.md
```

`ui/`, `integration/` and `helpers/` are additional directories inside the existing Android test source set. They preserve useful regression coverage without presenting tests with replaced HTTP providers or injected screen data as complete course E2E evidence. JVM tests stay in `src/test/`; Android-dependent tests stay in `src/androidTest/`.

Use the [test inventory](Test_Inventory.md) for commands, dependencies and honest coverage limits. The [organization record](Repository_Organization_2026-11-02.md) lists every moved file and content change.

## Git management requirements

- Keep frontend and backend in this repository and retain the course-template structure.
- The evaluated working version must be on `main`. This reorganization is prepared on `refactor/repo-organize` for pull-request review; merge and final submission validation remain separate steps.
- Use individual author names/emails and Conventional Commit messages such as `refactor: organize repository tests and documentation`. Include applicable human and AI contributions using `Co-authored-by` trailers.
- Repository publicity, template-fork provenance and explicit write access for the four TAs (`masihbr`, `sarahdagger`, `mickowale`, `dorawyy`) are GitHub settings to verify before submission; local file organization does not verify them.
- Keep generated Gradle/Jest reports, build outputs, SDK paths, local `.env` files and credentials out of Git. Commit selected sanitized submission evidence under `documentation/res/` or `documentation/images/`.
- Existing ignored reference PDFs/local design materials and the pre-existing untracked `pr68-work/` checkout are local working material. Review them separately before staging; do not include the duplicate checkout in the submission.
