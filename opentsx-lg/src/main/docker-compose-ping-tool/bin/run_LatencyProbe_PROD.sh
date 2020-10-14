#!/bin/bash

echo
echo "------------------------------------------"
echo " Start the LATENCY analysis ... PRODUCER "
echo "------------------------------------------"

#java -javaagent:./bin/jmx_prometheus_javaagent-0.13.0.jar=8080:./bin/prometheus_config.yaml -cp target/opentsx-lg-3.0.1.jar org.opentsx.lg.EventFlowAnalysisProducer
mvn clean compile package exec:java -Dexec.mainClass="org.opentsx.lg.kping.EventFlowAnalysisProducer"
