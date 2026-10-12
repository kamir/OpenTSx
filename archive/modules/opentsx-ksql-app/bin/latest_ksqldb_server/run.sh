cd /Users/mkampf/GITHUB.public/OpenTSx/opentsx-ksql-udf/demo-udf
mvn clean compile package

cp /Users/mkampf/GITHUB.public/OpenTSx/opentsx-ksql-udf/demo-udf/target/demo-udf-3.0.1.jar /Users/mkampf/GITHUB.public/OpenTSx/opentsx-ksql-app/ksql-server-extension/demo-udf-3.0.1.jar

mvn clean

cd /Users/mkampf/GITHUB.public/OpenTSx/opentsx-ksql-app/bin/latest_ksqldb_server

ls /Users/mkampf/GITHUB.public/OpenTSx/opentsx-ksql-app/ksql-server-extension

docker-compose up

