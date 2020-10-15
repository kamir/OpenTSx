#!/bin/bash

echo
echo "------------------------------------------"
echo " Start the LATENCY analysis ... CONSUMER"
echo "------------------------------------------"

echo "DISPLAY: " $DISPLAY

java -XshowSettings:properties -version

########################################################################################################################
#
# Chart creation is based on AWT : We need an XWindows system, e.g. XVFB or GhostAWT.
#
########################################################################################################################
# java -Dawt.toolkit=ghostawt.GhostToolkit \
#     -Djava.awt.graphicsenv=ghostawt.image.GhostGraphicsEnvironment \
#     -Djava.awt.headless=false \
#     -Dsun.font.fontmanager=ghostawt.sun.GFontManager \
#     -Dsun.boot.library.path=/opentsx-lg/bin/GhostAWT-0.0.3/dist/linux64/:/usr/lib/jvm/zulu-8-amd64/jre/lib/amd64 \
#     -cp /opentsx-lg/opentsx-lg-3.0.1.jar:/opentsx-lg/bin/GhostAWT-0.0.3/dist/ghostawt.jar org.opentsx.lg.kping.EventFlowAnalysisConsumer

########################################################################################################################
#
# Because we do not turn the GUI functions on we can simply use the headless mode.
#
java -Djava.awt.headless=true -cp /opentsx-lg/opentsx-lg-3.0.1.jar org.opentsx.lg.kping.EventFlowAnalysisConsumer


