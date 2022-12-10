
3932  rm -rf /Users/mkaempf/.m2/repository/org/opentsx
3933  cd /Users/mkaempf/GITHUB.private/OpenTSx

3935  cd opentsx-lg
3936  mvn clean generate-sources compile package install -PSimpleTimeSeriesProducer,Docker
3937  export OPENTSX_TOPIC_MAP_FILE_NAME=./../config/topiclist.def
3938  export OPENTSX_PRIMARY_CLUSTER_CLIENT_CFG_FILE_NAME=./../config/cpl.props


3942  export OPENTSX_SHOW_GUI=true
3943  mvn clean compile exec:java -Dexec.mainClass="org.opentsx.lg.TSDataSineWaveGenerator"
3944  export OPENTSX_USE_KAFKA=false
3945  mvn clean compile exec:java -Dexec.mainClass="org.opentsx.lg.TSDataSineWaveGenerator"
3946  mvn clean compile
3947  mvn clean compile exec:java -Dexec.mainClass="org.opentsx.lg.TSDataSineWaveGenerator"
