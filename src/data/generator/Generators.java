/*
 *  A simple factory ...
 */
package data.generator;

import app.utils.NetCat4J;

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
