#!/usr/bin/env bash

export JAVA_HOME=/Volumes/Macintosh\ HD/Library/Java/JavaVirtualMachines/jdk1.8.0_192.jdk/Contents/Home/

java -version

cd ..

mvn clean generate-sources compile package install -DskipTests=true
