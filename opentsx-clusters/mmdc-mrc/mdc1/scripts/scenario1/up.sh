cd ../..

sudo docker-compose exec cli-west-1 kafka-topics --create --topic test-szenario-01 --bootstrap-server 192.168.0.9:9091

sudo docker-compose exec cli-west-1 kafka-topics --list --bootstrap-server 192.168.0.9:9091

sudo docker-compose exec cli-west-1 kafka-producer-perf-test --topic test-szenario-01 --num-records 5000 --record-size 100 --throughput -1 --producer-props acks=1 bootstrap.servers=192.168.0.9:9091 buffer.memory=67108864 batch.size=8196