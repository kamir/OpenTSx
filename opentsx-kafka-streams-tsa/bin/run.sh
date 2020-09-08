cd /Users/mkampf/GITHUB.public/OpenTSx/opentsx-kafka-streams-tsa

export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk1.8.0_221.jdk/Contents/Home
export CONFLUENT_HOME=/Users/mkampf/bin/confluent-5.5.1

mvn clean compile package install

mvn exec:java -Dexec.mainClass="org.opentsx.tsa.TSAExample1" &

mvn exec:java -Dexec.mainClass="org.opentsx.tsa.TSAExample2" &

mvn exec:java -Dexec.mainClass="org.opentsx.tsa.TSAExample3" &

mvn exec:java -Dexec.mainClass="org.opentsx.tsa.TSAExample10" &
