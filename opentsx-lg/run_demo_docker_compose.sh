export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk1.8.0_221.jdk/Contents/Home
export CONFLUENT_HOME=/Users/mkampf/bin/confluent-5.5.0

confluent local status

#confluent local stop

#confluent local destroy

#confluent local start

cd src/main/docker-compose/ping-tool-cons
docker-compose --env-file .env-inhouse up -d
cd ../../../..

cd src/main/docker-compose/ping-tool-prod
docker-compose --env-file .env-inhouse up -d

#
# This setup is used for execution of the refernece workload in the CuH stack ... or locally on my Mac.
#
