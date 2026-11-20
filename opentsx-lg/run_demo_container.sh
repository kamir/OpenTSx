#!/usr/bin/env bash
#
# Needs CONFLUENT_HOME, the directory of a Confluent Platform installation; exits 2 without it.
#
if [[ -z "${CONFLUENT_HOME:-}" ]]; then
  echo "CONFLUENT_HOME: not set; expected the directory of a Confluent Platform installation" >&2
  exit 2
fi

confluent local status

confluent local destroy

confluent local start

rm -rf "$HOME/.m2/repository/org/opentsx"

cd .. || exit 1
mvn clean generate-sources compile package install

cd opentsx-lg || exit 1
mvn clean generate-sources compile package install -PSimpleTimeSeriesProducer,Docker

##################################
#
# Silent Mode ...
#
###################################
export OPENTSX_SHOW_GUI=false

#
# inspection of LOG-File size
#
"$CONFLUENT_HOME"/bin/kafka-log-dirs --bootstrap-server 127.0.0.1:9092 --describe --topic-list OpenTSx_Episodes,OpenTSx_Events
