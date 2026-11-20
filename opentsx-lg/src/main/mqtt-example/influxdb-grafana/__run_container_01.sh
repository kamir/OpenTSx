#!/usr/bin/env bash
# https://hub.docker.com/r/philhawthorne/docker-influxdb-grafana/
# https://thenewstack.io/how-to-setup-influxdb-telegraf-and-grafana-on-docker-part-1/

#
# http://localhost:3003
# user     : root
# password : the value of GRAFANA_ADMIN_PASSWORD

#
# http://localhost:3004
# Username: root
# Password: root
# Port: 8086
#
# Needs GRAFANA_ADMIN_PASSWORD, the Grafana admin password the script sets; exits 2 without it.
#
if [[ -z "${GRAFANA_ADMIN_PASSWORD:-}" ]]; then
  echo "GRAFANA_ADMIN_PASSWORD: missing; export the Grafana admin password to set before you run the script" >&2
  exit 2
fi

CONTAINER=docker-influxdb-grafana
WP=$(pwd)

mkdir -p influxdb
mkdir -p grafana

docker run -d \
  --name "$CONTAINER" \
  -p 3003:3003 \
  -p 3004:8083 \
  -p 8086:8086 \
  -v "$WP/influxdb:/var/lib/influxdb" \
  -v "$WP/grafana:/var/lib/grafana" \
  philhawthorne/docker-influxdb-grafana@sha256:fe36a6b2b9fc8779c5ffc4b25cd0db5d24ebbb62ce4ac18148a4bc8c2fef0771


docker container ls
printf '%s\n' "$GRAFANA_ADMIN_PASSWORD" |
  docker exec -i "$CONTAINER" grafana-cli admin reset-admin-password --password-from-stdin
