package org.opentsx.data.loader.util;

/**
 *
 * Preprocesses one input line of a file, for example to fill missing
 * fields or replace characters.
 *
 */


/**
 *
 * @author kamir
 */
public class LineFilter {

    public static String doFilterLine(String line) {
        
        while (line.contains(",,")) {
            line = line.replaceAll(",,", ",0,");
        }
        
        line = line.replaceAll( "," , " " );
        
        return line;
    }
}
