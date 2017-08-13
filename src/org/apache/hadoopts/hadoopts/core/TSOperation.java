package org.apache.hadoopts.hadoopts.core;

import org.apache.hadoopts.data.series.TimeSeriesObject;

import java.io.FileWriter;

/**
 *
 * @author kamir
 */
public abstract class TSOperation {
    
    abstract public String processReihe( TimeSeriesObject reihe ) throws Exception;
    abstract public TimeSeriesObject processReihe(FileWriter fw, TimeSeriesObject reihe, FileWriter exploder ) throws Exception;
    abstract public TimeSeriesObject processReihe(FileWriter fw, TimeSeriesObject reihe, Object para, FileWriter exploder ) throws Exception;
    
}
