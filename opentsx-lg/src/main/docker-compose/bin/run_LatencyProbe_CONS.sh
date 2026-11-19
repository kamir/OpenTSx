#!/bin/bash

echo
echo "------------------------------------------"
echo " Start the LATENCY analysis ... CONSUMER"
echo "------------------------------------------"

echo "DISPLAY: " $DISPLAY

java -XshowSettings:properties -version

java -Djava.awt.headless=true -cp /opentsx-lg/opentsx-lg-3.0.1.jar org.opentsx.lg.kping.EventFlowAnalysisConsumer


