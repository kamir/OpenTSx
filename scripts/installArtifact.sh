#--------------------------------------------------------------
# deploy sparl-solr artefakt into local maven repository
#

mvn install:install-file -Dfile=dist/Hadoop.TS.NG.jar -DgroupId=com.cloudera -DartifactId=hadoop-ts-core -Dversion=1.2.1 -Dpackaging=jar -DskipTests
