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
mvn clean generate-sources compile package install

#
# Silent Mode ...
#
# mvn exec:java -Dexec.mainClass="org.opentsx.lg.TSDataSineWaveGenerator" -Dexec.arguments="off"

#
# Show the time series in an TSA-Panel ...
#
mvn clean compile exec:java -Dexec.mainClass="org.opentsx.lg.TSDataSineWaveGenerator"
#mvn exec:java -Dexec.mainClass="org.opentsx.lg.TSDataSineWaveGenerator"

# inspect the JSON data : https://codebeautify.org/



#
# inspection of LOG-File size
#
$CONFLUENT_HOME/bin/kafka-log-dirs --bootstrap-server 127.0.0.1:9092 --describe --topic-list OpenTSx_Episodes,OpenTSx_Events | python -mjson.tool
