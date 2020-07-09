export TN=RUN1

docker-compose exec cli-west-2 kafka-topics --delete --topic $TN --bootstrap-server 192.168.3.5:9092

docker-compose exec cli-west-2 kafka-topics --list --bootstrap-server 192.168.3.5:9092

