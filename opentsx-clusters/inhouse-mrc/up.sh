source .env

$CONFLUENT_HOME/bin/confluent local stop

cd $1
docker-compose up -d

