# https://hub.docker.com/r/philhawthorne/docker-influxdb-grafana/
# https://thenewstack.io/how-to-setup-influxdb-telegraf-and-grafana-on-docker-part-1/

#
# http://localhost:3003
# user     : root
# password : root

#
# http://localhost:3004
# Username: root
# Password: root
# Port: 8086

export WP=$(pwd)

mkdir -p influxdb
mkdir -p grafana

docker run -d \
  --name docker-influxdb-grafana \
  -p 3003:3003 \
  -p 3004:8083 \
  -p 8086:8086 \
  -v $WP/influxdb:/var/lib/influxdb \
  -v $WP/grafana:/var/lib/grafana \
  philhawthorne/docker-influxdb-grafana:latest


docker container ls
docker exec -it 5210acac7ac3 bash
grafana-cli admin reset-admin-password admin




