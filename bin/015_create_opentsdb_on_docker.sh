#!/bin/sh

#######################################################################################################
#
# Github: https://github.com/kamir/opentsdb-docker
#
# This image provides HBase and OpenTSDB for temporary storage of time series data and for experiments.
#
#######################################################################################################

rm -rf temp
mkdir temp
cd temp

git clone https://github.com/kamir/opentsdb-docker

cd opentsdb-docker

make

echo ""
echo "*********************************************"
echo "* Run the container:                        *"
echo "*********************************************"
echo ""
echo docker-compose up -d
echo ""

