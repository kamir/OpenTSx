#!/bin/sh

#
# Run KUDU and OpenTSDB containers for TS persistency ...
#

./run_kudu_on_docker_locally.sh &

./run_opentsdb_on_docker_locally.sh &

