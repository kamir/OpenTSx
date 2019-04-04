/*
 *  A simple factory ...
 */
package org.apache.hadoopts.data.generator;

import org.apache.hadoopts.app.utils.NetCat4JDataStreamRecorder;

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
