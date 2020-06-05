export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk1.8.0_221.jdk/Contents/Home
export CONFLUENT_HOME=/Users/mkampf/bin/confluent-5.4.0

#$CONFLUENT_HOME/bin/confluent local status
#$CONFLUENT_HOME/bin/confluent local stop


export MDC1=192.168.3.172
export MDC2=192.168.3.5
export MDC3=192.168.3.105
export CONFLUENT_DOCKER_TAG=5.5.0
export REPOSITORY=confluentinc

echo "#################"
echo " START MDC: $1"
echo "#################"
echo ""
echo ">    MDC1                 : $MDC1"
echo ">    MDC2                 : $MDC2"
echo ">    MDC3                 : $MDC3"
echo ">>>  Repository           : $REPOSITORY"
echo ">>>  CONFLUENT_DOCKER_TAG : $CONFLUENT_DOCKER_TAG"
echo ""

cd $1
ls
docker-compose up

