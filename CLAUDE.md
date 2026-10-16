# OpenTSx: working rules

## Follow the binding rules

Read both files before every task. They win over everything in this file.

- [AGENTS.md](AGENTS.md)
- [DOCS.md](DOCS.md)

[CONTRIBUTING.md](CONTRIBUTING.md) has the build, the CI jobs and the branch and commit rules.

## Keep the public repository clean

This repository is public.

- No customer or project names, no real hosts or addresses, no credentials, no real measurement data.
  Private configuration goes to `config/private/`, which is git-ignored.
- No AI attribution and no agent tooling. `.claude/`, `.cursor/`, `.codex/` and `.grok/` are in `.gitignore`.
- Everything written is English. A German document you change is translated in the same pull request.

## Change the code

1. Work in a temporary clone or a dedicated worktree, never in a shared checkout.
2. Branch from `master` as `<type>/<name>`, for example `fix/market-empty-days`.
3. Ship a regression test with every fix and behavior change. Show that it fails on `master` and passes with the change.
4. Run `mvn -B -ntp verify` before you push. CI runs it on Java 17 and 21.
5. Update the module README and `CHANGELOG.md` under "Unreleased" in the same pull request.
6. Open a pull request. It is squash-merged when every required check is green. Never push to `master`.

## Release

A release is a tag `v<version>` on `master`, set after the release commit is merged and green.
The tag must equal the version in `pom.xml` and must not be a prerelease.
`.github/workflows/image.yml` then builds `opentsx-tools` for `linux/amd64` and `linux/arm64` and pushes
`ghcr.io/kamir/opentsx-tools:<version>`.
Images come from that workflow only. Never build or push them by hand.

## Talk to KafScale

- Clients connect to the KafScale proxy, never to a broker.
- Producers come from `KafScaleClients.producer`, which sets `enable.idempotence=false`. KafScale has no `InitProducerId`.
- Topic names come from `TopicLayout`: `tsx.<domain>.<kind>.v2`.
- A shared broker is changed additively. Never delete a topic you did not create.
