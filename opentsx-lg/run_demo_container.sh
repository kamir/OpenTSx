export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk1.8.0_221.jdk/Contents/Home
export CONFLUENT_HOME="$HOME/bin/confluent-5.4.0"

confluent local status

confluent local destroy

confluent local start

rm -rf "$HOME/.m2/repository/org/opentsx"

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
# inspection of LOG-File size
#
$CONFLUENT_HOME/bin/kafka-log-dirs --bootstrap-server 127.0.0.1:9092 --describe --topic-list OpenTSx_Episodes,OpenTSx_Events
