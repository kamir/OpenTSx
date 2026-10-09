// CQL scripts for managing the Cassandra tables

CREATE KEYSPACE ks1 WITH replication = {'class': 'SimpleStrategy','replication_factor' : 1 };

USE ks1;

CREATE TABLE refds_small_bucket_table (id text PRIMARY KEY, tsdata text);

INSERT INTO ks1.refds_small_bucket_table(id,tsdata)
VALUES ('DUMMY_ID','DUMMY_DATA');

SELECT count(*) FROM refds_small_bucket_table;

TRUNCATE refds_small_bucket_table;
