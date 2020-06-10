cd ../..

###
#
# https://gist.github.com/ueokande/b96eadd798fff852551b80962862bfb3
##

sudo docker-compose exec cli-west-1 kafka-topics --create --topic test-szenario-02 --partitions 1 --replication-factor 6 --bootstrap-server 192.168.0.9:9091

sudo docker-compose exec cli-west-1 kafka-topics --create --topic test-szenario-01 --replica-assignment 5:6:7:1:2:3:8:9:10 --bootstrap-server 192.168.0.9:9091

sudo docker-compose exec cli-west-1 kafka-topics --list --bootstrap-server 192.168.0.9:9091

sudo docker-compose exec cli-west-1 kafka-producer-perf-test --topic test-szenario-01 --num-records 1000000 --record-size 100 --throughput -1 --producer-props acks=1 bootstrap.servers=192.168.0.9:9091 buffer.memory=67108864 batch.size=8196

now=$(date);sudo docker-compose exec cli-west-1 kafka-consumer-perf-test --topic test-szenario-01 --messages 1000000 --group=perf-test-3-$now --show-detailed-stats --broker-list broker-west-1:9091 --reporting-interval 1000

sudo docker-compose exec cli-west-1 kafka-topics -describe --topic test-szenario-01 --bootstrap-server 192.168.0.9:9091