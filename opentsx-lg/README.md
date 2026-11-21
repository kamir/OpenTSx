> **Legacy (2.x model).** These load-generator and latency tools use the 2.x topics and Confluent serializers. For KafScale and v2 episodes, see [`opentsx-kafka-v2`](../opentsx-kafka-v2/README.md) and the [user manual](../docs/user-manual/README.md).

### How to Start the OpenTSx UI?

(1) Enter the folder:
```
cd opentsx-lg
```

(2) Build the packages and images
```
mvn clean generate-sources compile package install -PSimpleTimeSeriesProducer,Docker
```

(3) Define variables
```
export OPENTSX_TOPIC_MAP_FILE_NAME=./../config/topiclist.def
export OPENTSX_PRIMARY_CLUSTER_CLIENT_CFG_FILE_NAME=./../config/opentsx-local.props
export OPENTSX_SHOW_GUI=true
export OPENTSX_USE_KAFKA=false
```

(4) Run the Application

```
mvn clean compile exec:java -Dexec.mainClass="org.opentsx.lg.TSDataSineWaveGenerator"
```

### Configure the Kafka addresses

The tools and the Kafka Streams applications `FWL_TSA_KStreamsApplication` and `FWL_TSA_KTableApplication` take
`bootstrap.servers`, `schema.registry.url` and the other client settings from the Kafka client profile that
`OPENTSX_PRIMARY_CLUSTER_CLIENT_CFG_FILE_NAME` names (see [`opentsx-connectors`](../opentsx-connectors/README.md)).

The Docker Compose files under `src/main/docker-compose/` map the cluster host names to the addresses `MDC1`, `MDC2`
and `MDC3`. Set the three variables in the `.env` file next to the compose file. The compose files in
`ping-tool-cons/` and `ping-tool-prod/` mount `src/main/docker-compose/config/` at `/etc/opentsx/config/`, and
`ENV_CONTEXT` in their `.env` and `.env-inhouse` files names the client profile there: `.env` names
`opentsx-local.props`, the client profile of the ping tools and the default profile of the image
`opentsx/time-series-generator:3.0.1` that step (2) builds and both compose files run, and `.env-inhouse` names
`cpl.props`. Both profiles name `localhost:9092` and `http://localhost:8081`. They hold no serializer or deserializer
keys: each producer and consumer sets its own.
`DockerImageTest` fails when `ENV_CONTEXT` names a file that is not in `src/main/docker-compose/config/`.
`run_demo_docker_compose.sh` starts both services with `docker compose --env-file .env-inhouse up -d`, the Compose
plugin of Docker, and not with the standalone `docker-compose` binary. The `.env` and `.env-inhouse` files set only
the variables their compose file reads, and the compose files have no `version` key. The image has no X server, and
the scripts in `src/main/docker-compose/bin/` start none: the compose files set `OPENTSX_SHOW_GUI` to `false`, so no
tool opens a chart window. `DockerImageTest` fails on a variable of `.env` or `.env-inhouse` that the compose file
does not read, also one written after `export`, after blanks or as `MDC1: <value>`. The compose file reads `MDC1` as
`${MDC1}`, `$MDC1` or a form with a modifier such as `${MDC1:-<default>}`, outside a `#` comment; `$$MDC1` is not a
read. In the compose file a `#` after a blank starts a comment outside a quoted value. A quote opens a quoted value
only after the indentation of a line or after `- `, `: `, `? `, `[`, `{` or `,`, so `${OLD}` in
`command: echo it's up # ${OLD}` is in a comment, but `${OLD}` in `command: echo a, 'b # ${OLD}'` counts as a read,
although YAML reads `# ${OLD}'` as a comment. The test also
fails on a top-level `version` key, also an indented or quoted one, on the word `docker-compose` in
`run_demo_docker_compose.sh` and on the word `Xvfb` in a script of `src/main/docker-compose/bin/`. In a script a `#`
outside quotes starts a comment at the start of a line or after a blank, `;`, `&`, `|`, `(`, `)`, `<` or `>`, as in
bash. The test finds each word outside a comment at the start of a line or after a blank, `;`, `&`, `|`, `(`, `)`, a
backquote, a quote, `=` or the `:-`, `:=`, `:+` or `:?` of a variable, also without the colon, as in
`${COMPOSE:-docker-compose}`. It also finds the word as the last part of a path that starts with `/`, `./`, `../`,
`~` or a variable, such as `/usr/bin/Xvfb`, `./docker-compose` or `$HOME/bin/docker-compose`. The word must end the
line or be followed by a blank, `;`, `&`, `|`, `(`, `)`, `<`, `>`, a backquote, a quote or `}`, so
`docker-compose.yml` and `XvfbProbe` are not found. So it finds `sudo docker-compose up -d`, `x; docker-compose up`,
`"$(docker-compose ps)"`, `${COMPOSE:-docker-compose} up`, `./docker-compose up`, `~/bin/docker-compose up`,
`$HOME/bin/docker-compose up` and `nohup Xvfb :99 &`, but not `true;# docker-compose was replaced` or
`ls src/main/docker-compose`, a path that starts with a name. It fails as well when the banner of a
`run_LatencyProbe_<step>.sh` script in `bin/` or `src/main/docker-compose/bin/` does not name the
`EventFlowAnalysis<Step>` class that the script runs, in capitals, such as `SETUP` for `EventFlowAnalysisSetup`.

The scripts use the `JAVA_HOME` you set, or the `java` on your `PATH`. `run_demo.sh` prints the KSQL statements of the
demo for the ksql CLI. `run_demo.sh`, `run_demo_container.sh`, `run_demo_docker_compose.sh`, `bin/build_all.sh`,
`bin/run_cluster.sh` and `src/main/mqtt-example/env.sh` take `CONFLUENT_HOME`, the directory of a Confluent Platform
installation, from your shell. Without it they exit with status 2 and
`CONFLUENT_HOME: not set; expected the directory of a Confluent Platform installation`.

CI runs `bash -n` and `shellcheck -x -P SCRIPTDIR` on every shell script (`*.sh`) of this module.
`tools/test_lg_scripts.py` fails on a shell script whose first line is not a shebang (`#!`) and on a `cd` that is not
followed by `|| exit` or `|| return`, also a `cd` after `!`, `command`, `builtin` or `time`. A `#` inside quotes does
not start a comment for the check, and a `cd` inside quotes, as in `echo "a; cd b"`, is not a command for the check.
A `cd` in a command substitution inside double quotes, as in `X="$(cd b)"`, is still checked, and the check follows
the quotes inside the substitution, so `DIR="$(cd "$(dirname "$0")" || exit 1; pwd)"` passes. The suggested fix ends
at the end of the substitution or subshell, so `X="$(cd b)"` gives
`<file>:<line>: cd without a guard; write cd b || exit 1`. The `&` of a redirection, as in `2>&1`, `>&2` or
`&>/dev/null`, and the `|` of `>|` do not end the `cd`, so `cd x 2>&1 || exit 1` and `cd x >| log || exit 1` pass and
`cd x 2>&1` gives `<file>:<line>: cd without a guard; write cd x 2>&1 || exit 1`. The `&` of `>&` or `<&` and the
`|` of `>|` start no `cd` either, so in `echo a >&cd x` and `echo a >|cd x` the `cd` is the file that `>&` or `>|`
writes.
`tools/test_lg_python.py` fails on a Python script (`*.py`) of this module that
does not compile or does not start with `#!/usr/bin/env python3`.

### Run the example image

Step (2) builds the image `opentsx/time-series-generator:3.0.1` on your machine with the `Docker` profile, from the base
image `eclipse-temurin:21.0.8_9-jre-noble`. The image is a local example: no workflow builds or publishes it. It holds
the jar and `/opentsx-lg/config/topiclist.def`. The jar and the image carry no Kafka client profile. The container reads
the profile at run time from the file that `OPENTSX_PRIMARY_CLUSTER_CLIENT_CFG_FILE_NAME` names,
`/etc/opentsx/config/opentsx-local.props` by default. Mount a
directory with that file at `/etc/opentsx/config/` when you start the container. Without the file and without its
`.example` template, the generator stops with
`OPENTSX_PRIMARY_CLUSTER_CLIENT_CFG_FILE_NAME: "/etc/opentsx/config/opentsx-local.props" cannot be read (/etc/opentsx/config/opentsx-local.props); give a readable Kafka client profile or its .example template`.

[`src/main/docker/core-tool-image/README.md`](src/main/docker/core-tool-image/README.md) describes a Dockerfile for the
same jar with Xvfb and x11vnc. `DockerImageTest` fails on a Dockerfile under `src/` with an `ENV <key> <value>` line or
a `CMD` or `ENTRYPOINT` in shell form, and on a YAML file under `src/main/` with a list entry `- name:` that has
no value. It joins a Dockerfile line that ends with `\` with the next line before it checks the instruction, so
`ENV \` followed by `<key>=<value>` lines and `CMD \` followed by an exec-form array pass, and it names the first line
of the instruction.
It skips comment lines and empty lines as Docker does, so neither ends an instruction, and a comment line does not
continue, even when it ends with `\`. `tools/test_registries.py` fails on every line of a tracked YAML file or
Dockerfile of the repository, also under `archive/`, that names the registry `k8s.gcr.io` and is not a comment line,
in an image such as `k8s.gcr.io/pause:3.2` or as a value such as `registry: k8s.gcr.io`, with
`<file>:<line>: k8s.gcr.io is frozen; pull the image from registry.k8s.io`.

### Grant the ACLs the latency tools need

On a cluster that checks ACLs, the principal of the latency tools needs CREATE, READ, WRITE, ALTER and DELETE on the
topics with the prefix `OpenTSx_`, and READ on the consumer group `KPING-Consumer`. Create these ACLs with the tools of
your Kafka platform. A topic of the topic list without the prefix, such as `latency_benchmark_request` and
`latency_benchmark_response` in `../config/topiclist.def`, the list of step (3), needs ACLs of its own, and so does the
consumer group `LatencyTestEventConsumer` of `RequestResponseAnalysisConsumer`.

### Keep credentials out of the repository

A client profile named `ccloud.props` anywhere in this module is git-ignored. So is the MQTT password file
`src/main/mqtt-example/volume1/docker/mqtt/config/passwd`, which `src/main/mqtt-example/01_bootstrap.sh` creates with
`mosquitto_passwd` and `02_run_mqtt_broker.sh` mounts into the broker. `tools/check_tracked_config.py`, which CI runs,
fails when a `ccloud.props` file or the password file is tracked, for example after `git add -f`.

`src/main/mqtt-example/influxdb-grafana/__run_container_01.sh` starts the container `docker-influxdb-grafana` and sets
the Grafana admin password inside it to the value of `GRAFANA_ADMIN_PASSWORD`. It passes the password on standard input
to `grafana-cli admin reset-admin-password --password-from-stdin` (Grafana 7.2.0 in the pinned image), so the password
is not in the arguments of `docker exec` or `grafana-cli`. Without the variable it starts no container and exits with
status 2 and
`GRAFANA_ADMIN_PASSWORD: missing; export the Grafana admin password to set before you run the script`.

### Versions in the MQTT example

Three scripts in `src/main/mqtt-example/` pin the package, connectors and images they install and start:

* `01_bootstrap.sh` installs `paho-mqtt==1.6.1` with `pip3 install --user`, and the connectors
  `confluentinc/kafka-connect-mqtt:1.7.11` and `confluentinc/kafka-connect-influxdb:1.2.13` with `confluent-hub`;
* `02_run_mqtt_broker.sh` starts `toke/mosquitto@sha256:3c18df61133085cec34050ed3d2f851bbe8d883483fb9167a05c616950b5e94b`;
* `influxdb-grafana/__run_container_01.sh` starts
  `philhawthorne/docker-influxdb-grafana@sha256:fe36a6b2b9fc8779c5ffc4b25cd0db5d24ebbb62ce4ac18148a4bc8c2fef0771`.

Docker Hub has no version tag for the image that `latest` names in these two repositories, so the scripts pin its
digest. `tools/test_lg_scripts.py` reads the image or connector of every `docker run`, `docker create`, `docker pull`,
`docker container run`, `docker container create`, `docker image pull`, `confluent-hub install` and
`confluent connect plugin install` in the scripts of this module, also after `&&`, `||`, `;`, `|` or a single `&` on
the same line, and of every `FROM` in its Dockerfiles. The `&` of a redirection, as in `2>&1`, `>&2` or `&>log`, and
the `|` of `>|` do not end a command, and the `&` of `>&` or `<&` starts none, so in `echo >&docker pull <image>` the
`docker` is the file that `>&` writes. A `&&`, `||`, `;`, `|` or `&` inside a command substitution ends only the
command inside it, so `X=$(cat f | head -1) docker pull <image>` is read. It reads `docker`, `confluent-hub` and
`confluent` only as the command word, also with a path
such as `/usr/bin/docker`: at the start of a command, of a `case` arm such as `start)`, `(start)` or
`` `hostname`) ``, of a command substitution, of a subshell or of a `{` group, also after variable assignments,
options (words that start with `-`, with or without one value), a number or duration such as `10` or `5s`, and the
words `!`, `time`, `command`, `builtin`, `exec`, `nohup`, `sudo`, `env`, `nice`, `ionice`, `timeout`, `xargs`, `if`,
`elif`, `then`, `else`, `do`, `while` and `until`. The value of an assignment or an option may hold a command
substitution, also one that holds further substitutions, as in `X=$(cat f) docker pull <image>`,
`sudo -u $(whoami) docker pull <image>`, ``X=`cat f` docker pull <image>`` or
``X=`echo $(date)` docker pull <image>``. The duration of `timeout` may be a variable, also a positional parameter or
one with a default value, or a command substitution, also in double quotes or with a unit, as in
`timeout $T docker pull <image>`, `timeout ${T:-30} docker pull <image>`, `timeout $1 docker pull <image>`,
`timeout "${T}" docker pull <image>`, `timeout $(cat t) docker pull <image>` or `timeout $(cat t)s docker pull <image>`.
A positional parameter may be followed by digits, as in `timeout $10 docker pull <image>`, which bash reads as `${1}0`.
An option of `sudo`, `env`, `nice`, `ionice`, `timeout`, `xargs` or `exec` that takes a value,
such as `-u` of `sudo` or `-n` of `nice`, takes the next word as its value, so `sudo -u docker pull <image>`, which
runs `pull` as the user `docker`, is not read. `-S` and `--split-string` of `env` take the command as their value, so
`env -S docker pull <image>` is read. `--max-lines` of `xargs` takes its optional value only with `=`, as in
`--max-lines=2`, so `xargs --max-lines docker pull <image>` is read. It reads the options of `timeout` as `timeout`
does: its short flags are `-f`, `-p` and `-v` (`-f` and `-p` since GNU coreutils 9.6), so `-k` and `-s` take the next
word also at the end of a bundle such as `-vk 5` or `-fk 5`, and so does every abbreviation of `--kill-after` and
`--signal`, such as `--kill 5` or `--sig KILL`. A value in the same word, as in `-k5`, `-vsKILL` or `--kill=5`, takes
no further word. In `timeout -ks 5 KILL docker pull <image>` the value of `-k` is `s`, `5` is the duration and `KILL`
is the command, so the `docker` after it is not read. A bundle with a letter that is no option of `timeout`, as in
`-xk`, takes no word, so the `docker` of `timeout -xk docker pull <image>` is read, although `timeout` refuses `-x` and
runs nothing. A bundle of short options of `sudo`, `env`, `nice`, `ionice`, `xargs` or `exec` takes the next word only
when all its letters before the option that takes a value are options of that tool without a value, as in
`sudo -Eu bob` or `xargs -0n 1`. A letter that is no option of the tool, as in `nice -xn`, ends the bundle without a
word, and so do `-S` of `env`, `-h` of `sudo` and the options that take their value only in the same word: `-e`, `-i`
and `-l` of `xargs`. So the `docker` of `xargs -in docker pull <image>` is read, and so is the one of
`nice -xn docker pull <image>`, although `nice` refuses `-x` and runs nothing. `sudo` takes the word after `-h` as a
remote host and then runs no command, so the `docker` of `sudo -h docker pull <image>` is read although it never
runs. The check does not require a duration after the options of `timeout`, so the `docker` of
`timeout -k 5 docker pull <image>` is read, although `timeout` takes it as its duration, refuses it and runs nothing.
A `docker` that is an argument of another command, as in
`echo docker pull <image>`, `echo $(date) docker pull <image>`, ``echo `date` docker pull <image>``,
`$(date) docker pull <image>`, `` `date` docker pull <image> `` or `docker exec <container> docker pull <image>`, is
not read, and neither is the `docker` of `command -v docker`, `command -V docker` or
`command -p -v docker`, which do not run it.
Before the subcommand of `docker` it skips the global options `--config`, `-c` or `--context`, `-D`
or `--debug`, `-H` or `--host`, `-l` or `--log-level`, `--tls`, `--tlscacert`, `--tlscert`, `--tlskey` and
`--tlsverify`, with their values, also a value that holds a command substitution, as in `--context $(cat c)`, and
redirections such as `2>/dev/null`, `>log`, `2>&1` or `<<<x`, also several without a blank between them and one
written right after `docker` or right after `-D`, `--debug`, `--tls` or `--tlsverify`. A redirection between an
option of this list and its value, also one written right after the option, is no value. So
`docker 2>/dev/null pull <image>`, `docker 2>&1>/dev/null pull <image>`, `docker>/dev/null pull <image>`,
`docker --debug>/dev/null pull <image>`, `docker --debug <<<x run <image>`,
`docker --context 2>/dev/null <context> pull <image>` and `docker -l>log debug pull <image>` are read. It
fails on any other option between `docker` and a subcommand it reads, also on the `=` form of a flag such as
`--tlsverify=true`, except `-v`, `--version`, `-h` and `--help`, with
`<file>:<line>: <option>: unknown docker option before the subcommand; add it to DOCKER_GLOBAL in tools/test_lg_scripts.py`.
It reads each such option with at most one value word, a word that does not start with `-` and is no redirection,
and with none when the option has `=`. A redirection may stand between such options, so
`docker --foo bar >log pull <image>` is a finding. A command substitution is one value word, also with blanks or
further substitutions inside, as in `docker --foo $(cat v) pull <image>`, ``docker --foo `date +%s` pull <image>`` or
`docker --foo $(echo $(id -u)) pull <image>`. So `docker --foo pull <image>` and
`docker --foo bar pull <image>` are a finding, and `docker -x foo bar pull <image>`, whose subcommand is `bar`, and
`docker --x=1 ps pull <image>` are not. The finding names the option as the line writes it, as in `--label="a b"`.
It fails on a `docker` command whose subcommand is not a literal word of letters, digits, `_`, `.` and `-`, as in
`docker $OPTS pull <image>`, `docker "$CMD" <image>` or `docker $(echo pull) <image>`, with
`<file>:<line>: <word>: the check cannot read a docker command whose subcommand is not a literal word; write the docker options and the subcommand literally`.
The subcommand ends at a blank, at `;`, `&`, `|`, `(`, `)`, `<`, `>` and at the backtick that closes a substitution,
so `[ "$(docker info)" ]`, `` IDS=`docker ps` `` and `docker info>/dev/null` have the literal subcommands `info` and
`ps`, and `docker pull>/dev/null <image>` reads the subcommand `pull` and the image. A command substitution in the
subcommand is named whole, as in `$(echo pull)`.
It fails when an image or connector has no exact version, such as `1.2.3`, and no sha256 digest.
It fails on an install without an image, as in `docker pull >/dev/null`, `docker pull` at the end of a command or
`X=$(docker image pull)`, with
`<file>:<line>: docker <subcommand>: no image; name the image`, where `<subcommand>` is, for example, `pull` or
`container run`, and on one without a connector, as in `confluent-hub install` at the end of a command, with
`<file>:<line>: <command>: no connector; name the connector`, where `<command>` is `confluent-hub install` or
`confluent connect plugin install`. It fails on a Dockerfile `FROM` without an image, as in `FROM` or
`FROM --platform=linux/amd64`, with `<file>:<line>: FROM: no image; name the image`.
It also fails on an option before the image that it does not know, with
`<file>:<line>: <option>: unknown option before the image; add it to the option lists of tools/test_lg_scripts.py`,
on a command whose arguments do not split into words, for example at a quote that is not closed, with
`<file>:<line>: <command>: cannot split the arguments into words (<cause>); close every quote on the line`,
and on a Dockerfile `RUN` that runs `apt-get update` without `apt-get install`. A command substitution without quotes
in an argument, as in `docker run -e X=$(date +%s) <image>`, is one word for the check. A redirection such as `>out`,
`>|out`, `2>&1` or `<<<x` is no argument, also directly after the subcommand, before the image or directly after it,
so `docker run>log -d img:1.2.3`, `docker run >log -d img:1.2.3` and `docker pull img:1.2.3>out` read the image
`img:1.2.3`, and `confluent-hub install>/dev/null <connector>` reads the connector. A redirection between
`confluent-hub` and `install` or between the words of `confluent connect plugin install`, also one written right after
a word and several without a blank between them, is no argument either, so
`confluent-hub 2>/dev/null install <connector>`, `confluent-hub>/dev/null install <connector>`,
`confluent-hub 2>&1>/dev/null install <connector>`, `confluent-hub 2>/dev/null>log install <connector>` and
`confluent connect >log plugin install <connector>` read the connector. A command inside quotes, as in
`echo "use docker pull x"`, is not read. A command in a command substitution or a subshell, as in
`CID=$(docker run <image>)`, `CID="$(docker run <image>)"` or `(docker pull <image>)`, is read up to the end of the
substitution or subshell, also after `&&`, `||`, `;`, `|` or `&` inside it, and so is each further substitution of the
same command, as in `echo $(docker pull <image>) $(docker pull <image>)`. A command substitution inside an argument of
an install is read too, so `docker run -e "X=$(docker pull <image>)" <image>` gives a finding for each of the two
images that has no exact version, and so is one in the value of an assignment before a command it reads, as in
`X=$(docker pull <image>) docker pull <image>`. A command in a substitution inside a substitution is read at any depth,
as in `echo $(a $(docker pull <image>))`. Each command is read once, so
`X=$(docker pull <image> | tee log) docker pull <image>` gives one finding for each of the two images that has no
exact version.

`mqtt-generator-gist/mqttgen.py` uses the 1.x client API of `paho-mqtt`. With paho-mqtt 2.0.0 it stops with
`TypeError: Client.__init__() missing 1 required positional argument: 'callback_api_version'`. Before it connects, it
prints `MQTT broker <host>:<port>, user <username>` (`None` without a `username`) and no password. When it cannot
connect to the broker, it exits with status 1 and
`<host>:<port>: cannot connect to the MQTT broker (<cause>); start the broker first, for example with 02_run_mqtt_broker.sh`.
It writes its output line by line. When the reader of its output closes it, for example `head` after its last line,
the next line it prints makes it exit with status 1 and `stdout: closed by its reader; read the whole output`, also
with `PYTHONUNBUFFERED=1` or `python3 -u`. When that line is a payload line, which it prints only with `misc.verbose`
set to `true`, the message is
`stdout: closed by its reader; read the whole output or set misc.verbose to false`.
When its standard output is closed at the start, it prints nothing and publishes.
When a publish raises an error or paho-mqtt returns an error code for it, as after the broker closed the connection,
it exits with status 1 and `<host>:<port>: cannot publish to topic <topic> (<cause>); check the MQTT broker`, where
`<cause>` is the error or the text of the code, such as `The client is not currently connected.`.
When it cannot read the configuration file or the file is not valid JSON, it exits with status 1 and
`<file>: cannot read the configuration file (<cause>); pass the path of a JSON configuration file such as config.json`.
Before it connects, it checks that the top level, `mqtt`, `misc`, `sensors` and each sensor `sensors.<id>` are JSON
objects and that the `range` of each sensor is two whole numbers `[low, high]` with low <= high. When an entry is not a
JSON object, it exits with status 1 and `<file>: <key>: a JSON <type> is not allowed; expected a JSON object`, where
`<type>` is `array`, `string`, `number`, `boolean` or `null`. For the top level the message ends with
`expected a JSON object with mqtt, misc and sensors`, for `sensors` with
`expected a JSON object with one object per sensor`. When a `range` is not allowed, it exits with status 1 and
`<file>: sensors.<id>.range: <value> is not allowed; expected two whole numbers [low, high] with low <= high`.
Before it connects, it also checks these values:

| Key | Allowed values | Default |
|-----|----------------|---------|
| `misc.interval_ms` | a number of milliseconds from 0 to 86400000 | `500` |
| `misc.verbose` | `true` or `false` | `false` |
| `mqtt.host` | a non-empty JSON string, such as a host name or an IP address, of dot-separated labels with an optional dot at the end, where a label of ASCII characters has 1 to 63 characters and IDNA ToASCII turns any other label into 1 to 63 characters | `"localhost"` |
| `mqtt.port` | a whole number from 1 to 65535 | `1883` |
| `mqtt.topic` | a non-empty JSON string of at most 65535 UTF-8 bytes without `+`, `#` or a lone surrogate such as `\ud800` | `"mqttgen"` |
| `mqtt.username` | a JSON string without a lone surrogate | none |
| `mqtt.password` | a JSON string without a lone surrogate | none |

When a value is not allowed, it exits with status 1 and
`<file>: <key>: <value> is not allowed; expected <allowed values>`, for example
`config.json: misc.interval_ms: "x" is not allowed; expected a number of milliseconds from 0 to 86400000`.
A `mqtt.host` with an empty label, as in `broker..test`, `.test` or `.`, with a label of ASCII characters of more than
63 characters, or with a label whose IDNA form has more than 63 characters is refused this way before the connect.
One dot at the end, as in `broker.test.`, is allowed. IDNA also reads the full stops U+3002, U+FF0E and U+FF61 as
dots. A label of ASCII characters is not checked further, so letter case, blanks,
`_`, `:` and brackets pass the check, as in `BROKER.test`, `my_host`, `::1` or `[::1]`. Write an IPv6 address without
brackets: with `[::1]` the connect fails with the `cannot connect to the MQTT broker` message above. IDNA ToASCII runs
nameprep on any other label and refuses it when nameprep refuses a character, such as a lone surrogate `\ud800` or
U+2028, when the label holds a right-to-left character together with a left-to-right character, as in `"\u05d0a"`,
or holds a right-to-left character but does not start and end with one, as in `"1\u05d0"`, or when nameprep turns it
into a label that starts with `xn--` and still holds a character that is not ASCII, as in `xn--ü` or `XN--ü`. It also
refuses such a label when nameprep leaves nothing of it, as with the soft hyphen `"\u00ad"`, or when its IDNA form, such
as `xn--bcher-kva` for `bücher`, has more than 63 characters. Nameprep removes characters such as the soft hyphen and
U+200D, so a longer label can pass: 40 times `"a\u00ad"` is a label of 80 characters whose IDNA form has 40.
For `mqtt.password` the message names the JSON type and not the value:
`<file>: mqtt.password: a JSON <type> is not allowed; expected a JSON string without a lone surrogate`, where `<type>`
is `string with a lone surrogate` for a string such as `"p\ud800"`.
For a `mqtt.topic` of more than 65535 UTF-8 bytes the message names the size and not the value:
`<file>: mqtt.topic: a JSON string of <n> UTF-8 bytes is not allowed; expected a non-empty JSON string of at most 65535 UTF-8 bytes without +, # or a lone surrogate`.
Without exactly one argument, it exits with status 2 and
`<script>: <n> arguments given; expected 1, the path of a JSON configuration file such as config.json`.

`01_bootstrap.sh` runs with `set -euo pipefail`: it stops at the first command that fails and exits with the status of
that command. It runs no `mqtt` command, so it reaches the connector installs before a broker runs.
`02_run_mqtt_broker.sh` starts the broker, then runs `mqtt pub -t test -m "Hello2" --mqttVersion 3` and
`mqtt test -h 127.0.0.1`. `influxdb-grafana/__run_container_01.sh` is the script of the example that starts InfluxDB
and Grafana. `tools/test_lg_python.py` runs `01_bootstrap.sh` with stubs and fails unless it installs
`paho-mqtt==1.6.1` and reaches both connector installs, also when `mqtt` fails, and unless it stops at a failed `pip3`,
`mosquitto_passwd` or `confluent-hub`. It runs `02_run_mqtt_broker.sh` with stubs and fails unless `mqtt pub` comes
after `docker run`.

### Create a connector with the Kafka Connect REST API

You need Python 3 and a Kafka Connect worker on `127.0.0.1:8083`. `cfg.json` is a whole create request with `name`
`c1` and a `config` object, the body that `test.sh` posts with `curl`. `src/main/connect-cfg/deploy_connector.py`
posts the create request `{"name": "debug_c1", "config": <config>}` to `http://127.0.0.1:8083/connectors`, where
`<config>` is the `config` object of `cfg.json`. It uses only the Python standard library. Run it in
`src/main/connect-cfg/`:

```bash
python3 deploy_connector.py
```

When it cannot read `cfg.json` in the current folder, or `cfg.json` is not valid JSON, it posts nothing and exits with
status 1 and
`cfg.json: cannot read the configuration file (<cause>); run the script in a folder with a JSON file cfg.json, such as src/main/connect-cfg/`.
When `cfg.json` has no `config` object, it posts nothing and exits with status 1 and
`cfg.json: config: missing or not a JSON object; write the connector settings as the object "config": {...}`.
After HTTP 200 or 201 it prints `New connector debug_c1 created.`. On any other answer it exits with status 1 and
`debug_c1: POST http://127.0.0.1:8083/connectors answered HTTP <status> <body>; the connector was not created, check the log of the Kafka Connect worker`.
When the body of an answer ends before its `Content-Length`, the script keeps the status and uses the part of the
body that arrived: a 201 prints `New connector debug_c1 created.` and a 500 gives the message above with that part.
When the connection is reset while the body is read, or the body stops for 10 s, the script keeps the status as well,
and `<body>` is the cause in parentheses, as in `answered HTTP 500 ([Errno 104] Connection reset by peer)` or
`answered HTTP 500 (timed out)`.
When it cannot connect, it exits with status 1 and
`http://127.0.0.1:8083/connectors: cannot connect (<cause>); start Kafka Connect on 127.0.0.1:8083 first`.
A connection that takes longer than 10 s gives this message with the cause `timed out`. When Kafka Connect accepts
the connection and then sends nothing for 10 s, the script exits with status 1 and
`http://127.0.0.1:8083/connectors: no answer within 10 s; check the log of the Kafka Connect worker`.
The limit of 10 s applies to the connect and to each read on its own, not to the whole request: an answer that
arrives in parts less than 10 s apart can take longer. When the program on the port closes the connection without an
answer, or answers with something that is not HTTP, the script exits with status 1 and
`http://127.0.0.1:8083/connectors: no HTTP answer (<cause>); check that Kafka Connect listens on http://127.0.0.1:8083/connectors and read the log of its worker`.

### Handle errors

Each tool stops at the first failure and exits with status 1:

* `TSDataSineWaveGenerator`, `EventFlowAnalysisProducer` and `RequestResponseAnalysisProducer` stop when a record
  cannot be sent, with `<topic>: could not send the record; <cause>`. The first two also stop when the series cannot be
  created.
* `EventFlowAnalysisProducer`, `RequestResponseAnalysisProducer` and `EventFlowAnalysisPrecheck` stop when the topics
  cannot be listed or created, or when a topic is still missing after they created the topics. The topic check prints
  `<topic list>: topics missing on the cluster [<topic>, ...]; create them with TopicsUP` on stdout.
* `EventFlowAnalysisConsumer` and `RequestResponseAnalysisConsumer` stop when a poll fails, and close the consumer.
  They log each empty poll but the last at DEBUG as `empty poll topic=<topic> emptyPolls=<n> giveUp=<giveUp>`.
  `<topic>` is `OpenTSx_Events` for `EventFlowAnalysisConsumer` and `latency_benchmark_response` for
  `RequestResponseAnalysisConsumer`; `<giveUp>` is `kping.consumer.giveUp` or `klatency.consumer.giveUp` of the client
  profile. When no record arrived before they give up, they log
  `no latency measured topic=<topic> cause=getStatisticData: the series <topic> has no values; expected at least 1 value`
  at ERROR without a stack trace, write no table and exit with status 1. Standard output stays empty.
* `EventFlowAnalysisSetup` stops when the topics cannot be created.

With `OPENTSX_SHOW_GUI=true`, `EventFlowAnalysisConsumer` and `RequestResponseAnalysisConsumer` open and store the
latency chart. Unset or with any other value, they write the table `latency-by-message-<milliseconds>` to
`$HOME/opentsx/plots`.

`TSDataSineWaveGenerator` sends the flow states of all rounds through one `EventFlowStateProducer` and closes it when the
rounds end. It prints one line per round on stdout:
`round done round=<n> series=16 generateSeconds=<seconds> sendSeconds=<seconds>`.

The tools log through SLF4J. `mvn exec:java` in this module and the jar of the `SimpleTimeSeriesProducer` profile carry
slf4j-simple with [`conf/simplelogger.properties`](../conf/simplelogger.properties), so INFO and higher lines go to
stderr. `mvn -q` sets the JVM system property `org.slf4j.simpleLogger.defaultLogLevel` to `error`, which wins over the
default level in that file and hides the INFO and WARN lines that `mvn exec:java` writes for the OpenTSx loggers and
for every other logger without its own level there; run it without `-q` to see them. The loggers that file sets to
`warn` (`org.apache.kafka`, `org.apache.iceberg`, `org.apache.hadoop`, `org.apache.parquet` and `software.amazon`) keep
that level, so the Kafka clients print their WARN lines also with `-q`.
