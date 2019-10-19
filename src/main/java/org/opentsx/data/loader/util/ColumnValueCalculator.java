/*
 *
 */
package org.opentsx.data.loader.util;

/**
 *
 * @author kamir
 */
public interface ColumnValueCalculator {
    
    public double getValue( int mode, String[] line );
    public double calcLinkStrength( String[] line ); 
    public String getName();
 
    
}
