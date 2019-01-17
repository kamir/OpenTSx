package tswb.tsa.examples;

/**
 *
 * Load and show some Bucket Content ...
 *
 **/

import org.apache.hadoopts.app.experimental.SimpleBucketTool;

import java.io.IOException;

/**
 *
 * @author kamir
 */
public class ExampleBucketViewer {

    /**
     * @param args the command line arguments
     */
    public static void main(String[] args) throws IOException {

        System.out.println(">>> Show the dummy time series bucket ... " );

        /**
         * needed for working with Hadoop XML-Properties files.
         */
        System.setProperty("javax.xml.parsers.DocumentBuilderFactory", "com.sun.org.apache.xerces.internal.jaxp.DocumentBuilderFactoryImpl");

        /**
         * Default setup of the tool.
         */
        SimpleBucketTool.LIMIT = 25;
        SimpleBucketTool.toolname = "hadoopts.analysis.SingleTSToolSum";
        SimpleBucketTool.procm = SimpleBucketTool.procm_RECORD_STREAM;

        /**
         *   Which folder contains the data
         */
        String folder = "sample/";

        /**
         * Define our own arguments array ...
         */
        String[] a = new String[3];
        a[0] = folder;
        a[2] = ""+SimpleBucketTool.procm_RECORD_STREAM;

        /**
         *  2 Test-Options  (rows must exist in folder "sample")
         *     - alpha=0.5 (white noise)
         *     - LRC beta=2.9
         */

//        a[1] = "alpha_0.5";
//        SimpleBucketTool.main( a );
//        System.out.println("Done (1)\n\n");

//        a[1] = "LRC_beta_2.9";
//        SimpleBucketTool.main( a );
//        System.out.println("Done (2)\n\n");

        a[0] = "tsbucket/ex1";
        a[1] = "";
        a[2] = ""+SimpleBucketTool.procm_BULK;

        System.out.println("Go (1)\n\n");

        SimpleBucketTool.main( a );

        System.out.println("Done (3)\n\n");


    }
}