source .env

$CONFLUENT_HOME/bin/confluent local status

cd $1
docker-compose ps

