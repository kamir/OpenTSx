export TN=T2.1

echo "*******************"
echo "Topicname: $TN"
echo "*******************"

sudo docker-compose exec cli-west-1 kafka-topics --list --bootstrap-server 192.168.0.9:9091

sudo docker-compose exec cli-west-1 kafka-consumer-perf-test --topic $TN --messages 53950000 --group=perf-test-1 --show-detailed-stats --hide-header --timeout 60000 --bootstrap-server broker-west-1:9091 | tee /tmp/consumer1 &

