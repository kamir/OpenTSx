cd ..

sudo docker-compose exec cli-west-1 kafka-topics --crate --topic test-szenario-01 --bootstrap-server 192.168.0.9:9091

sudo docker-compose exec cli-west-1 kafka-topics --list --bootstrap-server 192.168.0.9:9091


