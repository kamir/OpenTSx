# TASK-020: Configuration and command line

## Task Metadata
- **Task ID**: TASK-020-configuration-and-cli
- **Created**: 2026-11-16
- **Status**: in progress
- **Priority**: high
- **Assigned To**: OpenTSx Core Team
- **Epic**: Configuration, command line, errors and logging

## Objective

One configuration model and one command line for both runtimes. Every setting of an OpenTSx command is a key in
one registry, it comes from one of six sources with a fixed precedence, it is parsed by one type grammar, and every
mistake fails with exit code 2 and one line that names the key or the flag. Java and Python read the same registry
file and pass the same shared test vectors.

This task delivers the specification (this file), the registry
[`opentsx.config.v1.json`](../opentsx-conf/src/main/resources/org/opentsx/conf/opentsx.config.v1.json), its schema
[`opentsx.config.v1.schema.json`](../docs/datamodel-2026/schemas/opentsx.config.v1.schema.json) and the shared vectors
[`conf.v1.json`](../docs/datamodel-2026/testvectors/conf.v1.json). The implementation follows in the batches under
[Sub-Tasks](#sub-tasks).

Why: 15 of the 19 main classes of the v2 modules read options, each with a parser of its own; the other 4 take positional arguments only. Unknown flags are
accepted silently ([`RetrievalServer.java:539-541`](../opentsx-retrieval/src/main/java/org/opentsx/retrieval/server/RetrievalServer.java)
takes every pair, also a token without dashes), a trailing flag without value is dropped (the loop condition
`i + 1 < args.length` in
[`StatsProcessorApp.java:31`](../opentsx-stats/src/main/java/org/opentsx/stats/StatsProcessorApp.java)),
`--observations yes` turns into `false` (`Boolean.parseBoolean` at `StatsProcessorApp.java:52`), and a usage error
exits with 1 in some commands ([`TwinLive.java:157`](../opentsx-twin-live/src/main/java/org/opentsx/twin/live/TwinLive.java),
[`HouseDemo.java:142`](../opentsx-twin/src/main/java/org/opentsx/twin/demo/HouseDemo.java)) and with 2 in others
([`ModbusCli.java:67`](../opentsx-modbus/src/main/java/org/opentsx/modbus/ModbusCli.java),
[`MarketCli.java:98`](../opentsx-market/src/main/java/org/opentsx/market/MarketCli.java)). The Python package has no
command line and no configuration object.

## Requirements

The decisions D1 to D12 are taken. Each section below states the rules, the reason and the reference model. The words
"must" and "never" are normative.

Reference models, verified 2026-11-16:

- Apache Spark configuration: [spark.apache.org/docs/latest/configuration.html](https://spark.apache.org/docs/latest/configuration.html).
  Keys set on `SparkConf` win over `--conf` and `--properties-file`, which win over `conf/spark-defaults.conf`;
  `spark-defaults.conf` has one key and value per line, separated by whitespace; time values take the units `ms`,
  `s`, `m` or `min`, `h`, `d`; the property tables have the columns name, default, meaning and since version.
- PySpark `SparkConf`: [python/pyspark/conf.py](https://github.com/apache/spark/blob/master/python/pyspark/conf.py).
  The methods carry the Java names in camelCase (`set`, `setIfMissing`, `setAll`, `get`, `getAll`, `contains`,
  `toDebugString`) and every setter returns the object.
- Apache Wayang `Configuration`, at the submodule commit
  [713faa7](https://github.com/apache/wayang/blob/713faa7d8bf4726536f6b0920ebb73053160274c/wayang-commons/wayang-core/src/main/java/org/apache/wayang/core/api/Configuration.java):
  a defaults resource per module (`wayang-core-defaults.properties`, line 93), a user file from the system property
  `wayang.configuration` (line 217) or the class path resource `wayang.properties` (line 223), and parent fallback
  through `fork()` (line 610). An unknown key is stored without a warning (line 296). OpenTSx takes the defaults
  resource and the layering, and rejects unknown keys.

### Decisions

| Id | Rule | Section |
|----|------|---------|
| D1 | Dotted lower-case keys under `opentsx.`, camelCase inside a segment | [Key names](#key-names-d1) |
| D2 | One registry `opentsx.config.v1` in JSON, shipped by both runtimes byte for byte | [Registry](#registry-d2) |
| D3 | Nine types with one grammar each, proven by shared vectors | [Types](#types-d3) |
| D4 | Six sources: code, command line, environment, properties file, defaults file, registry default | [Sources and precedence](#sources-and-precedence-d4) |
| D5 | Spark-style properties files and YAML files | [File formats](#file-formats-d5) |
| D6 | Fail closed with exit code 2, one line, and "did you mean" | [Fail closed](#fail-closed-d6) |
| D7 | Secrets only from the environment or a file, masked everywhere | [Secrets](#secrets-d7) |
| D8 | PySpark-style API with the same camelCase names in Java and Python | [API](#api-d8) |
| D9 | `opentsx <command> [options] [args]`, exit codes 0, 1, 2 | [Command line](#command-line-d9) |
| D10 | Existing flags stay as aliases; this task migrates no main class | [Existing flags](#existing-flags-d10) |
| D11 | One exception type per language, message `<subject>: <problem>; <what is allowed>` | [Errors](#errors-d11) |
| D12 | No new dependency in Java; none in Python core | [Dependencies of the implementation](#dependencies-of-the-implementation-d12) |

### Key names (D1)

A key is `opentsx` followed by one or more segments. A segment starts with a lower-case letter and continues with
lower-case letters and digits; a compound word starts each further word with one upper-case letter followed by at
least one lower-case letter or digit:

```text
key     = "opentsx" 1*( "." segment )
segment = lower *( lower / digit ) *( upper 1*( lower / digit ) )
```

As a regular expression: `^opentsx(\.[a-z][a-z0-9]*([A-Z][a-z0-9]+)*)+$`.

| Key | Valid | Why |
|-----|-------|-----|
| `opentsx.kafscale.bootstrap` | yes | |
| `opentsx.stats.sketchAlpha` | yes | camelCase inside a segment |
| `opentsx.s3Bucket` | yes | digits continue a word |
| `opentsx.stats.sketch_alpha` | no | underscore |
| `opentsx.stats.sketchALPHA` | no | two upper-case letters in a row |
| `opentsx.Stats` | no | upper-case first letter |
| `opentsx` | no | no segment |

Further rules:

- Leaf rule: no key is a prefix of another key at a segment boundary. `opentsx.modbus.simulate` and
  `opentsx.modbus.simulator.port` may both exist; `opentsx.modbus.simulate` and `opentsx.modbus.simulate.port` may
  not. Reason: a YAML file nests keys as maps, and a map node cannot also hold a value.
- A key names a setting, not a command line: `opentsx.stats.sketchAlpha`, not `opentsx.stats.alpha`. The old flag
  stays as the alias (D10).
- A key whose value is a bare number of a unit carries the unit in the `unit` field and, where the alias takes the
  bare number today, in the name: `opentsx.market.fetchDelayMs` (alias `--delay-ms 300`). New keys use the type
  `duration` instead.

Reason: Spark keys (`spark.executor.memory`, `spark.sql.adaptive.enabled`) are the model the users of OpenTSx know.
camelCase gives one word per segment, which keeps the environment mapping of D4 unambiguous for the registry.

### Registry (D2)

The registry is one JSON document with `"schema": "opentsx.config.v1"`, a list `commands` and a list `keys`. The
schema is [`opentsx.config.v1.schema.json`](../docs/datamodel-2026/schemas/opentsx.config.v1.schema.json).

Files:

| File | Role |
|------|------|
| `opentsx-conf/src/main/resources/org/opentsx/conf/opentsx.config.v1.json` | the source; class path resource `org/opentsx/conf/opentsx.config.v1.json` |
| `python-package/opentsx/conf/opentsx.config.v1.json` | byte-identical copy, package data of `opentsx.conf` |

A test in each language reads both files and fails when they differ by one byte.

Fields of a key entry:

| Field | Required | Content |
|-------|----------|---------|
| `key` | yes | the key, grammar of D1 |
| `type` | yes | `string`, `int`, `long`, `double`, `boolean`, `duration`, `list`, `enum` or `path` |
| `items` | no | type of the items of a `list`: `string` (when absent), `int`, `long`, `double`, `duration`, `enum` or `path` |
| `values` | for `enum` | the allowed values, in the order the error message lists them; also for a list with `enum` items |
| `unit` | no | unit of an `int`, `long` or `double`, for example `ms`, `s`, `Kd` |
| `default` | no | the default as text; it must parse as the type. A key without default has no value until a source sets it |
| `secret` | yes | `true` for a credential (D7) |
| `flag` | no | alias without dashes: `--kafscale x` equals `--conf opentsx.kafscale.bootstrap=x` |
| `legacyFlags` | no | further aliases read today under another name, for example `bootstrap` (`twin-live --bootstrap`) |
| `env` | no | environment variables read today, after the canonical `OPENTSX_` name, in this order |
| `commands` | yes | the commands that read the key |
| `since` | yes | first OpenTSx release that reads the setting under any name, or `unreleased` |
| `doc` | yes | one sentence in plain English |

Fields of a command entry: `name` (the command words, for example `market prices`), `args` (names of the required
positional arguments, for example `["out.json"]`), `since` and `doc`. The loader resolves a command from this list
(see [Command line](#command-line-d9)), and a command without keys, such as `studio-export`, still needs an entry.

Additions to the fields that D2 lists, each needed by the inventory:

- `commands` at the top level: the help needs a sentence per command, the loader needs the positional arguments, and
  commands such as `studio-export` read no key.
- `items`: `--windows PT10M,PT1H` is a list of durations and `--near 50.6,13.0` a list of doubles. Without the item
  type a malformed item would pass the load and fail later, which breaks D6.
- `legacyFlags`: `twin-live` reads the KafScale bootstrap as `--bootstrap`
  ([`TwinLive.java:43`](../opentsx-twin-live/src/main/java/org/opentsx/twin/live/TwinLive.java)), every other command
  as `--kafscale`; `history-twin` reads the archive as `--history`, `market` as `--archive`. One key keeps both
  spellings.
- `since: "unreleased"`: `stats-flink` is not in the release v3.0.1. The release commit replaces every `unreleased`
  with the version it releases, and the release gate fails while one is left. This follows the "Unreleased" section of
  [CHANGELOG.md](../CHANGELOG.md).

Rules the registry tests check in both languages (the schema cannot express them):

1. Commands are sorted by name and keys by key, so the file has one canonical form.
2. Every key follows D1 and the leaf rule.
3. The environment mapping is injective: no two keys share a canonical name, no legacy name equals another key's
   canonical or legacy name, and no key maps to a launcher variable (`OPENTSX_HOME`, `OPENTSX_CONF_DIR`,
   `OPENTSX_EXTRA_ARGS`).
4. Within one command, a flag (with its legacy flags) belongs to one key. The same flag may mean different keys in
   different commands: `--port` is `opentsx.retrieval.port` in `retrieval-server` and `opentsx.twinLive.port` in
   `twin-live bridge`.
5. No flag is a common option: `conf`, `c`, `properties-file`, `help`, `h`, `version`, `output`.
6. Every default parses as the type of its key; a secret has no default.
7. Every command named by a key is in `commands`.
8. Every `doc` is one sentence: it starts with an upper-case letter and ends with a period.

The shipped registry holds 133 keys and 48 commands.

### Types (D3)

Every source delivers text. Before the type is applied, the text is trimmed of spaces (U+0020) and tabs (U+0009) at
both ends; other white space is not trimmed. Then:

1. An empty text is an error. From the environment, an empty or blank variable counts as unset instead (k3s
   ConfigMaps leave keys empty, for example `OM_URL=` in [env.example](../deploy/k3s/env.example)).
2. A text with a control character (U+0000 to U+001F, U+007F) is an error.
3. A text with U+FFFD or a lone surrogate (U+D800 to U+DFFF) is an error: `<subject>: value is not valid UTF-8; pass
   UTF-8 text in a UTF-8 locale such as LANG=C.UTF-8`. The JVM decodes the command line and the environment with the
   locale and puts U+FFFD for every byte it cannot map; Python keeps an invalid byte as a lone surrogate. Without this
   rule Java would store two U+FFFD for a U+00EF in a value under the POSIX locale, and Python would write the raw
   byte.
4. The type grammar applies. The stored value is the trimmed text; for a list, the trimmed items joined by `,`.

| Type | Grammar | Valid | Invalid |
|------|---------|-------|---------|
| `string` | any text | `wind`, `a b` | empty |
| `path` | any text; relative paths resolve against the working directory | `data/history`, `C:/data` | empty |
| `int` | `0` or `-?[1-9][0-9]*` (ASCII digits), from -2147483648 to 2147483647 | `42`, `-7` | `007`, `+5`, `1_000`, `4.0`, `-0`, `2147483648`, Arabic-Indic digits |
| `long` | as `int`, from -9223372036854775808 to 9223372036854775807 | `9223372036854775807` | `9223372036854775808` |
| `double` | `-?(0\|[1-9][0-9]*)(\.[0-9]+)?([eE][-+]?[0-9]+)?`, finite | `0.01`, `1e-3`, `4300`, `0.30` | `.5`, `1.`, `NaN`, `Infinity`, `1e999`, `0x1p3`, `1,5` |
| `boolean` | `true` or `false`, ASCII letters in any case | `true`, `FALSE` | `yes`, `on`, `1`, `falſe` (with the long s U+017F) |
| `duration` | ISO-8601 subset or a number with a unit, see below | `PT10M`, `500ms`, `5min` | `pt10m`, `-PT1S`, `10`, `1.5h`, `5 s`, `1y`, `P1W` |
| `list` | items separated by `,`, each trimmed, none empty; each item follows `items` | `a,b`, ` a , b ` | `a,,b`, `a,` |
| `enum` | exactly one of `values`, case-sensitive | `csv` | `CSV` |

Duration:

```text
duration = iso / unit-form
iso       = "P" [ n "D" ] [ "T" ( n "H" [ n "M" ] [ seconds ] / n "M" [ seconds ] / seconds ) ]   ; at least one part
seconds   = n [ "." 1*3digit ] "S"
unit-form = n ( "ms" / "s" / "m" / "min" / "h" / "d" )
n         = "0" / %x31-39 *digit
```

Upper-case letters only in the ISO form, lower-case units only in the unit form. The resolution is one millisecond, so
seconds take at most three decimals (`PT0.25S` is 250 ms, `PT0.0001S` is an error). The largest duration is
999999999 days (86399999913600000 ms), the range of Python's `timedelta`. Java returns `java.time.Duration`, Python
`datetime.timedelta`; the vectors compare milliseconds.

Reason: Java's `Boolean.parseBoolean`, `Integer.parseInt` and `Duration.parse` and Python's `int`, `float` and
`bool` accept different inputs (`int("٥")` is 5 in Python, `Integer.parseInt("+5")` is 5 in Java, `"falſe"` equals
`"false"` under Java's `equalsIgnoreCase`). A grammar of its own, written as a regular expression over ASCII, gives the
same answer in both languages. The ISO form is what OpenTSx uses today (`--windows PT10M,PT1H`); the unit form is
Spark's.

A legacy flag whose value is a bare number keeps a numeric type with a `unit`: `opentsx.market.fetchDelayMs` is a
`long` with unit `ms`, because `--delay-ms 300` must keep working and `300` is not a duration.

### Sources and precedence (D4)

Highest first:

| Rank | Source | `source(key)` | How |
|------|--------|---------------|-----|
| 1 | code | `code` | `conf.set(key, value)` after the load |
| 2 | command line | `commandLine` | `--conf key=value`, `-c key=value`, alias flags |
| 3 | environment | `environment` | `OPENTSX_<NAME>` or a legacy name of the entry |
| 4 | properties file | `propertiesFile` | `--properties-file FILE` |
| 5 | defaults file | `defaultsFile` | `opentsx-defaults.conf` or `opentsx-defaults.yaml` in `$OPENTSX_CONF_DIR` |
| 6 | registry default | `default` | the `default` of the entry |

Rules:

- Every source is read and validated completely, also when a higher source sets the same key. A malformed defaults
  file fails the command even when `--conf` overrides the key.
- The sources are checked in the order command line, environment, properties file, defaults file. The first error
  stops the load.
- `OPENTSX_CONF_DIR` unset or blank: no defaults file. Set: the directory must exist, it may hold
  `opentsx-defaults.conf` or `opentsx-defaults.yaml` or neither, not both. The path in messages is
  `<OPENTSX_CONF_DIR>/<file name>`, joined with `/`; the value is used as given, so `conf/` gives
  `conf//opentsx-defaults.conf`. A file `opentsx-defaults.yml` is an error (`not a defaults file name; rename it to
  opentsx-defaults.yaml`), and a symbolic link that points nowhere is a missing file, not an absent one.
- A properties file is YAML when its name ends in `.yaml` or `.yml`, otherwise a properties file. `--properties-file`
  is given at most once.
- Within the command line a key is set at most once, whatever spelling sets it.
- `contains(key)` is true when a source of rank 1 to 5 set the key; a registry default does not count. A command that
  requires a key to be set explicitly (for example `market publish` and `opentsx.domain`) checks `contains`.

Difference to Spark: Spark skips `spark-defaults.conf` when `--properties-file` is given, unless
`--load-spark-defaults` is set. OpenTSx reads both: the defaults file holds what is the same for every command on a
machine (the KafScale proxy, the archive directory), the properties file holds one job. Both have their own rank, so
nothing needs to be copied from one into the other.

Worked example, the key `opentsx.stats.sketchAlpha` (default `0.01`) of `opentsx stats`:

| Set in | Value |
|--------|-------|
| `$OPENTSX_CONF_DIR/opentsx-defaults.conf` | `opentsx.stats.sketchAlpha 0.05` |
| `job.conf` given as `--properties-file job.conf` | `opentsx.stats.sketchAlpha 0.04` |
| environment | `OPENTSX_STATS_SKETCH_ALPHA=0.03` |
| command line | `--alpha 0.02` |
| code | `conf.set("opentsx.stats.sketchAlpha", "0.06")` |

| Sources present | `get` | `source` |
|-----------------|-------|----------|
| all five | `0.06` | `code` |
| all but code | `0.02` | `commandLine` |
| environment and both files | `0.03` | `environment` |
| both files | `0.04` | `propertiesFile` |
| defaults file | `0.05` | `defaultsFile` |
| none | `0.01` | `default` |

The vectors `precedence-*` run exactly these six rows.

#### Environment names

The canonical name of a key is `OPENTSX_` followed by the words of every segment after `opentsx`, joined by `_`, in
upper case. A word starts at the beginning of a segment and at every upper-case letter:

| Key | Variable |
|-----|----------|
| `opentsx.domain` | `OPENTSX_DOMAIN` |
| `opentsx.kafscale.bootstrap` | `OPENTSX_KAFSCALE_BOOTSTRAP` |
| `opentsx.stats.sketchAlpha` | `OPENTSX_STATS_SKETCH_ALPHA` |
| `opentsx.twinLive.allowOrigin` | `OPENTSX_TWIN_LIVE_ALLOW_ORIGIN` |
| `opentsx.s3Bucket` | `OPENTSX_S3_BUCKET` |

- The loader reads, per key, the canonical name and then the names in `env`, in that order; the first variable that
  is set and not blank wins. `KAFSCALE_BOOTSTRAP` therefore loses against `OPENTSX_KAFSCALE_BOOTSTRAP`. Every variable
  that is set and not blank is checked, also one that loses.
- Variables that name no key are ignored. The prefix `OPENTSX_` is shared with the launchers (`OPENTSX_HOME`,
  `OPENTSX_EXTRA_ARGS` in [opentsx-entrypoint.sh](../deploy/docker/opentsx-entrypoint.sh)), the k3s scripts
  (`OPENTSX_NS`, `OPENTSX_TAG` in [env.example](../deploy/k3s/env.example)) and the integration tests
  (`OPENTSX_EXPORT_DIR`, `OPENTSX_RETRIEVAL_URL`), so it cannot be closed. `opentsx config show` prints the source
  of every value, which shows a misspelled variable as a missing value.
- A malformed value of a variable that names a key is an error, also when the command does not read the key.
- Launcher variables are not keys: `OPENTSX_HOME`, `OPENTSX_CONF_DIR`, `OPENTSX_EXTRA_ARGS`, `JAVA_OPTS`.

### File formats (D5)

#### Properties file

```properties
# site defaults for this machine
! a comment may also start with an exclamation mark
opentsx.kafscale.bootstrap 192.0.2.10:9092
opentsx.domain = wind
opentsx.stats.windows=PT10M, PT1H
```

- UTF-8; a file that is not is an error, `<file>: not valid UTF-8; save the file as UTF-8`, for YAML files as well. A
  byte order mark at the start is skipped. Lines end at `\n`; a `\r` before it is removed.
- A line that is blank or whose first character after spaces and tabs is `#` or `!` is skipped. A `#` later in a
  line is part of the value.
- Otherwise the line is a key, then spaces or tabs, an optional `=`, spaces or tabs, and the value up to the end of
  the line, trimmed. The key ends at the first space, tab or `=`. `:` is no separator: a key with `:` is an error that
  shows only the text before the `:` (`<file>:<line>: <key>: ":" is not a separator; write key value or key = value`).
  `key:value` is valid in `java.util.Properties`, and the text after the `:` is often a secret.
- A line without value names its key only when the key is in the registry: `<file>:<line>: <key> has no value; ...`.
  Otherwise the message is `<file>:<line>: line has no value; ...`, because the line can be a secret glued to a key.
- A backslash anywhere in a line is an error: at the end of the line it would be a continuation, elsewhere an escape.
  Write Windows paths with `/`.
- A key twice in one file is an error. Secret keys are allowed.
- Errors start with `<file>:<line>: `.

#### YAML file

```yaml
opentsx:
  kafscale:
    bootstrap: 192.0.2.10:9092
  stats:
    windows: [PT10M, PT1H]
opentsx.domain: wind
```

- Nested maps flatten to dotted keys; a dotted key is allowed at any level. The example sets
  `opentsx.kafscale.bootstrap`, `opentsx.stats.windows` and `opentsx.domain`.
- A scalar is taken as its source text, not as a YAML-typed value: `sketchAlpha: 0.010` stores `0.010`,
  `enabled: yes` stores `yes` and then fails the boolean grammar, `station: 08080` stores `08080`. Java reads the
  token text of `YAMLParser` (`getText()`), Python the `value` of the PyYAML scalar event. Both parsers report the
  same text; checked for every YAML vector with jackson-dataformat-yaml 2.22.4 and PyYAML 6.0.3.
- A null scalar (empty, `~`, `null`) or a quoted empty string is an empty value, which is an error.
- A YAML sequence is allowed only for a key of type `list`. Its items must be scalars without a comma; the stored value
  is the items joined by `,`. The key of a sequence is looked up before its items, so a misspelled key fails as an
  unknown key; an item of a secret key is shown as `******`.
- Nesting deeper than 100 levels is an error, `<file>: not valid YAML; nesting deeper than 100 levels`. Above 1000
  levels Java's parser stops first with its own detail.
- Aliases (`*name`) and explicit tags (`!!int`) are errors. An anchor without an alias changes nothing.
- One document per file. An empty file or an empty document sets nothing. Any other top level that is not a mapping is
  an error.
- A key twice, nested or dotted, is an error.
- A syntax error, and a mapping key that is itself a list or a map, fail with `<file>: not valid YAML; <detail>`; the
  detail is the parser's own and differs between the languages. The vectors fix the message up to `not valid YAML; `.
- Errors start with `<file>: ` (no line number: the parsers count lines differently).
- Java reads YAML with jackson-dataformat-yaml at the version of the reactor's jackson-bom (2.22.4). Python needs the
  optional extra `opentsx[yaml]` (`PyYAML>=6.0.1`); without it a YAML file fails with
  `<file>: YAML files need the extra opentsx[yaml]; run pip install "opentsx[yaml]"`.

### Fail closed (D6)

These are usage or configuration errors. Each exits with code 2 and prints one line on stderr:

| Mistake | Example message |
|---------|-----------------|
| unknown key under `opentsx.` | `opentsx.stats.sketchAlfa: unknown key; did you mean opentsx.stats.sketchAlpha?` |
| key outside `opentsx.` | `spark.master: not an OpenTSx key; expected a key that starts with opentsx.` |
| malformed value | `--alpha: "abc" is not a double; expected a finite decimal number such as 0.01 or 1e-3` |
| key twice on the command line | `opentsx.domain: set twice on the command line (also by --domain); set each key once` |
| flag the command does not read | `--delay-ms: unknown option of stats; run opentsx stats --help for the options` |
| flag without value | `--kafscale: missing value; expected --kafscale <string>` |
| key the command does not read, on the command line | `opentsx.market.fetchDelayMs: not read by stats; run opentsx stats --help for its keys` |
| secret on the command line | see [Secrets](#secrets-d7) |
| required key without value | `opentsx.kafscale.bootstrap: not set; set it with --kafscale, --conf opentsx.kafscale.bootstrap=<value> or OPENTSX_KAFSCALE_BOOTSTRAP` |

"Did you mean": the candidate with the smallest Levenshtein distance (insert, delete, replace, each 1; over Unicode
code points), at most 2; on a tie the candidate that sorts first. Candidates are all registry keys for a key, the
command's flags and legacy flags plus the common options for an option, and the commands for a command. Without a
candidate the message names where to look.

Keys from files and the environment may belong to any command. The check "not read by" applies only to the command
line, where the user names one command.

Order of the checks on the command line, left to right, first error stops: the command; then for each option its
name, whether it is a secret, whether a value follows, whether its key is set twice, and the value; then the positional
arguments.

### Secrets (D7)

The registry marks 6 keys as secret: `opentsx.openmetadata.token`, `opentsx.retrieval.dagRunToken`,
`opentsx.market.entsoeToken`, `opentsx.market.ntpClientId`, `opentsx.market.ntpClientSecret` and
`opentsx.https.proxy` (a proxy URL can hold a user and a password).

- A secret comes from code, the environment, a properties file or the defaults file. On the command line it is an
  error, through `--conf` and through its flag alike; the message never contains the value:

  ```text
  --token: secret keys are not accepted on the command line; set OPENTSX_OPENMETADATA_TOKEN or OPENMETADATA_JWT or put opentsx.openmetadata.token in a properties file
  ```

- `toDebugString`, `Loaded.startLine`, `opentsx config show`, `opentsx config get`, the help, every error message and every log line show
  a secret value as `******`. `getAll` and `get` return the real value, for the code that needs it.
- A secret has no default.
- A key that is not secret is shown in clear. `opentsx.iceberg.properties` is passed on the command line today
  (`--iceberg` in [deploy/k3s](../deploy/k3s/components/lake/kustomization.yaml)), so it is not secret and must not hold
  catalog credentials (`credential`, `token`, `s3.secret-access-key`); the Iceberg and S3 clients read those from their
  own environment variables.

Reason: a value on the command line shows in `ps`, the shell history and the pod spec. `--token` and `--dag-run-token`
are read today ([`WindparkMetadata.java:59`](../opentsx-openmetadata/src/main/java/org/opentsx/openmetadata/WindparkMetadata.java),
[`RetrievalServer.java:568`](../opentsx-retrieval/src/main/java/org/opentsx/retrieval/server/RetrievalServer.java));
they stay in the registry so that the error names them, and the migration of these two commands documents the change.

### API (D8)

Java: module `opentsx-conf`, package `org.opentsx.conf`. Python: package `opentsx.conf`. Same names, camelCase in
both:

| Purpose | Java | Python |
|---------|------|--------|
| empty configuration on the shipped registry | `new OpenTsxConf()` | `OpenTsxConf()` |
| load sources 2 to 6 | `static Loaded load(List<String> args, Map<String, String> env, String confDir)` | `OpenTsxConf.load(args, env, confDir) -> Loaded`, `confDir` a `str` or path-like |
| load with another registry (tests, vectors) | `static Loaded load(Registry registry, List<String> args, Map<String, String> env, String confDir)` | `OpenTsxConf.load(args, env, confDir, registry=...)` |
| result of the load | `record Loaded(String command, OpenTsxConf conf, List<String> args, boolean help, boolean version)` | `Loaded(command, conf, args, help, version)`, a dataclass |
| one line `starting <command>: key=value (source), ...` with the keys the command reads, secrets masked | `String Loaded.startLine()` | `Loaded.startLine() -> str` |
| set a value (source code) | `OpenTsxConf set(String key, String value)` | `set(key, value) -> OpenTsxConf` |
| set unless a source of rank 1 to 5 set it; the value is checked either way | `OpenTsxConf setIfMissing(String key, String value)` | `setIfMissing(key, value)` |
| set several | `OpenTsxConf setAll(Map<String, String> pairs)` | `setAll(pairs)`, a mapping or an iterable of `(key, value)` |
| value, error if none | `String get(String key)` | `get(key) -> str` |
| value or a fallback | `String get(String key, String defaultValue)` | `get(key, defaultValue)` |
| value if any | `Optional<String> getOption(String key)` | `getOption(key) -> str \| None` |
| typed values | `int getInt`, `long getLong`, `double getDouble`, `boolean getBoolean`, `Duration getDuration`, `List<String> getList`, `List<Duration> getDurationList` | `getInt`, `getLong`, `getDouble`, `getBoolean`, `getDuration -> timedelta`, `getList -> list[str]`, `getDurationList -> list[timedelta]` |
| set by a source of rank 1 to 5 | `boolean contains(String key)` | `contains(key) -> bool` |
| every key with a value, sorted, real values | `List<Map.Entry<String, String>> getAll()` | `getAll() -> list[tuple[str, str]]` |
| where the value comes from | `String source(String key)`, null without value | `source(key) -> str \| None` |
| `key=value (source)` lines, sorted, secrets masked | `String toDebugString()` | `toDebugString() -> str` |
| error type | `OpenTsxConfException extends IllegalArgumentException` | `OpenTsxConfError(ValueError)` |
| the registry of the configuration | `Registry registry()` | `registry() -> Registry` |
| the six source names, highest first | `static final List<String> SOURCES` | `SOURCES`, a tuple |

- `args` of `load` starts with the command words: `["market", "prices", "--from", "2024-01-01"]`. A main class that
  `bin/opentsx` starts without its command word passes its words first.
- `confDir` is the value of `OPENTSX_CONF_DIR` as given, null or `None` or blank for none. It is a string in Java, so
  that messages show the path as the user wrote it. `load` reads no process state itself: the
  CLI passes `System.getenv()` or `os.environ`, a test passes a map.
- With `--help`, `-h` or `--version`, `load` returns `help` or `version` true, `conf` null or `None` and checks nothing
  else. `opentsx --help` and `opentsx --version` work without a command; `command` is then null or `None`.
- Setters return the object (chaining). `set` checks the key and the value like any other source; a secret may be
  set in code. Python's `set` takes `str` only.
- `getAll` and `toDebugString` include registry defaults.
- A typed getter on a key of another type is an error: `opentsx.domain: has type string; read it with get`.
- `getDurationList` is an addition to the methods D8 lists: `opentsx.stats.windows` is a list of durations, and the
  caller must not parse the items with `Duration.parse`, which rejects the unit form.

### Command line (D9)

```text
opentsx <command> [options] [args]
```

- The command is the longest run of leading words, before the first token that starts with `-`, that names a command
  of the registry. `opentsx market prices --from 2024-01-01` runs `market prices`; `opentsx vectors --kafscale x`
  runs `vectors`, which `bin/opentsx` maps to the vector processor as today.
- Options follow the command; an option before the command is a missing command. Options and positional arguments
  may interleave. `--` ends the options; every later token is positional.
- An option with a value takes the next token, unless there is none or it starts with `--`; a value may start with a
  single `-` (`--count -5`). `--name=value` is not supported.
- A boolean flag may stand alone, which means `true`, or take the next token when it is `true` or `false` (ASCII, any
  case). `--observations true` (used by `deploy/k3s` and the docs today), `--simulated` and `--dag-runs false` all
  work. A token after a boolean flag that is neither `true` nor `false` is a positional argument: `--enabled yes`
  becomes an unexpected argument `yes`.
- Common options of every command:

  | Option | Meaning |
  |--------|---------|
  | `--conf <key>=<value>`, `-c <key>=<value>` | set a key; repeatable; the value may contain `=` |
  | `--properties-file <path>` | read a properties or YAML file |
  | `--help`, `-h` | print the help on stdout and exit 0; anywhere before `--`, it wins over every error; with an unknown or incomplete command it prints the help of `opentsx` |
  | `--version` | print the version on stdout and exit 0 |
  | `--output text\|json` | only `config list` and `config show` |

- Exit codes: 0 success, 1 runtime failure, 2 usage or configuration error. Results go to stdout, diagnostics to
  stderr. A usage or configuration error prints `opentsx: <message>` and a newline on stderr and nothing on stdout.
  A result that cannot be written (a full disk, a closed stdout) is a runtime failure: exit 1 and
  `opentsx: stdout: cannot write the output; check that standard output is open and not full`.

Help (`opentsx stats --help`), generated from the registry; keys sorted; a secret shows no flag:

```text
usage: opentsx stats [options]
Run the streaming statistics processor on KafScale.

keys:
  opentsx.domain  string = wind  --domain  OPENTSX_DOMAIN
      Domain of the topic names tsx.<domain>.<kind>.v2.
  opentsx.kafscale.bootstrap  string  --kafscale, --bootstrap  OPENTSX_KAFSCALE_BOOTSTRAP, KAFSCALE_BOOTSTRAP
      Bootstrap address of the KafScale proxy as host:port, several separated by commas.
  opentsx.log.level  enum = info  one of error|warn|info|debug|trace  OPENTSX_LOG_LEVEL
      Lowest level of the log lines: slf4j-simple in Java, the logger opentsx in Python.
  opentsx.stats.allowedLateness  duration = PT0S  --lateness  OPENTSX_STATS_ALLOWED_LATENESS
      How long a closed window still accepts late episodes.
  opentsx.stats.checkpoint  path  --checkpoint  OPENTSX_STATS_CHECKPOINT
      Checkpoint file of the processor; without it the processor starts from the beginning every time.
  opentsx.stats.observations  boolean = false  --observations  OPENTSX_STATS_OBSERVATIONS
      Also read the observations topic of the domain.
  opentsx.stats.once  boolean = false  --once  OPENTSX_STATS_ONCE
      Process the backlog, close all windows and exit.
  opentsx.stats.sketchAlpha  double = 0.01  --alpha  OPENTSX_STATS_SKETCH_ALPHA
      Relative accuracy of the quantile sketch.
  opentsx.stats.windows  list of duration = PT10M,PT1H  --windows  OPENTSX_STATS_WINDOWS
      Window lengths of the statistics.

common options:
  -c, --conf <key>=<value>    set a key on the command line; repeatable
  --properties-file <path>    read keys from a properties file or a YAML file (.yaml, .yml)
  -h, --help                  print this help and exit
  --version                   print the version and exit
```

The layout of a key line: two spaces, then the parts separated by two spaces: the key; the type (`list of <items>` for a
list) with ` = <default>` when there is one; `one of a|b` for an enum; `unit <unit>`; the flag and the legacy flags
with dashes, separated by `, ` (not for a secret); the canonical variable and the legacy variables, separated by
`, `; `secret: environment or file only` for a secret. Then six spaces and the doc. A command other than `config get`,
`config list`, `config show` and `version` gets a third line that says what its Java command reads until its migration
batch. `opentsx --help` lists the commands with their doc.

Built-in commands of both runtimes:

| Command | Output |
|---------|--------|
| `opentsx config list` | the registry as a table with the columns `KEY`, `TYPE`, `DEFAULT`, `FLAG`, `ENV` (`-` for none); `--output json` prints the registry file byte for byte |
| `opentsx config show` | every key with a value after loading all sources, sorted, columns `KEY`, `VALUE`, `SOURCE`, secrets as `******`; `--output json` prints `{"schema":"opentsx.config.show.v1","values":[{"key":...,"value":...,"source":...}]}` compactly on one line |
| `opentsx config get <key>` | the effective value and a newline; a secret as `******`; unset without default exits 2 |
| `opentsx version` | `opentsx <version> (<runtime>)`, for example `opentsx 3.0.1 (java)`; the runtime's own package version, never `dev` |

Table layout: columns separated by two spaces, every column but the last padded with spaces to its widest cell, a
header row first. JSON output: compact (`,` and `:` without spaces), non-ASCII characters as they are, a newline at
the end. Values never hold control characters (D3), so Jackson and Python's `json.dumps(..., ensure_ascii=False,
separators=(",", ":"))` write the same bytes. `config show` and `config get` read every key, so `--conf` may set any key there.

Python registers the console script `opentsx = "opentsx.cli:main"` in `[project.scripts]` of
[pyproject.toml](../python-package/pyproject.toml); Java adds the commands `config` and `version` to
[bin/opentsx](../bin/opentsx) and to [opentsx-entrypoint.sh](../deploy/docker/opentsx-entrypoint.sh), on a main class
in `opentsx-conf`. The version numbers differ today (Java `3.0.1` in [pom.xml](../pom.xml), Python `1.0.0` in
`pyproject.toml`); `opentsx version` prints each runtime's own.

### Existing flags (D10)

- Every flag a main class reads today is the `flag` or a `legacyFlags` entry of a key; every environment variable it
  reads is in `env`. The [migration table](#migration-table) lists them per command.
- This task migrates no main class. Each migration batch replaces the hand-written parser of one module by
  `OpenTsxConf.load`, keeps every flag of the table working, updates the module README, `CHANGELOG.md`, the user
  manual and the `deploy/` files that pass the flags, and ships the regression tests that D6 needs: an unknown flag, a
  flag without value, a malformed value, `--help`.
- Behavior a batch changes on purpose is listed under [Notes](#notes) per command; it goes into the changelog of
  the batch.

Rule found by the inventory: a command must not change its mode because a key has a value. Today
`retrieval-server` serves Iceberg when `--iceberg` is given, KafScale when `--kafscale` is given and the demo store
otherwise ([`RetrievalServer.java:544-559`](../opentsx-retrieval/src/main/java/org/opentsx/retrieval/server/RetrievalServer.java));
`windpark-sim` writes to every sink whose flag is given
([`WindparkSim.java:66-104`](../opentsx-windpark/src/main/java/org/opentsx/windpark/WindparkSim.java)); `metadata`
publishes profiles only with `--kafscale` (`WindparkMetadata.java:67`); the market fetch commands store into the
archive only with `--archive` (`MarketCli.java:218`). With an environment or a defaults file, such a key gets a value
without the user asking for the mode, for example `OPENTSX_KAFSCALE_BOOTSTRAP` in a shell profile. The batch of each of
these commands adds an explicit `enum` or `boolean` key for the mode (k3s already names one: `RETRIEVAL_SOURCE=demo|kafscale`
in [env.example](../deploy/k3s/env.example)), with a default that keeps today's command lines working, and a
regression test that an environment value does not switch the mode.

### Errors (D11)

One exception type per language: Java `OpenTsxConfException extends IllegalArgumentException`, Python
`OpenTsxConfError(ValueError)`. The message is one line:

```text
<subject>: <problem>; <what is allowed>
```

The subject is the name as the user wrote it: the flag (`--alpha`), the option (`-c`), the key from `--conf`, a file
or code, the environment variable, the command, or `<file>:<line>: <key>` and `<file>: <key>` in files. A value is
shown between double quotes as it was given after trimming; a secret value as `"******"`.

| Problem | Message |
|---------|---------|
| empty value | `<subject>: empty value; set a value or leave the key unset` |
| control character | `<subject>: value contains a control character; remove it` |
| int | `<subject>: "<v>" is not an int; expected a whole number from -2147483648 to 2147483647` |
| long | `<subject>: "<v>" is not a long; expected a whole number from -9223372036854775808 to 9223372036854775807` |
| double | `<subject>: "<v>" is not a double; expected a finite decimal number such as 0.01 or 1e-3` |
| boolean | `<subject>: "<v>" is not a boolean; expected true or false` |
| duration | `<subject>: "<v>" is not a duration; expected ISO-8601 such as PT10M or a whole number with the unit ms, s, m, min, h or d such as 10m, at most 999999999 days` |
| enum | `<subject>: "<v>" is not allowed; expected one of <values joined by ", ">` |
| list | `<subject>: "<v>" has an empty item; expected comma-separated items such as a,b` |
| list item | the message of the item type with `item "<item>"` in place of `"<v>"` |
| unknown key | `<key>: unknown key; did you mean <near>?` or `...; run opentsx config list for the known keys` |
| key outside `opentsx.` | `<key>: not an OpenTSx key; did you mean <near>?` or `...; expected a key that starts with opentsx.` |
| key not read by the command | `<key>: not read by <command>; run opentsx <command> --help for its keys` |
| secret on the command line | `<subject>: secret keys are not accepted on the command line; set <variables> or put <key> in a properties file`, `<variables>` the canonical and the legacy names of the key joined with ` or ` |
| set twice on the command line | `<subject>: set twice on the command line (also by <first>); set each key once` |
| unknown option | `<option>: unknown option of <command>; did you mean --<near>?` or `...; run opentsx <command> --help for the options` |
| missing value | `<option>: missing value; expected <option> <type>` (`<a\|b>` for an enum, `key=value` for `--conf`) |
| `--conf` without `=` | `<option>: "<token>" has no "="; expected <option> key=value` |
| common option twice | `<option>: given twice; give it once` |
| unknown command | `<word>: unknown command; did you mean <near>?` or `...; run opentsx --help for the commands` |
| group without subcommand | `<word>: missing subcommand; expected one of <commands>` |
| unknown subcommand | `<word> <word>: unknown command; did you mean <near>?` or `...; expected one of <commands>` |
| unknown subcommand of a command that has subcommands | `<word>: unknown subcommand of <command>; did you mean <near>?` or `...; expected one of <subcommands>` |
| no command | `<command>: missing; run opentsx --help for the commands` |
| unexpected argument | `<token>: unexpected argument; expected opentsx <command> <args>` |
| missing argument | `<command>: missing argument <<name>>; expected opentsx <command> <args>` |
| not set | `<key>: not set; set it with --<flag>, --conf <key>=<value> or <OPENTSX_NAME>` (without flag, and from `config get`, which takes no flag: no `--<flag>, `; a secret: `<OPENTSX_NAME> or a properties file`) |
| not valid UTF-8 | `<subject>: value is not valid UTF-8; pass UTF-8 text in a UTF-8 locale such as LANG=C.UTF-8`; for a path, `--properties-file: path is not valid UTF-8; ...` and `OPENTSX_CONF_DIR: path is not valid UTF-8; ...`; for a file, `<file>: not valid UTF-8; save the file as UTF-8` |
| wrong getter | `<key>: has type <type>; read it with <getter>` |
| file missing | `<path>: no such file; give an existing file` |
| properties syntax | `<file>:<line>: line continuation is not supported; write the value on one line`, `... escape sequences are not supported; write the character itself`, `... <key> has no value; write key value or key = value`, `... line has no value; write key value or key = value`, `... line has no key; write key value or key = value`, `<file>:<line>: <key>: ":" is not a separator; write key value or key = value` |
| key twice in a file | `<file>:<line>: <key>: set twice in this file (first on line <n>); set each key once`; YAML: `<file>: <key>: set twice in this file; set each key once` |
| YAML structure | `<file>: aliases (*name) are not supported; write the value`, `<file>: tags (!!type) are not supported; write the value as plain text`, `<file>: more than one YAML document; keep one document per file`, `<file>: the top level must be a mapping of keys to values; write key: value lines`, `<file>: <key>: a YAML list is only allowed for keys of type list; write one value`, `<file>: <key>: item "<x>" contains a comma; write one value per item`, `<file>: <key>: list items must be plain values; write one value per item`, `<file>: not valid YAML; nesting deeper than 100 levels` |
| YAML syntax | `<file>: not valid YAML; <parser detail>` |
| configuration directory | `<dir>: not a directory; set OPENTSX_CONF_DIR to the directory that holds opentsx-defaults.conf or opentsx-defaults.yaml`, `<dir>: both opentsx-defaults.conf and opentsx-defaults.yaml exist; keep one`, `<dir>/opentsx-defaults.yml: not a defaults file name; rename it to opentsx-defaults.yaml` |

A main class catches the exception once, in `main`, prints `opentsx: <message>` on stderr and exits 2. Every other
exception is a runtime failure: exit 1, and the message on stderr.

### Dependencies of the implementation (D12)

- Java: `opentsx-conf` depends on `jackson-databind` and `jackson-dataformat-yaml` from the reactor's `jackson-bom`
  (2.22.4, [pom.xml](../pom.xml)), JUnit 5 for tests. No other dependency, no argument-parser library.
- Python: no new dependency of the core; `json` and `re` of the standard library. YAML through the extra
  `yaml = ["PyYAML>=6.0.1"]`. pytest for tests.

### Test vectors

[`docs/datamodel-2026/testvectors/conf.v1.json`](../docs/datamodel-2026/testvectors/conf.v1.json),
`"schema": "opentsx.testvectors.conf.v1"`, holds a fixture registry in the format `opentsx.config.v1` and 288 cases.
The vectors are normative data: they change together with this specification, and both runtimes must reproduce every
case byte for byte. A case with `"registry": "shipped"` runs on the shipped registry and checks its keys (`opentsx.log.level` and the mode keys) or the subcommands of `vectors`; the other cases do not depend on it, so a migration batch that adds keys leaves them valid.

| Kind | Cases | Input | Expected |
|------|-------|-------|----------|
| `keyGrammar` | 12 | `key` | `valid` |
| `envName` | 8 | `key` | `name` |
| `value` | 92 | `key`, `input`, applied with `set(key, input)` | `text` (stored) and `value` (typed; durations as `{"millis": n}`), or `error` |
| `properties` | 22 | `file`, `text` or `base64` (bytes that are not UTF-8) | `pairs` in file order, or `error` |
| `yaml` | 33 | `file`, `text` or `base64` | `pairs` in document order, `error`, or `errorStartsWith` for parser errors |
| `load` | 105 | `args`, `env`, `files`, `dirs`, `confDir`, `registry`, then `set` and `setIfMissing` | `command`, `positional`, `values` (value and source of the listed keys), `unset`; or `help`/`version`; or `error` with `exit` 2 |
| `notSet` | 4 | `key` on an empty configuration | `error` of `get` |
| `show` | 7 | `args`, `env` of `config show` and `config get` | `exit`, `stdout` or `stderr` |
| `debugString` | 4 | `args`, `env` | `text` of `toDebugString`, `line` of `Loaded.startLine` |
| `api` | 1 | none | `names`: the public methods and fields of `OpenTsxConf`, sorted |

`{dir}` stands for a fresh temporary directory: the test writes each entry of `files` below it, creates each entry of
`dirs`, and replaces `{dir}` in `args`, `confDir`, `file` and every expected string.

The vectors were computed by a reference implementation of this section in Python and reviewed case by case. The YAML
cases were run through `YAMLParser` of jackson-dataformat-yaml 2.22.4 as well: all structural outcomes match
PyYAML 6.0.3; the cases that end in a registry error (unknown key, value grammar) yield the same flattened pairs in
both parsers.

### Migration table

Every main class of the v2 modules, every command of [bin/opentsx](../bin/opentsx) and of
[opentsx-entrypoint.sh](../deploy/docker/opentsx-entrypoint.sh), with the flags and variables read today and the keys
they become. Format: alias -> key (type = default). "(new)" marks a key without a flag or variable today.

| Command | Read today at | Flags and variables -> keys |
|---------|---------------|-----------------------------|
| `stats` | [StatsProcessorApp.java:30-56](../opentsx-stats/src/main/java/org/opentsx/stats/StatsProcessorApp.java) | `--domain` -> `opentsx.domain` (string = `wind`)<br>`--kafscale`, `--bootstrap`, `KAFSCALE_BOOTSTRAP` -> `opentsx.kafscale.bootstrap` (string)<br>`--lateness` -> `opentsx.stats.allowedLateness` (duration = `PT0S`)<br>`--checkpoint` -> `opentsx.stats.checkpoint` (path)<br>`--observations` -> `opentsx.stats.observations` (boolean = `false`)<br>`--once` -> `opentsx.stats.once` (boolean = `false`)<br>`--alpha` -> `opentsx.stats.sketchAlpha` (double = `0.01`)<br>`--windows` -> `opentsx.stats.windows` (list of duration = `PT10M,PT1H`) |
| `stats-flink` | [StatsJob.java:113-150](../opentsx-flink-core/src/main/java/org/opentsx/flink/stats/StatsJob.java) | `--domain` -> `opentsx.domain` (string = `wind`)<br>`--kafscale`, `--bootstrap`, `KAFSCALE_BOOTSTRAP` -> `opentsx.kafscale.bootstrap` (string)<br>`--lateness` -> `opentsx.stats.allowedLateness` (duration = `PT0S`)<br>`--checkpoint-every` -> `opentsx.stats.checkpointInterval` (duration = `PT30S`)<br>`--global-every` -> `opentsx.stats.globalInterval` (duration = `PT30S`)<br>`--observations` -> `opentsx.stats.observations` (boolean = `false`)<br>`--once` -> `opentsx.stats.once` (boolean = `false`)<br>`--alpha` -> `opentsx.stats.sketchAlpha` (double = `0.01`)<br>`--windows` -> `opentsx.stats.windows` (list of duration = `PT10M,PT1H`) |
| `windpark-sim` | [WindparkSim.java:46-113](../opentsx-windpark/src/main/java/org/opentsx/windpark/WindparkSim.java) | `--domain` -> `opentsx.domain` (string = `wind`)<br>`--namespace` -> `opentsx.iceberg.namespace` (string = `tsx`)<br>`--iceberg` -> `opentsx.iceberg.properties` (list)<br>`--kafscale`, `--bootstrap`, `KAFSCALE_BOOTSTRAP` -> `opentsx.kafscale.bootstrap` (string)<br>`--partitions` -> `opentsx.kafscale.partitions` (int = `6`)<br>`--catalog` -> `opentsx.windpark.catalog` (path)<br>`--ground-truth` -> `opentsx.windpark.groundTruth` (path)<br>`--index` -> `opentsx.windpark.index` (path)<br>`--scenario` -> `opentsx.windpark.scenario` (path) |
| `metadata` | [WindparkMetadata.java:37-75](../opentsx-openmetadata/src/main/java/org/opentsx/openmetadata/WindparkMetadata.java) | `--domain` -> `opentsx.domain` (string = `wind`)<br>`--namespace` -> `opentsx.iceberg.namespace` (string = `tsx`)<br>`--kafscale`, `--bootstrap`, `KAFSCALE_BOOTSTRAP` -> `opentsx.kafscale.bootstrap` (string)<br>`--partitions` -> `opentsx.kafscale.partitions` (int = `6`)<br>`--dq-window` -> `opentsx.openmetadata.dqWindow` (duration = `PT10M`)<br>`--dry-run` -> `opentsx.openmetadata.dryRunDir` (path)<br>`--profile-window` -> `opentsx.openmetadata.profileWindow` (duration = `PT1H`)<br>`--token`, `OPENMETADATA_JWT` -> `opentsx.openmetadata.token` (string secret)<br>`--om` -> `opentsx.openmetadata.url` (string)<br>`--catalog` -> `opentsx.windpark.catalog` (path)<br>`--scenario` -> `opentsx.windpark.scenario` (path) |
| `retrieval-server` | [RetrievalServer.java:537-631](../opentsx-retrieval/src/main/java/org/opentsx/retrieval/server/RetrievalServer.java) | `--domain` -> `opentsx.domain` (string = `wind`)<br>`--namespace` -> `opentsx.iceberg.namespace` (string = `tsx`)<br>`--iceberg` -> `opentsx.iceberg.properties` (list)<br>`--kafscale`, `--bootstrap`, `KAFSCALE_BOOTSTRAP` -> `opentsx.kafscale.bootstrap` (string)<br>`--dag-run-token`, `OPENTSX_DAG_RUN_TOKEN` -> `opentsx.retrieval.dagRunToken` (string secret)<br>`--dag-runs` -> `opentsx.retrieval.dagRuns` (boolean = `false`)<br>`--max-episodes` -> `opentsx.retrieval.maxEpisodes` (int = `20000`)<br>`--port` -> `opentsx.retrieval.port` (int = `8080`)<br>`--refresh` -> `opentsx.retrieval.refreshInterval` (duration = `PT30S`)<br>`--systems` -> `opentsx.retrieval.systems` (path)<br>`--systems-root` -> `opentsx.retrieval.systemsRoot` (path = `.`)<br>`--catalog` -> `opentsx.windpark.catalog` (path)<br>`--scenario` -> `opentsx.windpark.scenario` (path) |
| `vectors` | [VectorProcessorApp.java:31-66](../opentsx-retrieval/src/main/java/org/opentsx/retrieval/vector/VectorProcessorApp.java) | `--domain` -> `opentsx.domain` (string = `wind`)<br>`--kafscale`, `--bootstrap`, `KAFSCALE_BOOTSTRAP` -> `opentsx.kafscale.bootstrap` (string)<br>`--state` -> `opentsx.vectors.state` (path = `vector-state`) |
| `vectors run` | same | `--domain` -> `opentsx.domain` (string = `wind`)<br>`--kafscale`, `--bootstrap`, `KAFSCALE_BOOTSTRAP` -> `opentsx.kafscale.bootstrap` (string)<br>`--state` -> `opentsx.vectors.state` (path = `vector-state`) |
| `vectors deploy` | same | `--domain` -> `opentsx.domain` (string = `wind`)<br>`--kafscale`, `--bootstrap`, `KAFSCALE_BOOTSTRAP` -> `opentsx.kafscale.bootstrap` (string)<br>`--by` -> `opentsx.vectors.by` (string = `cli`)<br>`--dag` -> `opentsx.vectors.dag` (path) |
| `vectors stop` | same | `--domain` -> `opentsx.domain` (string = `wind`)<br>`--kafscale`, `--bootstrap`, `KAFSCALE_BOOTSTRAP` -> `opentsx.kafscale.bootstrap` (string)<br>`--by` -> `opentsx.vectors.by` (string = `cli`)<br>`--name` -> `opentsx.vectors.name` (string) |
| `twin` | [H2ChainDemo.java:92-101](../opentsx-twin/src/main/java/org/opentsx/twin/demo/H2ChainDemo.java) | `--plan` -> `opentsx.twin.plan` (path) |
| `house` | [HouseDemo.java:89-110](../opentsx-twin/src/main/java/org/opentsx/twin/demo/HouseDemo.java) | `--calibrate` -> `opentsx.house.bills` (path)<br>`--hdd-ref` -> `opentsx.house.hddRef` (double = `4300`)<br>`--weather` -> `opentsx.house.weather` (path)<br>`--plan` -> `opentsx.twin.plan` (path)<br>`--simulated` -> `opentsx.twin.simulated` (boolean = `false`) |
| `boat` | [BoatDemo.java:178-196](../opentsx-twin/src/main/java/org/opentsx/twin/demo/BoatDemo.java) | `--calibrate` -> `opentsx.boat.calibrate` (boolean = `false`)<br>`--data` -> `opentsx.boat.data` (path)<br>`--zone` -> `opentsx.boat.zone` (string = `Europe/Berlin`)<br>`--plan` -> `opentsx.twin.plan` (path)<br>`--simulated` -> `opentsx.twin.simulated` (boolean = `false`) |
| `twin-coupling` | [CouplingStudy.java:41-50](../opentsx-twin/src/main/java/org/opentsx/twin/demo/CouplingStudy.java) | `--csv` -> `opentsx.twin.coupling.csv` (path)<br>`--scenario` -> `opentsx.twin.scenario` (string) |
| `twin-schemas` | [AtomSchemas.java:232-240](../opentsx-twin/src/main/java/org/opentsx/twin/schema/AtomSchemas.java) | `--out` -> `opentsx.twin.schemas.out` (path = `opentsx-twin/src/main/resources/twin/avro`) |
| `twin-live publish` | [TwinLive.java:33-150](../opentsx-twin-live/src/main/java/org/opentsx/twin/live/TwinLive.java) | `--kafscale`, `--bootstrap`, `KAFSCALE_BOOTSTRAP` -> `opentsx.kafscale.bootstrap` (string)<br>`--scenario` -> `opentsx.twin.scenario` (string)<br>`--duration` -> `opentsx.twinLive.durationSeconds` (long)<br>`--loop` -> `opentsx.twinLive.loop` (boolean = `false`) |
| `twin-live bridge` | same | `--kafscale`, `--bootstrap`, `KAFSCALE_BOOTSTRAP` -> `opentsx.kafscale.bootstrap` (string)<br>`--allow-origin`, `EVENT_BRIDGE_ALLOW_ORIGIN` -> `opentsx.twinLive.allowOrigin` (string = `*`)<br>`--max-streams` -> `opentsx.twinLive.maxStreams` (int = `16`)<br>`--port` -> `opentsx.twinLive.port` (int = `8090`) |
| `twin-live demo` | same | `--scenario` -> `opentsx.twin.scenario` (string)<br>`--allow-origin`, `EVENT_BRIDGE_ALLOW_ORIGIN` -> `opentsx.twinLive.allowOrigin` (string = `*`)<br>`--duration` -> `opentsx.twinLive.durationSeconds` (long)<br>`--max-streams` -> `opentsx.twinLive.maxStreams` (int = `16`)<br>`--port` -> `opentsx.twinLive.port` (int = `8090`) |
| `dwd stations` | [DwdCli.java:74-262](../opentsx-dwd/src/main/java/org/opentsx/dwd/DwdCli.java) | `HTTPS_PROXY`, `https_proxy` -> `opentsx.https.proxy` (string secret)<br>`--near` -> `opentsx.dwd.near` (list of double)<br>`--n` -> `opentsx.dwd.nearestCount` (int = `5`)<br>`--offline` -> `opentsx.dwd.offline` (path)<br>`--catalog` -> `opentsx.dwd.stationCatalog` (enum = `mosmix`) |
| `dwd mosmix` | same | `--boat` -> `opentsx.dwd.boat` (path)<br>`--daily` -> `opentsx.dwd.daily` (path)<br>`HTTPS_PROXY`, `https_proxy` -> `opentsx.https.proxy` (string secret)<br>`--offline` -> `opentsx.dwd.offline` (path)<br>`--out` -> `opentsx.dwd.out` (path)<br>`--station` -> `opentsx.dwd.station` (string) |
| `dwd obs` | same | `--boat` -> `opentsx.dwd.boat` (path)<br>`--daily` -> `opentsx.dwd.daily` (path)<br>`--hourly` -> `opentsx.dwd.hourly` (boolean = `false`)<br>`HTTPS_PROXY`, `https_proxy` -> `opentsx.https.proxy` (string secret)<br>`--offline` -> `opentsx.dwd.obs.offline` (list of path)<br>`--out` -> `opentsx.dwd.out` (path)<br>`--param` -> `opentsx.dwd.params` (list of enum)<br>`--station` -> `opentsx.dwd.station` (string) |
| `dwd house` | same | `HTTPS_PROXY`, `https_proxy` -> `opentsx.https.proxy` (string secret)<br>`--offline` -> `opentsx.dwd.offline` (path)<br>`--station` -> `opentsx.dwd.station` (string) |
| `modbus check` | [ModbusCli.java:58-279](../opentsx-modbus/src/main/java/org/opentsx/modbus/ModbusCli.java) | `--host` -> `opentsx.modbus.host` (string)<br>`--map` -> `opentsx.modbus.map` (string)<br>`--port` -> `opentsx.modbus.port` (int)<br>`--unit` -> `opentsx.modbus.unitId` (int) |
| `modbus read` | same | `--host` -> `opentsx.modbus.host` (string)<br>`--map` -> `opentsx.modbus.map` (string)<br>`--port` -> `opentsx.modbus.port` (int)<br>`--simulate` -> `opentsx.modbus.simulate` (boolean = `false`)<br>`--seed` -> `opentsx.modbus.simulator.seed` (long = `42`)<br>`--unit` -> `opentsx.modbus.unitId` (int) |
| `modbus poll` | same | `--count` -> `opentsx.modbus.count` (long = `0`)<br>`--episode` -> `opentsx.modbus.episodeLength` (duration = `PT1M`)<br>`--file` -> `opentsx.modbus.file` (path)<br>`--host` -> `opentsx.modbus.host` (string)<br>`--interval` -> `opentsx.modbus.interval` (duration)<br>`--map` -> `opentsx.modbus.map` (string)<br>`--out` -> `opentsx.modbus.out` (enum = `csv`)<br>`--port` -> `opentsx.modbus.port` (int)<br>`--simulate` -> `opentsx.modbus.simulate` (boolean = `false`)<br>`--seed` -> `opentsx.modbus.simulator.seed` (long = `42`)<br>`--unit` -> `opentsx.modbus.unitId` (int) |
| `modbus simulate` | same | `--map` -> `opentsx.modbus.map` (string)<br>`--bind` -> `opentsx.modbus.simulator.bind` (string = `127.0.0.1`)<br>`--port` -> `opentsx.modbus.simulator.port` (int = `1502`)<br>`--seconds` -> `opentsx.modbus.simulator.seconds` (long = `0`)<br>`--seed` -> `opentsx.modbus.simulator.seed` (long = `42`)<br>`--unit` -> `opentsx.modbus.unitId` (int) |
| `market backfill` | [MarketCli.java:90-450](../opentsx-market/src/main/java/org/opentsx/market/MarketCli.java) | `--archive`, `--history` -> `opentsx.archive.dir` (path = `data/history`)<br>`--from-year` -> `opentsx.market.backfill.fromYear` (int = `2019`)<br>`--series` -> `opentsx.market.backfill.series` (string = `market`)<br>`--to-year` -> `opentsx.market.backfill.toYear` (int)<br>`--delay-ms` -> `opentsx.market.backfillDelayMs` (long = `500`)<br>`ENTSOE_TOKEN` -> `opentsx.market.entsoeToken` (string secret)<br>`--force` -> `opentsx.market.force` (boolean = `false`)<br>`NTP_CLIENT_ID` -> `opentsx.market.ntpClientId` (string secret)<br>`NTP_CLIENT_SECRET` -> `opentsx.market.ntpClientSecret` (string secret) |
| `market catalog` | same | no options |
| `market forward` | same | `--archive`, `--history` -> `opentsx.archive.dir` (path = `data/history`)<br>`--series` -> `opentsx.market.backfill.series` (string = `market`)<br>`--to-year` -> `opentsx.market.backfill.toYear` (int)<br>`--delay-ms` -> `opentsx.market.backfillDelayMs` (long = `500`)<br>`ENTSOE_TOKEN` -> `opentsx.market.entsoeToken` (string secret)<br>`--force` -> `opentsx.market.force` (boolean = `false`)<br>`NTP_CLIENT_ID` -> `opentsx.market.ntpClientId` (string secret)<br>`NTP_CLIENT_SECRET` -> `opentsx.market.ntpClientSecret` (string secret) |
| `market import` | same | `--archive`, `--history` -> `opentsx.archive.dir` (path = `data/history`)<br>`--file` -> `opentsx.market.import.file` (path)<br>`--metric` -> `opentsx.market.import.metric` (string)<br>`--origin` -> `opentsx.market.import.origin` (enum = `REPORTED`)<br>`--partner` -> `opentsx.market.import.partner` (string = `default`)<br>`--resolution` -> `opentsx.market.import.resolution` (duration)<br>`--unit` -> `opentsx.market.import.unit` (string)<br>`--value-column` -> `opentsx.market.import.valueColumn` (string)<br>`--zone` -> `opentsx.market.import.zone` (string)<br>`--name` -> `opentsx.market.name` (string) |
| `market load` | same | `--archive`, `--history` -> `opentsx.archive.dir` (path = `data/history`)<br>`--namespace` -> `opentsx.iceberg.namespace` (string = `tsx`)<br>`--iceberg` -> `opentsx.iceberg.properties` (list)<br>`--from-day` -> `opentsx.market.fromDay` (string)<br>`--from-year` -> `opentsx.market.fromYear` (int = `2000`)<br>`--series` -> `opentsx.market.series` (string = `all-archived`)<br>`--to-day` -> `opentsx.market.toDay` (string)<br>`--to-year` -> `opentsx.market.toYear` (int = `2100`) |
| `market marktwert` | same | `--archive`, `--history` -> `opentsx.archive.dir` (path = `data/history`)<br>`ENTSOE_TOKEN` -> `opentsx.market.entsoeToken` (string secret)<br>`--delay-ms` -> `opentsx.market.fetchDelayMs` (long = `300`)<br>`--from` -> `opentsx.market.from` (string)<br>`NTP_CLIENT_ID` -> `opentsx.market.ntpClientId` (string secret)<br>`NTP_CLIENT_SECRET` -> `opentsx.market.ntpClientSecret` (string secret)<br>`--offline` -> `opentsx.market.offline` (path)<br>`--out` -> `opentsx.market.out` (path)<br>`--to` -> `opentsx.market.to` (string) |
| `market mosmix-archive` | same | `--archive`, `--history` -> `opentsx.archive.dir` (path = `data/history`)<br>`--stations` -> `opentsx.market.mosmix.stations` (list = `10579,10097,10046,E5411`) |
| `market prices` | same | `--archive`, `--history` -> `opentsx.archive.dir` (path = `data/history`)<br>`ENTSOE_TOKEN` -> `opentsx.market.entsoeToken` (string secret)<br>`--delay-ms` -> `opentsx.market.fetchDelayMs` (long = `300`)<br>`--from` -> `opentsx.market.from` (string)<br>`NTP_CLIENT_ID` -> `opentsx.market.ntpClientId` (string secret)<br>`NTP_CLIENT_SECRET` -> `opentsx.market.ntpClientSecret` (string secret)<br>`--offline` -> `opentsx.market.offline` (path)<br>`--out` -> `opentsx.market.out` (path)<br>`--source` -> `opentsx.market.source` (enum = `smard`)<br>`--to` -> `opentsx.market.to` (string) |
| `market publish` | same | `--archive`, `--history` -> `opentsx.archive.dir` (path = `data/history`)<br>`--domain` -> `opentsx.domain` (string = `wind`)<br>`--kafscale`, `--bootstrap`, `KAFSCALE_BOOTSTRAP` -> `opentsx.kafscale.bootstrap` (string)<br>`--from-day` -> `opentsx.market.fromDay` (string)<br>`--from-year` -> `opentsx.market.fromYear` (int = `2000`)<br>`--batch` -> `opentsx.market.publish.batch` (int = `100`)<br>`--dry-run` -> `opentsx.market.publish.dryRun` (boolean = `false`)<br>`--pause-ms` -> `opentsx.market.publish.pauseMs` (long = `3000`)<br>`--series` -> `opentsx.market.publish.series` (list)<br>`--to-day` -> `opentsx.market.toDay` (string)<br>`--to-year` -> `opentsx.market.toYear` (int = `2100`) |
| `market rebap` | same | `--archive`, `--history` -> `opentsx.archive.dir` (path = `data/history`)<br>`ENTSOE_TOKEN` -> `opentsx.market.entsoeToken` (string secret)<br>`--delay-ms` -> `opentsx.market.fetchDelayMs` (long = `300`)<br>`--from` -> `opentsx.market.from` (string)<br>`NTP_CLIENT_ID` -> `opentsx.market.ntpClientId` (string secret)<br>`NTP_CLIENT_SECRET` -> `opentsx.market.ntpClientSecret` (string secret)<br>`--offline` -> `opentsx.market.offline` (path)<br>`--out` -> `opentsx.market.out` (path)<br>`--to` -> `opentsx.market.to` (string) |
| `market report` | same | `--archive`, `--history` -> `opentsx.archive.dir` (path = `data/history`)<br>`--requality` -> `opentsx.market.requality` (boolean = `false`)<br>`--series` -> `opentsx.market.series` (string = `all-archived`) |
| `market series` | same | `--archive`, `--history` -> `opentsx.archive.dir` (path = `data/history`)<br>`ENTSOE_TOKEN` -> `opentsx.market.entsoeToken` (string secret)<br>`--delay-ms` -> `opentsx.market.fetchDelayMs` (long = `300`)<br>`--from` -> `opentsx.market.from` (string)<br>`--name` -> `opentsx.market.name` (string)<br>`NTP_CLIENT_ID` -> `opentsx.market.ntpClientId` (string secret)<br>`NTP_CLIENT_SECRET` -> `opentsx.market.ntpClientSecret` (string secret)<br>`--offline` -> `opentsx.market.offline` (path)<br>`--out` -> `opentsx.market.out` (path)<br>`--to` -> `opentsx.market.to` (string) |
| `market weather-backfill` | same | `--archive`, `--history` -> `opentsx.archive.dir` (path = `data/history`)<br>`--sites` -> `opentsx.history.sites` (path)<br>`--from-year` -> `opentsx.market.backfill.fromYear` (int = `2019`)<br>`--to-year` -> `opentsx.market.backfill.toYear` (int)<br>`--delay-ms` -> `opentsx.market.backfillDelayMs` (long = `500`)<br>`--force` -> `opentsx.market.force` (boolean = `false`)<br>`--stations` -> `opentsx.market.weather.stations` (list) |
| `market weather-forward` | same | `--archive`, `--history` -> `opentsx.archive.dir` (path = `data/history`)<br>`--sites` -> `opentsx.history.sites` (path)<br>`--to-year` -> `opentsx.market.backfill.toYear` (int)<br>`--delay-ms` -> `opentsx.market.backfillDelayMs` (long = `500`)<br>`--force` -> `opentsx.market.force` (boolean = `false`)<br>`--stations` -> `opentsx.market.weather.stations` (list) |
| `history-twin twin` | [HistoryTwins.java:41-183](../opentsx-market/src/main/java/org/opentsx/market/twin/HistoryTwins.java) | `--archive`, `--history` -> `opentsx.archive.dir` (path = `data/history`)<br>`--sites` -> `opentsx.history.sites` (path)<br>`--cf` -> `opentsx.history.twin.capacityFactor` (double = `0.30`)<br>`--days` -> `opentsx.history.twin.days` (int)<br>`--from` -> `opentsx.history.twin.from` (string)<br>`--wind` -> `opentsx.history.twin.wind` (enum = `smard`)<br>`--year` -> `opentsx.history.twin.year` (int = `2024`) |
| `history-twin house` | same | `--archive`, `--history` -> `opentsx.archive.dir` (path = `data/history`)<br>`--levers` -> `opentsx.history.house.levers` (boolean = `false`)<br>`--weather-year` -> `opentsx.history.house.weatherYear` (string = `2023`)<br>`--radiation-station` -> `opentsx.history.radiationStation` (string)<br>`--sites` -> `opentsx.history.sites` (path)<br>`--station` -> `opentsx.history.station` (string)<br>`--hdd-ref` -> `opentsx.house.hddRef` (double = `4300`) |
| `history-twin boat` | same | `--archive`, `--history` -> `opentsx.archive.dir` (path = `data/history`)<br>`--days` -> `opentsx.history.boat.days` (int = `14`)<br>`--start` -> `opentsx.history.boat.start` (string)<br>`--weather-year` -> `opentsx.history.boat.weatherYear` (int = `2024`)<br>`--radiation-station` -> `opentsx.history.radiationStation` (string)<br>`--sites` -> `opentsx.history.sites` (path)<br>`--station` -> `opentsx.history.station` (string) |
| `kafscale-check` | [KafScaleCheck.java:55-101](../deploy/docker/src/org/opentsx/deploy/KafScaleCheck.java) | `--domain` -> `opentsx.domain` (string = `wind`)<br>`--kafscale`, `--bootstrap`, `KAFSCALE_BOOTSTRAP` -> `opentsx.kafscale.bootstrap` (string)<br>`--timeout` -> `opentsx.kafscale.checkTimeout` (duration = `PT20S`)<br>`--partitions` -> `opentsx.kafscale.partitions` (int = `6`)<br>`--provision` -> `opentsx.kafscale.provision` (boolean = `false`) |
| `studio-export` | [StudioExport.java:39-45](../opentsx-twin/src/main/java/org/opentsx/twin/studio/StudioExport.java) | positional `<out.json>`; no options |
| none (developer tool) | [SimulatedLogs.java:214-226](../opentsx-twin/src/main/java/org/opentsx/twin/boat/SimulatedLogs.java) | positional `<out.csv> [seed]` (seed default 20260704); no options, no command |
| none (developer tool) | [SimulatedBills.java:324-330](../opentsx-twin/src/main/java/org/opentsx/twin/house/SimulatedBills.java) | positional `[dir]` (default `opentsx-twin/src/main/resources`); no options, no command |
| none (developer tool) | `OperatorCatalog.java:485-494` in `opentsx-retrieval/src/main/java/org/opentsx/retrieval/system/`, new and not yet committed | positional `[root]` (default `.`); no options, no command |
| `classpath` | [bin/opentsx](../bin/opentsx) | launcher function, not a Java command; stays in the shell scripts |

Launcher variables, not keys: `OPENTSX_HOME`, `OPENTSX_EXTRA_ARGS` and `JAVA_OPTS` in
[opentsx-entrypoint.sh](../deploy/docker/opentsx-entrypoint.sh); `OPENTSX_CONF_DIR` (new, D4). The k3s manifests pass
ConfigMap values as variables, not as flags
([Pass settings to a command](../deploy/k3s/README.md#pass-settings-to-a-command)). The containers map the ConfigMap
keys that `deploy/k3s/.env` feeds, `OPENTSX_PARTITIONS`, `OM_URL`, `ICEBERG_PROPS`, `RETRIEVAL_DAG_RUNS` and
`MODBUS_HOST`, `MODBUS_PORT`, `MODBUS_MAP`, onto `OPENTSX_KAFSCALE_PARTITIONS`, `OPENTSX_OPENMETADATA_URL`,
`OPENTSX_ICEBERG_PROPERTIES`, `OPENTSX_RETRIEVAL_DAG_RUNS` and `OPENTSX_MODBUS_HOST`, `OPENTSX_MODBUS_PORT`,
`OPENTSX_MODBUS_MAP`. The base keys are `OPENTSX_WINDPARK_SCENARIO`, `OPENTSX_STATS_WINDOWS`,
`OPENTSX_STATS_ALLOWED_LATENESS` and `OPENTSX_ICEBERG_NAMESPACE`.

## Sub-Tasks

The order matters: the foundation first, then one batch per module, then errors and logging.

- [x] Specification, registry with 126 keys and 48 commands, JSON Schema, 272 shared vectors (this change).
- [x] **Foundation, Java**: module `opentsx-conf` (package `org.opentsx.conf`): `OpenTsxConf`, `Registry`,
      `OpenTsxConfException`, properties and YAML readers, the loader, `config list|show|get` and `version` on one main
      class; tests: registry rules 1 to 8, byte equality with the Python copy, every vector. `bin/opentsx` and
      `opentsx-entrypoint.sh` get `config` and `version`.
- [x] **Foundation, Python**: `opentsx.conf` with the same API, the copy of the registry as package data, the extra
      `opentsx[yaml]`, the console script `opentsx`; tests: the same as Java. CI installs the extra for one Python
      version and runs the YAML vectors; the others check the message that names the extra.
- [x] **Migration `opentsx-stats`** and `stats-flink` (`opentsx-flink-core`): `StatsProcessorApp`, `StatsJob`.
- [x] **Migration `opentsx-windpark`**: `WindparkSim`, with the explicit sink key (mode rule).
- [x] **Migration `opentsx-openmetadata`**: `WindparkMetadata`; `--token` becomes an error (D7), with the profiles mode key.
- [x] **Migration `opentsx-retrieval`**: `RetrievalServer` (source key, `--dag-run-token` becomes an error, `--demo`
      leaves `deploy/k3s/base/retrieval.yaml`), `VectorProcessorApp`.
- [x] **Migration `opentsx-twin`**: `H2ChainDemo`, `HouseDemo`, `BoatDemo`, `CouplingStudy`, `AtomSchemas`,
      `StudioExport`.
- [x] **Migration `opentsx-twin-live`**: `TwinLive`.
- [x] **Migration `opentsx-dwd`**: `DwdCli`, `DwdClient` (proxy through `opentsx.https.proxy`; JVM proxy properties
      keep winning).
- [x] **Migration `opentsx-modbus`**: `ModbusCli`.
- [x] **Migration `opentsx-market`**: `MarketCli`, `BrokerPublish`, `Collector` (credentials), `HistoryTwins`; exit
      codes 3 and 4 become 1 and 2 (D9), [opentsx-market/README.md](../opentsx-market/README.md) line 117 changes.
- [x] **Migration deploy**: `KafScaleCheck`, the k3s manifests and `opentsx-entrypoint.sh` (dispatch of `vectors`,
      `history-twin`), `docs/user-manual/appendix-a-cli.md`.
- [ ] **Errors** (audit of the error handling): one `main` per command catches `OpenTsxConfException` (exit 2) and every other
      exception (exit 1); no swallowed exception in the main classes (for example `catch (Exception ignored)` at
      [`WindparkSim.java:119`](../opentsx-windpark/src/main/java/org/opentsx/windpark/WindparkSim.java)); regression
      tests per command for exit codes 1 and 2.
- [ ] **Logging** (audit of the logging): one key for the log level of the v2 commands, `opentsx.log.level` (done:
      `TwinLive` no longer sets the slf4j system property and retrieval-kafscale has no `JAVA_OPTS`); the effective
      configuration logged once at start with secrets masked (Spark's `spark.logConf`).
- [ ] **Release**: replace `unreleased` in the registry with the release version; the release gate fails while one is
      left.

## Dependencies

- TASK-011 (data model v2, `TopicLayout`, `KafScaleClients`): the keys `opentsx.domain` and
  `opentsx.kafscale.bootstrap` feed them unchanged.
- Other open work in the same modules (TASK-015 to TASK-017) touches the main classes; each migration batch rebases
  on it and keeps its own pull request.

## Success Criteria

- Both runtimes pass all 288 vectors and the registry rules 1 to 8; the registry copies are byte-identical.
- `opentsx config list`, `config show` and `config get` give the same bytes in Java and Python for the same registry,
  environment and files (the `show` vectors). `version` prints the version of each runtime (see
  [Command line](#command-line-d9)), and the YAML parser detail after `not valid YAML; ` differs.
- Every command of the migration table rejects an unknown flag, a flag without value and a malformed value with exit
  code 2 and one line on stderr, proven by a regression test per module.
- No secret appears on a command line in `deploy/`, in docs or in a log.

## Timeline

Foundation first (Java and Python in parallel), then the migration batches in the order of the sub-tasks; each batch
is one pull request.

## Notes

Findings of the inventory that the migration batches must handle; each is a behavior change for its changelog:

- `retrieval-server --demo true` is documented in the class comment and passed by
  [retrieval.yaml:28](../deploy/k3s/base/retrieval.yaml), but never read: the demo store is the fallback
  (`RetrievalServer.java:556-558`). It is not in the registry; the retrieval batch removes it from the manifest and the
  docs, or maps it onto the source key.
- `modbus simulate --bind` defaults to `127.0.0.1` in the code (`ModbusCli.java:238`), the class comment says
  `0.0.0.0`. The registry has the code's default.
- `twin-live --loop false` loops today, because only the presence of `--loop` counts (`TwinLive.java:90`); with D9 it
  does not loop.
- `market import --origin` takes any text today (`MarketCli.java:125`); the registry allows `REPORTED` and
  `FORECAST`, as the usage text says.
- `modbus --interval` accepts `1.5s`, `5 s` and upper-case units today (`RegisterMap.java:212-232`); D3 does not.
  `deploy/k3s/jobs/modbus-poll.yaml` passes `1s`, which stays valid.
- `market publish` requires `--domain` (`BrokerPublish.java:49`); with the registry default `wind` the command checks
  `contains("opentsx.domain")`.
- `KAFSCALE_BOOTSTRAP` is read by `twin-live` only today; as a legacy name of `opentsx.kafscale.bootstrap` it reaches
  every command that reads the key. The mode rule above keeps it from switching modes.
- The defaults of `dwd obs --param`, `history-twin twin --from` and `--days`, `history-twin boat --start`,
  `market backfill --to-year` and `twin-live --duration` depend on other values (the running year, `--hourly`, the
  subcommand); their keys have no `default`, and the `doc` says what happens without a value.
- Flags that mean different things in different commands got different keys: `--port` (retrieval, twin-live, modbus
  target, modbus simulator), `--calibrate` (a bills file for `house`, a switch for `boat`), `--offline` (one file, or a
  list of files for `dwd obs`), `--series`, `--from-year`, `--delay-ms` and `--stations` in `market` (different
  defaults per subcommand).
