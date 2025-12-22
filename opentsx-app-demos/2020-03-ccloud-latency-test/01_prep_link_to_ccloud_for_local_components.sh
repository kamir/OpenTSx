#
# Prepare environment variables for local CP
#
###
# Setup for Mirko's iMac
##
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk1.8.0_101.jdk/Contents/Home
$JAVA_HOME/bin/java -version

export CONFLUENT_HOME=/Users/kamir/bin/confluent-6.0.0

#
# clean and start local CP
#
$CONFLUENT_HOME/bin/confluent local version

$CONFLUENT_HOME/bin/confluent local current

$CONFLUENT_HOME/bin/confluent local destroy

$CONFLUENT_HOME/bin/confluent local services start

$CONFLUENT_HOME/bin/confluent local services status


##################################
# Build the OpenTSx tool stack
##################################
#
# Clean and build OpenTSx core project library
#
rm -rf /Users/mkampf/.m2/repository/org/opentsx
cd /Users/mkampf/GITHUB.public/OpenTSx
mvn clean generate-sources compile package install

cd opentsx-lg
mvn clean generate-sources compile package install -PSimpleTimeSeriesProducer,Docker

#
# Create some sine waves
#
#
# List of topics of our TSA-Engine
#
export OPENTSX_TOPIC_MAP_FILE_NAME=./../config/topiclist.def

#
# Client configuration for a particular Kafka cluster (CP local or Confluent cloud)
#
#export OPENTSX_PRIMARY_CLUSTER_CLIENT_CFG_FILE_NAME=./../config/cpl.props
export OPENTSX_PRIMARY_CLUSTER_CLIENT_CFG_FILE_NAME=./../config/cpl_iMac.props



##############################################
# The load generator tool has a GUI switch.
#
# We run it in headless mode on servers, or with a UI in
# workstations.
#
#export OPENTSX_SHOW_GUI=false
export OPENTSX_SHOW_GUI=true

#
# How much data should be generated?
#
export OPENTSX_NUMBER_OF_ITERATIONS=100

#
# Setup the topics in cluster
#
mvn exec:java -Dexec.mainClass="org.opentsx.connectors.kafka.topicmanager.TopicsUP"

mvn exec:java -Dexec.mainClass="org.opentsx.connectors.kafka.topicmanager.TopicsCHECK"

echo "=============================="
echo " DEMO Setup done."
echo "=============================="
echo " "
echo " "
echo " "

./10_demo.sh






