# 18. Configuration

This chapter gets you to set, check and read the configuration of OpenTSx; verified with `opentsx 3.0.1 (java)` and
`opentsx 1.0.0 (python)`.

You need JDK 17 or 21 and Maven 3.9 for `bin/opentsx config`, and Python 3.10 to 3.13 for the console script
`opentsx`. Build the module and install the package once, from the repository root:

```bash
mvn -B -ntp -q -pl opentsx-conf install
pip install -e "python-package[yaml]"
bin/opentsx version
opentsx version
```

```text
opentsx 3.0.1 (java)
opentsx 1.0.0 (python)
```

The extra `yaml` installs PyYAML, which the Python runtime needs for YAML files. Run every command of this chapter
from the repository root. The files of the examples go to `config/private/`, which is git-ignored.

## Know what reads the configuration

Every setting of an OpenTSx command is a key of the registry `opentsx.config.v1`, for example
`opentsx.stats.sketchAlpha`. [Appendix D](appendix-d-configuration.md) lists all 126 keys with type, default, flag and
environment variables. These read the registry today:

* `opentsx config list`, `opentsx config show`, `opentsx config get` and `opentsx version`, in both runtimes.
* `opentsx <command> --help` of the Python console script, for every command of the registry.
* Your code, through `OpenTsxConf` in Java (module `opentsx-conf`) and in Python (package `opentsx.conf`).

The other commands of [Appendix A](appendix-a-cli.md), for example `bin/opentsx stats`, parse their own flags. They
do not read `--conf`, `--properties-file`, `$OPENTSX_CONF_DIR` or the canonical `OPENTSX_` variables. The Python
console script runs only the commands above; `opentsx stats --kafscale localhost:9092` exits with 2 and
`opentsx: stats: runs only in the Java runtime; use bin/opentsx or the opentsx-tools image`.

The 2.x tools in `opentsx-core` and `opentsx-lg` use their own `ConfigManager` and `config/opentsx-default.properties`
([chapter 12](12-operations.md)). That file is not a defaults file of the registry.

## Find out which source wins

A key gets its value from one of six sources. The order matters; the highest source that sets the key wins:

1. code: `set` on `OpenTsxConf` after the load (source `code`),
2. command line: `--conf <key>=<value>`, `-c <key>=<value>` or the flag of the key (source `commandLine`),
3. environment: `OPENTSX_<NAME>`, then the legacy names of the key (source `environment`),
4. `--properties-file <path>` (source `propertiesFile`),
5. the defaults file `opentsx-defaults.conf` or `opentsx-defaults.yaml` in `$OPENTSX_CONF_DIR` (source `defaultsFile`),
6. the registry default (source `default`).

`<NAME>` is the key without `opentsx.`, in upper case, with `_` at every dot and before every upper-case letter:
`opentsx.stats.sketchAlpha` is `OPENTSX_STATS_SKETCH_ALPHA`. The legacy names are the variables the commands read
today, for example `KAFSCALE_BOOTSTRAP`; the canonical name wins over them. `opentsx config list` prints all names in
the column `ENV`.

Every source is read and checked, also when a higher source sets the same key. A malformed defaults file fails the
command even when `--conf` sets the key. A variable that is set but blank counts as unset.

## Show the effective configuration

`config show` prints every key that has a value, sorted, with its source. Without any source it prints the 65 keys
that have a registry default.

```bash
bin/opentsx config show
```

```text
KEY                                  VALUE                                      SOURCE
opentsx.archive.dir                  data/history                               default
opentsx.boat.calibrate               false                                      default
opentsx.boat.zone                    Europe/Berlin                              default
opentsx.domain                       wind                                       default
```

The output is trimmed. `opentsx config show` prints the same table. A variable in your shell can set a key without you
noticing: `HTTPS_PROXY` is a legacy name of `opentsx.dwd.httpsProxy` and shows up as `******` from `environment`.

`--output json` prints one line of JSON with the schema `opentsx.config.show.v1`:

```bash
bin/opentsx config show --output json | cut -c1-120
```

```text
{"schema":"opentsx.config.show.v1","values":[{"key":"opentsx.archive.dir","value":"data/history","source":"default"},{"k
```

`config get` prints one effective value. A key without value exits with 2.

```bash
bin/opentsx config get opentsx.stats.windows
opentsx config get opentsx.retrieval.port
```

```text
PT10M,PT1H
8080
```

`config list` prints the registry: every key with type, default, flag and variables. A secret shows no flag.
`config list --output json` prints the registry file `opentsx.config.v1.json` byte for byte.

```bash
bin/opentsx config list | grep -E '^KEY|sketchAlpha|openmetadata.token'
```

```text
KEY                                  TYPE              DEFAULT                                    FLAG                     ENV
opentsx.openmetadata.token           string            -                                          -                        OPENTSX_OPENMETADATA_TOKEN, OPENMETADATA_JWT
opentsx.stats.sketchAlpha            double            0.01                                       --alpha                  OPENTSX_STATS_SKETCH_ALPHA
```

The Python console script prints the keys of any command of the registry:

```bash
opentsx stats --help
```

```text
usage: opentsx stats [options]
Run the streaming statistics processor on KafScale.
In the Java runtime, stats parses its own flags and does not read --conf, --properties-file, OPENTSX_CONF_DIR or the canonical OPENTSX_ variables.

keys:
  opentsx.domain  string = wind  --domain  OPENTSX_DOMAIN
      Domain of the topic names tsx.<domain>.<kind>.v2.
  opentsx.kafscale.bootstrap  string  --kafscale, --bootstrap  OPENTSX_KAFSCALE_BOOTSTRAP, KAFSCALE_BOOTSTRAP
      Bootstrap address of the KafScale proxy as host:port, several separated by commas.
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

The third line says what `bin/opentsx stats` reads: the help lists every key of the registry, but the Java command
parses its own flags. In the Java runtime, `bin/opentsx config show --help` and the other `config` subcommands print
their help the same way, without that line. `--help` wins over every other error: `opentsx config --help` prints the
list of commands.

## Set a value

`--conf <key>=<value>` and `-c <key>=<value>` set a key on the command line. An environment variable sets it for every
command of the shell.

```bash
bin/opentsx config get opentsx.domain --conf opentsx.domain=solar
bin/opentsx config get opentsx.domain -c opentsx.domain=solar
OPENTSX_DOMAIN=solar bin/opentsx config get opentsx.domain
```

```text
solar
solar
solar
```

The flag of a key, for example `--domain`, sets the key only in a command that reads it. `config show` and
`config get` read every key, but take no flag of a key; use `--conf` there. A key is set at most once on the command
line, whatever spelling sets it.

`--properties-file <path>` reads the keys of one job from a file. A line is `key value` or `key = value`. A line that
starts with `#` or `!` is a comment.

```bash
mkdir -p config/private
cat > config/private/job.conf <<'EOF'
# keys of one job
opentsx.kafscale.bootstrap 192.0.2.10:9092
opentsx.domain = solar
opentsx.stats.windows=PT10M, PT1H, 1d
EOF
bin/opentsx config show --properties-file config/private/job.conf | grep -E '^KEY|propertiesFile'
```

```text
KEY                                  VALUE                                      SOURCE
opentsx.domain                       solar                                      propertiesFile
opentsx.kafscale.bootstrap           192.0.2.10:9092                            propertiesFile
opentsx.stats.windows                PT10M,PT1H,1d                              propertiesFile
```

The file is UTF-8; a file in another encoding fails with `<file>: not valid UTF-8; save the file as UTF-8`. A
backslash anywhere in a line is an error, because the format has no line continuation and no escapes; write Windows
paths with `/`. `:` is not a separator, unlike in `java.util.Properties`: `opentsx.domain:solar` fails with
`":" is not a separator`, and the message shows only the key, never the text after the `:`. A `#` after the key is
part of the value: `opentsx.kafscale.bootstrap 192.0.2.10:9092 # proxy` stores `192.0.2.10:9092 # proxy`. Put
comments on their own line. A key twice in one file is an error. A file name that ends in `.yaml` or `.yml`
is read as YAML (see [Use YAML](#use-yaml)).

A value follows the grammar of its type in both runtimes:

| Type | Valid | Not valid |
|------|-------|-----------|
| `int`, `long` | `42`, `-7` | `007`, `+5`, `1_000`, `4.0` |
| `double` | `0.01`, `1e-3`, `4300` | `.5`, `1.`, `NaN`, `1,5` |
| `boolean` | `true`, `FALSE` | `yes`, `on`, `1` |
| `duration` | ISO-8601 `PT10M`, `P1D`, `PT0.25S`; a whole number with `ms`, `s`, `m`, `min`, `h` or `d`: `500ms`, `5min`, `1d` | `pt10m`, `10`, `1.5h`, `5 s`, `P1W` |
| `list` | `a,b`, ` a , b ` (items are trimmed) | `a,,b`, `a,` |
| `enum` | one of the values the registry lists, case-sensitive | `CSV` for `csv` |
| `string`, `path` | any text without control characters | an empty value |

## Use a defaults file

The defaults file holds what is the same for every command on a machine, for example the KafScale proxy.
`OPENTSX_CONF_DIR` names its directory, which holds `opentsx-defaults.conf` or `opentsx-defaults.yaml`, not both. A
relative path is resolved against the working directory. A file named `opentsx-defaults.yml` is an error, not ignored. Start from the template, which lists every key with its
default and its sentence:

```bash
mkdir -p config/private/conf
cp conf/opentsx-defaults.conf.template config/private/conf/opentsx-defaults.conf
```

Every key of the template is commented out, so the copy sets nothing yet. Open
`config/private/conf/opentsx-defaults.conf`, remove the `# ` in front of the keys you set and write their values:

```properties
# Bootstrap address of the KafScale proxy as host:port, several separated by commas.
# Type: string; no default.
opentsx.kafscale.bootstrap 192.0.2.10:9092
```

```properties
# Relative accuracy of the quantile sketch.
# Type: double.
opentsx.stats.sketchAlpha 0.05
```

Check the result:

```bash
OPENTSX_CONF_DIR=config/private/conf bin/opentsx config show | grep -E '^KEY|defaultsFile'
```

```text
KEY                                  VALUE                                      SOURCE
opentsx.kafscale.bootstrap           192.0.2.10:9092                            defaultsFile
opentsx.stats.sketchAlpha            0.05                                       defaultsFile
```

Export `OPENTSX_CONF_DIR` in your shell profile to use the file for every command. Each higher source wins over the
defaults file:

```bash
printf 'opentsx.stats.sketchAlpha 0.04\n' > config/private/alpha.conf
OPENTSX_CONF_DIR=config/private/conf OPENTSX_STATS_SKETCH_ALPHA=0.03 bin/opentsx config show --properties-file config/private/alpha.conf --conf opentsx.stats.sketchAlpha=0.02 | grep -E '^KEY|sketchAlpha'
```

```text
KEY                                  VALUE                                      SOURCE
opentsx.stats.sketchAlpha            0.02                                       commandLine
```

The same command with fewer sources, in both runtimes:

| Sources given | Value | Source |
|---------------|-------|--------|
| `--conf`, environment, `--properties-file`, defaults file | `0.02` | `commandLine` |
| environment, `--properties-file`, defaults file | `0.03` | `environment` |
| `--properties-file`, defaults file | `0.04` | `propertiesFile` |
| defaults file | `0.05` | `defaultsFile` |
| none | `0.01` | `default` |

Unlike Spark, `--properties-file` does not replace the defaults file. Both are read, each with its own rank.

## Use YAML

A file whose name ends in `.yaml` or `.yml` is read as YAML, both with `--properties-file` and as
`opentsx-defaults.yaml`. Nested maps become dotted keys, and a dotted key is allowed at any level. A YAML list sets a
key of type `list`.

```bash
cat > config/private/job.yaml <<'EOF'
opentsx:
  kafscale:
    bootstrap: 192.0.2.10:9092
  stats:
    sketchAlpha: 0.010
    windows: [PT10M, PT1H, 1d]
opentsx.domain: solar
EOF
bin/opentsx config show --properties-file config/private/job.yaml | grep -E '^KEY|propertiesFile'
opentsx config show --properties-file config/private/job.yaml | grep -E '^KEY|propertiesFile'
```

```text
KEY                                  VALUE                                      SOURCE
opentsx.domain                       solar                                      propertiesFile
opentsx.kafscale.bootstrap           192.0.2.10:9092                            propertiesFile
opentsx.stats.sketchAlpha            0.010                                      propertiesFile
opentsx.stats.windows                PT10M,PT1H,1d                              propertiesFile
KEY                                  VALUE                                      SOURCE
opentsx.domain                       solar                                      propertiesFile
opentsx.kafscale.bootstrap           192.0.2.10:9092                            propertiesFile
opentsx.stats.sketchAlpha            0.010                                      propertiesFile
opentsx.stats.windows                PT10M,PT1H,1d                              propertiesFile
```

* A value is taken as written, not as a YAML type: `0.010` stays `0.010`, and `once: yes` is the text `yes`, which is
  not a boolean.
* An empty value, `~` and `null` are errors. Leave the key out instead.
* Aliases (`*name`), tags (`!!int`), more than one document and nesting deeper than 100 levels are errors.
* [`conf/opentsx-defaults.yaml.template`](../../conf/opentsx-defaults.yaml.template) is the template of
  `opentsx-defaults.yaml`. To set a key, remove the `# ` in front of it and in front of the maps above it.

Java reads YAML without extra packages. Python needs the extra `yaml`; without PyYAML a YAML file fails with exit
code 2:

```text
opentsx: config/private/job.yaml: YAML files need the extra opentsx[yaml]; run pip install "opentsx[yaml]"
```

## Pass secrets

Six keys are secret: `opentsx.openmetadata.token`, `opentsx.retrieval.dagRunToken`, `opentsx.market.entsoeToken`,
`opentsx.market.ntpClientId`, `opentsx.market.ntpClientSecret` and `opentsx.dwd.httpsProxy`. A secret comes from the
environment, a properties file, the defaults file or code. The command line refuses it, because a value there shows in
`ps`, the shell history and the pod spec:

```bash
bin/opentsx config show --conf opentsx.openmetadata.token=abc
```

```text
opentsx: opentsx.openmetadata.token: secret keys are not accepted on the command line; set OPENTSX_OPENMETADATA_TOKEN or put opentsx.openmetadata.token in a properties file
```

Set it in the environment instead. The legacy name works too. `<token>` stands for your token:

```bash
OPENMETADATA_JWT='<token>' bin/opentsx config show | grep -E '^KEY|openmetadata'
```

```text
KEY                                  VALUE                                      SOURCE
opentsx.openmetadata.dqWindow        PT10M                                      default
opentsx.openmetadata.profileWindow   PT1H                                       default
opentsx.openmetadata.token           ******                                     environment
```

Or keep it in a file that only you can read. Create the file, then write the line
`opentsx.market.entsoeToken <token>` into it with an editor:

```bash
touch config/private/secrets.conf
chmod 600 config/private/secrets.conf
```

```bash
bin/opentsx config show --properties-file config/private/secrets.conf | grep -E '^KEY|entsoe'
```

```text
KEY                                  VALUE                                      SOURCE
opentsx.market.entsoeToken           ******                                     propertiesFile
```

`config show`, `config get`, the JSON output, `toDebugString` and every error message show a secret as `******`. `get`
and `getAll` in code return the real value. A secret has no default.

A key that is not secret is shown in clear. `opentsx.iceberg.properties` is one of them, because `--iceberg` passes it
on the command line. Keep catalog credentials such as `credential`, `token` or `s3.secret-access-key` out of it.

The refusal applies where the registry is read. `bin/opentsx metadata` and `bin/opentsx retrieval-server` still take
`--token` and `--dag-run-token` with their own parsers. Set `OPENMETADATA_JWT` and `OPENTSX_DAG_RUN_TOKEN` for them
instead.

## Read configuration in Java

A module of the reactor reads the configuration through the module `opentsx-conf`:

```xml
<dependency>
    <groupId>org.opentsx</groupId>
    <artifactId>opentsx-conf</artifactId>
    <version>${project.version}</version>
</dependency>
```

`OpenTsxConf.load` takes the command words and the arguments, the environment and the value of `OPENTSX_CONF_DIR` as
a string; null or blank means no defaults file. It reads no process state itself. Save this class as `config/private/StatsSettings.java`:

```java
import org.opentsx.conf.OpenTsxConf;
import org.opentsx.conf.OpenTsxConfException;

import java.util.List;

public final class StatsSettings {
    public static void main(String[] args) {
        try {
            OpenTsxConf conf = OpenTsxConf.load(List.of("stats", "--alpha", "0.02"), System.getenv(),
                    System.getenv("OPENTSX_CONF_DIR")).conf();
            conf.setIfMissing("opentsx.kafscale.bootstrap", "localhost:9092");
            System.out.println("alpha     " + conf.getDouble("opentsx.stats.sketchAlpha") + " from " + conf.source("opentsx.stats.sketchAlpha"));
            System.out.println("windows   " + conf.getDurationList("opentsx.stats.windows"));
            System.out.println("bootstrap " + conf.get("opentsx.kafscale.bootstrap") + " from " + conf.source("opentsx.kafscale.bootstrap"));
            System.out.println("once      " + conf.getBoolean("opentsx.stats.once") + " from " + conf.source("opentsx.stats.once"));
        } catch (OpenTsxConfException e) {
            System.err.println("opentsx: " + e.getMessage());
            System.exit(2);
        }
    }
}
```

Run it with the module classpath:

```bash
java -cp "$(bin/opentsx classpath opentsx-conf)" config/private/StatsSettings.java
OPENTSX_STATS_ONCE=yes java -cp "$(bin/opentsx classpath opentsx-conf)" config/private/StatsSettings.java
```

```text
alpha     0.02 from commandLine
windows   [PT10M, PT1H]
bootstrap localhost:9092 from code
once      false from default
opentsx: OPENTSX_STATS_ONCE: "yes" is not a boolean; expected true or false
```

Catch `OpenTsxConfException` once, in `main`, print `opentsx: <message>` on stderr and exit with 2. Any other
exception is a runtime failure with exit code 1. With `--help`, `-h` or `--version` in the arguments, `load` checks
nothing else and returns `help()` or `version()` true and `conf()` null; check them before you read `conf()`.
`Registry.shipped().help("stats")` returns the help text of a command.

## Read configuration in Python

The same API, with the same camelCase names. Save this as `config/private/stats_settings.py`:

```python
import os
import sys

from opentsx.conf import OpenTsxConf, OpenTsxConfError

try:
    conf = OpenTsxConf.load(["stats", "--alpha", "0.02"], os.environ, os.environ.get("OPENTSX_CONF_DIR")).conf
    conf.setIfMissing("opentsx.kafscale.bootstrap", "localhost:9092")
    print("alpha    ", conf.getDouble("opentsx.stats.sketchAlpha"), "from", conf.source("opentsx.stats.sketchAlpha"))
    print("windows  ", conf.getDurationList("opentsx.stats.windows"))
    print("bootstrap", conf.get("opentsx.kafscale.bootstrap"), "from", conf.source("opentsx.kafscale.bootstrap"))
    print("once     ", conf.getBoolean("opentsx.stats.once"), "from", conf.source("opentsx.stats.once"))
except OpenTsxConfError as e:
    sys.stderr.write(f"opentsx: {e}\n")
    sys.exit(2)
```

```bash
python config/private/stats_settings.py
```

```text
alpha     0.02 from commandLine
windows   [datetime.timedelta(seconds=600), datetime.timedelta(seconds=3600)]
bootstrap localhost:9092 from code
once      False from default
```

`OpenTsxConf.load` returns `Loaded(command, conf, args, help, version)`; `args` holds the positional arguments.

## Find the same method in Java and Python

| Purpose | Java (`org.opentsx.conf`) | Python (`opentsx.conf`) |
|---------|---------------------------|-------------------------|
| empty configuration with the registry defaults | `new OpenTsxConf()` | `OpenTsxConf()` |
| load sources 2 to 6 | `OpenTsxConf.load(List<String> args, Map<String, String> env, String confDir)` | `OpenTsxConf.load(args, env, confDir)`, `confDir` a `str` or a path |
| result of the load | `record Loaded(command, conf, args, help, version)` | `Loaded(command, conf, args, help, version)` |
| set a value in code | `set(key, value)` | `set(key, value)`, `value` a `str` |
| set unless a source of rank 1 to 5 set the key | `setIfMissing(key, value)` | `setIfMissing(key, value)` |
| set several values | `setAll(Map<String, String>)` | `setAll(pairs)`, a `dict` or pairs of `(key, value)` |
| value, error without one | `get(key)` | `get(key)` |
| value or a fallback | `get(key, defaultValue)` | `get(key, defaultValue)` |
| value if any | `getOption(key)`, an `Optional<String>` | `getOption(key)`, a `str` or `None` |
| typed value | `getInt`, `getLong`, `getDouble`, `getBoolean`, `getDuration` (`Duration`), `getList`, `getDurationList` | `getInt`, `getLong`, `getDouble`, `getBoolean`, `getDuration` (`timedelta`), `getList`, `getDurationList` |
| set by a source of rank 1 to 5 | `contains(key)` | `contains(key)` |
| every key with a value, sorted, real values | `getAll()` | `getAll()` |
| source of the value | `source(key)`, null without value | `source(key)`, `None` without value |
| `key=value` lines, secrets as `******` | `toDebugString()` | `toDebugString()` |
| error type | `OpenTsxConfException`, an `IllegalArgumentException` | `OpenTsxConfError`, a `ValueError` |
| registry of the configuration | `registry()` | `registry()` |
| names of the six sources, highest first | `OpenTsxConf.SOURCES` | `OpenTsxConf.SOURCES` |

Setters return the object, so calls chain. `set` checks the key and the value like any other source and accepts a
secret. A typed getter on a key of another type fails:
`opentsx.domain: has type string; read it with get`.

`setIfMissing` checks the value also when the key is already set, so a malformed value fails in both runtimes.

## Fix a configuration error

A usage or configuration error prints one line `opentsx: <message>` on stderr, nothing on stdout, and exits with 2. A
runtime failure exits with 1. Both runtimes print the same messages, except the parser detail after
`not valid YAML; ` and the UTF-8 message that Java prints without a UTF-8 locale:

| Message | Cause | Fix |
|---------|-------|-----|
| `opentsx.domian: unknown key; did you mean opentsx.domain?` | a misspelled key | use the key the message names |
| `spark.master: not an OpenTSx key; expected a key that starts with opentsx.` | a key outside `opentsx.` | use a key of `opentsx config list` |
| `opentsx.stats.sketchAlpha: "abc" is not a double; expected a finite decimal number such as 0.01 or 1e-3` | a value that breaks its type | write a value of the type |
| `opentsx.stats.windows: item "1.5h" is not a duration; expected ISO-8601 such as PT10M or a whole number with the unit ms, s, m, min, h or d such as 10m, at most 999999999 days` | a fraction in the unit form | write `90m` or `PT1H30M` |
| `OPENTSX_STATS_ONCE: "yes" is not a boolean; expected true or false` | a malformed variable; it fails every command that loads the configuration, also `opentsx version` and `config list` | set `true` or `false`, or unset the variable |
| `opentsx.domain: set twice on the command line (also by opentsx.domain); set each key once` | the same key twice in `--conf`, `-c` or its flag | give the key once |
| `--domain: unknown option of config show; run opentsx config show --help for the options` | a flag the command does not read | use `--conf opentsx.domain=<value>` |
| `--conf: missing value; expected --conf key=value` | `--conf` at the end of the line | give `<key>=<value>` after it |
| `--conf: "opentsx.domain" has no "="; expected --conf key=value` | `--conf` without `=` | write `--conf opentsx.domain=solar` |
| `opentsx.openmetadata.token: secret keys are not accepted on the command line; set OPENTSX_OPENMETADATA_TOKEN or put opentsx.openmetadata.token in a properties file` | a secret on the command line | see [Pass secrets](#pass-secrets) |
| `opentsx.kafscale.bootstrap: not set; set it with --conf opentsx.kafscale.bootstrap=<value> or OPENTSX_KAFSCALE_BOOTSTRAP` | `config get` of a key without value | set the key |
| `opentsx.domain: value is not valid UTF-8; pass UTF-8 text in a UTF-8 locale such as LANG=C.UTF-8` | Java without a UTF-8 locale and a value with a character outside ASCII, or a byte that is not UTF-8 | run with `LANG=C.UTF-8` |
| `config/private/latin1.conf: not valid UTF-8; save the file as UTF-8` | a file in another encoding | save it as UTF-8 |
| `config/private/colon.conf:1: opentsx.domain: ":" is not a separator; write key value or key = value` | `key:value` in a properties file | write `key value` |
| `config/private/conf/opentsx-defaults.yml: not a defaults file name; rename it to opentsx-defaults.yaml` | a defaults file with the extension `.yml` | rename it |
| `config/private/escape.conf:1: escape sequences are not supported; write the character itself` | a backslash in a properties file, for example `C:\data` | write `C:/data` |
| `config/private/novalue.conf:1: opentsx.domain has no value; write key value or key = value` | a key without value in a properties file | write the value or remove the line |
| `config/private/twice.conf:2: opentsx.domain: set twice in this file (first on line 1); set each key once` | a key twice in one file | keep one line |
| `config/private/list.yaml: opentsx.domain: a YAML list is only allowed for keys of type list; write one value` | a YAML list for a key that is not a list | write one value |
| `config/private/nope.conf: no such file; give an existing file` | a wrong path in `--properties-file` | give an existing file |
| `config/private/nope: not a directory; set OPENTSX_CONF_DIR to the directory that holds opentsx-defaults.conf or opentsx-defaults.yaml` | `OPENTSX_CONF_DIR` names no directory | fix or unset `OPENTSX_CONF_DIR` |
| `config/private/both: both opentsx-defaults.conf and opentsx-defaults.yaml exist; keep one` | two defaults files | delete one |
| `config shwo: unknown command; did you mean config show?` | a misspelled subcommand | use the subcommand the message names |
| `--output: "yaml" is not allowed; expected one of text, json` | an unknown output format | use `text` or `json` |

A result that cannot be written exits with 1 and `opentsx: stdout: cannot write the output; check that standard
output is open and not full`, for example `bin/opentsx config list > /dev/full`.

`bin/opentsx` itself checks the first word. A misspelled first word, for example `bin/opentsx conifg show`, prints
`unknown command: conifg` and the list of commands and exits with 1. The console script `opentsx conifg show` exits
with 2 and `opentsx: conifg: unknown command; did you mean config?`.
