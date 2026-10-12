###########################################
# This is some KSQL CODE for our DEMO !!! #
###########################################

CREATE STREAM OpenTSx_Event_Flow_State_stream (
  persistEvents VARCHAR,
  persistEpisodes VARCHAR,
  l BIGINT,
  t2 BIGINT,
  t1 BIGINT,
  t0 BIGINT,
  generator VARCHAR,
  z BIGINT,
  sendDuration DOUBLE,
  generateDuration DOUBLE
) WITH (KAFKA_TOPIC = 'OpenTSx_Event_Flow_State', VALUE_FORMAT='JSON');


CREATE STREAM OpenTSx_Episodes_A_stream WITH (KAFKA_TOPIC = 'OpenTSx_Episodes_A', VALUE_FORMAT='AVRO');

CREATE STREAM OpenTSx_Episodes_B_stream WITH (KAFKA_TOPIC = 'OpenTSx_Episodes_B', VALUE_FORMAT='AVRO');

CREATE STREAM OpenTSx_Events_stream WITH (KAFKA_TOPIC = 'OpenTSx_Events', VALUE_FORMAT='AVRO');

CREATE STREAM OpenTSx_Events_stream_materialized
AS SELECT * FROM OpenTSx_Events_stream EMIT CHANGES;

#####################################
#
# Here we have 5 Streams in KSQLDB:
#
#   Show the flows-chart in C3 ...
#   Describe the streams ...
#
show streams;
show topics;

#--------------------------------------------
# NOT ALL STREAMS HAVE A CORRESPONDING TOPIC
#--------------------------------------------

#--------------------------------------------
# Show some episodes data ...
#
SELECT LABEL, TSTART, TEND, ZOBSERVATIONS, INCREMENT, URI FROM OPENTSX_EPISODES_A_STREAM EMIT CHANGES;
# BLOCKS and prints results

SELECT LABEL, TSTART, TEND, ZOBSERVATIONS, INCREMENT, URI FROM OPENTSX_EPISODES_B_STREAM EMIT CHANGES;
# BLOCKS and prints results

#
#--------------------------------------------


#--------------------------------------------
# Materialize the MD_Streams ...
#
Create stream OPENTSX_EPISODES_A_MD_STREAM
AS SELECT LABEL, TSTART, TEND, ZOBSERVATIONS, INCREMENT, URI
FROM OPENTSX_EPISODES_A_STREAM EMIT CHANGES;

Create stream OPENTSX_EPISODES_B_MD_STREAM
AS SELECT LABEL, TSTART, TEND, ZOBSERVATIONS, INCREMENT, URI
FROM OPENTSX_EPISODES_B_STREAM EMIT CHANGES;

#
#--------------------------------------------



#--------------------------------------------
# Create a table ...
#

CREATE TABLE OpenTSx_Events_tab
AS SELECT URI, count(*) as z FROM OpenTSx_Events_stream_materialized
WINDOW TUMBLING (size 10 seconds)
GROUP BY URI
EMIT CHANGES;

select * from OPENTSX_EVENTS_TAB emit changes;

#
#--------------------------------------------






#--------------------------------------------
# How to drop a stream ???
#

CREATE stream OpenTSx_Episodes_A_stats AS SELECT * FROM OpenTSx_Episodes_A_stream emit changes;
#
# track the id of the querey, so that we can close it before we drop the stream
#
terminate CSAS_OPENTSX_EPISODES_A_STATS_39;
Drop stream OpenTSx_Episodes_A_stats;

#
#--------------------------------------------




##############################################
#
# Use custom UDF for Metadata processing ...
#
#   How long is a particular episode?
#
CREATE stream OpenTSx_Episodes_B_stats3 AS SELECT label, MDEXTRACT(tstart, tend) as dt_in_ms FROM OpenTSx_Episodes_B_stream emit changes;
SELECT * from OpenTSx_Episodes_B_stats3 emit changes;

#################################################################################
# track the id of the querey, so that we can close it before we drop the stream
#
terminate CSAS_OPENTSX_EPISODES_B_STATS_37;  <----- this name varies
Drop stream OpenTSx_Episodes_B_stats2;



#--------------------------------------------------------------------------------
# Using a Kafka Streams application, we can extracts special events as alerts.
#--------------------------------------------------------------------------------
#
# Processing in TERMINAL 3 is needed next, in order to continue with following queries.
#
#--------------------------------------------------------------------------------




##############################################
#
# Count alerts by sensor and time-window
#
##############################################

#
# Project a particular set of fields from the simple_statistics_results object, calculated by the custom
# Kafka Streams application.
#
CREATE STREAM episodes_md_stats_y ( mkey STRING, mw__Y DOUBLE, sum_Y DOUBLE, std_Y DOUBLE, var_Y DOUBLE )
WITH (kafka_topic='EPISODES_DATA_B_SIMPLE_STATS_meetup_09_2020', value_format='JSON');

################################
# creat a materialized stream
#
CREATE STREAM episodes_md_stats_y_MAT AS SELECT * FROM episodes_md_stats_y;

CREATE TABLE EPISODES_B_COUNTED AS SELECT mkey, count(*) as z FROM episodes_md_stats_y_MAT Group by mkey;

############################################################
# Count number of episodes for which MD has been calculated
#
SELECT * FROM EPISODES_B_COUNTED emit changes;





##############################################
#
# Count alerts by sensor and time-window
#
##############################################

CREATE STREAM alerts WITH (kafka_topic='EVENT_DATA_ALERTS_meetup_09_2020', value_format='AVRO');
select * from alerts emit changes;
CREATE TABLE ALERTS_TAB AS SELECT URI, count(*) as z FROM alerts WINDOW TUMBLING (SIZE 15 SECONDS) Group by URI;

#
# Finally, we can use the PULL queries to get the alert counter per sensor instantly.
#
select * from alerts_tab where URI='http://www.w3.org/TR/2004/metrics-owl-20200210/deviceID#0815a';
select * from alerts_tab where URI='http://www.w3.org/TR/2004/metrics-owl-20200210/deviceID#17u4';



