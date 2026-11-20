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

`src/main/k8s/krake-app/lg-demo.yaml` mounts the ConfigMap `opentsx-lg-demo-config` at `/etc/opentsx/config/`. The
ConfigMap holds `cuh.props` and `topiclist.def`; `src/main/k8s/krake-app/config/` has templates of both files.
The manifest is an example: no workflow publishes its image `opentsx/time-series-generator:3.0.1`. Build the image
with step (2) and load it into your cluster.

The Docker Compose files under `src/main/docker-compose/` map the cluster host names to the addresses `MDC1`, `MDC2`
and `MDC3`. Set the three variables in the `.env` file next to the compose file. The compose files in
`ping-tool-cons/` and `ping-tool-prod/` mount `src/main/docker-compose/config/` at `/etc/opentsx/config/`, and
`ENV_CONTEXT` in their `.env` and `.env-inhouse` files names the client profile there.

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
`&>/dev/null`, does not end the `cd`, so `cd x 2>&1 || exit 1` passes and `cd x 2>&1` gives
`<file>:<line>: cd without a guard; write cd x 2>&1 || exit 1`.
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
a `CMD` or `ENTRYPOINT` in shell form, and on a manifest under `src/main/k8s/` with a list entry `- name:` that has
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
the same line, and of every `FROM` in its Dockerfiles. The `&` of a redirection, as in `2>&1`, `>&2` or `&>log`, does
not end a command. It reads `docker`, `confluent-hub` and `confluent` only as the command word, also with a path
such as `/usr/bin/docker`: at the start of a command, of a `case` arm such as `start)` or `(start)`, of a command
substitution, of a subshell or of a `{` group, also after variable assignments, options (words that start with `-`,
with or without one value), a number or duration such as `10` or `5s`, and the words `!`, `time`, `command`,
`builtin`, `exec`, `nohup`, `sudo`, `env`, `nice`, `ionice`, `timeout`, `xargs`, `if`, `elif`, `then`, `else`, `do`,
`while` and `until`. A `docker` that is an argument of another command, as in `echo docker pull <image>` or
`docker exec <container> docker pull <image>`, is not read, and neither is the `docker` of `command -v docker`,
`command -V docker` or `command -p -v docker`, which do not run it.
Before the subcommand of `docker` it skips the global options `--config`, `-c` or `--context`, `-D`
or `--debug`, `-H` or `--host`, `-l` or `--log-level`, `--tls`, `--tlscacert`, `--tlscert`, `--tlskey` and
`--tlsverify`, with their values. It fails on any other option between `docker` and a subcommand it reads, also on
the `=` form of a flag such as `--tlsverify=true`, except `-v`, `--version`, `-h` and `--help`, with
`<file>:<line>: <option>: unknown docker option before the subcommand; add it to DOCKER_GLOBAL in tools/test_lg_scripts.py`.
It fails on a `docker` command whose subcommand is not a literal word of letters, digits, `_`, `.` and `-`, as in
`docker $OPTS pull <image>` or `docker "$CMD" <image>`, with
`<file>:<line>: <word>: the check cannot read a docker command whose subcommand is not a literal word; write the docker options and the subcommand literally`.
It fails when an image or connector has no exact version, such as `1.2.3`, and no sha256 digest.
It also fails on an option before the image that it does not know, with
`<file>:<line>: <option>: unknown option before the image; add it to the option lists of tools/test_lg_scripts.py`,
on a command whose arguments do not split into words, for example at a quote that is not closed, with
`<file>:<line>: <command>: cannot split the arguments into words (<cause>); close every quote on the line`,
and on a Dockerfile `RUN` that runs `apt-get update` without `apt-get install`. A command inside quotes, as in
`echo "use docker pull x"`, is not read. A command in a command substitution or a subshell, as in
`CID=$(docker run <image>)`, `CID="$(docker run <image>)"` or `(docker pull <image>)`, is read up to the end of the
substitution or subshell, also after `&&`, `||`, `;`, `|` or `&` inside it, and so is each further substitution of the
same command, as in `echo $(docker pull <image>) $(docker pull <image>)`. A command substitution inside an argument of
an install is read too, so `docker run -e "X=$(docker pull <image>)" <image>` gives a finding for each of the two
images that has no exact version.

`mqtt-generator-gist/mqttgen.py` uses the 1.x client API of `paho-mqtt`. With paho-mqtt 2.0.0 it stops with
`TypeError: Client.__init__() missing 1 required positional argument: 'callback_api_version'`. Before it connects, it
prints `MQTT broker <host>:<port>, user <username>` (`None` without a `username`) and no password. When it cannot
connect to the broker, it exits with status 1 and
`<host>:<port>: cannot connect to the MQTT broker (<cause>); start the broker first, for example with 02_run_mqtt_broker.sh`.
An error after the connect, for example `BrokenPipeError` when you pipe its output into `head`, stops the script with
that error. When it cannot read the configuration file or the file is not valid JSON, it exits with status 1 and
`<file>: cannot read the configuration file (<cause>); pass the path of a JSON configuration file such as config.json`.
Before it connects, it checks that the top level, `mqtt`, `misc`, `sensors` and each sensor `sensors.<id>` are JSON
objects and that the `range` of each sensor is two whole numbers `[low, high]` with low <= high. When an entry is not a
JSON object, it exits with status 1 and `<file>: <key>: a JSON <type> is not allowed; expected a JSON object`, where
`<type>` is `array`, `string`, `number`, `boolean` or `null`. For the top level the message ends with
`expected a JSON object with mqtt, misc and sensors`, for `sensors` with
`expected a JSON object with one object per sensor`. When a `range` is not allowed, it exits with status 1 and
`<file>: sensors.<id>.range: <value> is not allowed; expected two whole numbers [low, high] with low <= high`.
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
  When no record arrived before they give up, they stop with
  `getStatisticData: the series <topic> has no values; expected at least 1 value`, where `<topic>` is
  `OpenTSx_Events` or `latency_benchmark_response`.
* `EventFlowAnalysisSetup` stops when the topics cannot be created.

With `OPENTSX_SHOW_GUI=true`, `EventFlowAnalysisConsumer` and `RequestResponseAnalysisConsumer` open and store the
latency chart. Unset or with any other value, they write the table `latency-by-message-<milliseconds>` to
`$HOME/opentsx/plots`.

`TSDataSineWaveGenerator` sends the flow states of all rounds through one `EventFlowStateProducer` and closes it when the
rounds end. It prints one line per round on stdout:
`round done round=<n> series=16 generateSeconds=<seconds> sendSeconds=<seconds>`.

The tools log through SLF4J. `mvn exec:java` in this module and the jar of the `SimpleTimeSeriesProducer` profile carry
slf4j-simple with [`conf/simplelogger.properties`](../conf/simplelogger.properties), so INFO and higher lines go to
stderr.
