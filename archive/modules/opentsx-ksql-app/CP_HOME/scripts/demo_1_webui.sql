SET 'auto.offset.reset'='earliest';
DROP STREAM OpenTSx_Events_Stream;
DROP STREAM OpenTSx_Events_Stream_Outliers;
CREATE STREAM OpenTSx_Events_Stream WITH (KAFKA_TOPIC='OpenTSx_Events', VALUE_FORMAT='AVRO');
CREATE STREAM OpenTSx_Events_Stream_Outliers AS SELECT * FROM OpenTSx_Events_Stream WHERE value > 0.75;
select timestamp, value from OpenTSx_Events_Stream_Outliers EMIT CHANGES;