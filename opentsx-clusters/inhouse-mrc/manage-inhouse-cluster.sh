export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk1.8.0_221.jdk/Contents/Home
export CONFLUENT_HOME=/Users/mkampf/bin/confluent-5.4.0

#$CONFLUENT_HOME/bin/confluent local status
#$CONFLUENT_HOME/bin/confluent local stop

if [ -z "$1" ]
  then
    echo "No MDC ID provided."
fi

if [ -z "$2" ]
  then
    echo "No command for docker compose provided."
fi

if [ -z "$3" ]
  then
    echo "Will run controle script in foreground."
fi

echo "********************************************"
echo "* Commands to start a MDC ...              *"
echo "********************************************"
echo " cd $1"
echo " docker-compose $2 $3"
echo "********************************************"

while true; do
    read -p "Are you ready to continue? " yn
    case $yn in
        [Yy]* ) break;;
        [Nn]* ) exit;;
        * ) echo "Please answer yes or no.";;
    esac
done

echo "********************************************"

ifconfig

ping -c 5 127.0.0.1

hostname

export MDC1=192.168.3.172
export MDC2=192.168.3.5
export MDC3=192.168.3.173
export CONFLUENT_DOCKER_TAG=5.5.0
export REPOSITORY=confluentinc

echo "> ping to local machine"
echo "> MDC2"
ping -c 5 $MDC2

echo "> ping to i-Mac"
echo "> MDC1"
ping -c 5 $MDC1

echo "> ping to old mac-book"
echo "> MDC3"
ping -c 5 $MDC3

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

#docker-compose up -d

docker-compose $2 $3


