cd ..

export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk1.8.0_221.jdk/Contents/Home
export CONFLUENT_HOME=/Users/mkampf/bin/confluent-5.5.1

confluent local stop

confluent local destroy

confluent local start





#
# do not forget the profile !!!
#
mvn clean compile package install -PSimpleTimeSeriesProducer,Docker

