#!/bin/bash
echo "*****************************"
echo " SETUP TOPICS for DEMO"
echo "*****************************"
java -cp /opentsx-lg/opentsx-lg-3.0.0.jar org.opentsx.lg.TopicsDOWN
java -cp /opentsx-lg/opentsx-lg-3.0.0.jar org.opentsx.lg.TopicsUP
java -cp /opentsx-lg/opentsx-lg-3.0.0.jar org.opentsx.lg.TopicsCHECK
echo "*****************************"
echo " RUN DEMO "
echo "*****************************"
java -cp /opentsx-lg/opentsx-lg-3.0.0.jar org.opentsx.lg.TSDataSineWaveGenerator

echo "*****************************"
echo " DEMO COMPLETED!"
echo "*****************************"
