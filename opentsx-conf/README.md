# opentsx-conf: configuration and the opentsx config commands

This module gets you the configuration of OpenTSx in Java: the key registry `opentsx.config.v1`, the class
`OpenTsxConf` and the commands `opentsx config list|show|get` and `opentsx version`. Verified with
`opentsx 3.0.1 (java)`, built from this repository on JDK 21.

The rules (key names, types, the six sources, file formats, errors) are in
[TASK-020](../EVOLUTION/TASK-020-configuration-and-cli.md). How to use them is in
[chapter 18](../docs/user-manual/18-configuration.md) of the user manual; every key is in
[Appendix D](../docs/user-manual/appendix-d-configuration.md). The Python package ships the same registry as
`python-package/opentsx/conf/opentsx.config.v1.json`; both runtimes pass the shared vectors
[`conf.v1.json`](../docs/datamodel-2026/testvectors/conf.v1.json).

| File or class | Purpose |
|---------------|---------|
| `src/main/resources/org/opentsx/conf/opentsx.config.v1.json` | the registry: every key with type, default, flag, environment variables and the commands that read it |
| `Registry` | reads the registry, checks the registry rules, renders `--help` |
| `OpenTsxConf` | `load(args, env, confDir)`, `set`, `setIfMissing`, `get`, `getOption`, the typed getters, `contains`, `getAll`, `source`, `toDebugString`; `Loaded.startLine` |
| `OpenTsxConfException` | every usage and configuration error, one line `<subject>: <problem>; <what is allowed>` |
| `ConfCli` | main class of `opentsx config` and `opentsx version` |
| `ConfReference` | renders Appendix D and the defaults-file templates in `conf/` from the registry |

## Build

You need JDK 17 or 21 and Maven 3.9.

```bash
mvn -B -ntp -q -pl opentsx-conf install
```

The build stamps the project version into `org/opentsx/conf/version.properties`. `opentsx version` fails when the
file is missing or not stamped.

## Print the registry

```bash
bin/opentsx config list
bin/opentsx config list --output json
```

The table has the columns `KEY`, `TYPE`, `DEFAULT`, `FLAG` and `ENV`; `-` marks none. A secret shows no flag.
`--output json` prints the registry file byte for byte.

## Show the effective configuration

```bash
bin/opentsx config show --conf opentsx.domain=solar
```

Every key with a value, sorted, with the source of the value. Excerpt of the real output:

```text
KEY                                  VALUE                                      SOURCE
opentsx.archive.dir                  data/history                               default
opentsx.domain                       solar                                      commandLine
opentsx.https.proxy                  ******                                     environment
```

A secret is shown as `******`. `--output json` prints one line
`{"schema":"opentsx.config.show.v1","values":[{"key":...,"value":...,"source":...}]}`.
`OPENTSX_CONF_DIR` names the directory of the defaults file `opentsx-defaults.conf` or `opentsx-defaults.yaml`.

## Get one value

```bash
bin/opentsx config get opentsx.stats.windows
```

```text
PT10M,PT1H
```

## Print the version

```bash
bin/opentsx version
```

```text
opentsx 3.0.1 (java)
```

## Log the configuration and set the log level

`OpenTsxConf.load` sets the slf4j-simple property `org.slf4j.simpleLogger.defaultLogLevel` from `opentsx.log.level`
(`error`, `warn`, `info`, `debug`, `trace`) when a source sets the key. slf4j-simple reads the property once, so a
main class calls `load` before it creates its first logger. `startLine()` of the result returns the line a service
logs once at INFO when it starts: `starting <command>: <key>=<value> (<source>), ...` with every key the command reads
that has a value, secrets as `******`. [Chapter 18](../docs/user-manual/18-configuration.md#set-the-log-level) shows
the log level and the line. `toDebugString()` returns every key with a value as `key=value (source)` lines.

A command reads the configuration once its main class calls `load`. `Registry.LOADS_CONF` and `CONF_COMMANDS` in
`python-package/opentsx/conf/conf.py` list these commands, and a test of the Python package checks that both lists are
equal; the help of every other command says that the Java command parses its own flags.

## Fix a usage error

A usage or configuration error prints one line on stderr and exits with code 2. A runtime failure exits with code 1,
also when the result cannot be written to stdout.

```bash
bin/opentsx config show --conf opentsx.domian=x
```

```text
opentsx: opentsx.domian: unknown key; did you mean opentsx.domain?
```

A secret on the command line is an error. Set it in the environment or in a properties file:

```text
opentsx: opentsx.openmetadata.token: secret keys are not accepted on the command line; set OPENTSX_OPENMETADATA_TOKEN or OPENMETADATA_JWT or put opentsx.openmetadata.token in a properties file
```

## Regenerate the configuration reference

`ConfReference` reads the registry file below the repository root and writes three files:
`docs/user-manual/appendix-d-configuration.md`, `conf/opentsx-defaults.conf.template` and
`conf/opentsx-defaults.yaml.template`. Run it from the repository root after a change of the registry or the version:

```bash
mvn -B -ntp -q -pl opentsx-conf install -DskipTests
java -cp "$(bin/opentsx classpath opentsx-conf)" org.opentsx.conf.ConfReference
```

```text
wrote docs/user-manual/appendix-d-configuration.md
wrote conf/opentsx-defaults.conf.template
wrote conf/opentsx-defaults.yaml.template
```

Commit the registry change and the three files together. Do not edit the files by hand.

## Run the tests

```bash
mvn -B -ntp -q -pl opentsx-conf install
```

`ConfVectorsTest` runs all 286 shared vectors. `RegistryTest` checks the registry against
[`opentsx.config.v1.schema.json`](../docs/datamodel-2026/schemas/opentsx.config.v1.schema.json) and the registry
rules, and that the Python copy is byte-identical. `ConfCliTest` checks output, exit codes, precedence and masking,
and, in a fresh JVM, that `load` sets the slf4j-simple level.
`ConfReferenceTest` fails when one of the three generated files differs from the rendering of the registry, and checks
that both templates read back as the registry defaults.
