package org.apache.hadoopts.data.generator;

/**
 *
 * @author kamir
 */
public class Generator implements Runnable {
    
    @Override
    public void run() {
        System.out.println( ">>> START GENERATOR: " + this.getClass().getCanonicalName() );
    } // 
    
}
