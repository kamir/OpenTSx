curl -X "POST" "http://localhost:8088/ksql" \
     -H "Content-Type: application/vnd.ksql.v1+json; charset=utf-8" \
     -d $'{
  "ksql": "show functions;",
  "streamsProperties": {}
}' |  jq .



curl -X "POST" "http://localhost:8088/ksql" \
     -H "Content-Type: application/vnd.ksql.v1+json; charset=utf-8" \
     -d $'{
  "ksql": "DESCRIBE FUNCTION EPISODE_METADATA;",
  "streamsProperties": {}
}' |  jq .



# echo CREATE STREAM episodes_stream WITH (KAFKA_TOPIC='OpenTSx_Episodes', VALUE_FORMAT='AVRO');

# echo CREATE STREAM events_stream WITH (KAFKA_TOPIC='OpenTSx_Events', VALUE_FORMAT='AVRO');

curl -X "POST" "http://localhost:8088/ksql" \
     -H "Content-Type: application/vnd.ksql.v1+json; charset=utf-8" \
     -d $'{
  "ksql": "LIST STREAMS;",
  "streamsProperties": {}
}' |  jq .


