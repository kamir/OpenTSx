#!/bin/bash

source env.sh

echo
echo "------------------------------------------"
echo " Start the LATENCY analysis ... CONSUMER"
echo "------------------------------------------"

mvn clean compile package exec:java -Dexec.mainClass="org.opentsx.lg.kping.EventFlowAnalysisConsumer"

