#
# Maintain the topics
#

$CONFLUENT_HOME/bin/kafka-topics --list --bootstrap-server 127.0.0.1:9092

$CONFLUENT_HOME/bin/kafka-topics --delete --bootstrap-server 127.0.0.1:9092 --topic refds_small_bucket_topic
$CONFLUENT_HOME/bin/kafka-topics --delete --bootstrap-server 127.0.0.1:9092 --topic refds_events_topic



1) Create a list with all topics ...
$CONFLUENT_HOME/bin/kafka-topics --list --zookeeper localhost:2181 > liste.dat

2) Remove topice to keep from the list

3) delete all the topics
while read p; do  $CONFLUENT_HOME/bin/kafka-topics --delete --bootstrap-server 127.0.0.1:9092 --topic $p; echo "deleted $p"; done < liste.dat

4) $CONFLUENT_HOME/bin/kafka-topics --create --bootstrap-server 127.0.0.1:9092 --topic refds_events_topic_A --replication-factor 1 --partitions 1

