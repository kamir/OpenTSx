TSCache
=======

TSCache is a temporary time series database. 
TSCache supports time series analysis on measured data and in simulation environments.

Temporary means here, that time series data is hold in the TSCache system as long as someone needs it for ad-hoc analysis. 
We use multiple storage levels to provide various access-patterns.

TSCache is not a long-term storage solution. HDFS and HBase are used to persist and manage time series buckets over the full life cycle of a product or a project whith undefined end. The two Hadoop based systems are the ideal long term persistence layer because processing capabilties and in-place processing are available this way.

We simply start with a tool which allows us to create time series buckets. A time series bucket can be a sequence file or evene special kind of a Parquet file (using AVRO serialization). The bucket gets filled up with collected data, with simulated time series or finally, with data loaded from operational DBs. 

The purpose of the TSB is to save time. In cases, where some time series data is needed more often, we have to find an efficient way of representing the data. But flexibility in terms of combining multiple of such buckets in a meaningful and consistent way has to be manintained as well.

Managing the time series metadata and transformation of time series data in multiple formats from multiple source systems into each other is the focus of this software library.

Features
========
This project provides a web-interface for time series and time series bucket manipulation for time series data stored in HBase.

Some time series analysis algorithms are already includes, others can be added as plugins.

Architecture
============
The web-application runs locally in a Jetty server.

The autoingest functionality is implemented as an Apache Flink application. 
The flink application can be started via Docker, assuming a Dockerized Flink cluster is available. 

Apache HBase runs in a Docker container.

Apache Flink runs in a Docker container.



