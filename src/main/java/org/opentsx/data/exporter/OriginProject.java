/*
 *  Ein Daten-Container, in dem Messreihen abgelegt werden,
 *  die dann für den Origin-Import optimiert gespeichert werden.
 * 
 */
package org.opentsx.data.exporter;

import org.semanpix.chart.simple.MultiChart;
import org.semanpix.chart.simple.MyXYPlot;
import org.opentsx.data.series.TimeSeriesObject;

import javax.swing.*;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Hashtable;
import java.util.Vector;
import java.util.logging.Level;
import java.util.logging.Logger;

public class OriginProject {

    public String folderName = null;

    // default on Mirko's Mac
    String header = "./out/";

    public void setHeader(String header) {
        this.header = header;
    }
    public void addToHeader(String header2) {
        header = header + "\n#\t" + header2 + "\n#\n#";
    }


    /**
     * Select a folder to store all results of an experiment in this 
     * location, including log-data.
     * 
     * @param folder 
     */
    public void initBaseFolder(String folder) { 
        if ( folder == null ) {
            javax.swing.JFileChooser jfc = new javax.swing.JFileChooser();
            jfc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            int i = jfc.showOpenDialog(null);
            File f = jfc.getSelectedFile();  
            //File p = f.getParentFile();
            folderName = f.getAbsolutePath();
        }
        else { 
            folderName = folder;
        }
        try {
            createProjectFile();
        } catch (IOException ex) {
            Logger.getLogger(OriginProject.class.getName()).log(Level.SEVERE, null, ex);
        }
        
        System.out.println(">>> Origin-Project: " +
                           "    Folder: " + folderName );
    }

    public void initNewProjectFolder(String f) throws IOException {
        String fn = this.folderName + File.separator + f;
        this.folderName = fn;
        this.createProjectFile();
    }
    /**
     * Select a folder to store all results of an experiment in this
     * location, including log-data.
     *
     * Creates a folder within the current project folder and steps into it.
     *
     * @param f
     * @throws IOException
     */
    public void initSubFolder(String f) throws IOException {
        String fn = this.folderName + File.separator + f;
        this.folderName = fn;
        this.createProjectFile();
    }

    public void addMessreihen(TimeSeriesObject[][] mrv2, String pre) {
        int i = mrv2[0].length;
        int j = mrv2.length;
        
        for( int a=0; a<j; a++ ) { 
            Vector<TimeSeriesObject> r = new Vector<TimeSeriesObject>();
            for( int b=0; b<i; b++ ) {
                r.add( mrv2[a][b]);
            }    
            addMessreihen(r, pre+"_"+a+"_", true);
        }    
    }

        
    public void addMessreihen(TimeSeriesObject[] mrv2, String pre) {
        Vector<TimeSeriesObject> rows = new Vector<TimeSeriesObject>();
        for( TimeSeriesObject mr : mrv2) {
            rows.add(mr);
        }
        addMessreihen(rows, pre, true);
     }
    
     public void addMessreihen(Vector<TimeSeriesObject> mrv2, String prefix, boolean writeTab ) {
         
        System.err.println( ">>> write to folder: " + folderName + File.separator + "tab_"+prefix+".dat" );
        if ( writeTab) { 
            MeasurementTable tab = new MeasurementTable();
            tab.singleX = false;
            tab.setHeader( header );
            
            tab.setLabel(folderName + File.separator + "tab_"+prefix+".dat");
            tab.setMessReihen(mrv2);
            tab.writeToFile();
        }    
            
        File f = new File( folderName + File.separator + "tab_"+prefix+".rows" );
        f.mkdirs();
        
        for( TimeSeriesObject mr : mrv2) {
            File ff = new File( f.getAbsolutePath() + File.separator + prefix + "_" + mr.getFileName() );
            mr.writeToFile( ff , '.' );            
        }
    }

    Hashtable<String, FileWriter> logger = new Hashtable<String, FileWriter>();

    /**
     * We collect processing logs in a project-log file.
     *
     * @param s
     * @throws IOException
     */
    public void createLogFile(String s) throws IOException {
        File f = new File(this.folderName + File.separator + s);
        
        if ( !f.getParentFile().exists() ) {
            f.getParentFile().mkdirs();
        }
        FileWriter fw = new FileWriter( f.getAbsolutePath() );
        logger.put(s, fw);

    }
    
    /**
     * If the foldername is known, we create a folder to store the data.
     * 
     * @throws IOException 
     */
    public void createProjectFile() throws IOException {
        File f = new File(this.folderName);
        
        if ( !f.exists() ) {
            f.mkdirs();
        }
    }

    public File getNewFile( String fn ) {

        File f = new File( this.folderName + "/" + fn );
        return f;

    };
    
    /**
     * We can log into several different files. 
     * Depending on the context ...
     * 
     * @param s      :   name of a log-channel.
     * @param line   :   content to log
     * @throws IOException 
     */
    public void logLine( String s, String line ) throws IOException { 
        FileWriter fw = logger.get(s);
        fw.write(line + "\n");
    };


    public void close() throws IOException {
        System.out.println(">>> Close all writers now: " );
        for( String fn : logger.keySet() ){
            System.out.println(">>> " + fn  );
            FileWriter fw = logger.get( fn );
            fw.flush();
            fw.close();
        }
    }

    public void closeAllWriters() throws IOException {
        for( FileWriter fw: logger.values() ) {
            fw.flush();
            fw.close();
        } 
    }

    /**
     *
     * A measurement table is written into the folder named foldeName.
     * @param mwt
     */
    public void storeMeasurementTable(MeasurementTable mwt) {
        System.out.println("> store measurement table into folder : " + this.folderName );
        File f = new File(this.folderName + File.separator + mwt.getLabel() );
        System.out.println("> table filename : " + f.getAbsolutePath() );
        mwt.writeToFile( f );
    }

    @Override
    public String toString() {
        return "Origin-Project{" + "folderName=" + folderName + ", logger=" + logger + '}';
    }


    /**
     * 
     * 
     * 
     * @param result
     * @param legende
     * @param title
     * @param name
     * @param xL
     * @param yL 
     */
    public void storeChart(TimeSeriesObject[] result, boolean legende, String title, String name, String xL, String yL) {

        Vector<TimeSeriesObject> v = new Vector<TimeSeriesObject>( result.length );
        for( TimeSeriesObject m : result ) {
            v.add(m);
        }
        
        String folder = this.folderName + File.separator; 
        String fileName = name;
        String comment = "";
        
        MultiChart.openAndStoreImage(v, title, xL, yL, legende, folder, fileName, comment);
        
    }

    /**
     * Store a simple line chart from a set of TimeSeriesObject.
     *
     * @param result
     * @param legende
     * @param title
     * @param name
     */
    public void storeChart(Vector<TimeSeriesObject> result, boolean legende, String title, String name) {

        String folder = this.folderName + File.separator; 
        String fileName = name;
        String comment = "";
        
        MultiChart.openAndStoreImage(result, title, "t", "f(t)", legende, folder, fileName, comment);
        
    }


    public void storePlotMyXYPlot(MyXYPlot plot, String filename) {
        plot.doStoreChart = true;
        plot.store(plot.chart, new File( this.folderName ), filename);
    }


 
    
    
    
    
}
