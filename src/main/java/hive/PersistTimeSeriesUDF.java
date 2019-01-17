package hive;

//import org.apache.hadoop.hive.ql.exec.UDF;

import connectors.opentsdb.OpenTSDBConnector;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.hive.ql.exec.Description;
import org.apache.hadoop.hive.ql.exec.UDF;
import org.apache.hadoop.io.Text;
import org.apache.hadoopts.data.series.TimeSeriesObject;
import java.util.ArrayList;

/**
 *
 * How to register this UDF in HUE?
 *
 * http://gethue.com/hadoop-tutorial-hive-udf-in-1-minute/
 *
 * mvn exec:java -Dexec.mainClass="hive.UDFTester"
 *
 * /usr/java/jdk1.7.0_67-cloudera/bin/java -cp target/cuda-tsa-0.2.0-SNAPSHOT-jar-with-dependencies.jar hive.UDFTester
 *
 *
 * sudo -u hdfs hdfs dfs -rm /user/admin/udf/udf-h-tsa.jar
 * sudo -u hdfs hdfs dfs -put target/cuda-tsa-0.2.0-SNAPSHOT-jar-with-dependencies.jar /user/admin/udf/udf-h-tsa.jar
 * sudo -u hdfs hdfs dfs -chmod 777 /user/admin/udf/udf-h-tsa.jar
 * sudo -u hdfs hdfs dfs -chown admin:admin /user/admin/udf/udf-h-tsa.jar
 *
 */

@Description(name = "StoreTS", value = "_FUNC( Array(LONG), Array(Double), String dbhost, String metric, Array(String) tags) - stores the aggregated data points into OpenTSDB.", extended = "TBD.")

public class PersistTimeSeriesUDF extends UDF {

    // Define Logging
    static final Log LOG = LogFactory.getLog(PersistTimeSeriesUDF.class );

    private Text result = new Text();

    public Text evaluate(Text str, String stripChars) {
        if(str == null) {
            return null;
        }
        result.set(StringUtils.strip(str.toString(), stripChars));
        return result;
    }

//    public Text evaluate(String[] tsList, String[] valList, String dbhost, String metric, String[] tags ) {





    public Text evaluate(String tsList, String valList, String dbhost, String metric, String tags) {
        
        return new Text( tags );

    }

    public Text evaluate(ArrayList<String> tsList, ArrayList<String> valList, String dbhost, String metric, ArrayList<String> tags ) {

        try {

            OpenTSDBConnector connector = new OpenTSDBConnector( dbhost );

            TimeSeriesObject mr = new TimeSeriesObject();

            String seriesName = metric;
            int i = 0;
            char sep= ',';
            for (String t : tags) {
                if ( i == 0 ) sep = ' ';
                else sep = ',';
                seriesName = seriesName + sep + t;
                i++;
            }

            mr.setLabel( seriesName );

            int z = tsList.size();
            int j = 0;
            while ( j < z ) {

                mr.addValuePair( Long.parseLong( tsList.get(j) ), Double.parseDouble( valList.get(j) ) );

                j++;
            }

            System.out.println( mr );

            result = new Text( OpenTSDBConnector.streamEventsToOpenTSDB( mr, connector ) );

        }
        catch (Exception ex){

            ex.printStackTrace();

            LOG.info(ex.getMessage() );
            LOG.debug(ex.toString() );

        }
        return result;
    }

}






