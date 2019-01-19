#!/usr/bin/env bash

#--------------------------------------------------------------
# deploy the Hadoop.TS.NG artifact into a local Maven repository
#

export VERSION=2.5.0

#-------------------------------------------------------------------------------
# Deploy to local MVN repo
#-------------------------------------------------------------------------------

mvn install:install-file -Dfile=./../scripts/infodynamics.jar -DgroupId=infodynamics -DartifactId=corelib -Dversion=1.3.1 -Dpackaging=jar -DskipTests

mvn install:install-file -Dfile=./../target/hadoop-ts-ng-$VERSION.jar -DgroupId=com.cloudera -DartifactId=hadoop-ts-ng -Dversion=$VERSION -Dpackaging=jar -DskipTests


