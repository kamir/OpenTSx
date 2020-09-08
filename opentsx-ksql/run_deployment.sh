export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk1.8.0_221.jdk/Contents/Home
export CONFLUENT_HOME=/Users/mkampf/bin/confluent-5.4.0



confluent local status

rm -rfmkdir $CONFLUENT_HOME/etc/ksql/ext
mkdir $CONFLUENT_HOME/etc/ksql/ext

confluent local stop ksql-server

cp target/opentsx-udf-ksql-3.0.0-jar-with-dependencies.jar $CONFLUENT_HOME/etc/ksql/ext
# cp target/opentsx-udf-ksql-3.0.0.jar $CONFLUENT_HOME/etc/ksql/ext

confluent local start ksql-server

confluent local status

ls $CONFLUENT_HOME/etc/ksql/ext

echo "CREATE STREAM episodes_stream WITH (KAFKA_TOPIC='OpenTSx_Episodes', VALUE_FORMAT='AVRO');"

echo "CREATE STREAM events_stream WITH (KAFKA_TOPIC='OpenTSx_Events', VALUE_FORMAT='AVRO');"

$CONFLUENT_HOME/bin/ksql
