export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk1.8.0_221.jdk/Contents/Home
export CONFLUENT_HOME=/Users/mkampf/bin/confluent-5.4.0

confluent local status

confluent local destroy

confluent local start

#$CONFLUENT_HOME/bin/kafka-topics --bootstrap-server 127.0.0.1:9092 --delete --topic opentsx_event_flow_state
#$CONFLUENT_HOME/bin/kafka-topics --bootstrap-server 127.0.0.1:9092 --delete --topic TSOData_Events
#$CONFLUENT_HOME/bin/kafka-topics --bootstrap-server 127.0.0.1:9092 --delete --topic TSOData_Episodes

rm -rf /Users/mkampf/.m2/repository/org/opentsx

cd ..
mvn clean generate-sources compile package install

cd opentsx-lg
mvn clean generate-sources compile package install -PSimpleTimeSeriesProducer,Docker

#
# Silent Mode ...
#
# mvn exec:java -Dexec.mainClass="org.opentsx.lg.TSDataSineWaveGenerator" -Dexec.arguments="off"

#
# Show the time series in an TSA-Panel ...
#
export OPENTSX_TOPIC_MAP_FILE_NAME=./../config/topiclist.def
export OPENTSX_PRIMARY_CLUSTER_CLIENT_CFG_FILE_NAME=./../config/cpl.props
export OPENTSX_SHOW_GUI=false

mvn clean compile exec:java -Dexec.mainClass="org.opentsx.lg.TSDataSineWaveGenerator"
#mvn exec:java -Dexec.mainClass="org.opentsx.lg.TSDataSineWaveGenerator"

# inspect the JSON data : https://codebeautify.org/

#
# inspection of LOG-File size
#
$CONFLUENT_HOME/bin/kafka-log-dirs --bootstrap-server 127.0.0.1:9092 --describe --topic-list OpenTSx_Episodes,OpenTSx_Events | python -mjson.tool


#
# FROM HERE WE HAVE KSQL CODE !!!
#

#
# TODO WRITE INTO FILE AND RUN VIA KSQL-CLI
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

CREATE STREAM OpenTSx_Episodes_stream WITH (KAFKA_TOPIC = 'OpenTSx_Episodes', VALUE_FORMAT='AVRO');

CREATE STREAM OpenTSx_Events_stream WITH (KAFKA_TOPIC = 'OpenTSx_Events', VALUE_FORMAT='AVRO');

SELECT LABEL, TSTART, TEND, ZOBSERVATIONS, INCREMENT, URI
FROM OPENTSX_EPISODES_STREAM EMIT CHANGES;

Create stream OPENTSX_EPISODES_MD_STREAM
AS SELECT LABEL, TSTART, TEND, ZOBSERVATIONS, INCREMENT, URI
FROM OPENTSX_EPISODES_STREAM EMIT CHANGES;

/**

CREATE TABLE OpenTSx_Events_tab AS SELECT FROM OpenTSx_Events_stream;

CREATE TABLE OpenTSx_Episodes_tab AS SELECT FROM OpenTSx_Episodes_stream;

CREATE TABLE OpenTSx_Event_Flow_State_tab AS SELECT FROM OpenTSx_Event_Flow_State_stream;

**/