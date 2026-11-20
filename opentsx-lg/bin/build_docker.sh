#!/usr/bin/env bash
cd .. || exit 1

#
# Builds the local example image opentsx/time-series-generator:<Maven version>.
#
sudo mvn clean compile package install -PSimpleTimeSeriesProducer,Docker

