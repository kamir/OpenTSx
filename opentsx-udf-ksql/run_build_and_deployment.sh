export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk1.8.0_221.jdk/Contents/Home
export CONFLUENT_HOME=/Users/mkampf/bin/confluent-5.4.0

#cd ..
mvn clean compile package install
#cd opentsx-udf-ksql

confluent local status

rm -rf $CONFLUENT_HOME/etc/ksql/ext
mkdir $CONFLUENT_HOME/etc/ksql/ext

confluent local stop ksql-server

cp target/opentsx-udf-ksql-3.0.0-jar-with-dependencies.jar $CONFLUENT_HOME/etc/ksql/ext
#cp target/opentsx-udf-ksql-3.0.0.jar $CONFLUENT_HOME/etc/ksql/ext

confluent local start ksql-server

confluent local status

ls $CONFLUENT_HOME/etc/ksql/ext

$CONFLUENT_HOME/bin/ksql
