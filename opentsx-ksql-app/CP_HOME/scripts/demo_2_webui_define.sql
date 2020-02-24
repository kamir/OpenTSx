SET 'auto.offset.reset'='earliest';

CREATE STREAM OpenTSx_Events_Stream WITH (KAFKA_TOPIC='OpenTSx_Events', VALUE_FORMAT='AVRO');

CREATE STREAM OpenTSx_Events_Stream_Outliers AS SELECT timestamp,value FROM OpenTSx_Events_Stream WHERE value > 0.75;

CREATE STREAM OpenTSx_Episodes_Stream WITH (KAFKA_TOPIC='OpenTSx_Episodes', VALUE_FORMAT='AVRO');

CREATE STREAM OpenTSx_Episodes_Stream_MD AS SELECT EPISODES_MD( TSTART, TEND ) AS MD , TSTART, TEND FROM OpenTSx_Episodes_Stream;

CREATE STREAM OpenTSx_Episodes_Stream_TRAFO AS SELECT EPISODES_PROCESSOR( TSTART , TEND ,  INCREMENT ,  OBSERVATIONARRAY  ) AS RESULT , TSTART, TEND FROM OpenTSx_Episodes_Stream;