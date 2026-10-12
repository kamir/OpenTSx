
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk1.8.0_221.jdk/Contents/Home
export CONFLUENT_HOME=/Users/mkampf/bin/confluent-5.5.1

echo "===================="
echo "[ in each terminal ]"
echo "===================="
echo ""
echo "export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk1.8.0_221.jdk/Contents/Home"
echo "export CONFLUENT_HOME=/Users/mkampf/bin/confluent-5.5.1"
echo " "
echo " "
echo " "

echo "==============="
echo "[ Terminal 2 ]"
echo "==============="
echo "(1) Start the KSQLDB-server."
echo " "
echo "export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk1.8.0_221.jdk/Contents/Home"
echo "export CONFLUENT_HOME=/Users/mkampf/bin/confluent-5.5.1"
#
# TODO: KSQLDB Integration with CCLOUD
#
echo "cd opentsx-ksql-app/bin/latest_ksqldb_server"
echo "docker-compose up"
echo " "
echo " "
echo " "

echo "==============="
echo "[ Terminal 3 ]"
echo "==============="
echo "(1) Start the KSQLDB-client."
echo "(2) Create KSQL-queries."
echo "(3) Inspect results using C3 UI."
echo " "
echo "cd opentsx-ksql-app/bin/latest_ksqldb_server"
echo "docker exec -it ksqldb-cli ksql http://ksqldb-server:8088"
echo " "
echo "# KSQL queries are available in file : [020_ksql_scripts.sql]"
echo " "

echo "==============="
echo "[ Terminal 4 ]"
echo "==============="
echo "(1) Start Kafka Streams applications."
echo "(2) Inspect results using C3 UI."
echo " "
#
# TODO: Kafka Streams application integration with CCLOUD
#
echo "export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk1.8.0_221.jdk/Contents/Home"
echo "export CONFLUENT_HOME=/Users/mkampf/bin/confluent-5.5.1"
echo "cd opentsx-kafka-streams-tsa"
echo "./bin/run.sh"
echo " "
echo " "
echo " "

echo "==============="
echo "[ Terminal 5 ]"
echo "==============="
echo "(1) Start Generator application:"
echo " "
#
# TODO: Client application integration with CCLOUD
#
echo "cd .. "
echo "cd .. "
echo "export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk1.8.0_221.jdk/Contents/Home"
echo "export CONFLUENT_HOME=/Users/mkampf/bin/confluent-5.5.1"
echo "cd opentsx-lg"
echo "export OPENTSX_TOPIC_MAP_FILE_NAME=./../config/topiclist.def"
echo "export OPENTSX_PRIMARY_CLUSTER_CLIENT_CFG_FILE_NAME=./../config/private/ccloud.props"
echo "export OPENTSX_SHOW_GUI=true"
echo "export OPENTSX_NUMBER_OF_ITERATIONS=500"
echo mvn exec:java -Dexec.mainClass="org.opentsx.util.topicmanager.TopicsUP"
echo mvn clean compile exec:java -Dexec.mainClass="org.opentsx.lg.TSDataSineWaveGenerator"
echo " "
echo " "
echo " "