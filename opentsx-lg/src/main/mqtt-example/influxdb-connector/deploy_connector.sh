#
# DataGenerator (requires installation of the connector from the connect-hub
#.
curl --insecure -XPOST -H 'Content-Type:application/json' -d @./connector_INFLUX-001_config.json http://127.0.0.1:8083/connectors
