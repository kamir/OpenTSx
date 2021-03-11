
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk1.8.0_101.jdk/Contents/Home
$JAVA_HOME/bin/java -version

export CONFLUENT_HOME=/Users/kamir/bin/confluent-6.0.0

echo "==============="
echo "[ Terminal 1 ]"
echo "==============="
echo "(1) Start the Event-Sender Service."
echo " "
echo "export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk1.8.0_101.jdk/Contents/Home"
echo "export CONFLUENT_HOME=/Users/kamir/bin/confluent-6.0.0"
echo " "
echo "cd opentsx-lg"
echo "export OPENTSX_TOPIC_MAP_FILE_NAME=./../config/topiclist.def"
echo "export OPENTSX_PRIMARY_CLUSTER_CLIENT_CFG_FILE_NAME=./../config/cpl_iMac.props"
echo "export OPENTSX_SHOW_GUI=true"
echo "export OPENTSX_NUMBER_OF_ITERATIONS=500"
echo "mvn clean compile exec:java -Dexec.mainClass='org.opentsx.lg.klatency.RequestResponseAnalysisProducer'"
echo " "

echo "==============="
echo "[ Terminal 2 ]"
echo "==============="
echo "(1) Start the Event-Receiver Service."
echo " "
echo "export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk1.8.0_101.jdk/Contents/Home"
echo "export CONFLUENT_HOME=/Users/kamir/bin/confluent-6.0.0"
echo " "
echo "cd opentsx-lg"
echo "export OPENTSX_PRIMARY_CLUSTER_CLIENT_CFG_FILE_NAME=./../config/cpl_iMac.props"
echo "export OPENTSX_SHOW_GUI=true"
echo "mvn clean compile exec:java -Dexec.mainClass='org.opentsx.lg.klatency.RequestResponseAnalysisConsumer'"
echo " "

echo "==============="
echo "[ Terminal 3 ]"
echo "==============="
echo "(3) Start the Event-Echo Service."
echo " "
echo "export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk1.8.0_101.jdk/Contents/Home"
echo "export CONFLUENT_HOME=/Users/kamir/bin/confluent-6.0.0"
echo " "
