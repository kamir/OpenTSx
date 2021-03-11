#!/bin/bash

echo
echo "------------------------------------------"
echo " Start the LATENCY analysis ... PRODUCER "
echo "------------------------------------------"

Xvfb :99 -screen 0 800x600x32 -nolisten tcp &

java -cp /opentsx-lg/opentsx-lg-3.0.1.jar org.opentsx.lg.kping.EventFlowAnalysisProducer
