export TN=RUN1
export ZM=5000000

echo "*******************"
echo "Topicname: $TN"
echo "*******************"
echo ""

docker-compose exec cli-west-2 kafka-topics --create --topic $TN --replication-factor 3 --partitions 108 --bootstrap-server broker-west-1:9091

rm -rf prod.config
echo "bootstrap.servers=broker-west-1:9091" >> prod.config

echo ""
echo "*******************"
cat prod.config
echo "*******************"
echo ""

docker cp prod.config cli-west-2:config
echo "- copied CFG to image ..."
echo "- started producer for $ZM messages ..."

rm -rf tmp_producer
docker-compose exec cli-west-2 kafka-producer-perf-test --topic $TN --record-size 512 --throughput 60000 --num-records $ZM --producer-props acks=all linger.ms=10 --producer.config /config > tmp_producer
echo "- done!"

docker-compose exec cli-west-2 kafka-topics --describe --topic $TN --bootstrap-server broker-west-1:9091

cat tmp_producer

echo "Test Results:"
tail -n 1 tmp_producer | sed 's|.*(\(.*\))|Producer: \1|g'
