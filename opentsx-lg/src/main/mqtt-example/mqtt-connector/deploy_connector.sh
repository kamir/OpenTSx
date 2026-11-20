#!/usr/bin/env bash
#
# Needs the MQTT source connector from Confluent Hub; 01_bootstrap.sh installs it.
#
curl --insecure -XPOST -H 'Content-Type:application/json' -d @./connector_MQTT-001_config.json http://127.0.0.1:8083/connectors
