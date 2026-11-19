cd ..

export CONFLUENT_HOME="$HOME/bin/confluent-5.5.1"

confluent local stop

confluent local destroy

confluent local start





#
# do not forget the profile !!!
#
mvn clean compile package install -PSimpleTimeSeriesProducer,Docker

