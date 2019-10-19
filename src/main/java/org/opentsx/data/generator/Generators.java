/*
 *  A simple factory ...
 */
package org.opentsx.data.generator;

import org.opentsx.utils.NetCat4JDataStreamRecorder;

/**
 *
 * @author kamir
 */
public class Generators {

    static Generator getNetCatGenerator(int port) {
        NetCat4JDataStreamRecorder ncg = new NetCat4JDataStreamRecorder( port );
        return ncg;
    }
    
}
