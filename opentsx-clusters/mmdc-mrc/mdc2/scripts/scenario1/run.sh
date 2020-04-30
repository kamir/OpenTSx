sudo docker-compose exec cli-east-1 kafka-topics --list --bootstrap-server 192.168.0.9:9091

sudo docker-compose exec cli-east-1 kafka-topics --create --topic test-szenario-01 --replication-factor 3 --partitions 108 --bootstrap-server 192.168.0.9:9091
