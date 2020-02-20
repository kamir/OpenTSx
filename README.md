# OpenTSx

OpenTSx is a Java based software library, and a collection of tools for *cloud native time series analysis projects*.  

The project provides a collection of *data models*, *data generators*, and *algorithms* for advanced time series statistics.
The central starage and processing framework is build around Apache Kafka, KStreams and ksqlDB. 

![alt text](./doc/sketches/Generic\ TSA\ Use\ Case/Simplified\ Architecture\ Overview.png "title")

In the context of renaming the old project from *Hadoop.TS.NG* to *OpenTSx* we also shiftet from Apache Spark to Apache Kafka.

This allows us to utilize the advanced analysisfunctionf already on all "inflight data" and all the expensive data wrangling became onbslote. We build standardized data flows and processing pipelines with open source components which are available in the Kafka ecosystem. 

Even with a focus on Apache Kafka which enables efficient stream processing, also batch oriented applications can be developed with OpenTSx and Apache Spark.

We use Apache Cassandra for long term persistence of time series data. 

ElasticSearch is used as metadata store, together with Apache Jena or Neo4J, which both can expose the querieable knowledge graph.

# The Abstraction Layer for Time Series Analysis in Cloud Native Applications
The essential concepts for OpenTSx are: several types of _TimeSeries_, the _TSBucket_, and the _TSProcessor_. 

The submodules contain implementations of specific algorithms for univariate and 
bivariate time series analysis. Such algorithms are especially useful for applications in complex systems research.

The major aspect of OpenTSx is to enable rapid prototyping for new algorithms based on existing
preprocessed data and for data streams. The data can be integrated, collected, and stored in any cloud system or on local systems. 

# Why should I use or contribute to OpenTSx?
Deployment and development cycles are shortened, because all implemented algorithms will come with demo applications, e.g:
- extraction of time series data from logfiles or Wikipedia click count data 
- conversion between time series and inter-event-time time-series.
- generating of standard output for network analysis tools like Gephi, networkx, or Neo4J
- integration with ML libraries such as TensorFlow and Deeplearning4J.
- integration of GPU based computing into ksqlDB via UDFs

In this sense OpenTSx serves as a 'glueware' to combine many already existing software components and frameworks. 

An example for this is the integration of algorithms developed for information dynamics. 

Because the solution is Apache Kafka based, OpenTSx benefits from all of Kafka's nice features, like scalability, fault-tolerance, and global availability of data in a multi-datacenter dataplane. 


## History
In the first phase of the project, we built scalable time-series processing applications on top of 
Apache Hadoop where data has been stored in HBase (via OpenTSDB), or in HDFS.

The framework contains a set of generic classes to represent and transform 
complex data structures. It offers features for management of time series buckets. The concept named TimeSeriesBucket
has evolved into a CompoundDataSet which can be persisted in cloud services using multiple technologies behind
one single abstraction. 

Besides OpenTSDB, many other API based sources can provide time series for a time series bucket, e.g., 
Yahoo! Financial services for stock market, ElasticSearch and Apache Solr for collected log data, or even 
Cloudera Manager for operational data generated by the Cloudera data platform (CDP). 



## Related Work: Application of the OpenTSx toolbox:
- IJCS :
  *Hadoop.TS:* The initial paper.
  https://www.ijcaonline.org/archives/volume74/number17/12974-0233

- PLOS ONE :
  *The Detection of Emerging Trends Using Wikipedia Traffic Data and Context Networks.*
  https://journals.plos.org/plosone/article?id=10.1371/journal.pone.0141892
  
- (old) WIKI : 
  https://github.com/kamir/Hadoop.TS.NG/wiki

- DPG 2014 : 
  *Context Sensitive and Time Dependent Relevance of Wikipedia Articles*
  https://www.slideshare.net/mirkokaempf/dpg-2014-time-05-1

- Wikimedia Foundation : 
  *Comparing_the_usage_of_global_and_local_Wikipedias_with_focus_on_Swedish_Wikipedia*
  https://www.researchgate.net/publication/255704719_Comparing_the_usage_of_global_and_local_Wikipedias_with_focus_on_Swedish_Wikipedia
