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
followed by `|| exit` or `|| return`. `tools/test_lg_python.py` fails on a Python script (`*.py`) of this module that
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
a `CMD` or `ENTRYPOINT` in shell form, on a Dockerfile or YAML file under `src/` that pulls an image from
`k8s.gcr.io`, and on a manifest under `src/main/k8s/` with a list entry `- name:` that has no value.

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
digest. `tools/test_lg_scripts.py` fails on an image or connector without an exact version in the scripts and
Dockerfiles of this module, and on a Dockerfile `RUN` that runs `apt-get update` without `apt-get install`.

`mqtt-generator-gist/mqttgen.py` uses the 1.x client API of `paho-mqtt`. With paho-mqtt 2.0.0 it stops with
`TypeError: Client.__init__() missing 1 required positional argument: 'callback_api_version'`. `01_bootstrap.sh`
runs no `mqtt sub`, so it runs on to the connector installs. Its `mqtt pub -t test -m "Hello2" --mqttVersion 3`
reaches a broker only if one already runs: `02_run_mqtt_broker.sh` starts the broker of the example after it.
`influxdb-grafana/__run_container_01.sh` is the script of the example that starts InfluxDB and Grafana.
`tools/test_lg_python.py` runs `01_bootstrap.sh` with stubs and fails unless it installs `paho-mqtt==1.6.1` and reaches
both connector installs.

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
