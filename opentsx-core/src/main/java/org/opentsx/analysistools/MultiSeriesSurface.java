package org.opentsx.analysistools;

import org.opentsx.data.series.TimeSeriesObject;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.image.AffineTransformOp;
import java.awt.image.BufferedImage;
import java.util.Vector;

/**
 * A set of time series is plotted in a 2D area. Y-values are color coded.
 * 
 * @author kamir
 */
public class MultiSeriesSurface {

    JFrame frame = null;
    BufferedImage img;
    public static int WIDTH = 500;
    public static int HEIGHT = 500;

    Vector<TimeSeriesObject> rows = null;

    // we set the range of all possible values to 0 ... 2
    double vMin = 0.0;
    double vMax = 2.0;
    double zColors = 255;

    /**
     * The MultiSeries-Surface shows some time series (usually dependent on a 
     * a variable parameter in 2D area.
     * 
     * @param rowsSorted ...
     * @param label ...
     * @param scale ...
     */
    public MultiSeriesSurface(Vector<TimeSeriesObject> rowsSorted, String label, int scale) {

        sw = scale;
        sh = scale;
        
        rows = rowsSorted;

        frame = new JFrame("MultiSeries-Surface : " + label);

        plot();

    }

    public MultiSeriesSurface() {
        frame = new JFrame("MultiSeries-Surface : " + "DEMO");

        Vector<TimeSeriesObject> v = new Vector<TimeSeriesObject>();

        for (int i = 0; i < 20; i++) {

            TimeSeriesObject m = new TimeSeriesObject();
            m.setLabel("row: " + i);

            for (int j = 0; j < 100; j++) {
                
                m.addValuePair(j / 20.0, Math.random() * 2.0);
            }

            v.add(m);
        }

        rows = v;

        plot();

    }

    public Image getImage() {
        return img;
    }

    public void plot() {

        int sy = rows.size();
        int sx = rows.elementAt(0).yValues.size();

        img = new BufferedImage(sx, sy, BufferedImage.TYPE_BYTE_GRAY);

        for (int x = 0; x < sx; x++) {

            for (int y = 0; y < sy; y++) {

                TimeSeriesObject r = rows.elementAt(y);

                double v = (double) r.yValues.elementAt(x);

                int value = (int) ((v / (double) vMax) * zColors);
                
                System.out.println(value);
                
                int col = (value << 16) | (value << 8) | value;
                
                img.setRGB(x, y, (int)col );

            }
        }

        frame.setVisible(true);

        frame.add(new JLabel(new ImageIcon(getScaledImage())));

        frame.pack();

        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);

    }

    public static void main(String[] a) {

        MultiSeriesSurface hs = new MultiSeriesSurface();

        hs.plot();

    }

    int sw = 5;
    int sh = 5;
        
    private BufferedImage getScaledImage() {

        BufferedImage before = img;

        
        int w = before.getWidth();
        int h = before.getHeight();
        
        BufferedImage after = new BufferedImage(w * sw, h * sh, BufferedImage.TYPE_BYTE_GRAY);
        
        AffineTransform at = new AffineTransform();
        
        at.scale(sw, sh);
        
        AffineTransformOp scaleOp
                = new AffineTransformOp(at, AffineTransformOp.TYPE_BILINEAR);
        
        after = scaleOp.filter(before, after);
        
        return after;
    }

}
