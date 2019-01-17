package connectors.yahoofin;

import connectors.opentsdb.OpenTSDBConnector;
import org.apache.hadoopts.chart.simple.MultiChart;
import org.apache.hadoopts.data.series.TimeSeriesObject;
import org.apache.hadoopts.hadoopts.core.TSBucket;
import yahoofinance.Stock;
import yahoofinance.histquotes.HistoricalQuote;
import yahoofinance.histquotes.Interval;

import java.io.IOException;
import java.util.Calendar;
import java.util.List;
import java.util.Vector;

/**
 * https://github.com/sstrickx/yahoofinance-api
 */
public class StockBucketRetriever {

    public static void main( String[] ARGS ) throws Exception {

        long t0 = System.currentTimeMillis();

        String[] DOW30 =  {"AXP", "AAPL", "BA", "CAT", "CSCO", "CVX", "DD", "XOM",
                "GE", "GS", "HD", "IBM", "INTC", "JNJ", "KO", "JPM",
                "MCD", "MMM", "MRK", "MSFT", "NKE", "PFE", "PG",
                "TRV", "UNH", "UTX", "VZ", "V", "WMT", "DIS"};

        OpenTSDBConnector connector = new OpenTSDBConnector();
        TSBucket bucket = TSBucket.createEmptyBucket();
        Vector<TimeSeriesObject> tsos = OpenTSDBConnector.loadBucketFromOpenTSDB( DOW30, connector, bucket );
        MultiChart.open( tsos, true, "DOW 30");

        long t1 = System.currentTimeMillis();

        System.out.println( "> time to retrieve time series :" + ( t1-t0 ) / 1000 + " s" );

    }


    private static class TSOMapper {

        public static TimeSeriesObject convertYahooStockToTSO(Stock stock, String label, Calendar from, Calendar to, Interval interval, String seriesName) {

            TimeSeriesObject tso = new TimeSeriesObject();
            tso.setLabel( label + " sn=" + seriesName +",provider=yahoo,interval="+interval+",currency="+stock.getCurrency()+",exchange="+stock.getStockExchange() );

            try {
                List<HistoricalQuote> data = stock.getHistory();
                for ( HistoricalQuote q : data ) {

                    long ts = q.getDate().getTimeInMillis();
                    double val = q.getClose().doubleValue();

                    if ( seriesName == "high" )
                        val = q.getHigh().doubleValue();

                    tso.addValuePair( ts , val );

                }
            }
            catch (IOException e) {
                e.printStackTrace();
            }

            return tso;

        }

    }
}
