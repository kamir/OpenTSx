#
# Prepare environment variables for local CP
#
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-11.0.15.1.jdk/Contents/Home
export CONFLUENT_HOME=/Users/mkaempf/bin/confluent-7.3.0

#
# clean and start local CP
#
confluent local current

confluent local services stop

confluent local services destroy

confluent local services start

#
# Build the stack
#
#
# Clean and build OpenTSx core project library
#
rm -rf /Users/mkaempf/.m2/repository/org/opentsx
cd /Users/mkaempf/GITHUB.private/OpenTSx
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
export OPENTSX_PRIMARY_CLUSTER_CLIENT_CFG_FILE_NAME=./../config/cpl.props


#
# GUI switch
#
#export OPENTSX_SHOW_GUI=false
export OPENTSX_SHOW_GUI=true

export OPENTSX_NUMBER_OF_ITERATIONS=100

mvn clean compile exec:java -Dexec.mainClass="org.opentsx.lg.TSDataSineWaveGenerator"

cd /Users/mkeampf/GITHUB.private/OpenTSx/opentsx-ksql-udf/demo-udf
mvn clean compile package

cp /Users/mkaempf/GITHUB.private/OpenTSx/opentsx-ksql-udf/demo-udf/target/demo-udf-3.0.1.jar /Users/mkaempf/GITHUB.private/OpenTSx/opentsx-ksql-app/ksql-server-extension/demo-udf-3.0.1.jar

cd /Users/mkaempf/GITHUB.private/OpenTSx/opentsx-app-demos/meetup-09-2020

echo "=============================="
echo " DEMO Setup done."
echo " Initital data has been loaded."
echo "=============================="
echo " "
echo " "
echo " "

./10_demo.sh






