#
# Prepare environment variables for local CP
#
export CONFLUENT_HOME="$HOME/bin/confluent-5.5.1"

#
# clean and start local CP
#
confluent local status

confluent local destroy

confluent local start

#
# Clean and build OpenTSx core project library
#
rm -rf "$HOME/.m2/repository/org/opentsx"

cd ..
mvn clean generate-sources compile package install

cd opentsx-lg
mvn clean generate-sources compile package install -PSimpleTimeSeriesProducer,Docker

#
# Set environment variable so that time series can be managed in an TSA-Panel ...
#

#
# List of topics of our TSA-Engine
#
export OPENTSX_TOPIC_MAP_FILE_NAME=./../config/topiclist.def
export OPENTSX_NUMBER_OF_ITERATIONS=100

#
# Client configuration for a particular Kafka cluster (CP local or Confluent cloud)
#
export OPENTSX_PRIMARY_CLUSTER_CLIENT_CFG_FILE_NAME=./../config/opentsx-local.props

#
# GUI switch
#
export OPENTSX_SHOW_GUI=true

mvn clean compile exec:java -Dexec.mainClass="org.opentsx.lg.TSDataSineWaveGenerator"

#
# inspection of LOG-File size
#
$CONFLUENT_HOME/bin/kafka-log-dirs --bootstrap-server 127.0.0.1:9092 --describe --topic-list OpenTSx_Episodes,OpenTSx_Events | grep "{" | python -mjson.tool

#
# inspect of TOPIC HEALTHINESS
#
$CONFLUENT_HOME/bin/kafka-log-dirs --bootstrap-server 127.0.0.1:9092 --describe

# Show all details of all topics:
$CONFLUENT_HOME/bin/kafka-topics --bootstrap-server 127.0.0.1:9092 --describe

# Show the details of all partitions (in all topics):
$CONFLUENT_HOME/bin/kafka-topics --bootstrap-server 127.0.0.1:9092 --describe | grep 'Offline:'

# Show the offline partitions (if any) from all topics:
$CONFLUENT_HOME/bin/kafka-topics --bootstrap-server 127.0.0.1:9092 --describe | grep 'Offline: .+'

#
# The KSQL statements of the demo. Paste them into the ksql CLI; the SELECT statements block and print results.
# The query ids in the terminate statements differ per server: look them up with SHOW QUERIES.
#
cat <<'KSQL'
set 'ksql.schema.registry.url'='http://localhost:8081';

CREATE STREAM OpenTSx_Event_Flow_State_stream (
  persistEvents VARCHAR,
  persistEpisodes VARCHAR,
  l BIGINT,
  t2 BIGINT,
  t1 BIGINT,
  t0 BIGINT,
  generator VARCHAR,
  z BIGINT,
  sendDuration DOUBLE,
  generateDuration DOUBLE
) WITH (KAFKA_TOPIC = 'OpenTSx_Event_Flow_State', VALUE_FORMAT='JSON');

CREATE STREAM OpenTSx_Episodes_A_stream WITH (KAFKA_TOPIC = 'OpenTSx_Episodes_A', VALUE_FORMAT='AVRO');

CREATE STREAM OpenTSx_Episodes_B_stream WITH (KAFKA_TOPIC = 'OpenTSx_Episodes_B', VALUE_FORMAT='AVRO');

CREATE STREAM OpenTSx_Events_stream WITH (KAFKA_TOPIC = 'OpenTSx_Events', VALUE_FORMAT='AVRO');

CREATE STREAM OpenTSx_Events_stream_materialized
AS SELECT * FROM OpenTSx_Events_stream EMIT CHANGES;

SELECT LABEL, TSTART, TEND, ZOBSERVATIONS, INCREMENT, URI FROM OPENTSX_EPISODES_A_STREAM EMIT CHANGES;

SELECT LABEL, TSTART, TEND, ZOBSERVATIONS, INCREMENT, URI FROM OPENTSX_EPISODES_B_STREAM EMIT CHANGES;

Create stream OPENTSX_EPISODES_A_MD_STREAM
AS SELECT LABEL, TSTART, TEND, ZOBSERVATIONS, INCREMENT, URI
FROM OPENTSX_EPISODES_A_STREAM EMIT CHANGES;

Create stream OPENTSX_EPISODES_B_MD_STREAM
AS SELECT LABEL, TSTART, TEND, ZOBSERVATIONS, INCREMENT, URI
FROM OPENTSX_EPISODES_B_STREAM EMIT CHANGES;

CREATE TABLE OpenTSx_Events_tab4
AS SELECT URI, count(*) FROM OpenTSx_Events_stream_materialized
WINDOW TUMBLING (size 120 seconds)
GROUP BY URI
EMIT CHANGES;

select * from OPENTSX_EVENTS_TAB4 emit changes;

CREATE stream OpenTSx_Episodes_A_stats AS SELECT * FROM OpenTSx_Episodes_A_stream emit changes;
terminate CSAS_OPENTSX_EPISODES_A_STATS_39;
Drop stream OpenTSx_Episodes_A_stats;

CREATE stream OpenTSx_Episodes_B_stats AS SELECT episodes_md(tstart, tend), label FROM OpenTSx_Episodes_B_stream emit changes;
SELECT * from OpenTSx_Episodes_B_stats emit changes;

terminate CSAS_OPENTSX_EPISODES_B_STATS_37;
Drop stream OpenTSx_Episodes_B_stats;
KSQL
