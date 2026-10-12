> **Legacy (2.x model).** These load-generator and latency tools use the 2.x topics and Confluent serializers. For KafScale and v2 episodes, see [`opentsx-kafka-v2`](../opentsx-kafka-v2/README.md) and the [user manual](../docs/user-manual/README.md).

### How to Start the OpenTSx UI?

(1) Enter the folder:
``` 
cd opentsx-lg
```

(2) Build the packages and images
``` 
mvn clean generate-sources compile package install -PSimpleTimeSeriesProducer,Docker
``` 

(3) Define variables 
``` 
export OPENTSX_TOPIC_MAP_FILE_NAME=./../config/topiclist.def
export OPENTSX_PRIMARY_CLUSTER_CLIENT_CFG_FILE_NAME=./../config/opentsx-local.props
export OPENTSX_SHOW_GUI=true
export OPENTSX_USE_KAFKA=false
```   

(4) Run the Application

```
mvn clean compile exec:java -Dexec.mainClass="org.opentsx.lg.TSDataSineWaveGenerator"
```  
