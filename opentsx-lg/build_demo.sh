#!/usr/bin/env bash

rm -rf "$HOME/.m2/repository/org/opentsx"

cd .. || exit 1
mvn clean generate-sources compile package install -U

cd opentsx-lg || exit 1
mvn clean generate-sources compile package install -PSimpleTimeSeriesProducer,Docker -U
