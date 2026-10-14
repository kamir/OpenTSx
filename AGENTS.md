# AGENTS.md

Hard rules. No exceptions. If a rule blocks you, stop and ask. Do not work around it.

## 1. NEVER AI attribution
- NEVER add `Co-authored-by`, `Co-Authored-By`, `Generated with`, `Assisted by` or any AI trailer.
- NEVER mention AI, agents, models or assistants in commits, PR titles, PR bodies, code, comments, changelogs, release notes or docs.
- NEVER commit agent tooling dirs: `.claude/`, `.grok/`, `.cursor/`, `.codex/`. They stay in `.gitignore`.
- Check every commit message and PR body before push. One violation is a failed task.

## 2. English only
- You MAY talk to the human in their own language.
- Everything written into the repo or GitHub is ALWAYS plain English. No matter what language the human uses.
- This covers code, identifiers, strings, comments, logs, error messages, tests, commit messages, branch names, PR titles and bodies, review comments, issues, changelogs, release notes and docs.
- NEVER copy the human's non-English wording into an artifact verbatim. Write it in plain English.
- Non-English text in a file you touch gets replaced with plain English.

## 3. Read the code first
- Read the relevant code before answering, planning or building. Every time.
- NEVER guess an API, flag, path, env var, config key or CLI verb. Find it in the source or `--help`.
- Read existing docs, env and config first. If a setting, gate or knob already exists, change it. NEVER add a second one.
- Read git history before touching code that was already fixed once.
- When diagnosing, read logs and live state first. Deliver findings before changing code.
- Live config is the deployed config. Docs and local copies can lag.

## 4. Evidence only
- No assumptions. Every claim cites `file:line`, command output, a test result or a link.
- If you do not know, say so, then go find out.
- NEVER report a test count, pass or fix you did not run yourself in this session.
- NEVER claim "should work". Run it.
- Numbers get concrete figures. No hedging, no philosophy.
- Measure before claiming a speedup. Show before and after.

## 5. Simple, direct code
- Modern computer science. Correct data structures, correct complexity.
- Performance over elegance.
- NO needless abstraction, layers, interfaces, patterns, generics, wrappers, factories or indirection.
- One obvious way. Flat over nested. Explicit over clever.
- Match the existing style and idiom of the file. Make the smallest change that fixes it.
- Minimal dependencies. Justify every new one. Pin versions.
- Delete dead code. NEVER comment it out.

## 6. No comments
- Code is code, not documentation.
- NO comments unless absolutely necessary: a non-obvious why, an invariant, or a workaround with a link.
- NO comments that restate the code. NO section banners. NO decorative headers. NO changelog comments.
- NO `TODO` litter. Fix it or open an issue.

## 7. Regression tests, always
- Every bug fix and every behavior change ships a regression test. No exceptions.
- The test MUST fail on current main and pass with the fix. Prove both.
- Verify the test actually ran. A filter that matches nothing still exits 0.
- NEVER weaken, skip or delete a test to make CI green.
- Tests are deterministic. No sleeps, no network, no wall-clock luck.

## 8. No slop
- No filler text, no marketing tone, no "comprehensive", "robust", "seamless".
- No speculative features. Build what was asked.
- No unrelated refactors, renames or reformatting in a fix. Separate PR.
- Small focused diffs. Group PRs by blast radius, not one mega PR.
- NEVER run a formatter over a whole tree. Format only the files you changed.
- NEVER use em dashes. No `--` or spaced ` - ` as substitutes either.
- Finish the change. Do not polish one sentence for ten minutes.

## 9. Errors and safety
- Handle every error explicitly. NO silent fallbacks, NO swallowed exceptions, NO default values that hide failure.
- Fail closed.
- NEVER put secrets, tokens, keys or credentials in code, logs, commits, PRs, test fixtures or chat output.
- NEVER print env values that hold secrets.
- Validate external input at the boundary. No path traversal, no injection.
- Security, funds and production changes need explicit approval.

## 10. Builds and versions
- Builds are deterministic and reproducible. Pin tool and dependency versions.
- Stamp real versions into binaries. NEVER ship `dev`.
- NEVER pin a version, tag or image that does not exist yet.
- NEVER build large images on shared boxes. Use CI.
- When bumping versions, find every pinned file with `rg`. Do not trust the bump script blindly.

## 11. Git workflow
- Work in a temp clone or a dedicated worktree. NEVER in a shared checkout.
- NEVER push to `main` or `master`. Org repos are PR only.
- NEVER open a PR whose head is `main` or `master`. Use `<type>/<name>` branches: `feat/`, `fix/`, `chore/`, `docs/`, `test/`, `refactor/`.
- Conventional commit subjects: `fix(scope): what changed`. Concise, reusable as PR text.
- Run lint, format and tests locally before every push. Run `make commit-check` where it exists.
- NEVER merge before CI is green. All required checks, not most of them.
- Squash merge after green CI unless the repo says otherwise. Delete the branch. Fast-forward local main.
- NEVER force push without `--force-with-lease` and explicit approval.
- Merge link targets before the PRs that link to them.
- Docs describe current behavior only and update in the same PR as the code.
- Follow DOCS.md for every documentation change. No exceptions.
- NEVER hand-edit generated files. Regenerate them.

## 12. Tags and releases
- Tags go on main after the release commit is merged and green.
- Lockstep repos release from the same tag. Verify every repo actually released the right version.
- Release notes: what changed, upgrade steps, breaking changes. No AI mentions.
- After a release, delete RC artifacts only when asked. Keep tags and release pages as history.

## 13. Verify after shipping
- After merge, confirm CI on main is green.
- After deploy, confirm the running version and read the logs.
- After release, pull the artifact and run it.
- Report what you verified, with evidence. Report what you did not verify.
