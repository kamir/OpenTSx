#!/usr/bin/env bash

#--------------------------------------------------------------
# deploy the Hadoop.TS.NG artifact into a local Maven repository
#

export VERSION=2.5.0

#-------------------------------------------------------------------------------
# Deploy external dependencies to local MVN repository
#-------------------------------------------------------------------------------
#

#
# https://github.com/jlizier/jidt/releases/tag/v1.3.1
#
mvn install:install-file -Dfile=./../scripts/infodynamics.jar -DgroupId=infodynamics -DartifactId=corelib -Dversion=1.3.1 -Dpackaging=jar -DskipTests

#-------------------------------------------------------------------------------
# Deploy binary release to local MVN repository
#-------------------------------------------------------------------------------

# mvn install:install-file -Dfile=./../target/hadoop-ts-ng-$VERSION.jar -DgroupId=com.cloudera -DartifactId=hadoop-ts-ng -Dversion=$VERSION -Dpackaging=jar -DskipTests


