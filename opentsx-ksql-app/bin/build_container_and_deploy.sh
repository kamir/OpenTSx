
export CONTAINER_NAME=opentsx-ksql
export CONTAINER_VERSION=3.0.0

export HOST_NAME_OF_HOST=MirkoKampfMBP15

export KSQL_SERVICE_ID=ksql_service_opentsx_1_

export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk1.8.0_221.jdk/Contents/Home
export CONFLUENT_HOME=/Users/mkampf/bin/confluent-5.4.0



confluent local destroy



#cd ../..
#mvn clean compile package install
#cd opentsx-ksql-app/bin



cd ..
rm -rf CP_HOME/etc/ksql/ext
mkdir -p CP_HOME/etc/ksql/ext

cp ../opentsx-udf-ksql/target/opentsx-udf-ksql-3.0.0-jar-with-dependencies.jar CP_HOME/etc/ksql/ext

ls CP_HOME/etc/ksql/ext

docker build . -t $CONTAINER_NAME:$CONTAINER_VERSION

#
# Rebuild CP environment
#
confluent local start

#
# Generate SAMPLE Data
#
cd ../opentsx-lg/
mvn exec:java -Dexec.mainClass="org.opentsx.lg.TSDataSineWaveGenerator" -Dexec.args="off" -Dexec.cleanupDaemonThreads=false

cd ../opentsx-ksql-app

#
# Stop KSQL-Server
#
confluent local stop ksql-server

docker run -d \
  -p 8088:8088 \
  -e KSQL_BOOTSTRAP_SERVERS=$HOST_NAME_OF_HOST:9092 \
  -e KSQL_LISTENERS=http://0.0.0.0:8088/ \
  -e KSQL_KSQL_SERVICE_ID=$KSQL_SERVICE_ID \
  -e KSQL_KSQL_CONNECT_URL=http://$HOST_NAME_OF_HOST:8083  \
  -e KSQL_KSQL_QUERIES_FILE=/scripts/demo_1.sql \
  -e KSQL_OPTS="-Dclient.id=ksql_service_opentsx_1_" \
  -e KSQL_KSQL_SCHEMA_REGISTRY_URL=http://$HOST_NAME_OF_HOST:8081 \
  $CONTAINER_NAME:$CONTAINER_VERSION

curl -X "POST" "http://localhost:8088/ksql" \
     -H "Content-Type: application/vnd.ksql.v1+json; charset=utf-8" \
     -d $'{
  "ksql": "LIST STREAMS;",
  "streamsProperties": {}
}'

curl -sX GET "http://localhost:8088/info" | jq '.'

curl -sX GET "http://localhost:8088/healthcheck" | jq '.'

echo $CONFLUENT_HOME/bin/ksql