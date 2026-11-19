#
#
#

export CONFLUENT_HOME="$HOME/bin/confluent-5.5.1"

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
