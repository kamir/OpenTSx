export TN=T2.1
export ZM=1000

echo "*******************"
echo "Topicname: $TN"
echo "*******************"

sudo docker-compose exec cli-west-1 kafka-consumer-perf-test --topic $TN --messages $ZM --group=perf-test-1 --show-detailed-stats --hide-header --timeout 60000 --bootstrap-server broker-west-1:9091 | tee /tmp/consumer1

sudo docker cp cli-west-1:/tmp/consumer1 consumer1

