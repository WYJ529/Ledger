# Backend non-functional requirement tests

No dedicated NFR test suite currently exists here. The directory is retained for the course template. Existing interface tests contain financial-integrity and access-control checks, but are not relabeled or duplicated as independent NFR acceptance tests.

Add real `*.test.ts` suites here only when their assertions explicitly verify the documented requirement and acceptance criteria. Record the requirement, method and execution logs in `documentation/Testing.md`.

Run from the repository root with `./scripts/run-backend-nfr-tests.ps1` or `bash scripts/run-backend-nfr-tests.sh`. An empty suite exits unsuccessfully so that a skipped directory cannot be submitted as a passing NFR run.
