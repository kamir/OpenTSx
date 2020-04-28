export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk1.8.0_221.jdk/Contents/Home
export CONFLUENT_HOME=/Users/mkampf/bin/confluent-5.4.0

$CONFLUENT_HOME/bin/confluent local status

ifconfig

ping -c 5 127.0.0.1

echo "> ping to local machine"
ping -c 5 192.168.3.104

echo "> ping to old mac-book"
ping -c 5 192.168.3.105

echo "> ping to i-Mac"
ping -c 5 192.168.3.172

hostname