package connectors.cmtsq;

import com.cloudera.api.ApiRootResource;
import com.cloudera.api.ClouderaManagerClientBuilder;
import com.cloudera.api.DataView;
import com.cloudera.api.model.*;
import com.cloudera.api.v2.ClustersResourceV2;
import com.cloudera.api.v6.RootResourceV6;
import com.cloudera.api.v6.TimeSeriesResourceV6;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;

/**
 * Created by kamir on 24.07.17.
 *
 * mvn exec:java -Dexec.mainClass="connectors.cmtsq.ClouderaManagerTSQClient"
 *
 */
public class ClouderaManagerTSQClient {

    public static String CM_HOST = "http://cdsw-demo-4.vpc.cloudera.com";
    public static int CM_PORT = 7180;
    public static String CM_USER = "admin";
    public static String CM_PWD = "admin";

    public static void main( String[] ARGS ) {


        CM_HOST = ARGS[0];
        CM_PORT = Integer.parseInt(ARGS[1]);
        CM_USER = ARGS[2];
        CM_PWD = ARGS[3];

        // https://www.cloudera.com/documentation/enterprise/5-9-x/topics/cm_dg_tsquery.html
        TSQ q1 = TSQ.getDefaultTSQ();

        System.out.println("  Access CM-API");
        System.out.println("  -------------");
        System.out.println("  CM-HOST : " + CM_HOST );
        System.out.println("  CM-PORT : " + CM_PORT );

        ApiRootResource root = new ClouderaManagerClientBuilder()
                .withHost( CM_HOST )
                .withPort( CM_PORT )
                .withUsernamePassword(CM_USER, CM_PWD)
                .build();


        RootResourceV6 v6 = root.getRootV6();

        // which clusters are available ???
        ClustersResourceV2 clustersResource = v6.getClustersResource();
        ApiClusterList clusters = clustersResource.readClusters(DataView.FULL);

        System.out.println("\n\nList of clusters:");
        System.out.println("-----------------");
        for (ApiCluster cluster: clusters) {
            System.out.println(cluster.getName());
        }

        TimeSeriesResourceV6 tsr = v6.getTimeSeriesResource();

        ApiDashboardList list = tsr.getDashboardsResource().getDashboards();
        List<ApiDashboard> dashboards = list.getDashboards();

        System.out.println("\n\nList of TS Dashboards:");
        System.out.println("----------------------");

        for( ApiDashboard dashboard : dashboards ) {

            System.out.println( dashboard.getName() );
            System.out.println( dashboard.getJson() );

            TSDashboard tsboard = TSDashboard.createTSDashboard_from_JSON( dashboard.getJson() );
            System.out.println( "\n" + tsboard.toString() );

        }


        /**
         *

        @param from Start of the period to query in ISO 8601 format (defaults to 5 minutes before the end of the period).
        @param to End of the period to query in ISO 8601 format (defaults to current time).

         *
         **/

        TimeZone tz = TimeZone.getTimeZone("UTC");
        DateFormat df = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssX");
        df.setTimeZone(tz);

        long nowStar = System.currentTimeMillis() - (1000*60*60*24);

        // NO MATTER HOW LONG THE WINDOW is, the system is doing a binning to approx 100 values per series.
        // NEED MORE DETAILS on this from docs.
        String nowAsISO = df.format(new Date( nowStar  ));
        String tenMinAgoAsISO = df.format( new Date( nowStar - (1000 * 60 * 10) ));

        System.out.println( tenMinAgoAsISO );
        System.out.println( nowAsISO );

        ApiTimeSeriesResponseList rl = v6.getTimeSeriesResource().queryTimeSeries( q1.tsquery, tenMinAgoAsISO , nowAsISO );
        System.out.println( "# of responses: " + rl.getResponses().size() );

        ApiTimeSeriesResponse r = rl.get( 0 );
        System.out.println( "tsquery: " + r.getTimeSeriesQuery().toString() );

        List<ApiTimeSeries> tslist = r.getTimeSeries();
        System.out.println( "# of series : " + tslist.size() );

        for( ApiTimeSeries ts : tslist ) {

            System.out.println( ts.getMetadata().getMetricName() + " - " + ts.getMetadata().getEntityName() );

            List<ApiTimeSeriesData> tsdata = ts.getData();
            for( ApiTimeSeriesData data : tsdata ) {

                System.out.println( "Data Point: " + data.getTimestamp() + " -> " + data.getValue() );

            }
            System.out.println( tsdata.size() );

        }

    }

}
