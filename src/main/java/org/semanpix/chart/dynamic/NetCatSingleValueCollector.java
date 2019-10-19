package org.semanpix.chart.dynamic;

import java.awt.*;
import javax.swing.*;
import java.io.*;
import java.beans.*;
import java.net.Socket;
import java.util.logging.Level;
import java.util.logging.Logger;

public class NetCatSingleValueCollector extends JFrame {

    DynamicSingleSeriesPlot plot = null;
            
    Collector c = null;
    
    LineProcessor lp = null;
    
    public NetCatSingleValueCollector(String title, int port, String label) {
        
        super("NetCatSingleValueCollector  (Series:" + label + ")");

        lp = new FloatLineProcessor();
        
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(new BorderLayout());
        JProgressBar probar = new JProgressBar(0, 100);
        probar.setStringPainted(true);
        JTextArea fileArea = new JTextArea();
        add(probar, BorderLayout.NORTH);
        add(new JScrollPane(fileArea), BorderLayout.CENTER);
        setSize(512, 384);
        setLocationByPlatform(true);

        plot = DynamicSingleSeriesPlot.open( label + " (port: " + port +")" );
        lp.init(plot);
        
        setVisible(true);
        
        c = new Collector(fileArea, port, label, plot, lp);
        c.addPropertyChangeListener(new MyProgressBarChanger(probar));
        c.execute();

        try {

            boolean b = c.get();
            if (b) {
                System.out.println("Errors occurred");
            } else {
                System.out.println("Everything was fine");
            }

        } catch (Exception e) {
            e.printStackTrace(System.console().writer());
            System.exit(1);
        }
    }

            
    /*
     *args[0] = source directory
     *args[1] = destination directory
     */
    public static void main(String[] args) {

        new NetCatSingleValueCollector("NC-Event-Collector", 22222, "TimeSeriesBuckets");

    }

    public void stop() throws IOException {
        c.socket.close();
        plot.setVisible(false);
        plot.dispose();
        this.setVisible(false);
        this.dispose();
    }
}

class Collector extends SwingWorker<Boolean, File> {

    private JTextArea appendTo;
    private int port;
    private String series;
    private int iterations;

    DynamicSingleSeriesPlot dssp = null;
    LineProcessor lp = null;

    
    public Collector(JTextArea appendTo, int port, String series, DynamicSingleSeriesPlot dssp, LineProcessor lp) {

        this.appendTo = appendTo;
        this.port = port;
        this.series = series;
        this.iterations = 100;
        this.dssp = dssp;
        this.lp = lp;
        
    }

    protected Boolean doInBackground() {
        
        boolean errorOccurred = readValuesFromStream(10);
        return Boolean.valueOf(errorOccurred);
    
    }

    Socket socket = null;
    
    /**
     * We connect to a server-port ...
     * 
     * @param z
     * @return 
     */
    public boolean readValuesFromStream(int z) {

        try {

            System.out.println("Open ... ");

            int port = 22222;

            //Verbindung zu Port 13000 auf localhost aufbauen:
            socket = new Socket("localhost", port);
            
            String serverResponse = "GO...";
            
            while (serverResponse.length() > 0) {
            
                System.out.println(serverResponse);
                
                serverResponse = getResponse(socket);

                lp.processString( serverResponse, this );
            
            }

            //Socket dichtmachen:
            socket.close();

            System.out.println("Close ... ");
        
            return true;
        
        } 
        catch (IOException ex) {
            Logger.getLogger(DynamicSingleSeriesPlot.class.getName()).log(Level.SEVERE, null, ex);
            System.out.println( ">>> Not connected.");
            return false;
        }

    }


    public void updateTS(final float f, final String series) {

        process(f);

        System.out.println(series + " >>> new value : " + f);

    }

  
    public void updateTS(final float f) {

        process(f);

        System.out.println(" >>> new value : " + f);

    }

    /**
     * Read data from the socket ...
     *
     * @param socket
     * @return
     * @throws IOException
     */
    private String getResponse(Socket socket) throws IOException {

        //Eine Zeile lesen:
        BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        String serverResponse = in.readLine();
        System.out.println("LENGTH: " + serverResponse.length());

        return serverResponse;
    }

    int max = 25;
    int z = 0;
 
    /**
     * Handle the new float value and update the UI.
     *
     * @param numbers
     */
    protected void process(Float... numbers) {
        for (Float n : numbers) {
            appendTo.append(n + "");
            appendTo.append("\n");
            
            if (dssp != null) {
                int p = (int)((double)z / (double)max * 100.0);
                
                if ( p < 100.1 ) {

                    dssp.append(n);
                    System.out.println( ">>> " + p + "%" );
                    setProgress(p);
                
                }
            }
            z++;
            
        }
    }

}

class MyProgressBarChanger implements PropertyChangeListener {

    private JProgressBar jpb;

    public MyProgressBarChanger(JProgressBar myBar) {
        jpb = myBar;
    }

    public void propertyChange(PropertyChangeEvent evt) {
        if ("progress".equals(evt.getPropertyName())) {
            jpb.setValue((Integer) evt.getNewValue());
        }
    }
}
