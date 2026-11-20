#!/usr/bin/env bash
pip3 install --user paho-mqtt==1.6.1

# https://gist.github.com/marianoguerra/be216a581ef7bc23673f501fdea0e15a
mkdir -p ./volume1/docker/mqtt/config

# brew install hivemq/mqtt-cli/mqtt-cli

mosquitto_passwd -c ./volume1/docker/mqtt/config/passwd demo1

mqtt pub -t test -m "Hello2" --mqttVersion 3

#
# On Kafka cluster
#
confluent-hub install confluentinc/kafka-connect-mqtt:1.7.11

confluent-hub install confluentinc/kafka-connect-influxdb:1.2.13
