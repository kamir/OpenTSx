export TN=test-szenario-01
export ZM=1000000

cd ../..

source env.sh

echo "*******************"
echo "Topicname: $TN"
echo "*******************"
echo $REPOSITORY
echo $CONFLUENT_DOCKER_TAG

rm -rf tmp_consumer1
rm -rf tmp_consumer2
rm -rf tmp_consumer3

now=$(date +%s);sudo docker-compose exec cli-west-1 kafka-consumer-perf-test --topic $TN --messages $ZM --group=perf-test-1-$now --show-detailed-stats --broker-list broker-west-5:9091 --reporting-interval 100 --hide-header > tmp_consumer1
now=$(date +%s);sudo docker-compose exec cli-west-1 kafka-consumer-perf-test --topic $TN --messages $ZM --group=perf-test-2-$now --show-detailed-stats --broker-list broker-west-6:9092 --reporting-interval 100 --hide-header > tmp_consumer2
now=$(date +%s);sudo docker-compose exec cli-west-1 kafka-consumer-perf-test --topic $TN --messages $ZM --group=perf-test-3-$now --show-detailed-stats --broker-list broker-west-7:9093 --reporting-interval 100 --hide-header > tmp_consumer3

cat tmp_consumer1
echo

cat tmp_consumer2
echo

cat tmp_consumer3
echo

echo "Consumer 1:" \
    `cat tmp_consumer1 \
     | awk -F"," '{if($8>0){msec+=$8};mb=$3}END{print mb*1000/msec}'` \
    "MB/sec"

echo "Consumer 2:" \
    `cat tmp_consumer2 \
     | awk -F"," '{if($8>0){msec+=$8};mb=$3}END{print mb*1000/msec}'` \
    "MB/sec"

echo "Consumer 3:" \
    `cat tmp_consumer3 \
     | awk -F"," '{if($8>0){msec+=$8};mb=$3}END{print mb*1000/msec}'` \
    "MB/sec"