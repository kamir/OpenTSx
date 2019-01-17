package connectors.yahoofin;

import org.apache.hadoopts.chart.simple.MultiChart;
import org.apache.hadoopts.data.series.TimeSeriesObject;
import yahoofinance.Stock;
import yahoofinance.YahooFinance;
import yahoofinance.histquotes.HistoricalQuote;
import yahoofinance.histquotes.Interval;

import java.io.IOException;
import java.util.Calendar;
import java.util.List;
import java.util.Vector;

/**
 * https://github.com/sstrickx/yahoofinance-api
 */
public class StockDataLoader_Demo2 {

    public static void main( String[] ARGS ) throws Exception {

        Calendar from = Calendar.getInstance();
        Calendar to = Calendar.getInstance();
        from.add(Calendar.YEAR, -5); // from 5 years ago

        Stock google = YahooFinance.get("GOOG", from, to, Interval.WEEKLY);
        Stock tesla = YahooFinance.get("TSLA", from, to, Interval.WEEKLY);
        Stock apple = YahooFinance.get("AAPL", from, to, Interval.WEEKLY);

        TimeSeriesObject tsoGoogleClose = TSOMapper.convertYahooStockToTSO( google, "GOOG", from, to, Interval.DAILY, "close" );
        TimeSeriesObject tsoGoogleHigh = TSOMapper.convertYahooStockToTSO( google, "GOOG", from, to, Interval.DAILY, "high" );


        TimeSeriesObject tsoTeslaClose = TSOMapper.convertYahooStockToTSO( tesla, "TSLA", from, to, Interval.DAILY, "close" );
        TimeSeriesObject tsoTeslaHigh = TSOMapper.convertYahooStockToTSO( tesla, "TSLA", from, to, Interval.DAILY, "high" );


        TimeSeriesObject tsoAppleClose = TSOMapper.convertYahooStockToTSO( apple, "AAPL", from, to, Interval.DAILY, "close" );
        TimeSeriesObject tsoAppleHigh = TSOMapper.convertYahooStockToTSO( apple, "AAPL", from, to, Interval.DAILY, "high" );


        Vector<TimeSeriesObject> tsos = new Vector<TimeSeriesObject>();

        tsos.add(tsoGoogleClose);
        tsos.add(tsoGoogleHigh);

        tsos.add(tsoTeslaClose);
        tsos.add(tsoTeslaHigh);

        tsos.add(tsoAppleClose);
        tsos.add(tsoAppleHigh);

        MultiChart.open( tsos, true );


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
