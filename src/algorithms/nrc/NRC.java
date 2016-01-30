/**
 * 
 * This project conains some implementations from the NRC book.
 * 
 *    "Numerical Recipies in C" is available here: 
 *    http://www2.units.it/ipl/students_area/imm2/files/Numerical_Recipes.pdf
 *      
 * 
 */
package algorithms.nrc;

import algorithms.nrc.distributions.KSTest;
import java.io.FileWriter;
import java.io.IOException;

/**
 *
 * @author kamir
 */
public class NRC {

    /**
     * @param args the command line arguments
     */
    public static void main(String[] args) throws IOException {
       
        /**
         * We test, if distributions are equal using the
         * Kolmogorow-Smirnow test as on page 620 in the 
         * NRC book.
         */
        
        // 
        // Just a simple test, nothing is stored.
        // 
//        KSTest.runWithSampleData(1000, -100.0, 2.5);
//        KSTest.runWithSampleData(1000, 2.5, 100);
//        KSTest.runWithSampleData(1000, -100.0, 100.0);
  
        //
        // Using a FileWriter, allows us to cross-check with 
        // other algorithms, such as our AWK based script
        // and routines in R and Apache Spark.
        //         
//        KSTest.runWithSampleData(1000, -100.0, 2.5, "range_A");
//        KSTest.runWithSampleData(1000, 2.5, 100, "range_B");
        
        KSTest.delta_mu = 0.0;
        KSTest.delta_sigma = 0.0;
        
        KSTest.runWithSampleData(1000, -100.0, 100.0, "all", "run_1" );
        
    }

    
}
