export TN=T2.4
export ZM=100000

echo "*******************"
echo "Topicname: $TN"
echo "*******************"

rm -rf tmp_consumer1
sudo docker-compose exec cli-west-1 kafka-consumer-perf-test --topic $TN --messages $ZM --group=perf-test-1 --show-detailed-stats --hide-header --timeout 60000 --bootstrap-server broker-west-1:9091 > tmp_consumer1

cat tmp_consumer1

