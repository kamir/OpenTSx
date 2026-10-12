#!/bin/sh

docker run -d --network=bridge -p 127.0.0.1:4242:4242 -p 127.0.0.1:60010:60010 -h 127.0.0.1 kamir/opentsdb-hbase-docker-all-in-one

open http://localhost:4242 http://localhost:60010

echo ""
echo "> Please give the container enough time to stop services ..."
echo ""
echo "cd $(pwd)"
echo docker-compose stop -t 30

#telnet 127.0.0.1 4242
#
#put sys.if.bytes.out 1479496000 1.3E3 host=web01 interface=eth0
#put sys.if.bytes.out 1479496000 3.3E3 host=web01 interface=eth1
#put sys.if.bytes.out 1479497000 2.3E3 host=web01 interface=eth0
#put sys.if.bytes.out 1479497000 0.3E3 host=web01 interface=eth1
#put sys.if.bytes.out 1479498000 3.3E3 host=web01 interface=eth0
#put sys.if.bytes.out 1479498000 1.3E3 host=web01 interface=eth1
#put sys.if.bytes.out 1479499000 0.3E3 host=web01 interface=eth0
#put sys.if.bytes.out 1479499000 1.3E3 host=web01 interface=eth1
#put sys.if.bytes.out 1479500000 1.5E3 host=web01 interface=eth0
#put sys.if.bytes.out 1479500000 1.1E3 host=web01 interface=eth1
#put sys.if.bytes.out 1479501000 1.3E3 host=web01 interface=eth0
#put sys.if.bytes.out 1479501000 1.3E3 host=web01 interface=eth1