cd ..

#
# opentsx-lg : opentsx/time-series-generator:${project.version}
#
sudo mvn clean compile package install -PSimpleTimeSeriesProducer,Docker