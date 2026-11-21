#!/bin/bash

echo
echo "------------------------------------------"
echo " Start the LATENCY analysis ... CONSUMER"
echo "------------------------------------------"

java -Djava.awt.headless=true -cp /opentsx-lg/opentsx-lg-3.0.1.jar org.opentsx.lg.kping.EventFlowAnalysisConsumer


