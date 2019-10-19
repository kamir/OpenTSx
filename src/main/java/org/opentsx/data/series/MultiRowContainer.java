/**
 *
 * A MultiRowContainer is a data structure to handle sets of TimeSeriesObjects in labeled groups.
 * 
 * E.g., the MultiRowDFATool works with this container.
 *
 * Such a data structure contains a subset of a TSBucket.
 *
 * Typicall, one would put all timeseries from a partition into this container in order to process
 * them locally in a Spark Executor or even to push the data up to a GPU for multi row processing.
 *
 */

package org.opentsx.data.series;

import java.util.Hashtable;
import java.util.Vector;

public class MultiRowContainer {

    Vector<String> labels = new Vector<String>();
    Hashtable<String, Vector<TimeSeriesObject>> reihenHash = new Hashtable<String, Vector<TimeSeriesObject>>();

    public void addNewRow(TimeSeriesObject mr, String label ) {
        if ( !labels.contains(label) ) labels.add(label);
        Vector<TimeSeriesObject> reihen = reihenHash.get(label);
        if ( reihen == null ) { 
            reihen = new Vector<TimeSeriesObject>();
            reihenHash.put(label, reihen);
        }
        reihen.add(mr);
    };

    public void addNewRows(Vector<TimeSeriesObject> v, String label ) {
       for( TimeSeriesObject mr : v ) {
          addNewRow(mr, label);
       }
    };

    public Iterable<String> getLabels() {
        return labels;
    }

    public Hashtable<String, Vector<TimeSeriesObject>> getRowSets() {
        return reihenHash;
    }

}
