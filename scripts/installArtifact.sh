#--------------------------------------------------------------
# deploy the Hadoop.TS.NG artifact into a local Maven repository
#

export VERSION=2.3.0

# :: Changelog ::
# ===============
#
# 2.3.0 :
# - switched to a maven based build.
# - added the RNGWrapper and extract old code base with non Apache License

# 1.2.6
# - fixed the poly solve dependency issue by removing the generic "fitN" function from DFA code.

# 1.2.5
# - started to manage the Hadoop.TS.NG JAR as a real artifact ;-)



#-------------------------------------------------------------------------------
# Deploy to local MVN repo and to cuda-tsa project folder
#-------------------------------------------------------------------------------
#cp /GITHUB/Hadoop.TS.NG/dist/Hadoop.TS.NG.jar /GITHUB/cuda-tsa/lib/hadoop-ts-core-$VERSION.jar
#mvn install:install-file -Dfile=dist/Hadoop.TS.NG.jar -DgroupId=com.cloudera -DartifactId=hadoop-ts-core -Dversion=$VERSION -Dpackaging=jar -DskipTests



mvn install:install-file -Dfile=target/hadoop-ts-ng-$VERSION.jar -DgroupId=com.cloudera -DartifactId=hadoop-ts-ng -Dversion=$VERSION -Dpackaging=jar -DskipTests


