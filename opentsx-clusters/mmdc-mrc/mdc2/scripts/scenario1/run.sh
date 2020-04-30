sudo docker-compose exec cli-west-2 kafka-topics --list --bootstrap-server broker-west-1:9091

sudo docker-compose exec cli-west-2 kafka-topics --create --topic test-szenario-01 --replication-factor 3 --partitions 108 --bootstrap-server broker-west-1:9091

sudo docker-compose exec cli-west-2 kafka-topics --list --bootstrap-server broker-west-1:9091
