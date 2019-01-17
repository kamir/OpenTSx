/*
 *  A simple factory ...
 */
package org.apache.hadoopts.data.generator;

import org.apache.hadoopts.app.utils.NetCat4J;

/**
 *
 * @author kamir
 */
public class Generators {

    static Generator getNetCatGenerator(int port) {
        NetCat4J ncg = new NetCat4J( port );
        return ncg;
    }
    
}
