package org.apache.hadoopts.analysistools;

import org.apache.hadoopts.analysistools.utils.FitbereichLogScale;
import org.apache.hadoopts.data.series.TimeSeriesObject;

import java.awt.Image;
import java.awt.geom.AffineTransform;
import java.awt.image.AffineTransformOp;
import java.awt.image.BufferedImage;
import java.util.Vector;
import javax.swing.ImageIcon;
import javax.swing.JFrame;
import javax.swing.JLabel;

/**
 *
 * @author kamir
 */
public class HurstSurface {

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
     * The Hurst-Surface shows ...
     * 
     * @param rowsSorted - Fluctuation Functions from DFA
     * 
     * @param label
     * @param scale 
     */
    public HurstSurface(Vector<TimeSeriesObject> rowsSorted, String label, int scale) {

        sw = scale;
        sh = scale;
        
        rows = getAlphaAsFunctionOfS ( rowsSorted );

        frame = new JFrame("Hurst-Surface : " + label);

        plot();

    }

    public HurstSurface() {
        frame = new JFrame("Hurst-Surface : " + "DEMO");

        Vector<TimeSeriesObject> v = new Vector<TimeSeriesObject>();

        for (int i = 0; i < 20; i++) {

            TimeSeriesObject m = new TimeSeriesObject();
            m.setLabel("row: " + i);

            for (int j = 0; j < 100; j++) {
                
                m.addValuePair(j / 20.0, Math.random() * 2.0);
            }

            v.add(m);
        }
 
        rows = getAlphaAsFunctionOfS ( v );

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

        HurstSurface hs = new HurstSurface();

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

    double shift = 0.2;
    double width = 0.5;
    
    private Vector<TimeSeriesObject> getAlphaAsFunctionOfS(Vector<TimeSeriesObject> r) {
        Vector<TimeSeriesObject> hRows = new Vector<TimeSeriesObject>();
        
        for( TimeSeriesObject m: r) {
        
            TimeSeriesObject h = new TimeSeriesObject();
            h.setLabel( "hurst(" + m.getLabel() + ")");
            FluctuationFunktion ff = new FluctuationFunktion( m, m.label );
            
            for( int i = 0; i < 20; i++ ){
                
                double l = 0.6 + ( i * shift );
                double u = l + width;
                
                double middle = l + 0.5 * width;
                
                FitbereichLogScale fb = new FitbereichLogScale( l, u );
                
                double alpha = ff.calcAlpha(fb);
                
                h.addValuePair( middle, alpha );
            }
            
            hRows.add(h);
            
        }
        
        return hRows;
    }

}
