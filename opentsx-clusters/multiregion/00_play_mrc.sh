#!/bin/bash
cd ~/Demos/examples/multiregion/

# Showcase to customer beginning from here
# Describe Topics
./scripts/describe-topics.sh

# Produce Data: And check throughput: async and single have nearly the same throughput (onyl WEST have to ack), 
#               sync is slower because of poor network bandwidth between the east and west
./scripts/run-producer.sh

# check status of multi-region-async
kafka-replica-status --bootstrap-server localhost:9092 --topics multi-region-async --partitions 0 --verbose 

# Consume Data: Consume for east and read from west leader lower throughput is lower
#               Consume from east observers throughput is higher
./scripts/run-consumer.sh

# Monitoring observers: multi-region-asynch
#                       ReplicasCount with Observers, InSyncReplicasCount w/o observers, CaughtUpReplicasCount all with leader
./scripts/jmx_metrics.sh

# Check Client Failover
./scripts/run-consumer-sync-failover.sh 
# open second terminal to stop west

# Failover and Failback
# Fail region west
docker-compose stop broker-west-1 broker-west-2 zookeeper-west
# produce Data, should be visible in consumer (automatic failover)
./scripts/run-producer-after-stopwest.sh 

# Single - NO Leader, SYNC has leader in EAST clients can failover, ASYN NO Leader because EAST not in ISR only west
./scripts/describe-topics.sh
# Trigger leader election of observers (ASYNCH), UNCLEAN may result in data loss
docker-compose exec broker-east-4 kafka-leader-election --bootstrap-server broker-east-4:19094 --election-type UNCLEAN --topic multi-region-async --partition 0
# Now ASNYCH has a leader
./scripts/describe-topics.sh

# Failback region WEST
docker-compose start broker-west-1 broker-west-2 zookeeper-west
# WAIT
# All topics have leaders again, Leaders restored to WEST
./scripts/describe-topics.sh