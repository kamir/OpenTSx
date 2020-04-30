export TN=T2.5
export ZM=1000000

echo "*******************"
echo "Topicname: $TN"
echo "*******************"

rm -rf tmp_consumer1

sudo docker-compose exec cli-west-1 kafka-consumer-perf-test --topic $TN --messages $ZM --group=perf-test-1 --show-detailed-stats --hide-header --timeout 60000 --bootstrap-server broker-west-1:9091 > tmp_consumer1

cat tmp_consumer1

echo "Consumer 1:" \
    `cat tmp_consumer1 \
     | awk -F"," '{if($8>0){msec+=$8};mb=$3}END{print mb*1000/msec}'` \
    "MB/sec"


