SET 'auto.offset.reset'='earliest';

CREATE STREAM OpenTSx_Events_Stream WITH (KAFKA_TOPIC='OpenTSx_Events', VALUE_FORMAT='AVRO');

CREATE STREAM OpenTSx_Events_Stream_Outliers AS SELECT * FROM OpenTSx_Events_Stream WHERE value > 0.75;
