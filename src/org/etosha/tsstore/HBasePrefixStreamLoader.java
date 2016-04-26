package org.etosha.tsstore;
 

import org.apache.hadoopts.data.series.MRT;
import org.apache.hadoopts.data.series.Messreihe;
import org.apache.hadoopts.hadoopts.core.TSBucket;
import java.io.IOException;
import java.util.Arrays;
import java.util.Date;
import java.util.Iterator;
import java.util.Vector;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.client.Get;
import org.apache.hadoop.hbase.client.HTable;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.ResultScanner;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.filter.Filter;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.util.Pair;

import org.apache.hadoop.hbase.filter.PrefixFilter;

/**
 *
 * @author kamir
 */
public class HBasePrefixStreamLoader {

    public static TSBucket bucket;
     
    public static void forkStream( 
            TSBucket b,
            int CNid,
            String grouplabel,
            Vector<String> nodes,
            String prefix
        ) throws IOException {
     
        
//        int max = 2000;
        boolean stop = false;
        
        bucket = b;

        // You need a configuration object to tell the client where to connect.
        // When you create a HBaseConfiguration, it reads in whatever you've set
        // into your hbase-site.xml and in hbase-default.xml, as long as these can
        // be found on the CLASSPATH
        Configuration config = HBaseConfiguration.create();

        config.set("hbase.zookeeper.quorum", "192.168.3.171");  // Here we are running zookeeper locally
        config.set("hbase.zookeeper.property.clientPort", "22181");  // Here we are running zookeeper locally

        String tabName = "wikinodes.mk";

        // This instantiates an HTable object that connects you to
        // the "myLittleHBaseTable" table.
        HTable table = new HTable(config, tabName);

        PrefixFilter pr = new PrefixFilter(prefix.getBytes());

        // This instantiates an HTable object that connects you to
        // the "myLittleHBaseTable" table.
        HTable tableEdits = new HTable(config, tabName);

        // Sometimes, you won't know the row you're looking for. In this case, you
        // use a Scanner. This will give you cursor-like interface to the contents
        // of the table.  To set up a Scanner, do like you did above making a Put
        // and a Get, create a Scan.  Adorn it with column names, etc.
        System.out.println("\nEDITS.TS SCAN with prefix: " + prefix);

        Scan sFilter = new Scan();
//        sFilter.setBatch(1000);
//        sFilter.setCaching(1000);

        sFilter.setFilter(pr);

        ResultScanner scannerEdits = table.getScanner(sFilter);

        System.out.println(new Date(System.currentTimeMillis()));

        Iterator<Result> iterator = scannerEdits.iterator();
        
        while( iterator.hasNext() ) {

            Result resultRow = iterator.next();

            countRow();
            
            byte[] value = resultRow.getValue(Bytes.toBytes("edit.ts"), "raw".getBytes() );
        
            // If we convert the value bytes, we should get back 'Some Value', the
            // value we inserted at this location.
            String valueStr = Bytes.toString(value);

            Messreihe mr = MRT.deserializeFromXMLString(valueStr);

            String newID = CNid + "." + grouplabel;
            
            System.out.println( rowCount + "  #===>GET: " + mr.getIdentifier() + " => " + newID );
            
            if ( nodes.contains( prefix + "___" + mr.getIdentifier() ) ) {
                b.putMessreihe(mr, newID );
            }
            else {
                System.out.println( ">>> NOT IN GROUP: " + mr.getIdentifier() );
            }
            
//            if ( rowCount == max ) stop = true;

        }

        System.out.println("Found : " + rowCount + " rows for the prefix: " + prefix);
        System.out.println(new Date(System.currentTimeMillis()));

    }

    static int rowCount = 0;

    private static void countRow() {
        rowCount++;
        if (rowCount % 1000 == 0) {
            System.out.println("ROW-Count: " + rowCount + " ");
        }
    }

}
