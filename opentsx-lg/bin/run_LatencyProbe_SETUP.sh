#!/bin/bash

source env.sh

echo
echo "------------------------------------------"
echo " Start the LATENCY analysis ... SETUP "
echo "------------------------------------------"

mvn clean compile package exec:java -Dexec.mainClass="org.opentsx.lg.kping.EventFlowAnalysisSetup"
