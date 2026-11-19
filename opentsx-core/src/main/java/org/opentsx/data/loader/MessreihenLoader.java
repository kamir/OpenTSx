/**
 * Loads time series from text files.
 * 
 */

package org.opentsx.data.loader;

import org.opentsx.data.loader.util.ColumnValueCalculator;
import org.opentsx.data.loader.util.LineFilter;
import org.opentsx.data.loader.util.TSValueCalculator;
import org.opentsx.data.series.TimeSeriesObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.Date;
import java.util.Hashtable;
import java.util.StringTokenizer;

public class MessreihenLoader {

    private static final Logger LOG = LoggerFactory.getLogger(MessreihenLoader.class);

    public static boolean debug = false;
    public static final String DEFAULT_DELIMITER = ",";
    public static final int DEFAULT_LIMIT = 1000000000;

    public static MessreihenLoader getLoader() {
        return new MessreihenLoader();
    };

    /**
     * Assumes that the file has no header line and
     * that all lines have the same number of columns.
     *
     * @param fn
     * @param spalteX
     * @param spalteY
     * @return
     */
    public TimeSeriesObject _loadLogBinnedMessreihe(File fn , int _spalteX , int spalteY, double factor, double maxY  ) {

        int logSkipped = 0;
        int logUsed = 0;
        
        //
        // number of bins
        //
        double z = 1.0;
        int c = 0;
        while( z < maxY ) {
            z = z * factor;
            LOG.debug("bin border index={} border={}", c, z);
            c++;
        }
        
        double[] widthOfBin = new double[c];
        double[] lowerBorder = new double[c];
        double[] conuterBin = new double[c];
        double[] conuterBin2 = new double[c];
        
        for( int i = 0; i < c; i++ ) { 
            widthOfBin[i] = 0.0;
            conuterBin[i] = 0.0;
            conuterBin2[i] = 0.0;
        }
        
        //
        // bin widths
        //
        z=1;
        c=0;
        double b = 1;
        while( z < maxY ) {
            z = z * factor;
            widthOfBin[c] = z - b;
            lowerBorder[c] = b;
            
            b = z;
            LOG.debug("bin width index={} width={}", c, widthOfBin[c]);
            c++;
        }
        
        TimeSeriesObject mr = new TimeSeriesObject();

        LOG.debug("loading log-binned series file={}", fn.getAbsolutePath());

        mr.setLabel( fn.getName() );

        int lineNumber = 0;
        try (BufferedReader br = new BufferedReader(new FileReader(fn))) {
            String line;
            while ( (line = br.readLine()) != null ) {
                lineNumber++;
                if ( line.startsWith("#") || line.length() == 0 ) {
                    continue;
                }
                line = decimalPoints(line, DEFAULT_DELIMITER);
                if ( debug ) LOG.debug("read line file={} line={} text={}", fn, lineNumber, line);

                // columns count from 1
                String[] data = columns(fn, lineNumber, line, DEFAULT_DELIMITER, Math.max(_spalteX, spalteY));
                double dx = parseNumber(fn, lineNumber, data[_spalteX-1]);
                double dy = parseNumber(fn, lineNumber, data[spalteY-1]);

                boolean notCorrectBin = true;
                int zi = 1;
                
                while( notCorrectBin ) {
                    
                    if ( dx > lowerBorder[zi] ) { 
                        zi++;        
                        if ( zi >= conuterBin.length ) {
                            logSkipped++;
                            notCorrectBin = false;
                        }
                    }
                    else { 
                        if ( zi >= conuterBin.length ) {
                            logSkipped++;
                        }
                        else { 
                            logUsed++;
                            conuterBin[zi-1] = conuterBin[zi-1] + dy; 
                            conuterBin2[zi-1] = conuterBin2[zi-1] + 1; 
                            
                            notCorrectBin = false;
                            LOG.debug("binned point x={} y={} bin={} border={}", dx, dy, zi, lowerBorder[zi]);
                        }
                    }
                }
            }
        }
        catch (IOException ex) {
            throw readFailure(fn, ex);
        }
        if ( logSkipped + logUsed == 0 ) {
            throw noDataLines(fn, Math.max(_spalteX, spalteY), DEFAULT_DELIMITER);
        }
        
        // summarize
        for( int j = 1; j < conuterBin.length ; j++ ) { 
            double x = lowerBorder[j] + widthOfBin[j] * 0.5;
            double y = conuterBin[j] / widthOfBin[j];  // DEGREE DISTRIBUTION
            double y2 = conuterBin[j] / conuterBin2[j]; // DENSITY OF THE TIME SERIES
            
            LOG.debug("bin value x={} density={} sum={} count={}", x, y2, conuterBin[j], conuterBin2[j]);
            
            mr.addValuePair(x, y2);
        }
        
        logSkipped(fn, logUsed, logSkipped);
        return mr;
    };

    

    
    /**
     * Assumes that the file has no header line and
     * that all lines have the same number of columns.
     *
     * @param fn
     * @param spalteX
     * @param spalteY
     * @return
     */
    public TimeSeriesObject _loadLogBinnedMessreihe_DIV_BY_BINWIDTH(File fn , int _spalteX , int spalteY, double factor, double maxY  ) {

        int logSkipped = 0;
        int logUsed = 0;
        
        //
        // number of bins
        //
        double z = 1.0;
        int c = 0;
        while( z < maxY ) {
            z = z * factor;
            LOG.debug("bin border index={} border={}", c, z);
            c++;
        }
        
        double[] widthOfBin = new double[c];
        double[] lowerBorder = new double[c];
        double[] conuterBin = new double[c];
        double[] conuterBin2 = new double[c];
        
        for( int i = 0; i < c; i++ ) { 
            widthOfBin[i] = 0.0;
            conuterBin[i] = 0.0;
            conuterBin2[i] = 0.0;
        }
        
        //
        // bin widths
        //
        z=1;
        c=0;
        double b = 1;
        while( z < maxY ) {
            z = z * factor;
            widthOfBin[c] = z - b;
            lowerBorder[c] = b;
            
            b = z;
            LOG.debug("bin width index={} width={}", c, widthOfBin[c]);
            c++;
        }
        
        TimeSeriesObject mr = new TimeSeriesObject();

        LOG.debug("loading log-binned series file={}", fn.getAbsolutePath());

        mr.setLabel( fn.getName() );

        int lineNumber = 0;
        try (BufferedReader br = new BufferedReader(new FileReader(fn))) {
            String line;
            while ( (line = br.readLine()) != null ) {
                lineNumber++;
                if ( line.startsWith("#") || line.length() == 0 ) {
                    continue;
                }
                if ( debug ) LOG.debug("read line file={} line={} text={}", fn, lineNumber, line);

                // columns count from 1
                String[] data = columns(fn, lineNumber, line, DEFAULT_DELIMITER, Math.max(_spalteX, spalteY));
                double dx = parseNumber(fn, lineNumber, data[_spalteX-1]);
                double dy = parseNumber(fn, lineNumber, data[spalteY-1]);

                boolean notCorrectBin = true;
                int zi = 1;
                
                while( notCorrectBin ) {
                    
                    if ( dx > lowerBorder[zi] ) { 
                        zi++;        
                        if ( zi >= conuterBin.length ) {
                            logSkipped++;
                            notCorrectBin = false;
                        }
                    }
                    else { 
                        if ( zi >= conuterBin.length ) {
                            logSkipped++;
                        }
                        else { 
                            logUsed++;
                            conuterBin[zi-1] = conuterBin[zi-1] + dy; 
                            conuterBin2[zi-1] = conuterBin2[zi-1] + 1; 
                            
                            notCorrectBin = false;
                        }
                    }
                }
            }
        }
        catch (IOException ex) {
            throw readFailure(fn, ex);
        }
        if ( logSkipped + logUsed == 0 ) {
            throw noDataLines(fn, Math.max(_spalteX, spalteY), DEFAULT_DELIMITER);
        }
        
        // summarize
        for( int j = 1; j < conuterBin.length ; j++ ) { 
            double x = lowerBorder[j] + widthOfBin[j] * 0.5;
            double y = conuterBin[j] / widthOfBin[j];  // DEGREE DISTRIBUTION
            
            LOG.debug("bin value x={} density={} sum={} count={}", x, y, conuterBin[j], conuterBin2[j]);
            
            mr.addValuePair(x, y);
        }
        
        logSkipped(fn, logUsed, logSkipped);
        return mr;
    };
    
    
     /**
     * Assumes that the file has no header line and
     * that all lines have the same number of columns.
     *
     * @param fn
     * @param spalteX
     * @param spalteY
     * @return
     */
    public TimeSeriesObject _loadLogBinnedMessreihe_DIV_BY_BINWIDTH(File fn , int spalteX , int spalteY, double factor, double maxY, String delim , LineFilter filter ) {

        int logSkipped = 0;
        int logUsed = 0;
        
        //
        // number of bins
        //
        double z = 1.0;
        int c = 0;
        while( z < maxY ) {
            z = z * factor;
            if ( debug ) LOG.debug("bin border index={} border={}", c, z);
            c++;
        }
        
        double[] widthOfBin = new double[c];
        double[] widthOfBinCounted = new double[c];
        
        double[] lowerBorder = new double[c];
        double[] conuterBin = new double[c];
        double[] conuterBin2 = new double[c];
        
        for( int i = 0; i < c; i++ ) { 
            widthOfBin[i] = 0.0;
            conuterBin[i] = 0.0;
            conuterBin2[i] = 0.0;
        }
        
        
        
        //
        // bin widths
        //
        z=1;
        c=0;
        double b = 1;
        while( z < maxY ) {
            z = z * factor;
            widthOfBin[c] = z - b;
            lowerBorder[c] = b;
            
            b = z;
            if ( debug ) LOG.debug("bin width index={} width={}", c, widthOfBin[c]);
            c++;
        }
        
        
        for( int i = 0; i < maxY; i++ ) {
            int index = getBin(i, lowerBorder, conuterBin );
            widthOfBinCounted[index] = widthOfBinCounted[index] + 1; 
        }
        LOG.debug("counted bin widths file={} widths={}", fn, Arrays.toString(widthOfBinCounted));
        
       
        
        TimeSeriesObject mr = new TimeSeriesObject();

        LOG.debug("loading log-binned series file={}", fn.getAbsolutePath());

        mr.setLabel( fn.getName() );

        int lineNumber = 0;
        try (BufferedReader br = new BufferedReader(new FileReader(fn))) {
            String line;
            while ( (line = br.readLine()) != null ) {
                lineNumber++;
                if ( line.startsWith("#") || line.length() == 0 ) {
                    continue;
                }
                if ( filter != null ) {
                    line = filter.doFilterLine(line);
                }
                if ( debug ) LOG.debug("read line file={} line={} text={}", fn, lineNumber, line);

                // columns count from 1
                String[] data = columns(fn, lineNumber, line, delim, Math.max(spalteX, spalteY));
                double dx = parseNumber(fn, lineNumber, data[spalteX-1]);
                double dy = parseNumber(fn, lineNumber, data[spalteY-1]);

                if ( debug ) LOG.debug("read point file={} line={} x={} y={}", fn, lineNumber, dx, dy);
                boolean notCorrectBin = true;
                int zi = 1;
                
                while( notCorrectBin ) {
                    
                    if ( dx > lowerBorder[zi] ) { 
                        zi++;        
                        if ( zi >= conuterBin.length ) {
                            logSkipped++;
                            notCorrectBin = false;
                        }
                    }
                    else { 
                        if ( zi >= conuterBin.length ) {
                            logSkipped++;
                        }
                        else { 
                            logUsed++;
                            conuterBin[zi-1] = conuterBin[zi-1] + dy; 
                            conuterBin2[zi-1] = conuterBin2[zi-1] + 1; 
                            
                            notCorrectBin = false;
                            if ( debug ) LOG.debug("binned point x={} y={} bin={} border={}", dx, dy, zi, lowerBorder[zi]);
                        }
                    }
                }
            }
        }
        catch (IOException ex) {
            throw readFailure(fn, ex);
        }
        if ( logSkipped + logUsed == 0 ) {
            throw noDataLines(fn, Math.max(spalteX, spalteY), delim);
        }
        
        // summarize
        for( int j = 1; j < conuterBin.length ; j++ ) { 
            double x = lowerBorder[j] + widthOfBin[j] * 0.5;
            
            double y = conuterBin[j] / widthOfBin[j];  // DEGREE DISTRIBUTION
            double y2 = conuterBin[j] / widthOfBinCounted[j]; // DENSITY OF THE TIME SERIES
            
            
            mr.addValuePair(x, y2);
        }
        
        logSkipped(fn, logUsed, logSkipped);
        return mr;
    };
    
        
    private static int getBin(double d, double[] lowerBorder, double[] conuterBin ) {
        
        boolean notCorrectBin = true;
        
        int z = 0;
        int b = 0;
        
        while (z < conuterBin.length) {

            if (d > lowerBorder[z]) {
               b = z;
            }    
            z++;      
        }
        
        return b;
    }



    /**
     * Loads x from column spalteX and y from column spalteY, both counted from 1. Lines that start with # and empty
     * lines are skipped. The columns are split at the characters of delim, or at white space when delim is null.
     * A comma is read as a decimal point unless delim contains a comma. At most limit pairs are read.
     */
    public TimeSeriesObject loadMessreihe_2(File fn , int spalteX , int spalteY, String delim, int limit ) {

        TimeSeriesObject mr = new TimeSeriesObject();
        mr.setLabel( fn.getName() );

        int columns = Math.max(spalteX, spalteY);
        int lineNumber = 0;
        int limitCounter = 0;
        try (BufferedReader br = new BufferedReader(new FileReader(fn))) {
            String line;
            while ( limitCounter < limit && (line = br.readLine()) != null ) {
                lineNumber++;
                if ( line.startsWith("#") || line.length() == 0 ) {
                    continue;
                }
                line = decimalPoints(line, delim);
                if ( debug ) LOG.debug("read line file={} line={} text={}", fn, lineNumber, line);

                String[] data = columns(fn, lineNumber, line, delim, columns);
                mr.addValuePair(parseNumber(fn, lineNumber, data[spalteX - 1]), parseNumber(fn, lineNumber, data[spalteY - 1]));
                limitCounter++;
            }
        }
        catch (IOException ex) {
            throw readFailure(fn, ex);
        }
        return mr;
    };

    public TimeSeriesObject loadMessreihe_2(File fn , int spalteX , int spalteY ) {
        return loadMessreihe_2(fn, spalteX, spalteY, DEFAULT_DELIMITER, DEFAULT_LIMIT);
    }

    public TimeSeriesObject loadMessreihe_2(File f, int i, int i0, int limit) {
        return loadMessreihe_2(f, i, i0, DEFAULT_DELIMITER, limit);
    }

    public TimeSeriesObject loadMessreihe_2(File f, int i, int i0, String theDelim) {
        return loadMessreihe_2(f, i, i0, theDelim, DEFAULT_LIMIT);
    }

    private static double parseNumber(File fn, int lineNumber, String token) {
        try {
            return Double.parseDouble(token);
        }
        catch (NumberFormatException ex) {
            throw new IllegalArgumentException(fn + ":" + lineNumber + ": \"" + token
                    + "\" is not a number; expected a decimal number such as 1.5", ex);
        }
    }

    private static String[] columns(File fn, int lineNumber, String line, String delim, int needed) {
        StringTokenizer st = delim != null ? new StringTokenizer(line, delim) : new StringTokenizer(line);
        String[] data = new String[st.countTokens()];
        for (int i = 0; i < data.length; i++) {
            data[i] = st.nextToken();
        }
        if ( data.length < needed ) {
            throw new IllegalArgumentException(fn + ":" + lineNumber + ": the line has " + data.length
                    + " columns; expected at least " + needed + " columns separated by " + separator(delim));
        }
        return data;
    }

    private static String separator(String delim) {
        return delim == null ? "white space" : "\"" + delim + "\"";
    }

    private static IllegalArgumentException noDataLines(File fn, int columns, String delim) {
        return new IllegalArgumentException(fn + ": the file has no data lines; expected at least one line with "
                + columns + " columns separated by " + separator(delim));
    }

    private static void logSkipped(File fn, int used, int skipped) {
        LOG.debug("loaded log-binned series file={} used={} skipped={} skippedPercent={}", fn, used, skipped,
                100.0 * skipped / (used + skipped));
    }

    private static String decimalPoints(String line, String delim) {
        return delim == null || delim.indexOf(',') < 0 ? line.replace(',', '.') : line;
    }

    private static UncheckedIOException readFailure(File fn, IOException ex) {
        return new UncheckedIOException(fn + ": cannot read the file (" + ex + ")", ex);
    }

    private static IllegalArgumentException mapperFailure(File fn, int lineNumber, String mapper, RuntimeException ex) {
        return new IllegalArgumentException(fn + ":" + lineNumber + ": " + mapper + " cannot read the line (" + ex
                + "); expected a line the mapper accepts", ex);
    }

    /**
    * Reads a file that holds only the y values, in column 1. The x values count the values from 0.
     */
    public TimeSeriesObject loadMessreihe_1(File fn ) {

        TimeSeriesObject mr = new TimeSeriesObject();
        mr.setLabel( fn.getName() );

        int lineNumber = 0;
        double cnt = 0;
        try (BufferedReader br = new BufferedReader(new FileReader(fn))) {
            String line;
            while ( (line = br.readLine()) != null ) {
                lineNumber++;
                if ( line.startsWith("#") || line.length() == 0 ) {
                    continue;
                }
                String[] data = columns(fn, lineNumber, decimalPoints(line, null), null, 1);
                mr.addValuePair(cnt++, parseNumber(fn, lineNumber, data[0]));
            }
        }
        catch (IOException ex) {
            throw readFailure(fn, ex);
        }
        return mr;
    };

    /**
     * Loads x from column 1 and y from column 2, split at white space.
     * 
     * @param fn
     * @return
     */
    public TimeSeriesObject loadMessreihe(File fn ) {

        TimeSeriesObject mr = new TimeSeriesObject();
        mr.setLabel( fn.getName() );

        int lineNumber = 0;
        try (BufferedReader br = new BufferedReader(new FileReader(fn))) {
            String line;
            while ( (line = br.readLine()) != null ) {
                lineNumber++;
                if ( line.startsWith( "#" ) || line.length() == 0 ) {
                    continue;
                }
                String[] data = columns(fn, lineNumber, line, null, 2);
                mr.addValuePair(parseNumber(fn, lineNumber, data[0]), parseNumber(fn, lineNumber, data[1]));
            }
        }
        catch (IOException ex) {
            throw readFailure(fn, ex);
        }
        return mr;
    };

    /**
     * Loads y from column spalteY and the label pair a_b from column 1. The x values count the values from 1.
     */
    public TimeSeriesObject loadMessreihe_zr_SC(File fn, int spalteY , String del ) {

        TimeSeriesObject mr = new TimeSeriesObject();
        
        LOG.debug("loading series file={} column={}", fn.getAbsolutePath(), spalteY);

        mr.setLabel( fn.getName() );

        int lineNumber = 0;
        int x = 0;
        try (BufferedReader br = new BufferedReader(new FileReader(fn))) {
            String line;
            while ( (line = br.readLine()) != null ) {
                lineNumber++;
                if ( line.startsWith("#") || line.length() == 0 ) {
                    LOG.debug("skipped comment or empty line file={} line={}", fn, lineNumber);
                    continue;
                }
                line = decimalPoints(line, del);
                if ( debug ) LOG.debug("read line file={} line={} text={}", fn, lineNumber, line);

                // columns count from 1
                String[] data = columns(fn, lineNumber, line, del, Math.max(1, spalteY));
                x++;
                mr.addValuePair(1.0 * x, parseNumber(fn, lineNumber, data[spalteY-1]));

                StringTokenizer st2 = new StringTokenizer(data[0], "_" );
                if ( st2.countTokens() < 2 ) {
                    throw new IllegalArgumentException(fn + ":" + lineNumber + ": \"" + data[0]
                            + "\" has no \"_\"; expected two labels joined by \"_\" in column 1");
                }
                String[] aray = new String[2];
                aray[0] = st2.nextToken();
                aray[1] = st2.nextToken();
                mr.xLabels.add( aray );
            }
        }
        catch (IOException ex) {
            throw readFailure(fn, ex);
        }
        return mr;
    }
    
    public TimeSeriesObject loadMessreihe_For_LinkStrength(File fn, int spalteLabel1, int spalteLabel2, int spalteValue , String del ) {

        TimeSeriesObject mr = new TimeSeriesObject();
        
        LOG.debug("loading series file={} column={}", fn.getAbsolutePath(), spalteValue);

        mr.setLabel( fn.getName() );

        int columns = Math.max(spalteValue, Math.max(spalteLabel1, spalteLabel2));
        int lineNumber = 0;
        try (BufferedReader br = new BufferedReader(new FileReader(fn))) {
            String line;
            while ( (line = br.readLine()) != null ) {
                lineNumber++;
                if ( line.startsWith("#") || line.length() == 0 ) {
                    LOG.debug("skipped comment or empty line file={} line={}", fn, lineNumber);
                    continue;
                }
                line = decimalPoints(line, del);

                // columns count from 1
                String[] data = columns(fn, lineNumber, line, del, columns);
                String[] aray = new String[2];
                aray[0] = data[spalteLabel1-1];
                aray[1] = data[spalteLabel2-1];
                mr.addValue( parseNumber(fn, lineNumber, data[spalteValue-1]) , aray );
            }
        }
        catch (IOException ex) {
            throw readFailure(fn, ex);
        }
        return mr;
    }

    public TimeSeriesObject loadMessreihe_For_LinkStrength(File fn, int spalteLabel1, int spalteLabel2, ColumnValueCalculator mapper, String del) {
        TimeSeriesObject mr = new TimeSeriesObject();
        
        LOG.debug("loading series file={} mapper={}", fn.getAbsolutePath(), mapper.getName());

        mr.setLabel( fn.getName() );

        int columns = Math.max(spalteLabel1, spalteLabel2);
        int lineNumber = 0;
        int x = 0;
        try (BufferedReader br = new BufferedReader(new FileReader(fn))) {
            String line;
            while ( (line = br.readLine()) != null ) {
                lineNumber++;
                if ( line.startsWith("#") || line.length() == 0 ) {
                    LOG.debug("skipped comment or empty line file={} line={}", fn, lineNumber);
                    continue;
                }
                line = decimalPoints(line, del);
                if ( x == 0 ) LOG.debug("first line file={} line={} text={}", fn, lineNumber, line);
                x++;

                // columns count from 1
                String[] data = columns(fn, lineNumber, line, del, columns);
                double dy;
                try {
                    dy = mapper.calcLinkStrength( data );
                }
                catch( RuntimeException ex ) {
                    throw mapperFailure(fn, lineNumber, mapper.getName(), ex);
                }

                String[] aray = new String[2];
                aray[0] = data[spalteLabel1-1];
                aray[1] = data[spalteLabel2-1];
                mr.addValue( dy , aray );
            }
        }
        catch (IOException ex) {
            throw readFailure(fn, ex);
        }
        return mr;
    }

    public TimeSeriesObject _loadMessreihe_For_FD(File fn, TSValueCalculator mapper, String del, boolean logreturn )  {
        
        TimeSeriesObject mr = new TimeSeriesObject();

        mr.dateHash = new Hashtable<Date,Double>();
        
        LOG.debug("loading series file={} mapper={}", fn.getAbsolutePath(), mapper.getName());

        mr.setLabel( fn.getName() );

        int lineNumber = 0;
        int x = 0;
        try (BufferedReader br = new BufferedReader(new FileReader(fn))) {
            String line;
            while ( (line = br.readLine()) != null ) {
                lineNumber++;
                if ( line.startsWith("#") || line.length() == 0 ) {
                    LOG.debug("skipped comment or empty line file={} line={}", fn, lineNumber);
                    continue;
                }
                line = decimalPoints(line, del);
                if ( x == 0 ) LOG.debug("first line file={} line={} text={}", fn, lineNumber, line);
                x++;

                String[] data = columns(fn, lineNumber, line, del, 0);
                double dy;
                Date date;
                try {
                    dy = mapper.getValue( data );
                    date = mapper.getDate( data );
                }
                catch( RuntimeException ex ) {
                    throw mapperFailure(fn, lineNumber, mapper.getName(), ex);
                }
                if ( date == null ) {
                    throw new IllegalArgumentException(fn + ":" + lineNumber + ": " + mapper.getName()
                            + " returned no date; expected a date for every line");
                }

                mr.addValue( dy );
                mr.hashValueByDate( date, dy );
            }
        }
        catch (IOException ex) {
            throw readFailure(fn, ex);
        }
        return mr;
    }

}
