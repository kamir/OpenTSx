# TASK-010 - Open Source Readiness + Onboarding Paths

## Goal
Prepare OpenTSx for an open source release with a clear, role-based onboarding flow that guides users to the right path in under 60 seconds, and ensures demos are reliable and discoverable.

## Success Criteria
- README landing section helps users identify intent/role and choose a path in < 60 seconds.
- At least one “Hello World” demo runs without Kafka/DB setup.
- All demo scripts listed in README are verified and documented.
- OSS release essentials exist: license, contribution guide, code of conduct, security policy, release process, and CI.

## Scope
- Public-facing documentation and onboarding.
- Demo script validation and docs alignment.
- Open source readiness artifacts and release checklist.

## Tasks

### 1) Onboarding Entry Points (README)
- Add a short “Who are you?” chooser near the top (SWE, Data Scientist, Operator, Evaluator).
- Add a “Goal-based quick start” section (e.g., “Run a demo,” “Analyze a CSV,” “Stream from Kafka”).
- Ensure each path ends with a link to a deeper guide (Step 2).

### 2) Step 1 → Step 2 Flow (Guides)
- Define Step 1 as a single quick win (demo + minimal config).
- Define Step 2 as a deeper path in `docs/manual/` or a dedicated onboarding guide per role.
- Provide a short 2–3 bullet “What you’ll learn next” for each Step 2 guide.

### 3) Demo Reliability
- Verify all scripts listed in `README.md` exist and run in a clean environment.
- Document expected outputs and failure modes in `bin/README.md`.
- Add a “no-Kafka demo” in `bin/` if none exists.

### 4) OSS Release Essentials
- Add `CONTRIBUTING.md` with development setup, tests, and PR rules.
- Add `CODE_OF_CONDUCT.md` (e.g., Contributor Covenant).
- Add `SECURITY.md` with disclosure policy.
- Add `CHANGELOG.md` and release versioning scheme.
- Add issue/PR templates under `.github/`.
- Add a release checklist (tagging, artifacts, Docker image, docs publish).

### 5) CI/Automation
- Add GitHub Actions for build + tests + basic lint.
- Add a docs link checker or markdown link validation.
- Add a smoke-test job that runs a minimal demo.

### 6) License and Attribution
- Confirm `LICENSE` and ensure third-party license attribution.
- Add a `NOTICE` file if required by dependencies.

## Deliverables
- Updated `README.md` with role/intent chooser and Step 1 → Step 2 flow.
- Verified demo scripts documented in `bin/README.md`.
- OSS release docs and templates in the repo root and `.github/`.
- CI workflows in `.github/workflows/`.

## Notes
- Prioritize a “Hello World” path that does not require Kafka/DB setup.
- Keep onboarding instructions short; link to deeper guides as Step 2.
