#
# opentsx-lg : opentsx/time-series-generator:${project.version}
#
cd opentsx-lg
sudo mvn clean compile package install -PSimpleTimeSeriesProducer,Docker
