# Governance

OpenTSx is a maintainer-led project. The maintainer, [@kamir](https://github.com/kamir), decides the direction, the
scope, the design and what is merged.

## Issues are welcome

Open an issue for a bug, a question, a missing feature or a documentation error. Issues are the place to discuss a
change before anyone writes code.

## Pull requests after agreement

* A pull request is reviewed when the maintainer agreed to the change in an issue first.
* A small fix (a typo, a broken link, a one-line bug fix with a test) may come without an issue.
* A pull request without prior agreement may be closed without review. This says nothing about its quality.
* Accepted pull requests follow [CONTRIBUTING.md](CONTRIBUTING.md) and are squash-merged when every required check is
  green.

## Specifications

The normative specifications in [`docs/datamodel-2026/`](docs/README.md) define the data model, the operators and the
documents that code and tests read. A change to a specification goes into the same pull request as the code that
implements it.

## Security

Report vulnerabilities as described in [SECURITY.md](SECURITY.md), not in public issues.

## License

OpenTSx is licensed under the [Apache License 2.0](LICENSE). Contributions are accepted under the same license.
