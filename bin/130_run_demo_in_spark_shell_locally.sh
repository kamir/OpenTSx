#!/bin/sh

cd ../scripts
ls -ls
spark-shell --master local[2] --packages org.apache.kudu:kudu-spark2_2.11:1.4.0 --jars /Users/kamir/.m2/repository/com/cloudera/hadoop-ts-ng/2.5.0/hadoop-ts-ng-2.5.0-jar-with-dependencies.jar