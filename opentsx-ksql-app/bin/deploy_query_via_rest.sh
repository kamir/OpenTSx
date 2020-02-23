
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk1.8.0_221.jdk/Contents/Home
export CONFLUENT_HOME=/Users/mkampf/bin/confluent-5.4.0

#confluent local destroy
#confluent local start

#
# Generate SAMPLE Data
#
cd ../../opentsx-lg/
mvn clean compile install exec:java -Dexec.mainClass="org.opentsx.lg.TSDataSineWaveGenerator" -Dexec.args="off" -Dexec.cleanupDaemonThreads=false

cd ../opentsx-ksql-app

#
# Some SQL statements require a specific endpoint in our KSQL Rest-API
#
###################################################################################

#
#  /ksql : only 1 statement is allowed!
#
statements1=$(< ./CP_HOME/scripts/demo_2_webui_define.sql) && \
    echo '{"ksql":"'$statements1'", "streamsProperties": {}}' | \
        curl -X "POST" "http://localhost:8088/ksql" \
             -H "Content-Type: application/vnd.ksql.v1+json; charset=utf-8" \
             -d @- | \
        jq
echo $statements1

#
#  /query : only 1 statement is allowed!
#
#statements2=$(< ./CP_HOME/scripts/demo_2_webui_query.sql) && \
#    echo '{"ksql":"'$statements2'", "streamsProperties": {}}' | \
#        curl -X "POST" "http://localhost:8088/query" \
#             -H "Content-Type: application/vnd.ksql.v1+json; charset=utf-8" \
#             -d @- | \
#        jq
#echo $statement2
#

curl -sX GET "http://localhost:8088/info" | jq '.'

curl -sX GET "http://localhost:8088/healthcheck" | jq '.'



