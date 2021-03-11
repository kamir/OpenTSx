sudo pip3 install paho-mqtt

# https://gist.github.com/marianoguerra/be216a581ef7bc23673f501fdea0e15a
mkdir -p ./volume1/docker/mqtt/config

# brew install hivemq/mqtt-cli/mqtt-cli

mosquitto_passwd -c ./volume1/docker/mqtt/config/passwd demo1

mqtt sub -t test --mqttVersion 3

mqtt pub -t test -m "Hello2" --mqttVersion 3

#
# On Kafka cluster
#
confluent-hub install confluentinc/kafka-connect-mqtt:latest

confluent-hub install confluentinc/kafka-connect-influxdb:latest
