export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk1.8.0_221.jdk/Contents/Home
export CONFLUENT_HOME=/Users/mkampf/bin/confluent-5.5.0

#confluent local status

#confluent local destroy

#confluent local start

rm -rf /Users/mkampf/.m2/repository/org/opentsx

cd ..
mvn clean generate-sources compile package install

cd opentsx-lg
mvn clean generate-sources compile package install -PSimpleTimeSeriesProducer,Docker

#mvn exec:java -Dexec.mainClass="org.opentsx.lg.TopicsDOWN"
#mvn exec:java -Dexec.mainClass="org.opentsx.lg.TopicsUP"
#mvn exec:java -Dexec.mainClass="org.opentsx.lg.TopicsCHECK"

#
# Main class: org.opentsx.lg.TSDataSineWaveGenerator
#
# PROPERTIES:
#
### <OPENTSX_TOPIC_MAP_FILE_NAME>/opentsx-lg/config/topiclist.def</OPENTSX_TOPIC_MAP_FILE_NAME>
### <OPENTSX_PRIMARY_CLUSTER_CLIENT_CFG_FILE_NAME>/opentsx-lg/config/mdc_c1.props</OPENTSX_PRIMARY_CLUSTER_CLIENT_CFG_FILE_NAME>
### <OPENTSX_SHOW_GUI>false</OPENTSX_SHOW_GUI>
#

export MDC1=192.168.3.172
export MDC2=192.168.3.5
export MDC3=192.168.3.173

export OPENTSX_TOPIC_MAP_FILE_NAME=config/topiclist.def
export OPENTSX_PRIMARY_CLUSTER_CLIENT_CFG_FILE_NAME=config/cpl.props

export CONFLUENT_DOCKER_TAG=5.5.0
export REPOSITORY=confluentinc

cd src/main/docker
docker-compose --env-file .env-inhouse up

#
# This setup is used for execution of the refernece workload in the CuH stack ... or locally on my Mac.
#
