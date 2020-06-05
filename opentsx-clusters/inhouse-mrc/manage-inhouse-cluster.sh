export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk1.8.0_221.jdk/Contents/Home
export CONFLUENT_HOME=/Users/mkampf/bin/confluent-5.4.0

$CONFLUENT_HOME/bin/confluent local status
$CONFLUENT_HOME/bin/confluent local stop

ifconfig

ping -c 5 127.0.0.1
hostname

echo "> ping to local machine"
echo "> MDC2"
ping -c 5 192.168.3.104

echo "> ping to i-Mac"
echo "> MDC1"
ping -c 5 192.168.3.172

echo "> ping to old mac-book"
echo "> MDC3"
ping -c 5 192.168.3.105

