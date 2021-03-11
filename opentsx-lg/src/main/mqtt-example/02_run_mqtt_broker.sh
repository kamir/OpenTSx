# https://philhawthorne.com/setting-up-a-local-mosquitto-server-using-docker-for-mqtt-communication/

export WP=$(pwd)
docker run --name mqtt --restart=always -tid \
 -p 1883:1883  \
 -v $WP/volume1/docker/mqtt/config:/mqtt/config:ro \
 -v $WP/volume1/docker/mqtt/log:/mqtt/log \
 -v $WP/volume1/docker/mqtt/data/:/mqtt/data/ \
 toke/mosquitto

mqtt test -h 127.0.0.1

