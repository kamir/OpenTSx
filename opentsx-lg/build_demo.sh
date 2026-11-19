#
# Prepare environment variables for local CP
#
export CONFLUENT_HOME="$HOME/bin/confluent-7.3.0"

rm -rf "$HOME/.m2/repository/org/opentsx"

cd ..
mvn clean generate-sources compile package install -U

cd opentsx-lg
mvn clean generate-sources compile package install -PSimpleTimeSeriesProducer,Docker -U
