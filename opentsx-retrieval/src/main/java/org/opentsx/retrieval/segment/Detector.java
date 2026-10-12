package org.opentsx.retrieval.segment;

import java.util.List;

/**
 * Incremental segment detector over one series. It sees every sample once, in time order, and reports a cut only
 * when no later sample can change it - so feeding a whole series at once (batch) and feeding it episode by episode
 * (streaming) gives the same cuts by construction.
 */
interface Detector {

    /** Sample {@code i} (absolute index) was appended to {@code buf}; returns cuts that became final. */
    List<Cut> onSample(SampleBuffer buf, long i);

    /** End of data: cuts that are still open, decided with the data available. */
    List<Cut> flush(SampleBuffer buf);

    /** Smallest absolute index the detector may still need; older samples can be dropped. */
    long earliestNeeded(SampleBuffer buf);
}
