#sudo docker-compose exec cli-west-2 kafka-topics --list --bootstrap-server broker-west-1:9091

sudo docker-compose exec cli-west-2 kafka-topics --create --topic test-szenario-01 --replication-factor 3 --partitions 108 --bootstrap-server broker-west-1:9091

echo "bootstrap-server=broker-west-1:9091" >> config

sudo docker cp config cli-west-2:config

sudo docker-compose exec cli-west-2 kafka-producer-perf-test --topic test-szenario-01 --record-size 512 --throughput 60000 --num-records 54000000 --producer-props acks=all linger.ms=10 --producer.config /config | tee /tmp/producer &

#sudo docker-compose exec cli-west-2 kafka-topics --list --bootstrap-server broker-west-1:9091
