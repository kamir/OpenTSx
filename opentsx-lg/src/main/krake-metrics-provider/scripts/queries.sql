create table krake_metrics 
( 
  rowkey STRING KEY, 
  metric VARCHAR, 
  dc VARCHAR, 
  m VARCHAR, 
  value BIGINT
) 
WITH (
     KAFKA_TOPIC='topic_2', 
     VALUE_FORMAT='DELIMITED', 
     KEY='metric'
);
CREATE TABLE kmt WITH (KAFKA_TOPIC='topic_kmt', VALUE_FORMAT='AVRO') as select * from krake_metrics;
create table kvt with (KAFKA_TOPIC='topic_kvt', VALUE_FORMAT='AVRO') as select metric, COLLECT_LIST(value)[0] AS value from km group by metric;
