#!/usr/bin/env bash
#
# DataGenerator connector; install it from Confluent Hub first.
#
curl --insecure -XPOST -H 'Content-Type:application/json' -d @./connector_Generator-001_config.json http://127.0.0.1:8083/connectors

#
# FileSink connector
#
curl --insecure -XPOST -H 'Content-Type:application/json' -d @./cfg.json http://127.0.0.1:8083/connectors