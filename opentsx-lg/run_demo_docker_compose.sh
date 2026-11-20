#!/usr/bin/env bash
#
# Needs CONFLUENT_HOME, the directory of a Confluent Platform installation; exits 2 without it.
#
if [[ -z "${CONFLUENT_HOME:-}" ]]; then
  echo "CONFLUENT_HOME: not set; expected the directory of a Confluent Platform installation" >&2
  exit 2
fi

confluent local status

cd src/main/docker-compose/ping-tool-cons || exit 1
docker-compose --env-file .env-inhouse up -d
cd ../../../.. || exit 1

cd src/main/docker-compose/ping-tool-prod || exit 1
docker-compose --env-file .env-inhouse up -d
