/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package org.opentsx.data.exporter;

import org.opentsx.data.series.TimeSeriesObject;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.DecimalFormat;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 *
 * @author kamir
 */
public class MeasurementTable {

    String headerNULL = "#\n# unknown parameter set \n#\n";
    
    String label = null;
    public boolean singleX = true;

    public MeasurementTable() {}

    public MeasurementTable(String l) {
        label = l;
    }
        
    public MeasurementTable(String l, String header) {
        label = l;
        headerNULL = header;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }
    Vector<TimeSeriesObject> messReihen = null;

    public Vector<TimeSeriesObject> getMessReihen() {
        return messReihen;
    }

    public void setMessReihen(Vector<TimeSeriesObject> _messReihen) {
        this.messReihen = _messReihen;
    }

    public void setMessReihen(TimeSeriesObject[] rows) {
        Vector<TimeSeriesObject> mrv = new Vector<TimeSeriesObject>();
        for (TimeSeriesObject r : rows) {
            mrv.add(r);
        }
        this.setMessReihen(mrv);
    }

    public void writeToFile() {
        File f = new File(this.getLabel());
        writeToFile(f);
    }

    public void createParrentFile(File f) {
        System.out.println("> MT : file ===> " + f);
        File p = f.getParentFile();
        System.out.println("> MT : parent => " + p);

        if (p == null || !p.exists()) {
            p.mkdirs();
            System.out.println( f.getAbsolutePath() + " was created. " );
        } else {
            System.out.println("> MT : Nothing to create. ");
        }
    }

    ;

    public void writeToFile(File f) {
        
        System.out.println(">>> MWT => writeToFile() ... " + f.getAbsolutePath());

        createParrentFile(f);

        FileWriter fw = null;
        
        try {
            
            fw = new FileWriter(f);
            
            String content = this.toString();
            
            fw.write( content );
            fw.close();
            
        } catch (IOException ex) {
            Logger.getLogger(TimeSeriesObject.class.getName()).log(Level.SEVERE, null, ex);
        } finally {
            try {
                fw.close();
            } catch (IOException ex) {
                Logger.getLogger(TimeSeriesObject.class.getName()).log(Level.SEVERE, null, ex);
            }
        }
    }
    public double fill_UP_VALUE = -5.0;

    public static String getCommentLine(String comment) {
        return "#\n# " + comment + "\n#\n";
    }

    /**
     * Die Daten zur Ausgabe auf der Konsole ausgeben.
     *
     * @return
     */
    public String toSignificanzString() {

        Hashtable keyedHash = new Hashtable<String, TimeSeriesObject>();
        for (TimeSeriesObject m : this.messReihen) {
            if (m == null) {
                m = new TimeSeriesObject("empty");
            }
            keyedHash.put(new Integer(m.getLabel()), m);
        }

        Enumeration<TimeSeriesObject> en1 = this.messReihen.elements();
        int maxLength = 0;
        while (en1.hasMoreElements()) {
            TimeSeriesObject mr = en1.nextElement();
            int l = mr.yValues.size();
            if (l > maxLength) {
                maxLength = l;
            }
        }

        StringBuffer sb = new StringBuffer();

        int size = this.messReihen.size();
        Enumeration<TimeSeriesObject> en = this.messReihen.elements();

        String headline = "#X" + "\t";

        TimeSeriesObject mr = en.nextElement();
        // sb.append("\n# " + mr.getLabel() + " [" + mr.xValues.size() +","+ mr.yValues.size() +"] Werte" );
        //headline = headline.concat( mr.getLabel_X() +"\t" + mr.getLabel_Y() +"\t" );


        Set<Integer> s = keyedHash.keySet();
        List l = new ArrayList();
        for (Integer lab : s) {
            l.add(lab);
        };

        System.out.println(l);
        Collections.sort(l);
        System.out.println(l);

        Iterator it = l.iterator();
        while (it.hasNext()) {
            
            Integer key = (Integer) it.next();
            mr = (TimeSeriesObject) keyedHash.get(key);
            System.err.println("k=" + key);
            sb.append("\n# " + mr.getLabel() + " [" + mr.xValues.size() + "," + mr.yValues.size() + "] Werte");
            
            if (singleX) {
                headline = headline.concat(mr.getLabel_Y() + "\t");
            } 
            else {
                headline = headline.concat(mr.getLabel_X() + "\t" + mr.getLabel_Y() + "\t");
            }

            // size = mr.yValues.size();
        }

        sb.append("\n#\n#\n");
        sb.append(headline + "***\n");

        // DecimalFormat df = new DecimalFormat("0.00000E00");
        DecimalFormat df = new DecimalFormat("0,00");

        TimeSeriesObject longest = getLogestRow(this.messReihen);
        int j = 0;
        for (int i = 0; i < maxLength; i++) {
            it = l.iterator();
            while (it.hasNext()) {
                mr = (TimeSeriesObject) keyedHash.get((Integer) it.next());
                double x = 0.0;
                double y = 0.0;

                try {
                    x = (Double) longest.getXValues().elementAt(i);
                } catch (Exception ex) {
                    // x = 0.0;
                }

                try {
                    y = (Double) mr.getYValues().elementAt(i);
                } catch (Exception ex) {
                    y = fill_UP_VALUE;
                }

                String linePart;
                if (singleX && j > 0) {
                    linePart = df.format(y) + getSymbol(y) + "\t";
                } else {
                    linePart = x + "\t" + df.format(y) + getSymbol(y) + "\t";
                }

                sb.append(linePart);
                j++;
            }
            sb.append("\n");
        }
        sb.append("# * p<0.03; + p<0.01; # p<0.001\n#");
        return sb.toString();
    }

    ;
    
    /**
     * Die Daten zur Ausgabe auf der Konsole ausgeben.
     *
     * @return
     */
    public String toString() {

        Hashtable keyedHash = new Hashtable<Integer, TimeSeriesObject>();
        int zi = 1;
        for (TimeSeriesObject m : this.messReihen) {
            if (m == null) {
                m = new TimeSeriesObject("empty");
            }
            Integer key = calcKey(m, zi);
            zi++;
            keyedHash.put(key, m);
        }

        int maxLength = 0;

        // max length ermitteln zum auffüllen ...
        Enumeration<TimeSeriesObject> en1 = this.messReihen.elements();
        while (en1.hasMoreElements()) {
            TimeSeriesObject mr = en1.nextElement();
            int l = mr.yValues.size();
            if (l > maxLength) {
                maxLength = l;
            }
        }

        StringBuffer sb = new StringBuffer();
        sb.append(this.header);

        sb.append("#\n# M e s s w e r t t a b e l l e  der Reihen:\n#");

        int size = this.messReihen.size();
        Enumeration<TimeSeriesObject> en = this.messReihen.elements();

        if ( en == null ) return "EMPTY Row";
        
        String headline = "";

        TimeSeriesObject mr = en.nextElement();
        //sb.append("\n# " + mr.getLabel() + " [" + mr.xValues.size() +","+ mr.yValues.size() +"] Werte" );
        //headline = headline.concat( mr.getLabel_X() +"\t" + mr.getLabel_Y() +"\t" );

        Set<Integer> s = keyedHash.keySet();
        List l = new ArrayList();
        for (Integer lab : s) {
            l.add(lab);
        };

        System.out.println(l);
        Collections.sort(l);
        System.out.println(l);

        Iterator it = l.iterator();
        int col = 0;
        while (it.hasNext()) {
            
            if (singleX && col == 0 ) {
                headline = headline.concat(mr.getLabel_X() + "\t");
            }
            
            mr = (TimeSeriesObject) keyedHash.get((Integer) it.next());
            sb.append("\n# " + mr.getLabel() + " [" + mr.xValues.size() + "," + mr.yValues.size() + "] Werte");

            if (singleX ) {
                headline = headline.concat(mr.getLabel_Y() + "\t");
            } else {
                headline = headline.concat(mr.getLabel_X() + "\t" + mr.getLabel_Y() + "\t");
            }
            col++;
            // size = mr.yValues.size();
        };

        sb.append("\n#\n#\n#");
        sb.append(headline + "\n");

        TimeSeriesObject longest = getLogestRow(this.messReihen);

        DecimalFormat dfX = new DecimalFormat("0.00000");
        DecimalFormat dfY = new DecimalFormat("0.00000");

        int j = 0;
        for (int i = 0; i < maxLength; i++) {
            it = l.iterator();
            while (it.hasNext()) {
                mr = (TimeSeriesObject) keyedHash.get((Integer) it.next());
                double x = 0.0;
                double y = 0.0;

                try {
                    x = (Double) longest.getXValues().elementAt(i);
                } catch (Exception ex) {
                    // x = 0.0;
                };

                try {
                    y = (Double) mr.getYValues().elementAt(i);
                } catch (Exception ex) {
                    y = fill_UP_VALUE;
                };

                String linePart;
                if (singleX && j > 0) {
                    linePart = dfY.format(y) + "\t";
                } else {
                    linePart = dfX.format(x) + "\t" + dfY.format(y) + "\t";
                }

                sb.append(linePart);
                j++;
            }
            j=0;
            sb.append("\n");
        };
        return sb.toString();
    }

    String header = "# MesswertTabelle\n";
    public void setHeader(String parameterSet) {
        this.header = parameterSet;
    }
    
    public void appendToHeader(String h) {
        this.header = header +"\n#\t" + h; 
    }

    private TimeSeriesObject getLogestRow(Vector<TimeSeriesObject> messReihen) {
        TimeSeriesObject l = null;
        int max = 0;
        int c = 0;
        int index = 0;
        for (TimeSeriesObject m : messReihen) {
            int s = m.getSize()[0];
            if (s > max) {
                max = s;
                index = c;
            }
            c++;
        }
        l = messReihen.elementAt(index);
        return l;
    }

    public void addMessreihe(TimeSeriesObject mr) {
        if (messReihen == null) {
            messReihen = new Vector<TimeSeriesObject>();
        }
        messReihen.add(mr);
    }

    private String getSymbol(double y) {
        String s = "\t~\t";
        if (y < 0.03) {
            s = "\t*\t";
        }
        if (y < 0.01) {
            s = "\t+\t";
        }
        if (y < 0.001) {
            s = "\t#\t";
        }
        return s;
    }

//    public String[] getHeaders() {
//        throw new UnsupportedOperationException("Not yet implemented");
//    }

    public double[][] getDataArray() {
        return null;
    }

    public int getNrLines() {
        return 8;
    }

    public int getNrCols() {
        return 7;
    }

    private Integer calcKey(TimeSeriesObject m, int lastKey) {
        Integer key = lastKey;
//        try {
//            Integer i = new Integer( m.getLabel() );
//            key = i;
//        } 
//        catch(Exception ex) { 
//        
//        }    
        return key;
    }

 
}
