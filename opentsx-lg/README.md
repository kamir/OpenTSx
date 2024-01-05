### How to Start the OpenTSx UI?

1) Enter the folder:
   ``` 
   cd opentsx-lg
   ```

2) Build the packages and images
   ``` 
    mvn clean generate-sources compile package install -PSimpleTimeSeriesProducer,Docker
   ``` 

3) Define variables 
     ``` 
    export OPENTSX_TOPIC_MAP_FILE_NAME=./../config/topiclist.def
    export OPENTSX_PRIMARY_CLUSTER_CLIENT_CFG_FILE_NAME=./../config/cpl.props
    export OPENTSX_SHOW_GUI=true
    export OPENTSX_USE_KAFKA=false
     ```   

4.) Run the Application
     ```  
3947  mvn clean compile exec:java -Dexec.mainClass="org.opentsx.lg.TSDataSineWaveGenerator"
```  