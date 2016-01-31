package app.gui;

import java.awt.Toolkit;
import javax.swing.JFrame;
 
/**
 *
 * A collection of screen utils.
 * 
 * (A) zoom a JFrame to full screen.
 * 
 * @author kamir
 */
public class ScreenUtils {
    
    /**
     * Zoom a JFrame to full screen.
     * 
     * @param o  - a JFrame instance.
     */
    public static void fullScreen( Object o ) { 
        Toolkit tk = Toolkit.getDefaultToolkit();  
        int xSize = ((int) tk.getScreenSize().getWidth());  
        int ySize = ((int) tk.getScreenSize().getHeight());  
        JFrame frame = (JFrame)o;
        frame.setSize(xSize,ySize);  
    }
    
}
