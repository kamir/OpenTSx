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
same jar with Xvfb and x11vnc.

### Prepare the ACLs on a Confluent Cloud cluster

`bin/bootstrap_LatencyProbe_CHECK.sh` creates the ACLs the latency tools need: CREATE, READ, WRITE, ALTER and DELETE on
the topics with the prefix `OpenTSx_`, and READ on the consumer group `KPING-Consumer`, for one service account. You
need the `ccloud` CLI, logged in with `ccloud login --save`. Export the ids of your environment, cluster and service
account, then run the script from `bin/`:

```bash
cd opentsx-lg/bin
export CCLOUDENV=<environment-id>
export CLUSTER=<cluster-id>
export SERVICE_ACCOUNT=<service-account-id>
./bootstrap_LatencyProbe_CHECK.sh
```

Without one of the three variables, or with an empty one, the script calls no `ccloud` command and exits with status 2
and one line per missing variable, for example
`CLUSTER: missing; export the id of your Confluent Cloud cluster before you run the script`. The script stops with exit
status 1 at the first `ccloud` command that fails, so it creates no ACL when the environment or the cluster cannot be
selected.

### Keep credentials out of the repository

A client profile named `ccloud.props` anywhere in this module is git-ignored. So is the MQTT password file
`src/main/mqtt-example/volume1/docker/mqtt/config/passwd`, which `src/main/mqtt-example/01_bootstrap.sh` creates with
`mosquitto_passwd` and `02_run_mqtt_broker.sh` mounts into the broker.

`src/main/mqtt-example/influxdb-grafana/__run_container_01.sh` starts the container `docker-influxdb-grafana` and sets
the Grafana admin password inside it to the value of `GRAFANA_ADMIN_PASSWORD`. Without the variable it starts no
container and exits with status 2 and
`GRAFANA_ADMIN_PASSWORD: missing; export the Grafana admin password to set before you run the script`.

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
