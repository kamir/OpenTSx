package org.opentsx.retrieval.segment;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Threshold-to-threshold segments with hysteresis: a segment starts when the value reaches {@code enter} and ends
 * when it crosses back over {@code exit}; it is extended by {@code pre} before and {@code post} after. Phases shorter
 * than {@code minDuration} are ignored; phases longer than {@code maxDuration} are cut at maxDuration (the phase must
 * end before a new segment can start). NaN values change nothing.
 */
final class ThresholdDetector implements Detector {

    private enum State { IDLE, ACTIVE, CAPPED }

    private record Pending(long from, long coreEndTs, long anchor) {
    }

    private final boolean above;
    private final double enter;
    private final double exit;
    private final long pre;
    private final long post;
    private final long minDuration;
    private final long maxDuration;
    private State state = State.IDLE;
    private long enterIdx = -1;
    private long fromIdx = -1;
    private final List<Pending> pending = new ArrayList<>();

    ThresholdDetector(boolean above, double enter, double exit, long pre, long post, long minDuration, long maxDuration) {
        this.above = above;
        this.enter = enter;
        this.exit = exit;
        this.pre = pre;
        this.post = post;
        this.minDuration = minDuration;
        this.maxDuration = maxDuration;
    }

    private boolean enters(double v) {
        return above ? v >= enter : v <= enter;
    }

    private boolean exits(double v) {
        return above ? v < exit : v > exit;
    }

    @Override
    public List<Cut> onSample(SampleBuffer buf, long i) {
        double v = buf.v(i);
        long t = buf.ts(i);
        if (!Double.isNaN(v)) {
            switch (state) {
                case IDLE -> {
                    if (enters(v)) {
                        state = State.ACTIVE;
                        enterIdx = i;
                        fromIdx = buf.indexAtOrAfter(t - pre, buf.base());
                    }
                }
                case ACTIVE -> {
                    long active = t - buf.ts(enterIdx);
                    if (exits(v)) {
                        if (active >= minDuration) {
                            pending.add(new Pending(fromIdx, t, enterIdx));
                        }
                        state = State.IDLE;
                    } else if (maxDuration > 0 && active >= maxDuration) {
                        pending.add(new Pending(fromIdx, t, enterIdx));
                        state = State.CAPPED;
                    }
                }
                case CAPPED -> {
                    if (exits(v)) {
                        state = State.IDLE;
                    }
                }
            }
        }
        List<Cut> out = null;
        for (Iterator<Pending> it = pending.iterator(); it.hasNext(); ) {
            Pending p = it.next();
            if (t >= p.coreEndTs() + post) {
                long to = buf.indexAtOrAfter(p.coreEndTs() + post, p.anchor());
                if (out == null) {
                    out = new ArrayList<>(1);
                }
                out.add(new Cut(p.from(), Math.max(to, p.anchor() + 1), p.anchor()));
                it.remove();
            }
        }
        return out == null ? List.of() : out;
    }

    @Override
    public List<Cut> flush(SampleBuffer buf) {
        List<Cut> out = new ArrayList<>();
        for (Pending p : pending) {
            out.add(new Cut(p.from(), buf.end(), p.anchor()));
        }
        pending.clear();
        if (state == State.ACTIVE && !buf.isEmpty() && buf.lastTs() - buf.ts(enterIdx) >= minDuration) {
            out.add(new Cut(fromIdx, buf.end(), enterIdx));
        }
        state = State.IDLE;
        return out;
    }

    @Override
    public long earliestNeeded(SampleBuffer buf) {
        long need = buf.isEmpty() ? buf.end() : buf.indexAtOrAfter(buf.lastTs() - pre, buf.base());
        if (state != State.IDLE) {
            need = Math.min(need, Math.min(fromIdx, enterIdx));
        }
        for (Pending p : pending) {
            need = Math.min(need, p.from());
        }
        return need;
    }
}
