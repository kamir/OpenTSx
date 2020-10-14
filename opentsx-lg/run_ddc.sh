#
#
#

export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk1.8.0_221.jdk/Contents/Home
export CONFLUENT_HOME=/Users/mkampf/bin/confluent-5.5.1

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

cd src/main/docker-compose

docker-compose up -d
