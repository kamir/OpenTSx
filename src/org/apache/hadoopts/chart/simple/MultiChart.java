/**
 * Eine Klasse zur ANZEIGE von mehreren Messreihen, die in einem Vector
 * übergeben werden. Dabei gilt ein einheitliches Koordinatensystem für alle
 * Messreihen.
 *
 *
 */
package org.apache.hadoopts.chart.simple;

import org.apache.hadoopts.app.bucketanalyser.TSOperationControlerPanel;
import org.apache.hadoopts.app.bucketanalyser.MacroTrackerFrame;
import org.apache.hadoopts.data.series.TimeSeriesObject;
import org.apache.hadoopts.data.export.MeasurementTable;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Container;
import java.awt.Font;
import java.io.File;
import java.io.IOException;
import java.text.DecimalFormat;
import java.util.Enumeration;
import java.util.Locale;
import java.util.Vector;
import javax.swing.JFrame;
import javax.swing.JTextArea;

import org.apache.hadoopts.app.bucketanalyser.ICorrelator;
import org.jfree.chart.ChartFactory;
import org.jfree.chart.ChartPanel;
import org.jfree.chart.ChartRenderingInfo;
import org.jfree.chart.ChartUtilities;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.axis.NumberAxis;
import org.jfree.chart.axis.TickUnits;
import org.jfree.chart.entity.StandardEntityCollection;
import org.jfree.chart.plot.PlotOrientation;
import org.jfree.chart.plot.XYPlot;
import org.jfree.chart.renderer.xy.XYLineAndShapeRenderer;
import org.jfree.data.xy.XYSeries;
import org.jfree.data.xy.XYSeriesCollection;
import org.jfree.ui.RefineryUtilities;
//import simulation.datarecorder.GeneralResultRecorder;
//import util.LogFile;

public class MultiChart extends javax.swing.JDialog {
    
    /***
     * 
     * We expose some attributes for customizing the plot ...
     *
     **/
    
//    public static Color bgCOLOR = Color.BLACK;
//    public static Color bgCOLOR = Color.LIGHT_GRAY;
    public static Color bgCOLOR = Color.WHITE;
    
    public static boolean autoscale = true;

    public static boolean setDefaultRange = false;
    
    // AXIS FORMAT
    public static DecimalFormat df1 = new DecimalFormat("0");
    public static DecimalFormat df2 = new DecimalFormat("0");
    
    public static void setSmallFont() {
    // LABEL
    fontL = new Font("Dialog", Font.PLAIN, 12);
    
    // Tick-Label
    fontTL = new Font("Dialog", Font.PLAIN, 9);
        // AXIS FORMAT
  df1 = new DecimalFormat("0.0");
 df2 = new DecimalFormat("0.0");
        
    }
    
    // LABEL
    public static Font fontL = new Font("Dialog", Font.PLAIN, 28);
    
    // Tick-Label
    public static Font fontTL = new Font("Dialog", Font.PLAIN, 18);


    
    /**
     * 
     * Here we encapsule functionality 
     *
     */ 
    public ChartPanel cp = null;
    static TSOperationControlerPanel fcp = null;


    public static void open2(Vector<TimeSeriesObject> rows, boolean b) {
        open(rows, b);
    }

    public static void _initColors(Color[] colors) {
        dcSet = colors;
    }

    public static void _setTypes(int[] _dt) {
        useMyStroke = true;
        dt = _dt;
    }

    public static double xRangDEFAULT_MIN = 0.0;
    public static double xRangDEFAULT_MAX = 170.0;
    public static double yRangDEFAULT_MAX = 6000.0;
    public static double yRangDEFAULT_MIN = 0.0;

    public String chartTitle = "untitled";
    public String xLabel = "x";
    public String yLabel = "y";
    
    public boolean useLegende = false;

    public boolean doStoreChart = true;
    public String filename = null;

    public JFreeChart chart = null;
    public XYSeriesCollection dataset = new XYSeriesCollection();

    public MultiChart() {    };
    
    public MultiChart(String label) {
        super(new JFrame(label), false);
        initComponents();
        this.setSize(800, 600);
        initChart();
    }

    /**
     * Creates new form MultiChart
     */
    public MultiChart(java.awt.Frame parent, boolean modal) {
        super(parent, modal);
        initComponents();
        this.setSize(800, 600);
        initChart();
    }

    public Object getCP() {
        return cp;
    }

  
    public static void persistPixNode() throws IOException {
        
        fcp.save();
//        fcp.persistTSB();
    
    }
    
    /**
     * This method is called from within the constructor to initialize the form.
     * WARNING: Do NOT modify this code. The content of this method is always
     * regenerated by the Form Editor.
     */
    @SuppressWarnings("unchecked")
    public void initComponents() {

        jPanel1 = new javax.swing.JPanel();
        chartPanel = new javax.swing.JPanel();
        jPanel2 = new javax.swing.JPanel();
        jScrollPane1 = new javax.swing.JScrollPane();
        statisticTextField = new javax.swing.JTextArea();
        jPanel3 = new javax.swing.JPanel();
        jButton1 = new javax.swing.JButton();

        setDefaultCloseOperation(javax.swing.WindowConstants.DISPOSE_ON_CLOSE);

        jPanel1.setLayout(new java.awt.BorderLayout());

        chartPanel.setBorder(javax.swing.BorderFactory.createEmptyBorder(5, 5, 5, 5));
        chartPanel.setLayout(new java.awt.BorderLayout());
        jPanel1.add(chartPanel, java.awt.BorderLayout.CENTER);

        jPanel2.setLayout(new java.awt.BorderLayout());

        jScrollPane1.setBorder(javax.swing.BorderFactory.createEmptyBorder(5, 5, 5, 5));

        statisticTextField.setColumns(20);
        statisticTextField.setRows(5);
        jScrollPane1.setViewportView(statisticTextField);

        jPanel2.add(jScrollPane1, java.awt.BorderLayout.CENTER);

        jPanel1.add(jPanel2, java.awt.BorderLayout.EAST);

        jPanel3.setPreferredSize(new java.awt.Dimension(400, 47));

        jButton1.setText("Close");
        jButton1.addActionListener(new java.awt.event.ActionListener() {

            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jButton1ActionPerformed(evt);
            }
        });

        javax.swing.GroupLayout jPanel3Layout = new javax.swing.GroupLayout(jPanel3);
        jPanel3.setLayout(jPanel3Layout);
        jPanel3Layout.setHorizontalGroup(
                jPanel3Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING).addGroup(javax.swing.GroupLayout.Alignment.TRAILING, jPanel3Layout.createSequentialGroup().addContainerGap(331, Short.MAX_VALUE).addComponent(jButton1).addContainerGap()));
        jPanel3Layout.setVerticalGroup(
                jPanel3Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING).addGroup(javax.swing.GroupLayout.Alignment.TRAILING, jPanel3Layout.createSequentialGroup().addContainerGap(13, Short.MAX_VALUE).addComponent(jButton1).addContainerGap()));

        jPanel1.add(jPanel3, java.awt.BorderLayout.PAGE_END);

        getContentPane().add(jPanel1, java.awt.BorderLayout.CENTER);

        pack();
    }

    private void jButton1ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jButton1ActionPerformed
        this.setVisible(false);
    }

//    /**
//     * @param args the command line arguments
//     */
//    public static void save(final TimeSeriesObject mr) {
//        java.awt.EventQueue.invokeLater(new Runnable() {
//
//            public void run() {
//                final MultiChart dialog = new MultiChart(new javax.swing.JFrame(), false);
//                dialog.addWindowListener(new java.awt.event.WindowAdapter() {
//
//                    public void windowClosing(java.awt.event.WindowEvent e) {
//                        dialog.setVisible(false);
//                    }
//                });
//                dialog.initMessreihe(mr);
//            }
//        });
//    }
    public static void open(TimeSeriesObject[] mrs, final String title, final String x, final String y, final boolean b) {
        Vector<TimeSeriesObject> mr = new Vector<TimeSeriesObject>();
        for (int i = 0; i < mrs.length; i++) {
            mr.add(mrs[i]);
        }
        open(mr, title, x, y, b, "no comment", null);
    }

    public static void open(TimeSeriesObject[] mrs) {
        open(mrs, "?", "x", "y", true);
    }

    public static void open(Vector<TimeSeriesObject> mrs) {
        open(mrs, "?", "x", "y", true, "", null);
    }

    public static void open(Vector<TimeSeriesObject> mrs, boolean legende) {
        open(mrs, "?", "x", "y", legende, "", null);
    }

    
    static boolean useL = false;
    
    public static void openWithCorrelator(Vector<TimeSeriesObject> mrs, boolean legende, String label, ICorrelator c) {
        useL = legende;
        open(mrs, label, "t", "m", useL, "CAPTION", c);

    }
    public static void open(Vector<TimeSeriesObject> mrs, boolean legende, String label) {
        useL = legende;
        open(mrs, label, "t", "m", useL, "CAPTION", null);

    }

    public static MultiChart openWithCorrelator(final Vector<TimeSeriesObject> mrs, final String string, final String x, final String y, final boolean b, final String comment, ICorrelator c) {
        final MultiChart dialog = new MultiChart(new javax.swing.JFrame(string), false);
        dialog.addWindowListener(new java.awt.event.WindowAdapter() {

            public void windowClosing(java.awt.event.WindowEvent e) {
                dialog.setVisible(false);
            }
        });
        Enumeration<TimeSeriesObject> en = mrs.elements();
        while (en.hasMoreElements()) {
            TimeSeriesObject mr = en.nextElement();
            dialog.initMessreihe(mr);
            //System.out.println( mr.getStatisticData("> ") );
        }
        dialog.chartTitle = string;
        dialog.rows = mrs;
        dialog.xLabel = x;
        dialog.yLabel = y;
        dialog.useLegende = b;
        dialog.statisticTextField.setText(comment);
        dialog.initChart();
        dialog.setTitle(string);
        dialog.setVisible(true);
//            }
//        });
        
        
        fcp = new TSOperationControlerPanel( mrs, dialog.chart , string, dialog.statisticTextField );
        fcp.registerPanelHolder(dialog);
        
        if ( c != null)
            c.setTSOperationControlerPanel( fcp );
        
        MacroTrackerFrame._registerDialogToNode( string, dialog );
        
        dialog.chartPanel.add(fcp, BorderLayout.NORTH);
        dialog.setSize( 1400, 900);
        
        RefineryUtilities.centerFrameOnScreen( dialog );
        
        return dialog;
    
    }

    
    Vector<TimeSeriesObject> rows = null;
    public static MultiChart open(final Vector<TimeSeriesObject> mrs, final String string, final String x, final String y, final boolean b, final String comment, ICorrelator c) {
//        java.awt.EventQueue.invokeLater(new Runnable() {
//
//            public void run() {
        final MultiChart dialog = new MultiChart(new javax.swing.JFrame(string), false);
        dialog.addWindowListener(new java.awt.event.WindowAdapter() {

            public void windowClosing(java.awt.event.WindowEvent e) {
                dialog.setVisible(false);
            }
        });
        Enumeration<TimeSeriesObject> en = mrs.elements();
        while (en.hasMoreElements()) {
            TimeSeriesObject mr = en.nextElement();
            dialog.initMessreihe(mr);
            //System.out.println( mr.getStatisticData("> ") );
        }
        dialog.chartTitle = string;
        dialog.rows = mrs;
        dialog.xLabel = x;
        dialog.yLabel = y;
        dialog.useLegende = b;
        dialog.statisticTextField.setText(comment);
        dialog.initChart();
        dialog.setTitle(string);
        dialog.setVisible(true);
//            }
//        });
        
        
        fcp = new TSOperationControlerPanel( mrs, dialog.chart , string, dialog.statisticTextField );
        fcp.registerPanelHolder(dialog);
        
        if (c != null)  
            c.setTSOperationControlerPanel(fcp);
        
        MacroTrackerFrame._registerDialogToNode( string, dialog );
        
        dialog.chartPanel.add(fcp, BorderLayout.NORTH);
        dialog.setSize( 1400, 900);
        
        RefineryUtilities.centerFrameOnScreen( dialog );
        
        return dialog;
        
    }
    
    public static JFreeChart createChart(final Vector<TimeSeriesObject> mrs, final String string, final String x, final String y, final boolean b, final String comment) {
//        java.awt.EventQueue.invokeLater(new Runnable() {
//
//            public void run() {
        final MultiChart dialog = new MultiChart(new javax.swing.JFrame(string), false);
        dialog.addWindowListener(new java.awt.event.WindowAdapter() {

            public void windowClosing(java.awt.event.WindowEvent e) {
                dialog.setVisible(false);
            }
        });
        Enumeration<TimeSeriesObject> en = mrs.elements();
        while (en.hasMoreElements()) {
            TimeSeriesObject mr = en.nextElement();
            dialog.initMessreihe(mr);
            //System.out.println( mr.getStatisticData("> ") );
        }
        dialog.chartTitle = string;
        dialog.rows = mrs;
        dialog.xLabel = x;
        dialog.yLabel = y;
        dialog.useLegende = b;
        dialog.statisticTextField.setText(comment);
        dialog.initChart();
        dialog.setTitle(string);
        
        return dialog.chart;
        
    }

    public static MultiChart open2(final Vector<TimeSeriesObject> mrs, final String string, final String x, final String y, final boolean b, final String comment) {
//        java.awt.EventQueue.invokeLater(new Runnable() {
//
//            public void run() {
        final MultiChart dialog = new MultiChart(new javax.swing.JFrame(string), false);
        dialog.addWindowListener(new java.awt.event.WindowAdapter() {

            public void windowClosing(java.awt.event.WindowEvent e) {
                dialog.setVisible(false);
            }
        });
        Enumeration<TimeSeriesObject> en = mrs.elements();
        while (en.hasMoreElements()) {
            TimeSeriesObject mr = en.nextElement();
            dialog.initMessreihe(mr);
            //System.out.println( mr.getStatisticData("> ") );
        }
        dialog.chartTitle = string;
        dialog.xLabel = x;
        dialog.yLabel = y;
        dialog.useLegende = b;
        dialog.statisticTextField.setText(comment);
        dialog.initChart();
        dialog.setTitle(string);
        // dialog.setVisible(true);
        dialog.setSize( 1024, 768);
//            }
//        });
        return dialog;
    }

    public static Container openAndStoreImage(final Vector<TimeSeriesObject> mrs,
            final String string, final String x, final String y,
            final boolean b, final String folder, final String filename,
            String comment) {

        final MultiChart dialog = new MultiChart(new javax.swing.JFrame(string), false);
        dialog.addWindowListener(new java.awt.event.WindowAdapter() {

            public void windowClosing(java.awt.event.WindowEvent e) {
                dialog.setVisible(false);
            }
        });
        Enumeration<TimeSeriesObject> en = mrs.elements();
        while (en.hasMoreElements()) {
            TimeSeriesObject mr = en.nextElement();
            dialog.initMessreihe(mr);
            //System.out.println( mr.getStatisticData("> ") );
        }
        dialog.chartTitle = string;
        dialog.xLabel = x;
        dialog.yLabel = y;
        dialog.useLegende = b;
        
        

        dialog.statisticTextField.setText(comment);

        dialog.initChart();
        dialog.setTitle(string);

        dialog.setVisible(true);
        File f2 = new File(folder);

        dialog.store(dialog.chart, f2, filename);

        return dialog.getContentPane();

    }

    public static Container openAndStore(final Vector<TimeSeriesObject> mrs,
            final String string, final String x, final String y,
            final boolean b, final String folder, final String filename,
            String comment) {

        final MultiChart dialog = new MultiChart(new javax.swing.JFrame(string), false);
        dialog.addWindowListener(new java.awt.event.WindowAdapter() {

            public void windowClosing(java.awt.event.WindowEvent e) {
                dialog.setVisible(false);
            }
        });
        Enumeration<TimeSeriesObject> en = mrs.elements();
        while (en.hasMoreElements()) {
            TimeSeriesObject mr = en.nextElement();
            dialog.initMessreihe(mr);
            //System.out.println( mr.getStatisticData("> ") );
        }
        dialog.chartTitle = string;
        dialog.xLabel = x;
        dialog.yLabel = y;
        dialog.useLegende = b;

        dialog.statisticTextField.setText(comment);

        dialog.initChart();
        dialog.setTitle(string);

        dialog.setVisible(true);
        File f2 = new File(folder);

        dialog.store(dialog.chart, f2, filename);

        MeasurementTable tab = new MeasurementTable();

        File f = new File(folder + "/" + filename + ".mwt.data");
        tab.setMessReihen(mrs);

        tab.writeToFile(f);

        return dialog.getContentPane();

    }

    public static void openNormalized(final Vector<TimeSeriesObject> mrs, final String string, final String x, final String y, final boolean b) {
        java.awt.EventQueue.invokeLater(new Runnable() {

            public void run() {
                final MultiChart dialog = new MultiChart(new javax.swing.JFrame(string), false);
                dialog.addWindowListener(new java.awt.event.WindowAdapter() {

                    public void windowClosing(java.awt.event.WindowEvent e) {
                        dialog.setVisible(false);
                    }
                });
                Enumeration<TimeSeriesObject> en = mrs.elements();
                while (en.hasMoreElements()) {
                    TimeSeriesObject mr = en.nextElement();
                    mr.normalize();
                    dialog.initMessreihe(mr);
                    //System.out.println( mr.getStatisticData("> ") );
                }
                dialog.chartTitle = string;
                dialog.xLabel = x;
                dialog.yLabel = y;
                dialog.useLegende = b;

                dialog.initChart();
                dialog.setTitle(string);
                dialog.setVisible(true);
            }
        });
    }

    // Variables declaration - do not modify//GEN-BEGIN:variables
    private javax.swing.JPanel chartPanel;
    private javax.swing.JButton jButton1;
    private javax.swing.JPanel jPanel1;
    private javax.swing.JPanel jPanel2;
    private javax.swing.JPanel jPanel3;
    private javax.swing.JScrollPane jScrollPane1;
    private javax.swing.JTextArea statisticTextField;

    public JTextArea getStatisticTextField() {
        return statisticTextField;
    }

    public void setStatisticTextField(JTextArea statisticTextField) {
        this.statisticTextField = statisticTextField;
    }
    // End of variables declaration//GEN-END:variables

    public void initMessreihe(TimeSeriesObject mr) {
        // if ( this.normalizeY ) mr.normalizeY();
        if (mr == null) {
            return;
        }

        XYSeries series = new XYSeries(mr.getLabel());

        Vector<Double> x = mr.getXValues();
        Vector<Double> y = mr.getYValues();

        for (int i = 0; i < x.size(); i++) {
            series.add(x.elementAt(i), y.elementAt(i));
        }
        series.setDescription(mr.getLabel());
        this.addSerie(series);
    }

    public void addSerie(XYSeries serie) {
        dataset.addSeries(serie);
    }

    public void store(JFreeChart cp, File folder, String filename) {
//        if (doStoreChart) {

//            File folder = LogFile.folderFile;
//            String fn = folder.getAbsolutePath() + File.separator + "images/Distribution_";
//            File file = null;
//            fn = fn + GeneralResultRecorder.currentSimulationLabel;
        String fn = filename;
        try {

            final File file1 = new File(folder.getAbsolutePath() + File.separator + fn + ".png");
            System.out.println("\n>>> Save as PNG Image - Filename: " + file1.getAbsolutePath()
                    + "; CP: " + cp);
            try {
                final ChartRenderingInfo info = new ChartRenderingInfo(new StandardEntityCollection());

                Thread.currentThread().sleep(1000);

                ChartUtilities.saveChartAsPNG(file1, chart, 600, 400, info);

                Thread.currentThread().sleep(1000);
            } catch (Exception e) {
                e.printStackTrace();
            }

//                File file = new File(folder.getAbsolutePath() + File.separator + fn + ".svg");
//                System.out.println(">>> Save as SVG Image - Filename: " + file.getAbsolutePath()
//                        + "; CP: "+ cp);
//
//
//                // Get a DOMImplementation and create an XML document
//                DOMImplementation domImpl =
//                        GenericDOMImplementation.getDOMImplementation();
//                Document document = domImpl.createDocument(null, "svg", null);
//
//                // Create an instance of the SVG Generator
//                SVGGraphics2D svgGenerator = new SVGGraphics2D(document);
//
//                // draw the chart in the SVG generator
//                cp.draw(svgGenerator, new Rectangle(800, 600));
//
//                // Write svg file
//                OutputStream outputStream = new FileOutputStream(file);
//                Writer out = new OutputStreamWriter(outputStream, "UTF-8");
//                svgGenerator.stream(out, true /* use css */);
//                outputStream.flush();
//                outputStream.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
////        }
    }



    void initChart() {
        
        chartPanel.removeAll();
        
        cp = _createChartPanel();
        
        chartPanel.add(cp, BorderLayout.CENTER);

        NumberAxis xAxis = (NumberAxis) chart.getXYPlot().getDomainAxis();
        NumberAxis yAxis = (NumberAxis) chart.getXYPlot().getRangeAxis();

        if (setDefaultRange) {
            
            xAxis.setRange(xRangDEFAULT_MIN, xRangDEFAULT_MAX);
            yAxis.setRange(yRangDEFAULT_MIN, yRangDEFAULT_MAX);

        } 
        else {
            
            xAxis.setAutoRange(autoscale);
            yAxis.setAutoRange(autoscale);
            
        }

        Locale deLocale = new Locale("en_US");
        Locale.setDefault(deLocale);

        TickUnits units = new TickUnits();

        XYPlot plot = (XYPlot) chart.getPlot();

        ((NumberAxis) plot.getRangeAxis()).setNumberFormatOverride(df2);
        ((NumberAxis) plot.getDomainAxis()).setNumberFormatOverride(df1);

        plot.getRangeAxis().setTickLabelFont(fontTL);
        plot.getDomainAxis().setTickLabelFont(fontTL);

        plot.getDomainAxis().setLabelFont(fontL);
        plot.getRangeAxis().setLabelFont(fontL);

        RefineryUtilities.centerFrameOnScreen(this);

    }

    public ChartPanel _createChartPanel() {
        JFreeChart chart2 = ChartFactory.createXYLineChart(
                chartTitle,
                xLabel,
                yLabel,
                dataset,
                PlotOrientation.VERTICAL,
                useLegende, // legende
                true,
                true);

        XYPlot plot = chart2.getXYPlot();
        plot.setBackgroundPaint(bgCOLOR);

        if (dcSet == null) {

            plot.getRenderer().setSeriesPaint(0, Color.BLACK);
            plot.getRenderer().setSeriesPaint(1, Color.blue);
            plot.getRenderer().setSeriesPaint(2, Color.orange);
            plot.getRenderer().setSeriesPaint(3, Color.RED);
            plot.getRenderer().setSeriesPaint(4, Color.gray);

//            plot.getRenderer().setSeriesPaint(5, Color.BLACK);
//            plot.getRenderer().setSeriesPaint(6, Color.blue);
//            plot.getRenderer().setSeriesPaint(7, Color.orange);
//            plot.getRenderer().setSeriesPaint(8, Color.RED);
//            plot.getRenderer().setSeriesPaint(9, Color.gray);

            plot.getRenderer().setSeriesPaint(5, Color.GREEN);
            plot.getRenderer().setSeriesPaint(6, Color.PINK);
            plot.getRenderer().setSeriesPaint(7, Color.MAGENTA);
            plot.getRenderer().setSeriesPaint(8, Color.CYAN);
            plot.getRenderer().setSeriesPaint(9, Color.white);

            
        } else {

            int i = 0;
            for (Color c : dcSet) {
                plot.getRenderer().setSeriesPaint(i, c);
                System.out.println("Color: " + c);
                i++;
            }
        }

        XYLineAndShapeRenderer renderer = (XYLineAndShapeRenderer) plot.getRenderer();

        BasicStroke s1 = new BasicStroke(
                1.9f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND,
                0.0f);

//        renderer.setSeriesStroke( 0, s1 );
//        renderer.setSeriesStroke( 1, s );
        renderer.setSeriesStroke(8, s1);
//        renderer.setSeriesStroke( 3, s );
//        renderer.setSeriesStroke( 4, s );

        if (useMyStroke) {
            initStrokes(renderer);
        }

        ChartPanel chartPanel = new ChartPanel(chart2);
        chartPanel.setPreferredSize(new java.awt.Dimension(800, 600));
        chart = chart2;
        return chartPanel;
    }

    static public boolean useMyStroke = false;
    static BasicStroke[] strokes = new BasicStroke[3];
    static int[] dt = null;

    public static void initStrokes(XYLineAndShapeRenderer renderer) {
        BasicStroke s0 = new BasicStroke(1.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 0.0f);
        BasicStroke s1 = new BasicStroke(1.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1.0f, new float[]{2.0f, 4.0f}, 0.0f);
        BasicStroke s2 = new BasicStroke(2.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 0.0f);
        strokes[0] = s1;
        strokes[1] = s0;
        strokes[2] = s2;

        for (int i = 0; i < dt.length; i++) {
            renderer.setSeriesStroke(i, strokes[dt[i]]);
        }
    }

    boolean normalizeY = false;

    void normalizeY() {
        this.normalizeY = true;
    }

    public static void open(final Vector<TimeSeriesObject> mrs, final String string, final String x, final String y, final boolean b) {
        java.awt.EventQueue.invokeLater(new Runnable() {

            public void run() {
                final MultiChart dialog = new MultiChart(new javax.swing.JFrame(string), false);
                dialog.addWindowListener(new java.awt.event.WindowAdapter() {

                    public void windowClosing(java.awt.event.WindowEvent e) {
                        dialog.setVisible(false);
                    }
                });
                Enumeration<TimeSeriesObject> en = mrs.elements();
                while (en.hasMoreElements()) {
                    TimeSeriesObject mr = en.nextElement();
                    dialog.initMessreihe(mr);
                    //System.out.println( mr.getStatisticData("> ") );
                }
                dialog.chartTitle = string;
                dialog.xLabel = x;
                dialog.yLabel = y;
                dialog.useLegende = b;

                dialog.setBackground(Color.lightGray);
                dialog.initChart();
                dialog.setTitle(string);

                dialog.setVisible(true);
            }
        });
    }

    public static void store(final TimeSeriesObject[] mrs,
            final String string, final String x, final String y,
            final boolean b, final String folder, final String filename,
            String comment) {
        Vector<TimeSeriesObject> r = new Vector<TimeSeriesObject>();
        for (int i = 0; i < mrs.length; i++) {
            r.add(mrs[i]);
        }
        store(r, string, x, y, b, folder, filename, comment);
    }

    public static void store(final Vector<TimeSeriesObject> mrs,
            final String title, final String x, final String y,
            final boolean b, final String folder, final String filename,
            String comment) {

        final MultiChart dialog = new MultiChart(new javax.swing.JFrame(title), false);
        dialog.addWindowListener(new java.awt.event.WindowAdapter() {

            public void windowClosing(java.awt.event.WindowEvent e) {
                dialog.setVisible(false);
            }
        });

        Enumeration<TimeSeriesObject> en = mrs.elements();
        int i = 0;
        while (en.hasMoreElements()) {
            TimeSeriesObject mr = en.nextElement();
            dialog.initMessreihe(mr);
            // System.out.println( "... init reihe="+i );
            i++;
        }
        dialog.chartTitle = title;
        dialog.xLabel = x;
        dialog.yLabel = y;
        dialog.useLegende = b;

        dialog.statisticTextField.setText(comment);

        dialog.initChart();
        dialog.setTitle(title);
        //dialog.setVisible(true);
        File f2 = new File(folder);

        dialog.store(dialog.chart, f2, filename);

        System.out.println(folder + "\t" + filename);
        MeasurementTable tab = new MeasurementTable();
        tab.fill_UP_VALUE = 0.0;

        if (mrs.size() > 0) {
            File f = new File(folder + "/" + "TAB_" + filename + ".dat");
            tab.setMessReihen(mrs);
            System.out.println(mrs.size() + " reihen ...");
            tab.writeToFile(f);
        }
        
        

    }

    public ChartPanel createChartPanel() {
        JFreeChart chart = ChartFactory.createXYLineChart(
                chartTitle,
                xLabel,
                yLabel,
                dataset,
                PlotOrientation.VERTICAL,
                useLegende, // legende
                true,
                true);
        chart.getXYPlot().setForegroundAlpha(0.75f);
        ChartPanel chartPanel = new ChartPanel(chart);
        chartPanel.setPreferredSize(new java.awt.Dimension(500, 270));
        return chartPanel;
    }

    static Color[] dcSet = null;

    public void _setDefaultColors(Color[] dcSet1) {
        dcSet = dcSet1;
        initChart();
    }

    public static void setDefaultColors() {
        Color[] c = new Color[10];

        c[0] = Color.BLACK;
        c[1] = Color.RED;
        c[2] = Color.GREEN;
        c[3] = Color.GRAY;
        c[4] = Color.ORANGE;
        c[5] = Color.MAGENTA;
        c[6] = Color.BLUE;
        c[7] = Color.PINK;
        c[8] = Color.cyan;
        c[9] = Color.YELLOW;

        dcSet = c;
    }

    void saveInProject() {
        
         

    }

}
