CREATE KEYSPACE ks2 with replication = {'class':'SimpleStrategy','replication_factor':1};

CREATE TABLE ks2.user (
city text,
name text,
favoritefood text,
favoritenumber int,
PRIMARY KEY ((city), name)
);

INSERT INTO ks2.user (city,name,favoritefood,favoritenumber) VALUES ('nyc','user1','kfc',3);
INSERT INTO ks2.user (city,name,favoritefood,favoritenumber) VALUES ('nyc','user2','popeyes',7);