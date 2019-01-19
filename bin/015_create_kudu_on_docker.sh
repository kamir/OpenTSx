#!/bin/sh

export RUN_ID=2

rm -rf temp
mkdir temp
cd temp

git clone https://github.com/kamir/kudu-docker-all-in-one

cd kudu-docker-all-in-one

make

cd ../..

echo ""
echo "*********************************************"
echo "* Run the container:                        *"
echo "*********************************************"
echo ""
echo docker run -d --network=bridge -p 127.0.0.1:7051:7051 -p 127.0.0.1:8051:8051 -p 127.0.0.1:8050:8050 -p 127.0.0.1:25000:25000 -p 127.0.0.1:7050:7050 -h 127.0.0.1  kamir/kudu-docker-all-in-one
echo ""

