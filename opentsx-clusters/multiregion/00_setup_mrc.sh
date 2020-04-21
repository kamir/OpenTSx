#!/bin/bash
cd ~/Demos
# Install examples
git clone https://github.com/confluentinc/examples
# MRC demo
cd ~/Demos/examples/multiregion/
# Demo see https://github.com/confluentinc/examples/tree/5.4.1-post/multiregion

# Start MRC demos
docker-compose up -d
# Check: Three regions
docker-compose ps
# check latency between all regions: Oumba docker testing tool
./scripts/latency_docker.sh
docker container ls --filter "name=pumba"
docker inspect -f '{{.Name}} - {{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' $(docker ps -aq)

# Create Topics
./scripts/create-topics.sh

# go to 00_play_mrc.sh

# stop docker
./scripts/stop.sh
docker-compose down -v


# END TO END DEMO
./scripts/start.sh
# STOP DEMO
./scripts/stop.sh


### TROUBLESHOOT
# Stop demo
./scripts/stop.sh

# Clean up the Docker environment
for c in $(docker container ls -q --filter "name=pumba"); do docker container stop "$c" && docker container rm "$c"; done
docker-compose down -v --remove-orphans
for v in $(docker volume ls -q --filter="dangling=true"); do docker volume rm "$v"; done

# Restart demo
./scripts/start.sh