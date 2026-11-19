export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk1.8.0_221.jdk/Contents/Home
export CONFLUENT_HOME="$HOME/bin/confluent-5.5.1"

confluent local status

confluent local start