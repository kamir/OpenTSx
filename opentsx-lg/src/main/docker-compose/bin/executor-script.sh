#!/bin/bash

Xvfb :99 -screen 0 640x480x8 -nolisten tcp &

echo "*****************************"
echo "OPENTSX_PRIMARY_CLUSTER_CLIENT_CFG_FILE_NAME: $OPENTSX_PRIMARY_CLUSTER_CLIENT_CFG_FILE_NAME"
echo "OPENTSX_SHOW_GUI:                             $OPENTSX_SHOW_GUI"
echo "OPENTSX_TOPIC_MAP_FILE_NAME:                  $OPENTSX_TOPIC_MAP_FILE_NAME"
echo "*****************************"

echo "*****************************"
echo " SETUP TOPICS for DEMO"
echo "*****************************"
#java -cp /opentsx-lg/opentsx-lg-3.0.1.jar org.opentsx.util.topicmanager.TopicsDOWN
java -cp /opentsx-lg/opentsx-lg-3.0.1.jar org.opentsx.util.topicmanager.TopicsUP
java -cp /opentsx-lg/opentsx-lg-3.0.1.jar org.opentsx.util.topicmanager.TopicsCHECK

echo "*****************************"
echo " RUN DEMO "
echo "*****************************"

#java -cp /opentsx-lg/opentsx-lg-3.0.1.jar org.opentsx.lg.TSDataSineWaveGenerator

echo "*****************************"
echo " DEMO COMPLETED!"
echo "*****************************"
