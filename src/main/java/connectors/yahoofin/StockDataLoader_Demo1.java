package connectors.yahoofin;

import yahoofinance.Stock;
import yahoofinance.YahooFinance;
import yahoofinance.quotes.fx.FxQuote;
import yahoofinance.quotes.fx.FxSymbols;

import java.math.BigDecimal;

/**
 * https://github.com/sstrickx/yahoofinance-api
 */
public class StockDataLoader_Demo1 {

    public static void main( String[] ARGS ) throws Exception {

        Stock stock = YahooFinance.get("GOOG");

        // Load some properties about the stock
        BigDecimal price = stock.getQuote().getPrice();
        BigDecimal change = stock.getQuote().getChangeInPercent();
        BigDecimal peg = stock.getStats().getPeg();
        BigDecimal dividend = stock.getDividend().getAnnualYieldPercent();

        stock.print();



        FxQuote usdeur = YahooFinance.getFx(FxSymbols.USDEUR);
        FxQuote usdgbp = YahooFinance.getFx("USDGBP=X");

        System.out.println(usdeur);
        System.out.println(usdgbp);

    }

}
