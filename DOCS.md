# DOCS.md

Hard rules for documentation. Docs are part of the code. Wrong docs are a bug.

## 1. Same PR, current behavior
- Docs change in the SAME PR as the code that changes behavior. NEVER "docs later".
- Describe current behavior only. NO history, NO "previously", NO "will", NO "coming soon".
- NEVER document an unreleased version, tag, image or pin. Pin only what exists.
- Removed feature: delete its docs, examples and links in the same PR.
- On every behavior change update README, CHANGELOG, `llms*.txt`, examples and guides that mention it.

## 2. English only
- You MAY talk to the human in their own language.
- Everything written into the repo or GitHub is ALWAYS plain English. No matter what language the human uses.
- This covers code, identifiers, strings, comments, logs, error messages, tests, commit messages, branch names, PR titles and bodies, review comments, issues, changelogs, release notes and docs.
- NEVER copy the human's non-English wording into an artifact verbatim. Write it in plain English.
- Non-English text in a file you touch gets replaced with plain English.

## 3. Evidence for every claim
- Every command, flag, path, port, env var, config key, endpoint and error string MUST exist in the code. Find it first.
- Cite the source in the PR: `file:line` or `--help` output.
- NEVER copy a flag or path from memory, another doc or an old version.
- Run every documented command before merge, on the version you document. Paste nothing you did not run.
- Output examples are real output. NEVER invent output, IDs or counts. Trim, do not edit.
- If it was not verified, it does not ship.

## 4. Structure
- One-line intro: what this page gets you to, and on which version it was verified.
- Prerequisites up front: `You need X.` Before the first command.
- Headings are tasks: `Install`, `Log in`, `Create a device`. NOT `Overview`, `Introduction`, `Architecture`.
- What it does, then how. One or two sentences, then the command.
- Numbered steps only when order matters. Say so: `The order matters.`
- One topic per page. Link, do not repeat.
- Reference pages list fields, types, allowed values and limits. Exact, complete, no prose padding.

## 5. Commands
- Every command block is copy-paste runnable. No `$` prompts, no fake line breaks.
- Use fenced blocks with a language: `bash`, `yaml`, `json`.
- Placeholders are `<name>` and explained once. Everything else is literal.
- Ports, hosts, flags and file paths are explicit. NEVER "the usual port".
- Flags spelled exactly as the binary takes them. Long form in docs.
- Name the file a command writes and where state is stored.
- Show the check after the action: `version`, `whoami`, `list`, `curl`.

## 6. Failure modes
- Document the errors people actually hit, with the exact message and the fix.
- Say what is NOT true when people commonly get it wrong: `Port 8000 is not the API port.`
- Say what a flag does NOT do when that is the trap.
- Security-relevant steps are explicit: who may do it, what token, shown once or stored.

## 7. Tone
- Plain English. Short sentences. One fact per sentence.
- Second person, imperative: `Run`, `Set`, `Open`.
- NO marketing. NO "simply", "just", "easy", "powerful", "seamless", "robust", "blazing".
- NO em dashes. NO `--` or spaced ` - ` as substitutes.
- NO emoji. NO exclamation marks.
- NO filler intros, NO summaries of what you just said.
- Name things exactly as the code does. Same name everywhere.

## 8. Links
- Link checker runs in CI. A broken link fails the build.
- Merge link targets BEFORE the pages that link to them.
- Relative links inside a repo. Absolute links across repos.
- NEVER link to a branch that will be deleted. Link to main or a tag.
- Images and media live in the repo and have alt text that says what they show.

## 9. Generated and pinned content
- Generated docs are regenerated with their script. NEVER hand-edited.
- Commit the generator change and its output together.
- On a version bump, `rg` every file for the old version. Docs, README, examples, charts, `llms*.txt`. Update all or none.
- Pins in docs MUST pass the pin check. A pin to a tag that does not exist is a broken build.

## 10. Before merge
- Run every changed command against the documented version.
- Run the link checker and markdown lint locally.
- `rg` for every renamed or removed flag, path and env var. Zero stale hits.
- Read the rendered page once. Broken tables and code blocks are bugs.
- NO AI attribution in docs, commits or PRs. See AGENTS.md.
