package org.apache.hadoopts.hadoopts.core;

import org.apache.hadoopts.data.series.TimeSeriesObject;

import java.io.Writer;

/**
 *
 * @author kamir
 */
public abstract class TSOperation {

    /**
     * Provide the result as a String.
     *
     *
     * @param resultWriter
     * @param reihe
     * @return
     * @throws Exception
     */
    abstract public String processReihe(Writer resultWriter, TimeSeriesObject reihe) throws Exception;

    /**
     * Write Result into a StringWriter or directly into a FileWriter.
     *
     * @param fw
     * @param reihe
     * @param exploder
     * @return
     * @throws Exception
     */
    abstract public TimeSeriesObject processReihe(Writer fw, TimeSeriesObject reihe, Writer exploder ) throws Exception;

    /**
     * Write Result into a StringWriter or directly into a FileWriter.
     * Keep processing parameters.
     * Explode intermediate results.
     *
     * @param fw
     * @param reihe
     * @param para
     * @param exploder
     * @return
     * @throws Exception
     */
    abstract public TimeSeriesObject processReihe(Writer fw, TimeSeriesObject reihe, Object para, Writer exploder ) throws Exception;

    public abstract void init();

    public abstract void finish();

    public abstract String getSymbol();
}
