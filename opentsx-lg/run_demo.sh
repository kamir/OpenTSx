#
# Prepare environment variables for local CP
#
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk1.8.0_221.jdk/Contents/Home
export CONFLUENT_HOME=/Users/mkampf/bin/confluent-5.5.1

#
# clean and start local CP
#
confluent local status

confluent local destroy

confluent local start

#
# clean demo topics if such exist
#
#$CONFLUENT_HOME/bin/kafka-topics --bootstrap-server 127.0.0.1:9092 --delete --topic opentsx_event_flow_state
#$CONFLUENT_HOME/bin/kafka-topics --bootstrap-server 127.0.0.1:9092 --delete --topic TSOData_Events
#$CONFLUENT_HOME/bin/kafka-topics --bootstrap-server 127.0.0.1:9092 --delete --topic TSOData_Episodes

#
# Clean and build OpenTSx core project library
#
rm -rf /Users/mkampf/.m2/repository/org/opentsx

cd ..
mvn clean generate-sources compile package install

cd opentsx-lg
mvn clean generate-sources compile package install -PSimpleTimeSeriesProducer,Docker

#
# Run sine wave generator in "Silent Mode" (no GUI)
#
#mvn exec:java -Dexec.mainClass="org.opentsx.lg.TSDataSineWaveGenerator" -Dexec.arguments="off"

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
export OPENTSX_PRIMARY_CLUSTER_CLIENT_CFG_FILE_NAME=./../config/cpl.props

#
# GUI switch
#
#export OPENTSX_SHOW_GUI=false
export OPENTSX_SHOW_GUI=true

mvn clean compile exec:java -Dexec.mainClass="org.opentsx.lg.TSDataSineWaveGenerator"
#mvn exec:java -Dexec.mainClass="org.opentsx.lg.TSDataSineWaveGenerator"

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
# FROM HERE WE HAVE KSQL CODE !!!
#

#
# TODO WRITE INTO FILE AND RUN VIA KSQL-CLI
# Be aware of some limitations!
#

set 'ksql.schema.registry.url'='http://localhost:8081';

#
# It would be better to use the latest KSQDB version and the Java client.
#
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
# BLOCKS and prints results

SELECT LABEL, TSTART, TEND, ZOBSERVATIONS, INCREMENT, URI FROM OPENTSX_EPISODES_B_STREAM EMIT CHANGES;
# BLOCKS and prints results

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
#
# track the id of the querey, so that we can close it before we drop the stream
#
terminate CSAS_OPENTSX_EPISODES_A_STATS_39;
Drop stream OpenTSx_Episodes_A_stats;




CREATE stream OpenTSx_Episodes_B_stats AS SELECT episodes_md(tstart, tend), label FROM OpenTSx_Episodes_B_stream emit changes;
SELECT * from OpenTSx_Episodes_B_stats emit changes;

#
# track the id of the querey, so that we can close it before we drop the stream
#
terminate CSAS_OPENTSX_EPISODES_B_STATS_37;
Drop stream OpenTSx_Episodes_B_stats;

