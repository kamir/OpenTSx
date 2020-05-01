#!/bin/bash

echo -e "\n\n==> Consume: Multi-region async Replication reading from Leader in west (topic: multi-region-async) \n"

docker-compose exec broker-east-3 kafka-console-consumer --topic multi-region-sync \
    --bootstrap-server broker-west-1:19091,broker-east-3:19093,broker-west-2:19092,broker-east-4:19094 \
    --from-beginning

