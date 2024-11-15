package org.opentsx.chart.dynamic;

import java.awt.BorderLayout;
import java.awt.EventQueue;
import java.util.Random;
import javax.swing.Timer;
import org.jfree.chart.ChartFactory;
import org.jfree.chart.ChartPanel;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.axis.ValueAxis;
import org.jfree.chart.plot.XYPlot;
import org.jfree.data.time.DynamicTimeSeriesCollection;
import org.jfree.data.time.Second;
import org.jfree.data.xy.XYDataset;

import org.jfree.ui.ApplicationFrame;
import org.jfree.ui.RefineryUtilities;

/**
 * @see http://stackoverflow.com/questions/5048852
 */
public class DynamicSingleSeriesPlot extends ApplicationFrame {

    private static final String TITLE = "Dynamic Series";
    private static final String START = "Start";
    private static final String STOP = "Stop";
    private static final float MINMAX = 100;
    private static final int COUNT = 2 * 60;
    private static final int FAST = 100;
    private static final int SLOW = FAST * 5;
    private static final Random random = new Random();
    private Timer timer;

    final DynamicTimeSeriesCollection dataset
            = new DynamicTimeSeriesCollection(1, COUNT, new Second());

    public DynamicSingleSeriesPlot(final String title) {
        super(title);

        dataset.setTimeBase(new Second(0, 0, 0, 1, 1, 2011));
        
        dataset.addSeries( emptyData(), 0, "series: " + title );
        
        JFreeChart chart = createChart(dataset);

//        final JButton run = new JButton(STOP);
//        
//        run.addActionListener(new ActionListener() {
//
//            @Override
//            public void actionPerformed(ActionEvent e) {
//                String cmd = e.getActionCommand();
//                if (STOP.equals(cmd)) {
//                    timer.stop();
//                    run.setText(START);
//                } else {
//                    timer.start();
//                    run.setText(STOP);
//                }
//            }
//        });

//        final JComboBox combo = new JComboBox();
//        combo.addItem("Fast");
//        combo.addItem("Slow");
//        combo.addActionListener(new ActionListener() {
//
//            @Override
//            public void actionPerformed(ActionEvent e) {
//                if ("Fast".equals(combo.getSelectedItem())) {
//                    timer.setDelay(FAST);
//                } else {
//                    timer.setDelay(SLOW);
//                }
//            }
//        });

        this.add(new ChartPanel(chart), BorderLayout.CENTER);
//        JPanel btnPanel = new JPanel(new FlowLayout());
//        btnPanel.add(run);
////        btnPanel.add(combo);
//        this.add(btnPanel, BorderLayout.SOUTH);

//        timer = new Timer(FAST, new ActionListener() {
//
//            float[] newData = new float[1];
//
//            @Override
//            public void actionPerformed(ActionEvent e) {
//                newData[0] = randomValue();
//                dataset.advanceTime();
//                dataset.appendData(newData);
//            }
//        });
    }

//    private float randomValue() {
//        return (float) (random.nextGaussian() * MINMAX / 3);
//    }

//    private float[] gaussianData() {
//        float[] a = new float[COUNT];
//        for (int i = 0; i < a.length; i++) {
//            a[i] = randomValue();
//        }
//        return a;
//    }
    
    private float[] emptyData() {
        float[] a = new float[1];
        for (int i = 0; i < a.length; i++) {
            a[i] = 0;
        }
        return a;
    }

    private JFreeChart createChart(final XYDataset dataset) {
        final JFreeChart result = ChartFactory.createTimeSeriesChart(
                TITLE, "t [hh:mm:ss]", "f(t)", dataset, true, true, false);
        final XYPlot plot = result.getXYPlot();
        ValueAxis domain = plot.getDomainAxis();
        domain.setAutoRange(true);
        ValueAxis range = plot.getRangeAxis();
        range.setRange(-MINMAX, MINMAX);
        return result;
    }

    public void start() {
        //timer.start();
    }

    public static DynamicSingleSeriesPlot open(final String a) {
        //EventQueue.invokeLater(new Runnable() {

        //    @Override
        //    public void run() {
        DynamicSingleSeriesPlot demo = new DynamicSingleSeriesPlot(a);
        demo.pack();
        RefineryUtilities.centerFrameOnScreen(demo);
        demo.setVisible(true);
        demo.start();
        //    }
        //});
        return demo;
    }

    public static void main(final String[] args) {
        EventQueue.invokeLater(new Runnable() {

            @Override
            public void run() {
                DynamicSingleSeriesPlot demo = new DynamicSingleSeriesPlot(TITLE);
                demo.pack();
                RefineryUtilities.centerFrameOnScreen(demo);
                demo.setVisible(true);
                demo.start();
            }
        });
    }

    void append(Float n) {

        float[] newData = new float[1];

        newData[0] = n;

        dataset.advanceTime();
        dataset.appendData(newData);

    }
    
    LineProcessor lp = null;

    void setLineProcessor(FloatLineProcessor a) {
        lp = a;
        
        lp.setDataSet( dataset );
    }
}
