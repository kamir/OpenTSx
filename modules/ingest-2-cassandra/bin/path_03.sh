#
# https://howtoprogram.xyz/2016/07/10/apache-kafka-connect-example/
#


/Users/mkampf/bin/confluent-5.3.0/bin/connect-standalone \
./../config/path3/connect-standalone.properties \
./../config/path3/connect-cassandra-source.properties \
./../config/path3/connect-file-sink.properties