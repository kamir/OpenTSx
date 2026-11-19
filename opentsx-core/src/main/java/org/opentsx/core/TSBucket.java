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
import org.opentsx.core.config.ConfigManager;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.text.DecimalFormat;
import java.util.Hashtable;
import java.util.Vector;

/**
 * @author Mirko Kaempf
 */
public class TSBucket {

    private static final Logger LOG = LoggerFactory.getLogger(TSBucket.class);

    StringBuffer processLog = new StringBuffer();

    public static boolean useHDFS = false;

    public static TSBucket loadViaContext(Object sc, String tsbaseexP1Collectiontsbvecseq) {
        throw new UnsupportedOperationException("Context-based bucket loading is not implemented");
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

    public TSBucket processBucket(String t, Object parameter) throws IOException {
        throw new UnsupportedOperationException("Named bucket processing is not implemented");
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

        try (FileWriter output = new FileWriter(f); FileWriter exploded = new FileWriter(fe)) {
            fw = output;
            fwe = exploded;
            loadFromSequenceFile(fn, ids, t);
        }

        LOG.debug("processed bucket file={} output={}", fn, f.getAbsolutePath());
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
        try (SequenceFile.Reader reader = new SequenceFile.Reader(fs, path, config)) {

            LOG.debug("loading bucket file={} codec={} key={} value={}", fn, reader.getCompressionCodec(),
                    reader.getKeyClassName(), reader.getValueClassName());

            TSBucket.setRecordMode("counter");

            int i = 1;
            while (i <= LIMIT) {

                Text key = new Text();

                VectorWritable vec = new VectorWritable();
                if (!reader.next(key)) {
                    break;
                }

                String seriesID = key.toString();

                if (processSeriesLabelOnLoad) {
                    seriesID = "" + TSBucket.getRecordID(key.toString());
                }

                boolean skipped = false;

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

                    mr.addValue(value);

                    c++;
                }
                try {
                    if (tst != null) {
                        tst.processReihe(fw, mr, fwe);
                    }
                } catch (Exception ex) {
                    throw new IOException("Failed to process series " + seriesID, ex);
                }

                i = i + 1;

                int code = 1;
                if (skipped) {
                    code = 0;
                }

                if (inMEM) {
                    bucketData.add(mr);
                }

            }
            LOG.debug("loaded bucket file={} records={}", fn, i - 1);
        }
    }

    Configuration config = null;

    public void setConfig(Configuration conf) {
        config = conf;
    }

    private Configuration initConfig() {
        
        if (config == null) {
            config = new Configuration();
        }


        return config;
    }

    private FileSystem initFileSystem() throws IOException {
        Configuration conf = initConfig();
        try {
            return FileSystem.get(conf);
        }
        catch (IOException ex) {
            throw new IOException(FileSystem.getDefaultUri(conf) + ": cannot open the file system (" + ex + ")", ex);
        }
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

        FileSystem fs = initFileSystem();

        Path path = new Path(s + "_LRC_beta_" + df.format(BETA) + ".tsb.vec.seq");

        // write a SequenceFile form a Vector
        try (SequenceFile.Writer writer = new SequenceFile.Writer(fs, config, path, Text.class, VectorWritable.class)) {

            int SAMPLES = 0;
            for (int i = 0; i < z; i++) {

                boolean showTESTS = true;

                TimeSeriesObject mr = LongTermCorrelationSeriesGenerator.getRandomRow((int) Math.pow(2, EXP), BETA, showTESTS, false);
                if (SAMPLES < TSPropertyTester.zSAMPLES) {
                    TSPropertyTester.addSample(mr);
                }
                SAMPLES++;

                TSData data = TSData.convertMessreihe(mr);

                NamedVector nv = new NamedVector(new DenseVector(data.getData()), data.label);
                VectorWritable vec = new VectorWritable();
                vec.set(nv);

                writer.append(new Text(nv.getName()), vec);
            }
        }
        LOG.debug("wrote bucket path={} series={} beta={}", path, z, BETA);
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

        Configuration config = initConfig();
        FileSystem fs = initFileSystem();

        Path path = new Path(s + "_alpha_0.5_.tsb.vec.seq");

        // write a SequenceFile form a Vector
        try (SequenceFile.Writer writer = new SequenceFile.Writer(fs, config, path, Text.class, VectorWritable.class)) {

            int SAMPLES = 0;
            for (int i = 0; i < ANZ; i++) {

                TSData data = new TSData();
                data.dataset = rescaleRandomData(data.getRandomData((int) Math.pow(2, EXP)), 24.0);
                TimeSeriesObject mr = data.getMessreihe();
                if (SAMPLES < TSPropertyTester.zSAMPLES) {
                    TSPropertyTester.addSample(mr);
                }
                SAMPLES++;
                // the bucket keeps only the label and the values of each row
                NamedVector nv = new NamedVector(new DenseVector(data.getData()), data.label);
                VectorWritable vec = new VectorWritable();
                vec.set(nv);

                writer.append(new Text(nv.getName()), vec);
            }
        }
        LOG.debug("wrote bucket path={} series={} length={}", path, ANZ, (int) Math.pow(2, EXP));
    }

    /**
     * Create Uncorrelated time series ...
     *
     *
     * @throws IOException
     */
    public void createBucketFromVectorOfSeries(String track, String label, Vector<TimeSeriesObject> rows) throws IOException {

        Configuration config = initConfig();
        FileSystem fs = initFileSystem();

        Path pathFolder = new Path( TSBASE.BASE_PATH + "/" + track);
        fs.mkdirs(pathFolder);

        Path path = new Path(TSBASE.BASE_PATH + "/" + track + "/" + label + ".tsb.vec.seq");

        // write a SequenceFile form a Vector
        try (SequenceFile.Writer writer = new SequenceFile.Writer(fs, config, path, Text.class, VectorWritable.class)) {
            for (int i = 0; i < rows.size(); i++) {
                // the bucket keeps only the label and the values of each row
                TimeSeriesObject m = rows.elementAt(i);
                NamedVector nv = new NamedVector(new DenseVector(m.getYData()), m.label);

                VectorWritable vec = new VectorWritable();
                vec.set(nv);

                writer.append(new Text(nv.getName()), vec);
            }
        }
        LOG.debug("wrote bucket path={} series={}", path, rows.size());
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
        LOG.debug("rescaled random data sum={} mean={}", sum, sum / (double) randomData.length);
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
        String outputFormat = ConfigManager.getInstance().getString("opentsx.demo.output.format", "csv");
        boolean writeCsv = "csv".equalsIgnoreCase(outputFormat);
        boolean writeSequence = !SKIP_WRITES && !writeCsv;

        Path path = new Path(filename_core + ".tsb.vec.seq");

        Vector<TSData> tsbd = new Vector<TSData>();

        // write a SequenceFile form a Vector
        try (SequenceFile.Writer writer = writeSequence
                     ? new SequenceFile.Writer(initFileSystem(), config, path, Text.class, VectorWritable.class) : null;
             FileWriter csvWriter = writeCsv ? openCsv(filename_core) : null) {

            for (int i = 0; i < ANZ; i++) {

                double fre = RNGWrapper.getStdRandomUniform(fMIN, fMAX);
                double ampl = RNGWrapper.getStdRandomUniform(aMIN, aMAX);

                TimeSeriesObject mr = TSGenerator.getSinusWave(fre, time, SR, ampl, ""+i, unit );

                TSData data = TSData.convertMessreihe(mr);

                NamedVector nv = new NamedVector(new DenseVector(data.getData()), data.label);
                VectorWritable vec = new VectorWritable();
                vec.set(nv);

                tsbd.add( data );

                // to speed up the generator, we do not persist the bucket.
                if ( writer != null ) {
                    writer.append(new Text(nv.getName()), vec);
                }
                else if ( csvWriter != null ) {
                    double[][] seriesData = mr.getData();
                    for (int k = 0; k < seriesData[0].length; k++) {
                        csvWriter.write(i + "," + seriesData[0][k] + "," + seriesData[1][k] + "\n");
                    }
                }
            }
        }

        LOG.debug("generated sine bucket path={} series={} length={} skipWrites={} format={}", path, ANZ, time * SR,
                SKIP_WRITES, outputFormat);

        return tsbd;
    }

    private static FileWriter openCsv(String filename_core) throws IOException {
        File csvFile = new File(filename_core + ".csv");
        File parent = csvFile.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        FileWriter csvWriter = new FileWriter(csvFile);
        try {
            csvWriter.write("series_id,x,y\n");
        } catch (IOException ex) {
            csvWriter.close();
            throw ex;
        }
        return csvWriter;
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

    public void putMessreihe(TimeSeriesObject mr, String keyPrefix) throws IOException {
        TSData data = TSData.convertMessreihe(mr);

        NamedVector nv = new NamedVector(new DenseVector(data.getData()), data.label);
        VectorWritable vec = new VectorWritable();
        vec.set(nv);

        writer.append(new Text(keyPrefix + KEY_PREFIX_DELIMITER + nv.getName()), vec);
    }

    /**
     *
     * The TimeSeriesObject is converted into a "VectorWritable". The NamedVectors
     * getName() method defines the key of the element stored in the
     * SequenceFile;
     *
     * @param mr
     */
    public void putMessreihe(TimeSeriesObject mr) throws IOException {
        if (mr == null) {
            return;
        }

        TSData data = TSData.convertMessreihe(mr);

        NamedVector nv = new NamedVector(new DenseVector(data.getData()), data.label);
        VectorWritable vec = new VectorWritable();
        vec.set(nv);

        writer.append(new Text(nv.getName()), vec);
    }


    SequenceFile.Writer writer = null;
    File writerFile = null;

    /**
     * We stream data into a file, so we open it. Later we close it.
     *
     * @param file
     */
    public void _open(File file) throws IOException {
        writerFile = file;

        Configuration config = initConfig();
        FileSystem fs = initFileSystem();

        Path path = new Path(file.getAbsolutePath());
        LOG.debug("opening bucket for write path={}", path);

        // write a SequenceFile form a Vector
        writer = new SequenceFile.Writer(fs, config, path, Text.class, VectorWritable.class);
    }

    public void _open(String s) throws IOException {
        File f = new File( s );
        _open( f );
    }

    public void close() throws IOException {
        if ( writer != null ) {
            writer.close();
            LOG.debug("closed bucket writer file={}", writerFile.getAbsolutePath());
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

        Path path = new Path(f.getAbsolutePath());

        // read NamedVectors from a SequenceFile 
        try (SequenceFile.Reader reader = new SequenceFile.Reader(fs, path, config)) {

            LOG.debug("loading local bucket file={} codec={} key={} value={} limit={} inMemory={}", f.getAbsolutePath(),
                    reader.getCompressionCodec(), reader.getKeyClassName(), reader.getValueClassName(), LIMIT, inMEM);

            int i = 0;
            while (i < LIMIT) {
                Text key = new Text();

                VectorWritable vec = new VectorWritable();
                if (!reader.next(key)) {
                    break;
                }
                i++;

                String tsKey = key.toString();

                reader.getCurrentValue(vec);

                TimeSeriesObject mr = new TimeSeriesObject();
                mr.setDescription(i + " ) " + f.getName() + "_[" + key.toString() + "]");
                mr.setLabel(i + " " + key.toString());

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
            LOG.debug("loaded local bucket file={} records={}", f.getAbsolutePath(), i);
        }
    }

    public void createBucketFromFilesViaWordLength(File[] folder, String s, String experiment) throws IOException {

        int ANZ = folder.length;

        Configuration config = initConfig();
        FileSystem fs = initFileSystem();

        Path path = new Path(s + "/" + experiment + "/bucket_wordLength_.tsb.vec.seq");

        // write a SequenceFile form a Vector
        try (SequenceFile.Writer writer = new SequenceFile.Writer(fs, config, path, Text.class, VectorWritable.class)) {
            for (int i = 0; i < ANZ; i++) {

                TimeSeriesObject mr = TSGenerator.getWordLengthSeries(folder[i]);
                append(writer, TSData.convertMessreihe(mr));
            }
        }
        LOG.debug("wrote word length bucket path={} files={}", path, ANZ);
    }

    public void createBucketFromGrayImageFile(File imageFile, String s, String experiment) throws IOException {

        Configuration config = initConfig();
        FileSystem fs = initFileSystem();

        Path path = new Path(s + "/" + experiment + "/bucket_grayImage.tsb.vec.seq");

        TimeSeriesObject[] mrS = TSGenerator.getGrayImageSeries(imageFile);

        // write a SequenceFile form a Vector
        try (SequenceFile.Writer writer = new SequenceFile.Writer(fs, config, path, Text.class, VectorWritable.class)) {
            for (TimeSeriesObject mr : mrS) {
                append(writer, TSData.convertMessreihe(mr));
            }
        }
        LOG.debug("wrote gray image bucket path={} image={} rows={}", path, imageFile, mrS.length);
    }

    public void createBucketFromGrayImageFile(File[] folder, String baseOut, String experiment) {
        throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
    }

    public void createBucketFromFilesViaWordLengthRecursive(File[] folder, String s, String experiment) throws IOException {

        int ANZ = folder.length;

        Configuration config = initConfig();
        FileSystem fs = initFileSystem();

        Path path = new Path(s + "/" + experiment + "/bucket_wordLength_.tsb.vec.seq");

        // write a SequenceFile form a Vector
        try (SequenceFile.Writer writer = new SequenceFile.Writer(fs, config, path, Text.class, VectorWritable.class)) {
            for (int i = 0; i < ANZ; i++) {

                File[] FOLDERS = folder[i].listFiles();

                for (File f : FOLDERS) {

                    TimeSeriesObject mr = TSGenerator.getWordLengthSeries(f);
                    append(writer, TSData.convertMessreihe(mr));
                }
            }
        }
        LOG.debug("wrote word length bucket path={} folders={}", path, ANZ);
    }

    public void createBucketFromFilesViaTFIDFRecursive(File[] folder, String s, String experiment) throws IOException {

        int ANZ = folder.length;

        Configuration config = initConfig();
        FileSystem fs = initFileSystem();

        Path path = new Path(s + "/" + experiment + "/bucket_tfidf_.tsb.vec.seq");

        Hashtable<String, Integer> idfs = new Hashtable<String, Integer>();

        for (int i = 0; i < ANZ; i++) {

            File[] FOLDERS = folder[i].listFiles();

            for (File f : FOLDERS) {

                storeIDFCOntribs(f, idfs);

            }
        }

        // write a SequenceFile form a Vector
        try (SequenceFile.Writer writer = new SequenceFile.Writer(fs, config, path, Text.class, VectorWritable.class)) {
            for (int i = 0; i < ANZ; i++) {

                File[] FOLDERS = folder[i].listFiles();

                for (File f : FOLDERS) {

                    TimeSeriesObject mr = TSGenerator.getTFIDFSeries( f, idfs , getWC(f), getText(f) );
                    append(writer, TSData.convertMessreihe(mr));
                }
            }
        }
        LOG.debug("wrote tf-idf bucket path={} folders={} words={}", path, ANZ, idfs.size());
    }

    private static void append(SequenceFile.Writer writer, TSData data) throws IOException {
        NamedVector nv = new NamedVector(new DenseVector(data.getData()), data.label);

        VectorWritable vec = new VectorWritable();
        vec.set(nv);

        writer.append(new Text(nv.getName()), vec);
        LOG.debug("appended series name={}", nv.getName());
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
        
        try (BufferedReader br = new BufferedReader(new FileReader(f))) {
            while (br.ready()) {
                String l = br.readLine();
                sb.append(l + "\n");
            }
        }

        return sb.toString();
    }

    private Hashtable<String, Integer> getWC(File f) throws FileNotFoundException, IOException {

        Hashtable<String, Integer> wc = new Hashtable<String, Integer>();

        try (BufferedReader br = new BufferedReader(new FileReader(f))) {
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
        }

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

        if (inMEM) {
            bucketData.add(mr);
        }

    }

}
