#!/usr/bin/env bash

#-------------------------------------------------------------------------------
# Deploy external dependencies to local MVN repository
#-------------------------------------------------------------------------------
#

#
# https://github.com/jlizier/jidt/releases/tag/v1.3.1
#
mvn install:install-file -Dfile=./../lib/infodynamics.jar -DgroupId=infodynamics -DartifactId=corelib -Dversion=1.3.1 -Dpackaging=jar -DskipTests

