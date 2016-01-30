/*
 *  Ein Daten-Container, in dem Messreihen abgelegt werden,
 *  die dann für den Origin-Import optimiert gespeichert werden.
 * 
 */
package data.export;

import chart.simple.MultiChart;
import chart.simple.MyXYPlot;
import data.series.Messreihe;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Hashtable;
import java.util.Vector;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.JFileChooser;

/**
 *
 * @author kamir
 */
public class OriginProject {
    
    /**
     * absoluter Name im File-System
     */
    public String folderName = null;
    
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

    public void addMessreihen( Messreihe[][] mrv2, String pre) {
        int i = mrv2[0].length;
        int j = mrv2.length;
        
        for( int a=0; a<j; a++ ) { 
            Vector<Messreihe> r = new Vector<Messreihe>();
            for( int b=0; b<i; b++ ) {
                r.add( mrv2[a][b]);
            }    
            addMessreihen(r, pre+"_"+a+"_", true);
        }    
    }

        
    public void addMessreihen( Messreihe[] mrv2, String pre) {
        Vector<Messreihe> rows = new Vector<Messreihe>();
        for( Messreihe mr : mrv2) {
            rows.add(mr);
        }
        addMessreihen(rows, pre, true);
     }
    
     public void addMessreihen(Vector<Messreihe> mrv2, String prefix, boolean writeTab ) {
         
        System.err.println( ">>> write to folder: " + folderName + File.separator + "tab_"+prefix+".dat" );
        if ( writeTab) { 
            MesswertTabelle tab = new MesswertTabelle();
            tab.singleX = false;
            tab.setHeader( header );
            
            tab.setLabel(folderName + File.separator + "tab_"+prefix+".dat");
            tab.setMessReihen(mrv2);
            tab.writeToFile();
        }    
            
        File f = new File( folderName + File.separator + "tab_"+prefix+".rows" );
        f.mkdirs();
        
        for( Messreihe mr : mrv2) {
            File ff = new File( f.getAbsolutePath() + File.separator + prefix + "_" + mr.getFileName() );
            mr.writeToFile( ff , '.' );            
        }
    }

    Hashtable<String, FileWriter> logger = new Hashtable<String, FileWriter>();
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
    
    public void closeAllWriter() throws IOException { 
        for( FileWriter fw: logger.values() ) {
            fw.flush();
            fw.close();
        } 
    }

    public void storeMesswertTabelle(MesswertTabelle mwt) {
        File f = new File(this.folderName + File.separator + mwt.getLabel() );
        mwt.writeToFile( f );
    }

    @Override
    public String toString() {
        return "OriginProject{" + "folderName=" + folderName + ", logger=" + logger + '}';
    }

    String header = "";
    public void setHeader(String header) {
        this.header = header;
    }

    public void storeChart(Messreihe[] result, boolean legende, String title, String name, String xL, String yL) {
   
    
        Vector<Messreihe> v = new Vector<Messreihe>( result.length );
        for( Messreihe m : result ) {
            v.add(m);
        }
        
        String folder = this.folderName + File.separator; 
        String fileName = name;
        String comment = "";
        
        MultiChart.openAndStoreImage(v, title, xL, yL, legende, folder, fileName, comment);
        
    }
    
    public void storeChart(Vector<Messreihe> result, boolean legende, String title, String name) {
        
//        MultiChart.open(result, legende, title);
  
        String folder = this.folderName + File.separator; 
        String fileName = name;
        String comment = "";
        
        MultiChart.openAndStoreImage(result, title, "t", "f(t)", legende, folder, fileName, comment);
        
    }

    public void addToHeader(String header2) {
        header = header + "\n#\t" + header2 + "\n#\n#";
    }

    public void storePlotMyXYPlot(MyXYPlot plot, String filename) {
        plot.doStoreChart = true;
        plot.store(plot.chart, new File( this.folderName ), filename);
    }

    public void initFolder(String f) throws IOException {
        String fn = this.folderName + File.separator + f;
        this.folderName = fn;
        this.createProjectFile();
    }
 
    
    
    
    
}
