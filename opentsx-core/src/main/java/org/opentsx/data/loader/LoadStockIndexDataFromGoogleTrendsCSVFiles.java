/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package org.opentsx.data.loader;

import org.opentsx.app.bucketanalyser.MacroTrackerFrame;
import org.opentsx.app.bucketanalyser.TSBucketSource;
import org.opentsx.app.bucketanalyser.TSBucketTransformation;
import org.opentsx.chart.simple.MultiChart;
import org.opentsx.data.series.TimeSeriesObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.StringTokenizer;
import java.util.Vector;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 *
 * @author kamir
 */
public class LoadStockIndexDataFromGoogleTrendsCSVFiles {

    static String folderName = "sample-data/google-trends";

    public static void main(String[] ARGS) {

        MacroTrackerFrame.init("Global Financial Indices - from Google-Trends (2004 ... 2016");

        MacroTrackerFrame.addSource(TSBucketSource.getSource("Collection"));

        File f = new File(folderName);
        File[] l = f.listFiles();

        Vector<TimeSeriesObject> rows = new Vector<TimeSeriesObject>();

        for (File fi : l) {

            if (fi.getName().endsWith(".csv")) {
                System.out.println(">>> " + fi.getAbsolutePath());
                rows.addAll(loadStockDataForOneFile(fi));
            }

        }

        String windowName = "GoogleTrendsSeries_" + folderName;

        MacroTrackerFrame.addTransformation(TSBucketTransformation.getTransformation("Collection", windowName, "LOAD"));

        MultiChart.open(rows, true, windowName);

    }

    private static Vector<TimeSeriesObject> loadStockDataForOneFile(File f) {

        Vector<TimeSeriesObject> r0 = GTDataLoader.getRows(f);

        return r0;

    }

    private static class GTDataLoader {

        private static Vector<TimeSeriesObject> getRows(File f) {

            /**
             * lade x aus Spalte 1 und y aus Spalte 2
             *
             * @param fn
             * @return
             */
            Vector<TimeSeriesObject> vmr = new Vector<TimeSeriesObject>();

            TimeSeriesObject mr1 = new TimeSeriesObject();
            TimeSeriesObject mr2 = new TimeSeriesObject();
            TimeSeriesObject mr3 = new TimeSeriesObject();
            TimeSeriesObject mr4 = new TimeSeriesObject();
            TimeSeriesObject mr5 = new TimeSeriesObject();

            TimeSeriesObject[] ms = new TimeSeriesObject[5];
            ms[0] = mr1;
            ms[1] = mr2;
            ms[2] = mr3;
            ms[3] = mr4;
            ms[4] = mr5;

            vmr.add(mr1);
            vmr.add(mr2);
            vmr.add(mr3);
            vmr.add(mr4);
            vmr.add(mr5);

//            if (!MessreihenLoader.checkFileAccess(f.getAbsolutePath())) {
//                vmr = null;
//                return vmr;
//            }
            BufferedReader br = null;

            try {
                br = new BufferedReader(new FileReader(f));

                int linecounter = 0;
                int valuecounter = 1;

                StringBuffer sb = new StringBuffer();

                while (br.ready()) {

                    String line = br.readLine();

                    if (linecounter == 4) {
                        // process header of series   
                        StringTokenizer st = new StringTokenizer(line, ",");
                        st.nextToken();

                        mr1.setLabel(st.nextToken());
                        mr2.setLabel(st.nextToken());
                        mr3.setLabel(st.nextToken());
                        mr4.setLabel(st.nextToken());
                        mr5.setLabel(st.nextToken());
                    }
                    if (linecounter > 4) {

                        if (line.length() < 1) {
                            br.close();

                        } else {
                            // process data ...
                            StringTokenizer st = new StringTokenizer(line, ",");
                            st.nextToken();

                            Double dx = 1.0 * valuecounter;
                            // System.out.println(line );

                            try {
                                Double dy1 = Double.parseDouble((String) st.nextElement());
                                Double dy2 = Double.parseDouble((String) st.nextElement());
                                Double dy3 = Double.parseDouble((String) st.nextElement());
                                Double dy4 = Double.parseDouble((String) st.nextElement());
                                Double dy5 = Double.parseDouble((String) st.nextElement());

                                mr1.addValuePair(dx, dy1);
                                mr2.addValuePair(dx, dy2);
                                mr3.addValuePair(dx, dy3);
                                mr4.addValuePair(dx, dy4);
                                mr5.addValuePair(dx, dy5);

                                valuecounter++;
                            } catch (Exception ex) {
                                // ex.printStackTrace();
                            }
                        }

                    }

                    linecounter++;
                }
                // br.close();
            } catch (Exception ex) {
                Logger.getLogger(MessreihenLoader.class.getName()).log(Level.SEVERE, null, ex);
            } finally {
                try {
                    br.close();
                } catch (IOException ex) {
                    Logger.getLogger(MessreihenLoader.class.getName()).log(Level.SEVERE, null, ex);
                }
            }
            return vmr;
        }

        public GTDataLoader() {

        }
    }

}
