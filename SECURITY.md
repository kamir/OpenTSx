# Security Policy

## Reporting a vulnerability

Please do **not** report security vulnerabilities in public issues, pull requests or discussions.

Report them privately through GitHub:

1. Open the repository's **Security** tab: <https://github.com/kamir/OpenTSx/security>.
2. Choose **Report a vulnerability** (GitHub private vulnerability reporting). This creates a draft security advisory
   that only you and the maintainers can see.

Please include the affected module and version or commit, steps to reproduce, and the impact you expect. If private
reporting is not available to you, open a public issue that asks for a private contact, without any details of the
vulnerability.

What happens next:

* We acknowledge the report as soon as we can, normally within a week.
* We confirm or reject it, agree on a fix and a disclosure date with you, and publish a GitHub security advisory
  when the fix is available. Reporters are credited unless they ask not to be.

OpenTSx is maintained by volunteers; there is no bug bounty.

## Supported versions

| Version | Supported |
|---------|-----------|
| `master` (Maven version 3.0.0: data model v2 modules, Python package `opentsx` 1.0.x) | yes |
| 2.x modules on `master` (`opentsx-data`, `-core`, `-connectors`, `-lg`, `-flink-core`) | fixes on a best-effort basis |
| older releases, branches and everything in `archive/` | no |

There are no tagged releases yet; fixes go to `master`.

## Scope notes

* The experimental web stack (`opentsx-saas-backend/`, `opentsx-saas-frontend/`) is a prototype and not meant for
  production use.
* Credentials and real hosts must never be committed. Local configuration belongs in the git-ignored
  `config/private/` and `config/opentsx-local.props`; deployment secrets belong in `deploy/k3s/.env` (git-ignored) and
  Kubernetes Secrets. If you find a committed secret, report it as described above.
