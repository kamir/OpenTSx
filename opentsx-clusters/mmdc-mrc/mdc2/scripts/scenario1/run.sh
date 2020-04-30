export TN=T2.3
export ZM=1000

echo "*******************"
echo "Topicname: $TN"
echo "*******************"
echo ""

sudo docker-compose exec cli-west-2 kafka-topics --create --topic $TN --replication-factor 3 --partitions 108 --bootstrap-server broker-west-1:9091

rm -rf prod.config
echo "bootstrap.servers=broker-west-1:9091" >> prod.config

echo ""
echo "*******************"
cat prod.config
echo "*******************"
echo ""

sudo docker cp prod.config cli-west-2:config
echo "- copied CFG to image ..."
echo "- started producer for $ZM messages ..."

sudo docker-compose exec cli-west-2 kafka-producer-perf-test --topic $TN --record-size 512 --throughput 60000 --num-records $ZM --producer-props acks=all linger.ms=10 --producer.config /config > tmp_producer

cat tmp_producer

sudo docker-compose exec cli-west-2 kafka-topics --describe --topic $TN