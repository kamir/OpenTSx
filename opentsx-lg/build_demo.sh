#
# Prepare environment variables for local CP
#
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-11.0.15.1.jdk/Contents/Home
export CONFLUENT_HOME="$HOME/bin/confluent-7.3.0"

rm -rf "$HOME/.m2/repository/org/opentsx"

cd ..
mvn clean generate-sources compile package install -U

cd opentsx-lg
mvn clean generate-sources compile package install -PSimpleTimeSeriesProducer,Docker -U
