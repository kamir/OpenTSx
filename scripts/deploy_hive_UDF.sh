


git pull

mvn package

export $JAVA_HOME=/usr/java/jdk1.7.0_67-cloudera/

mvn exec:java -Dexec.mainClass="hive.UDFTester"
 
echo $JAVA_HOME

mvn exec:java -Dexec.mainClass="hive.UDFTester"

/usr/java/jdk1.7.0_67-cloudera/bin/java -cp target/cuda-tsa-0.2.0-SNAPSHOT-jar-with-dependencies.jar hive.UDFTester
  
sudo -u hdfs hdfs dfs -put target/cuda-tsa-0.2.0-SNAPSHOT-jar-with-dependencies.jar /user/admin/udf/udf-h-tsa-$1.jar

sudo -u hdfs hdfs dfs -chmod 777 /user/admin/udf/udf-h-tsa-$1.jar
   
sudo -u hdfs hdfs dfs -chown admin:admin /user/admin/udf/udf-h-tsa-$1.jar

