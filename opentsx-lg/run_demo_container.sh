export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk1.8.0_221.jdk/Contents/Home
export CONFLUENT_HOME=/Users/mkampf/bin/confluent-5.4.0

confluent local status

confluent local destroy

confluent local start

rm -rf /Users/mkampf/.m2/repository/org/opentsx

cd ..
mvn clean generate-sources compile package install

cd opentsx-lg
mvn clean generate-sources compile package install -PSimpleTimeSeriesProducer,Docker

##################################
#
# Silent Mode ...
#
###################################
export OPENTSX_SHOW_GUI=false

#
# in container: use absolut path
#
export OPENTSX_TOPIC_MAP_FILE_NAME=/opentsx-lg/config/topiclist.def
export OPENTSX_PRIMARY_CLUSTER_CLIENT_CFG_FILE_NAME=/opentsx-lg/config/cpl.props

#
# locally with Confluent platform: use relative path
#
export OPENTSX_TOPIC_MAP_FILE_NAME=/opentsx-lg/config/topiclist.def
export OPENTSX_PRIMARY_CLUSTER_CLIENT_CFG_FILE_NAME=/opentsx-lg/config/cpl.props

#
# via Maven on MDC: use relative path
#
export OPENTSX_TOPIC_MAP_FILE_NAME=config/topiclist.def
export OPENTSX_PRIMARY_CLUSTER_CLIENT_CFG_FILE_NAME=config/cuh.props



mvn exec:java -Dexec.mainClass="org.opentsx.util.topicmanager.TopicsDOWN"
mvn exec:java -Dexec.mainClass="org.opentsx.util.topicmanager.TopicsUP"
mvn exec:java -Dexec.mainClass="org.opentsx.util.topicmanager.TopicsCHECK"

#
# Main class: org.opentsx.lg.TSDataSineWaveGenerator
#
# PROPERTIES:
#
### <OPENTSX_TOPIC_MAP_FILE_NAME>/opentsx-lg/config/topiclist.def</OPENTSX_TOPIC_MAP_FILE_NAME>
### <OPENTSX_PRIMARY_CLUSTER_CLIENT_CFG_FILE_NAME>/opentsx-lg/config/mdc_c1.props</OPENTSX_PRIMARY_CLUSTER_CLIENT_CFG_FILE_NAME>
### <OPENTSX_SHOW_GUI>false</OPENTSX_SHOW_GUI>
#
docker run opentsx/time-series-generator:3.0.0
#
# !!! Warning !!! this needs also additional iformation for networking with remote hosts
#




#
# inspection of LOG-File size
#
$CONFLUENT_HOME/bin/kafka-log-dirs --bootstrap-server 127.0.0.1:9092 --describe --topic-list OpenTSx_Episodes,OpenTSx_Events
