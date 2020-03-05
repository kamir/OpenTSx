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

export OPENTSX_TOPIC_MAP_FILE_NAME=/opentsx-lg/config/topiclist.def
export OPENTSX_PRIMARY_CLUSTER_CLIENT_CFG_FILE_NAME=/opentsx-lg/config/cpl.props

mvn exec:java -Dexec.mainClass="org.opentsx.lg.TopicsDOWN"
mvn exec:java -Dexec.mainClass="org.opentsx.lg.TopicsUP"
mvn exec:java -Dexec.mainClass="org.opentsx.lg.TopicsCHECK"


docker run opentsx/time-series-generator:3.0.0

#
# inspection of LOG-File size
#
$CONFLUENT_HOME/bin/kafka-log-dirs --bootstrap-server 127.0.0.1:9092 --describe --topic-list OpenTSx_Episodes,OpenTSx_Events
