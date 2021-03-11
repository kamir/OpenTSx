/**
 *
 * A set of time series is grouped for efficient bulk processing in TSBucket.
 *
 * The default implementation works with a SequenceFile. An alternative
 * representation of the TSBucket are AVRO and PARQUET files.
 *
 * The data is stored as a (k,v) pair from:
 *
 * key: Text value: VectorWritable;
 *
 * EXAMPLE:
 *
 * NamedVector nv = new NamedVector(new DenseVector(data.getData()),
 * data.label); VectorWritable vec = new VectorWritable(); vec.set(nv);
 * writer.append(new Text(nv.getName()), vec);
 *
 */
package org.opentsx.core;

import com.google.gson.Gson;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.SequenceFile;
import org.apache.hadoop.io.Text;

import org.opentsx.data.generator.RNGWrapper;
import org.opentsx.data.series.TimeSeriesObject;

import org.opentsx.generators.LongTermCorrelationSeriesGenerator;
import org.opentsx.generators.TSGenerator;

import org.opentsx.tsbucket.folderStore.TSBASE;
import org.opentsx.algorithms.ris.experimental.TSPropertyTester;
import org.apache.mahout.math.DenseVector;
import org.apache.mahout.math.NamedVector;
import org.apache.mahout.math.VectorWritable;

import java.io.*;
import java.text.DecimalFormat;
import java.util.Hashtable;
import java.util.Vector;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * @author Mirko Kaempf
 */
public class TSBucket {

    StringBuffer processLog = new StringBuffer();

    public static boolean useHDFS = false;

    public static TSBucket loadViaContext(Object sc, String tsbaseexP1Collectiontsbvecseq) {
        return TSBucket.createEmptyBucket();
    }

    public void setInMemory(boolean b) {
        inMEM = b;
    }

    public boolean inMEM = false;

    public static int default_LIMIT = 5;
    public int LIMIT = Integer.MAX_VALUE;

    public boolean isProcessed = false;

    int[] ids = null;
    public java.util.Vector<TimeSeriesObject> bucketData = new java.util.Vector<TimeSeriesObject>();

    // record mode is a domain specific feature from WIKIPEDIA analysis.
    static String recoderIdMode = "counter";

    public String sourcFolder = "/media/esata/wiki/extract/svwiki/";

    FileWriter fw = null;
    FileWriter fwe = null;
    public static String FN_EXT = "A";

    static int counter = 0;

    /**
     * Create an empty TSBucket and set LIMIT to Integer.MAX_VALUE.
     *
     * @return a bucket
     */
    public static TSBucket createEmptyBucketFull() {
        TSBucket bu = new TSBucket();
        bu.LIMIT = Integer.MAX_VALUE;
        return bu;
    }

    /**
     * Create an empty TSBucket and set LIMIT to Integer.MAX_VALUE.
     *
     * @return a bucket
     */
    public static TSBucket createEmptyBucket() {
        TSBucket bu = new TSBucket();
        bu.LIMIT = TSBucket.default_LIMIT;
        return bu;
    }

    // define the limit ...
    public void setLimitTo(int i) {
        LIMIT = i;
    }

    private static void setRecordMode(String c) {
        recoderIdMode = c;
        resetCounter();
    }

    /**
     * Where should the time series be loaded from?
     *
     * Within the source folder, there is a "group folder", from which data can
     * be loaded.
     *
     * @param in
     */
    public void setSourceDataFolder(String in) {
        sourcFolder = in;
        System.out.println("> bucket source base : " + in);
        System.out.println("> bucket limit       : " + LIMIT);
    }

    public TSBucket(String in, String out, String s) {
        setSourceDataFolder(in);
    }

    public TSBucket() {
        initConfig();
    }

    public boolean SKIP_WRITES = true;

    public TSBucket(boolean skipWrites) {
        initConfig();
        SKIP_WRITES = skipWrites;
    }

    public TSBucket(Configuration conf) {
        setConfig(conf);
    }

    /**
     * Processing results are stored in CSV files.
     *
     * This processing mode is just for testing. Large scale processing is done
     * via Crunch.TS and scalable pipelines.
     *
     * @param fn
     * @param t
     * @param ids
     * @throws IOException
     */
    String mode = "local";
    // String mode = "cluster";  

    public TSBucket processBucket(String t, Object parameter) throws IOException {
        System.out.println("[" + mode + "] > Operation: " + t + "  { PARAMETER: " + parameter + " } ");
        return TSBucket.createEmptyBucket();
    }

    /**
     * Processing results are stored in CSV files.
     *
     * This processing mode is just for testing. Large scale processing is done
     * via Crunch.TS and scalable pipelines.
     *
     * @param fn
     * @param t
     * @param ids
     * @throws IOException
     */
    public void _loadAndProcess(String fn, TSOperation t, java.util.Vector<Integer> ids) throws IOException {

        File f = new File(fn + ".proc." + FN_EXT + ".csv");
        File fe = new File(fn + ".proc.explode." + FN_EXT + ".csv");

        fw = new FileWriter(f);
        fwe = new FileWriter(fe);

        loadFromSequenceFile(fn, ids, t);

        fw.close();
        fwe.close();

        System.out.println("**** #### ****" + f.getAbsolutePath() + " created ... ");
    }

    /**
     * Im sourceFolder wird eine komplette Gruppe gewählt und in einen TS Bucket
     * überführt.
     *
     * ==> ist nur eine SAVE Funktion ...
     *
     * @param groupFolder
     * @param limit
     *
     */
    public void createBucketFromLocalFilesInDirectory(String groupFolder, int limit) throws IOException {

        LIMIT = limit;
        String s = groupFolder;

        File f = new File(sourcFolder + s);

        System.out.println("--> load data from ($source/$group) : " + f.getAbsolutePath());

        Configuration config = initConfig();
        FileSystem fs = initFileSystem();

        Path path = new Path(s + ".tsb.vec.seq");
        System.out.println("--> create bucket : " + path.toUri().toString());

        // write a SequenceFile form a Vector
        SequenceFile.Writer writer = new SequenceFile.Writer(fs, config, path, Text.class, VectorWritable.class);

        File[] liste = f.listFiles();
        System.out.println(liste.length);
        System.out.println("--> process bucket : " + f.getAbsolutePath() + " (" + liste.length + ")");

        int c = 0;
        for (File file : liste) {
            c++;
            if (c < LIMIT) {
                TSData data = new TSData(file);
                System.out.println("(" + c + ")");
                NamedVector nv = new NamedVector(new DenseVector(data.getData()), data.label);
                VectorWritable vec = new VectorWritable();
                vec.set(nv);

                writer.append(new Text(nv.getName()), vec);
            }
            if (c % 10000 == 0) {
                System.out.println(c);
            }
        }
        writer.close();
    }

    /**
     * Reload a TSBucket from a Sequence-File and do an implicit processing, if
     * the TSOperation object is not null.
     *
     * Handle the ts-ID in a general way.
     *
     * @param fn
     * @param ids
     *
     * @throws IOException
     */
    public static boolean processSeriesLabelOnLoad = true;

    public void loadFromSequenceFile(String fn, java.util.Vector<Integer> ids, TSOperation tst) throws IOException {

        if (tst != null) {
            isProcessed = true;
        }

        FileSystem fs = initFileSystem();
        Configuration config = initConfig();

        Path path = new Path(fn);

        // write a SequenceFile form a Vector
        SequenceFile.Reader reader = new SequenceFile.Reader(fs, path, config);

        System.out.println("--> process bucket    : " + fn);
        System.out.println("--> compression-codes : " + reader.getCompressionCodec());
        System.out.println("--> key-classename    : " + reader.getKeyClassName());
        System.out.println("--> value-classname   : " + reader.getValueClassName());

        // TSBucket.setRecordMode( "page-id" );
        TSBucket.setRecordMode("counter");

        boolean goOn = true;
        int i = 1;
        while (goOn && i <= LIMIT) {

            Text key = new Text();

            VectorWritable vec = new VectorWritable();
            goOn = reader.next(key);

            String seriesID = key.toString();

            if (processSeriesLabelOnLoad) {
                seriesID = "" + TSBucket.getRecordID(key.toString());
            }

            boolean skipped = false;

            // bearbeite nur die, die im FILTER file stehen ...
            if (ids != null) {
                if (ids.contains(seriesID)) {
                    skipped = false;
                } else {
                    skipped = true;
                }
            }

            reader.getCurrentValue(vec);

            TimeSeriesObject mr = new TimeSeriesObject();
            mr.setDescription(i + " ) " + fn + "_[" + key.toString() + "]");
            mr.setLabel(seriesID);

            int c = 0;
            NamedVector vector = (NamedVector) vec.get();

            while (c < vector.size()) {

                double value = vector.get(c);
                //System.out.println( c + "\t" + value  );

                mr.addValue(value);

                c++;
            }
            try {
                TimeSeriesObject m = null;
                if (tst != null) {
                    m = tst.processReihe(fw, mr, fwe);
                }
            } catch (Exception ex) {
                Logger.getLogger(TSBucket.class.getName()).log(Level.SEVERE, null, ex);
            }

            i = i + 1;

            int code = 1;
            if (skipped) {
                code = 0;
            }

            if (inMEM) {
                bucketData.add(mr);
            }

            //System.out.println( code + "\t" + mr.getLabel() + "\t" + mr.xValues.size() + "\t" + mr.summeY() );
        }
        System.out.println("--> nr of records     : " + (i - 1));
    }

    Configuration config = null;

    public void setConfig(Configuration conf) {
        config = conf;
    }

    private Configuration initConfig() {
        
        if (config == null) {
            System.out.println( ">>> create new Configuration() ..." );
            config = new Configuration();
        }

        /*

        config.set("fs.hdfs.impl",
                org.apache.hadoop.hdfs.DistributedFileSystem.class.getName()
        );
        config.set("fs.file.impl",
                org.apache.hadoop.fs.LocalFileSystem.class.getName()
        );

        */

        return config;
    }

    private FileSystem initFileSystem() throws IOException {

        FileSystem fs = null;
        
        try {
        
            fs = FileSystem.get(initConfig());

            if (initConfig() == null) {
                System.err.println("!!! ERROR !!! ");
            }

            if (fs == null) {
                System.err.println("!!! ERROR !!! ");
            }
            
            System.out.println(fs);
        }
        catch (IOException ex) {
            ex.printStackTrace();
            System.out.println(">>> IOException: " + ex.getMessage() );
        }
        catch (Exception ex) {
            ex.printStackTrace();
        }

        return fs;
    }

    public java.util.Vector<TimeSeriesObject> getBucketData() {
        return bucketData;
    }

    public static void resetCounter() {
        counter = 0;
    }

    public static int getRecordID(String t) {

        if (recoderIdMode.equals("counter")) {
            counter++;
            return counter;

        } else {

            if (t.length() > 0) {

                int i1 = t.indexOf("PageID_");

                String a = t.substring(i1 + 7);

                int i2 = a.indexOf(".nrv.h.dat");

                String b = a.substring(0, i2);
                // System.out.append( b + " " + t );
                return Integer.parseInt(b);

            } else {
                return 0;
            }

        }
    }

    /**
     * Create Long Term Correlated time series ...
     *
     * @param s
     * @param z
     * @param EXP
     * @param BETA
     * @throws IOException
     * @throws Exception
     */
    @SuppressWarnings("deprecation")
    public void createBucketWithRandomTS(String s, int z, int EXP, double BETA) throws IOException, Exception {

        DecimalFormat df = new DecimalFormat("0.000");

        System.out.println("--> create bucket : LRC with beta=" + df.format(BETA));

        FileSystem fs = initFileSystem();

        Path path = new Path(s + "_LRC_beta_" + df.format(BETA) + ".tsb.vec.seq");
        System.out.println("--> create bucket : (" + path.toString() + ") " + fs);

        // write a SequenceFile form a Vector
        SequenceFile.Writer writer = new SequenceFile.Writer(fs, config, path, Text.class, VectorWritable.class);

        System.out.println("--> process bucket : LRC-Generator (" + z + ")");

        int SAMPLES = 0;
        for (int i = 0; i < z; i++) {

            boolean showTESTS = true;

            TimeSeriesObject mr = LongTermCorrelationSeriesGenerator.getRandomRow((int) Math.pow(2, EXP), BETA, showTESTS, false);
            if (SAMPLES < TSPropertyTester.zSAMPLES) {
                TSPropertyTester.addSample(mr);
            }
            SAMPLES++;

            TSData data = TSData.convertMessreihe(mr);

            System.out.println("(" + i + ")");
            NamedVector nv = new NamedVector(new DenseVector(data.getData()), data.label);
            VectorWritable vec = new VectorWritable();
            vec.set(nv);

            writer.append(new Text(nv.getName()), vec);
        }

        writer.close();
        System.out.println("### DONE : " + path.toString());
    }

    /**
     * Create Uncorrelated time series ...
     *
     * @param s
     * @param EXP
     * @param ANZ
     * @throws IOException
     */
    public void createBucketWithRandomTS(String s, int EXP, int ANZ) throws IOException {

        DecimalFormat df = new DecimalFormat("0.000");

        System.out.println("--> create bucket : uncorrelated TS alpha=0.5");

        Configuration config = initConfig();
        FileSystem fs = initFileSystem();

        Path path = new Path(s + "_alpha_0.5_.tsb.vec.seq");
        System.out.println("--> create bucket : " + path.toString());

        // write a SequenceFile form a Vector
        SequenceFile.Writer writer = new SequenceFile.Writer(fs, config, path, Text.class, VectorWritable.class);

        System.out.println("--> process bucket : Uniform-Random-Generator ( z=" + ANZ + ", l=" + Math.pow(2, EXP) + ", TestA )");

        int SAMPLES = 0;
        for (int i = 0; i < ANZ; i++) {

            TSData data = new TSData();
            data.dataset = rescaleRandomData(data.getRandomData((int) Math.pow(2, EXP)), 24.0);
            TimeSeriesObject mr = data.getMessreihe();
            if (SAMPLES < TSPropertyTester.zSAMPLES) {
                TSPropertyTester.addSample(mr);
            }
            SAMPLES++;
            /**
             *
             * Here we lose the METADATA of each row!!!
             *
             */
            System.out.print("  (" + i + ")");
            NamedVector nv = new NamedVector(new DenseVector(data.getData()), data.label);
            VectorWritable vec = new VectorWritable();
            vec.set(nv);

            writer.append(new Text(nv.getName()), vec);
        }

        writer.close();
        System.out.println("### DONE : " + path.toString());
    }

    /**
     * Create Uncorrelated time series ...
     *
     *
     * @throws IOException
     */
    public void createBucketFromVectorOfSeries(String track, String label, Vector<TimeSeriesObject> rows) throws IOException {

        DecimalFormat df = new DecimalFormat("0.000");

        System.out.println("--> label bucket : " + label);

        Configuration config = initConfig();
        FileSystem fs = initFileSystem();

        Path pathFolder = new Path( TSBASE.BASE_PATH + "/" + track);
        fs.mkdirs(pathFolder);

        Path path = new Path(TSBASE.BASE_PATH + "/" + track + "/" + label + ".tsb.vec.seq");

        System.out.println("--> create bucket : " + path.toString());

        // write a SequenceFile form a Vector
        SequenceFile.Writer writer = new SequenceFile.Writer(fs, config, path, Text.class, VectorWritable.class);

        System.out.println("--> process bucket : Memory dump ... ( z=" + rows.size() + " )");

        int SAMPLES = 0;
        for (int i = 0; i < rows.size(); i++) {

            /**
             *
             * Here we lose the METADATA of each row!!!
             *
             */
            System.out.println("  (" + i + ")");
            TimeSeriesObject m = rows.elementAt(i);
            NamedVector nv = new NamedVector(new DenseVector(m.getYData()), m.label);

            VectorWritable vec = new VectorWritable();
            vec.set(nv);
            
            writer.append(new Text(nv.getName()), vec);
        }

        writer.close();

        System.out.println("\n### DONE : " + path.toString() + " ... and the write is closed.");
    }

    /**
     * a set of random numbers is multiplied by a factor
     *
     * @param randomData, factor
     *
     * @return rescaled data
     */
    private double[] rescaleRandomData(double[] randomData, double factor) {

        double sum = 0.0;
        for (int i = 0; i < randomData.length; i++) {
            randomData[i] = randomData[i] * factor;
            sum = sum + randomData[i];
        }
        System.out.println(sum + "\t" + sum / (double) randomData.length);
        return randomData;
    }


    /**
     * Generate a sinus wave.
     *
     * @param filename_core - PATH-PREFIX for TSBucket file
     * @param ANZ - NR of TSOs in TSB
     * @param fMIN - min Freq
     * @param fMAX - max Freq
     * @param aMIN - min Ampl
     * @param aMAX - max Ampl
     * @param SR - Sampling Rate
     * @param time - duration of episode
     *
     *    TimeSeriesObject mr = TSGenerator.getSinusWave(fre, time, SR, ampl);
     *
     *    With SKIP_WRITES=TRUE we generate in MEMORY and return the TSB for
     *    further processing.
     *
     *             This allows a simulation of "Data point dump from measurement devices".
     *
     *
     * @throws IOException
     */
    public Vector<TSData> createBucketWithRandomTS_sinus(String filename_core, int ANZ,
            double fMIN, double fMAX, double aMIN, double aMAX, double SR, double time, String uuid, String unit) throws Exception {

        Configuration config = initConfig();
        // FileSystem fs = initFileSystem();

        Path path = new Path(filename_core + ".tsb.vec.seq");
        System.out.println("--> create bucket with filename : " + path.toString());

        Vector<TSData> tsbd = new Vector<TSData>();

        DecimalFormat df = new DecimalFormat("0.000");

        System.out.println("--> create bucket : uncorrelated Time Series with frequency range : f=[ " + fMIN + ", " + fMAX + "]");

        // write a SequenceFile form a Vector
/**
        SequenceFile.Writer writer = new SequenceFile.Writer(fs, config, path, Text.class, VectorWritable.class);
**/
        System.out.println("--> process bucket : Sinus-Generator ( z=" + ANZ + ", length=" + (time * SR) + ")");
        System.out.println("--> zDP : " + ( ANZ * (time * SR) ) + " " );

        for (int i = 0; i < ANZ; i++) {

            double fre = RNGWrapper.getStdRandomUniform(fMIN, fMAX);  // stdlib.StdRandom.uniform(fMIN, fMAX);
            double ampl = RNGWrapper.getStdRandomUniform(aMIN, aMAX); // stdlib.StdRandom.uniform(aMIN, aMAX);

            TimeSeriesObject mr = TSGenerator.getSinusWave(fre, time, SR, ampl, ""+i, unit );

            if( i==0 ) {
                System.out.println( "LENGTH : " + mr.getYValues().size() );
                System.out.println( "LABEL  : " + mr.getLabel() );
            }

            TSData data = TSData.convertMessreihe(mr);

            NamedVector nv = new NamedVector(new DenseVector(data.getData()), data.label);
            VectorWritable vec = new VectorWritable();
            vec.set(nv);

            tsbd.add( data );

            // to speed up the generator, we do not persist the bucket.
            if ( !SKIP_WRITES ) {
                writer.append(new Text(nv.getName()), vec);
            }
            else
                System.out.println("!!! SKIP-WRITE: ["+SKIP_WRITES+"] : no data written to TIME SERIES BUCKET ON disc!" );

        }

        if ( !SKIP_WRITES )
            writer.close();

            System.out.println("### DONE {SKIP-WRITE:="+SKIP_WRITES+"} : TIME SERIES BUCKET IN PATH: " + path.toString());

        return tsbd;
    }



    /**
     *
     * The TimeSeriesObject is converted into a "VectorWritable". The NamedVectors
     * getName() method defines the key of the element stored in the
     * SequenceFile;
     *
     * @param mr
     */
    public static final String KEY_PREFIX_DELIMITER = "###";

    public void putMessreihe(TimeSeriesObject mr, String keyPrefix) {
        try {

            TSData data = TSData.convertMessreihe(mr);

            NamedVector nv = new NamedVector(new DenseVector(data.getData()), data.label);
            VectorWritable vec = new VectorWritable();
            vec.set(nv);

            writer.append(new Text(keyPrefix + KEY_PREFIX_DELIMITER + nv.getName()), vec);

        }
        catch (IOException ex) {
            Logger.getLogger(TSBucket.class.getName()).log(Level.SEVERE, null, ex);
        }
    }

    /**
     *
     * The TimeSeriesObject is converted into a "VectorWritable". The NamedVectors
     * getName() method defines the key of the element stored in the
     * SequenceFile;
     *
     * @param mr
     */
    public void putMessreihe(TimeSeriesObject mr) {
        try {

            if (mr == null) {
                return;
            }

            TSData data = TSData.convertMessreihe(mr);

            NamedVector nv = new NamedVector(new DenseVector(data.getData()), data.label);
            VectorWritable vec = new VectorWritable();
            vec.set(nv);

            writer.append(new Text(nv.getName()), vec);

        } catch (IOException ex) {
            Logger.getLogger(TSBucket.class.getName()).log(Level.SEVERE, null, ex);
        }
    }


    SequenceFile.Writer writer = null;
    File writerFile = null;

    /**
     * We stream data into a file, so we open it. Later we close it.
     *
     * @param file
     */
    public void _open(File file) {
        try {
            DecimalFormat df = new DecimalFormat("0.000");
            writerFile = file;

            System.out.println("--> open a time series bucket for write : " + file.getAbsolutePath());

            Configuration config = initConfig();
            FileSystem fs = initFileSystem();

            Path path = new Path(file.getAbsolutePath());
            System.out.println("--> bucket path : " + path.toString());

            // write a SequenceFile form a Vector
            writer = new SequenceFile.Writer(fs, config, path, Text.class, VectorWritable.class);
        } catch (IOException ex) {
            Logger.getLogger(TSBucket.class.getName()).log(Level.SEVERE, null, ex);
        }

    }

    public void _open(String s) {
        File f = new File( s );
        _open( f );
    }


    public void flush() {
        try {
            // writer.hflush();
        } catch (Exception ex) {
            Logger.getLogger(TSBucket.class.getName()).log(Level.SEVERE, null, ex);
        }
    }

    public void close() {
        try {
            if ( writer != null ) {
                writer.close();
                System.out.println("--> closed the time series bucket writer : " + writerFile.getAbsolutePath());
            }
        } catch (IOException ex) {
            Logger.getLogger(TSBucket.class.getName()).log(Level.SEVERE, null, ex);
        }
    }

    /**
     * Reload a TSBucket from a Sequence-File and do an implicit processing, if
     * the TSOperation object is not null.
     *
     * @param f
     *
     * @throws IOException
     */
    public void loadFromSequenceFile(File f) throws IOException {

        FileSystem fs = FileSystem.getLocal(config);

        System.out.println("--> LIMIT to local FileSystem ");

        Path path = new Path(f.getAbsolutePath());

        // read NamedVectors from a SequenceFile 
        SequenceFile.Reader reader = new SequenceFile.Reader(fs, path, config);

        System.out.println("--> process bucket    : " + f.getAbsolutePath());
        System.out.println("--> compression-codes : " + reader.getCompressionCodec());
        System.out.println("--> key-classename    : " + reader.getKeyClassName());
        System.out.println("--> value-classname   : " + reader.getValueClassName());
        System.out.println("--> LIMIT             : " + LIMIT);
        System.out.println("--> IN MEM MODE       : " + inMEM);

        boolean goOn = true;
        int i = 0;
        while (goOn && i <= LIMIT) {
            i = i + 1;
            Text key = new Text();

            VectorWritable vec = new VectorWritable();
            goOn = reader.next(key);

            String tsKey = key.toString();

            reader.getCurrentValue(vec);

            TimeSeriesObject mr = new TimeSeriesObject();
            mr.setDescription(i + " ) " + f.getName() + "_[" + key.toString() + "]");
            mr.setLabel(i + " " + key.toString());

            System.out.println("-> " + i + " " + key.toString());

            int c = 0;
            NamedVector vector = (NamedVector) vec.get();
            while (c < vector.size()) {
                double value = vector.get(c);
                mr.addValue(value);
                c++;
            }

            if (inMEM) {
                bucketData.add(mr);
            }
        }
        System.out.println("--> nr of records     : " + (i - 1));
    }

    public void createBucketFromFilesViaWordLength(File[] folder, String s, String experiment) throws IOException {

        int ANZ = folder.length;

        DecimalFormat df = new DecimalFormat("0.000");

        System.out.println("--> create bucket : from text by wordlength ");

        Configuration config = initConfig();
        FileSystem fs = initFileSystem();

        Path path = new Path(s + "/" + experiment + "/bucket_wordLength_.tsb.vec.seq");

        System.out.println("--> create bucket : " + path.toString());

        // write a SequenceFile form a Vector
        SequenceFile.Writer writer = new SequenceFile.Writer(fs, config, path, Text.class, VectorWritable.class);

        System.out.println("--> process bucket : Word-Length-Series-Generator ( z=" + ANZ + ")");

        for (int i = 0; i < ANZ; i++) {

            TimeSeriesObject mr = TSGenerator.getWordLengthSeries(folder[i]);
            TSData data = TSData.convertMessreihe(mr);

            System.out.print("   (" + i + ")\t" + data.label + "\n");

            NamedVector nv = new NamedVector(new DenseVector(data.getData()), data.label);

            VectorWritable vec = new VectorWritable();
            vec.set(nv);

            writer.append(new Text(nv.getName()), vec);

            System.out.print("   (name: " + nv.getName() + ")\n");

        }

        writer.close();
        System.out.println("### DONE : " + path.toString());
    }

    public void createBucketFromGrayImageFile(File imageFile, String s, String experiment) throws IOException {
        int ANZ = 1;

        DecimalFormat df = new DecimalFormat("0.000");

        System.out.println("--> create bucket : from gray image by wordlength ");

        Configuration config = initConfig();
        FileSystem fs = initFileSystem();

        Path path = new Path(s + "/" + experiment + "/bucket_grayImage.tsb.vec.seq");

        System.out.println("--> create bucket : " + path.toString());

        // write a SequenceFile form a Vector
        SequenceFile.Writer writer = new SequenceFile.Writer(fs, config, path, Text.class, VectorWritable.class);

        System.out.println("--> process bucket : GrayImageLine-Series-Generator ( z=" + ANZ + ")");

        TimeSeriesObject[] mrS = TSGenerator.getGrayImageSeries(imageFile);

        for (TimeSeriesObject mr : mrS) {

            TSData data = TSData.convertMessreihe(mr);

            System.out.print(data.label + "\n");

            NamedVector nv = new NamedVector(new DenseVector(data.getData()), data.label);

            VectorWritable vec = new VectorWritable();
            vec.set(nv);

            writer.append(new Text(nv.getName()), vec);

            System.out.print("   (name: " + nv.getName() + ")\n");

        }
        writer.close();
        System.out.println("### DONE : " + path.toString());
    }

    public void createBucketFromGrayImageFile(File[] folder, String baseOut, String experiment) {
        throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
    }

    public void createBucketFromFilesViaWordLengthRecursive(File[] folder, String s, String experiment) throws IOException {

        int ANZ = folder.length;

        DecimalFormat df = new DecimalFormat("0.000");

        System.out.println("--> create bucket : from text by wordlength ");

        Configuration config = initConfig();
        FileSystem fs = initFileSystem();

        Path path = new Path(s + "/" + experiment + "/bucket_wordLength_.tsb.vec.seq");

        System.out.println("--> create bucket : " + path.toString());

        // write a SequenceFile form a Vector
        SequenceFile.Writer writer = new SequenceFile.Writer(fs, config, path, Text.class, VectorWritable.class);

        System.out.println("--> process bucket : Word-Length-Series-Generator ( z=" + ANZ + ")");

        for (int i = 0; i < ANZ; i++) {

            File[] FOLDERS = folder[i].listFiles();

            for (File f : FOLDERS) {

                TimeSeriesObject mr = TSGenerator.getWordLengthSeries(f);

                TSData data = TSData.convertMessreihe(mr);

                System.out.print("   (" + i + ")\t" + data.label + "\n");

                NamedVector nv = new NamedVector(new DenseVector(data.getData()), data.label);

                VectorWritable vec = new VectorWritable();
                vec.set(nv);

                writer.append(new Text(nv.getName()), vec);

                System.out.print("   (name: " + nv.getName() + ")\n");

            }

        }

        writer.close();
        System.out.println("### DONE : " + path.toString());
    }

    public void createBucketFromFilesViaTFIDFRecursive(File[] folder, String s, String experiment) throws IOException {

        int ANZ = folder.length;

        DecimalFormat df = new DecimalFormat("0.000");

        System.out.println("--> create bucket : from text by wordlength ");

        Configuration config = initConfig();
        FileSystem fs = initFileSystem();

        Path path = new Path(s + "/" + experiment + "/bucket_tfidf_.tsb.vec.seq");

        System.out.println("--> create bucket : " + path.toString());

        // write a SequenceFile form a Vector
        SequenceFile.Writer writer = new SequenceFile.Writer(fs, config, path, Text.class, VectorWritable.class);

        System.out.println("--> process bucket : Word-Length-Series-Generator ( z=" + ANZ + ")");

        Hashtable<String, Integer> idfs = new Hashtable<String, Integer>();

        for (int i = 0; i < ANZ; i++) {

            File[] FOLDERS = folder[i].listFiles();

            for (File f : FOLDERS) {

                storeIDFCOntribs(f, idfs);

            }

            
        }
        
//        for( String w : idfs.keySet() )
//            System.out.println( "[" + w + "] " + " => " + idfs.get(w) );
        
        System.out.println(" DONE PART 1" );

        for (int i = 0; i < ANZ; i++) {

            File[] FOLDERS = folder[i].listFiles();

            for (File f : FOLDERS) {

                TimeSeriesObject mr = TSGenerator.getTFIDFSeries( f, idfs , getWC(f), getText(f) );

                TSData data = TSData.convertMessreihe(mr);

                System.out.print("   (" + i + ")\t" + data.label + "\n");

                NamedVector nv = new NamedVector(new DenseVector(data.getData()), data.label);

                VectorWritable vec = new VectorWritable();
                vec.set(nv);

                writer.append(new Text(nv.getName()), vec);

                System.out.print("   (name: " + nv.getName() + ")\n");

            }

        }

        writer.close();
        System.out.println("### DONE : " + path.toString());
    }

    private void storeIDFCOntribs(File f, Hashtable<String, Integer> idfs) throws FileNotFoundException, IOException {

        Hashtable<String, Integer> wc = getWC(f);

        for (String w : wc.keySet()) {
            if (idfs.containsKey(w)) {
                Integer c = idfs.get(w);
                c++;
                idfs.put(w, c);
            } else {
                idfs.put(w, 1);
            };
        }
    }
    
    private String getText(File f) throws FileNotFoundException, IOException {

        StringBuffer sb = new StringBuffer();
        
        BufferedReader br = new BufferedReader(new FileReader(f));
        while (br.ready()) {
            String l = br.readLine();
            sb.append(l + "\n");
        }
        br.close();

        return sb.toString();
    }

    private Hashtable<String, Integer> getWC(File f) throws FileNotFoundException, IOException {

        Hashtable<String, Integer> wc = new Hashtable<String, Integer>();

        BufferedReader br = new BufferedReader(new FileReader(f));
        while (br.ready()) {
            String l = br.readLine();
            String[] ws = l.split( TSGenerator.PATTERN );
            for (String w : ws) {
                if (wc.containsKey(w)) {
                    Integer c = wc.get(w);
                    c++;
                    wc.put(w, c);
                }
                else { 
                    wc.put(w,1);
                };
            }
        }
        br.close();

        return wc;
    }




    public void clearProcessingLog() {
        this.processLog = new StringBuffer();
    }

    public String getProcessingLog() {
        return this.processLog.toString();
    }




    Gson gson = new Gson();






    public void processKVToTSO( String key, String dataJSON, String topic ) {

        TSData data = gson.fromJson( dataJSON, TSData.class );

        String descr = "(" + "---" + "){" + topic + "}\n[" + key.toString() + "]";
        this.processLog.append( descr + "\n" );

        TimeSeriesObject mr = new TimeSeriesObject();
        mr.setDescription( descr );
        mr.setLabel(key);

        NamedVector vector = new NamedVector(new DenseVector(data.getData()), data.label);

        int c = 0;
        while (c < vector.size()) {

            double value = vector.get(c);

            mr.addValue(value);

            c++;
        }
        try {

                /*

                TimeSeriesObject m = null;
                if (tst != null) {
                    m = tst.processReihe(fw, mr, fwe);
                }

                 */

        }
        catch (Exception ex) {
            Logger.getLogger(TSBucket.class.getName()).log(Level.SEVERE, null, ex);
        }

        if (inMEM) {
            bucketData.add(mr);
        }

    }

}
