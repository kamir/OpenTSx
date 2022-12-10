cd ..

#
# We start the KStreams app ...
#
export OPENTSX_TOPIC_MAP_FILE_NAME=./../config/topiclist.def
export OPENTSX_PRIMARY_CLUSTER_CLIENT_CFG_FILE_NAME=./../config/cpl.props
export OPENTSX_SHOW_GUI=true
export OPENTSX_USE_KAFKA=true

java -javaagent:./bin/jmx_prometheus_javaagent-0.13.0.jar=8080:./bin/prometheus_config.yaml -jar target/opentsx-lg-3.0.0.jar
