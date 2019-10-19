#!/usr/bin/env bash

export JAVA_HOME=/Volumes/Macintosh\ HD/Library/Java/JavaVirtualMachines/jdk1.8.0_192.jdk/Contents/Home/

java -version

cd ..

mvn clean generate-sources compile package install -DskipTests=true

########################################################################################################################
# The Hadoop.TS.NG library is used to provide some TS-Operations, to simplify the DAL.
#
mvn install:install-file -Dfile=/Users/kamir/GitHub_TMP/Hadoop.TS.NG/target/hadoop-ts-ng-2.5.0-jar-with-dependencies.jar -DgroupId=org.hadoop-ts-ng -DartifactId=stsx-core -Dversion=2.5.0 -Dpackaging=jar
mvn install:install-file -Dfile=$(pwd)/target/hadoop-ts-ng-2.5.0-jar-with-dependencies.jar -DgroupId=org.hadoop-ts-ng -DartifactId=stsx-core -Dversion=2.5.0 -Dpackaging=jar

#scp /Users/kamir/GitHub_TMP/Hadoop.TS.NG/target/hadoop-ts-ng-2.5.0-jar-with-dependencies.jar root@cc-poc-mk-1.gce.cloudera.com:/opt/cloudera/hadoop-ts-ng-2.5.0-jar-with-dependencies.jar
#mvn install:install-file -Dfile=/opt/cloudera/hadoop-ts-ng-2.5.0-jar-with-dependencies.jar -DgroupId=org.hadoop-ts-ng -DartifactId=stsx-core -Dversion=2.5.0 -Dpackaging=jar

#        <dependency>
#            <groupId>org.hadoop-ts-ng</groupId>
#            <artifactId>stsx-core</artifactId>
#            <version>2.5.0</version>
#        </dependency>

