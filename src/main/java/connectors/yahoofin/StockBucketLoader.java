package connectors.yahoofin;

import connectors.opentsdb.OpenTSDBConnector;
import org.apache.hadoopts.chart.simple.MultiChart;
import org.apache.hadoopts.data.series.TimeSeriesObject;
import yahoofinance.Stock;
import yahoofinance.YahooFinance;
import yahoofinance.histquotes.HistoricalQuote;
import yahoofinance.histquotes.Interval;

import java.io.IOException;
import java.util.Calendar;
import java.util.List;
import java.util.Map;
import java.util.Vector;

/**
 * https://github.com/sstrickx/yahoofinance-api
 */
public class StockBucketLoader {

    public static void main( String[] ARGS ) throws Exception {

        long t0 = System.currentTimeMillis();

        Calendar from = Calendar.getInstance();
        Calendar to = Calendar.getInstance();
        from.add(Calendar.YEAR, -5); // from 5 years ago

        String[] DOW30 =  {"AXP", "AAPL", "BA", "CAT", "CSCO", "CVX", "DD", "XOM",
                "GE", "GS", "HD", "IBM", "INTC", "JNJ", "KO", "JPM",
                "MCD", "MMM", "MRK", "MSFT", "NKE", "PFE", "PG",
                "TRV", "UNH", "UTX", "VZ", "V", "WMT", "DIS"};

        Map<String, Stock> stocks2 = YahooFinance.get( DOW30, from, to, Interval.DAILY ); // single request

        OpenTSDBConnector connector = new OpenTSDBConnector();

        Vector<TimeSeriesObject> tsos2 = new Vector<TimeSeriesObject>();
        for( String s : stocks2.keySet() ) {

            Stock st = stocks2.get( s );
            TimeSeriesObject tso = TSOMapper.convertYahooStockToTSO( st, s, from, to, Interval.DAILY, "close" );

            tsos2.add( tso );

//            OpenTSDBConnector.streamEventsToOpenTSDB( tso, connector );

        }

        long t1 = System.currentTimeMillis();

        System.out.println( "> time to load time series :" + ( t1-t0 ) / 1000 + " s" );

        MultiChart.open( tsos2, true );


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
