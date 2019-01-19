#!/bin/sh

#
# Run a MacroRecorder tool with some random time series to get a first idea of the
# TSA Workbench in action ...
#

export JAVA_HOME=/Volumes/Macintosh\ HD/Library/Java/JavaVirtualMachines/jdk1.8.0_192.jdk/Contents/Home/
echo $JAVA_HOME

cd ..
mvn exec:java -Dexec.mainClass="org.apache.hadoopts.app.bucketanalyser.MacroRecorder2"
