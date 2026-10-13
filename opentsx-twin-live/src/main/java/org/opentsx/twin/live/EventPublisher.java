package org.opentsx.twin.live;

import org.opentsx.twin.Mode;
import org.opentsx.twin.RunResult;
import org.opentsx.twin.TwinEvent;
import org.opentsx.twin.TwinPlan;
import org.opentsx.twin.TwinRunner;
import org.opentsx.twin.studio.StudioScenario;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Writes the events of a twin run to its event topic, in the order of the run. With a duration the run is played in
 * that time: every event is published when its moment in the (compressed) run has come, so that a reader of the
 * topic sees the run unfold. Without one, all events are published at once. The topic is created before the first
 * event, so a reader can open it while the run has not reported anything yet.
 */
public final class EventPublisher {

    /** Waits; replaceable in tests. */
    public interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    private final Topics topics;
    private final Sleeper sleeper;

    public EventPublisher(Topics topics) {
        this(topics, Thread::sleep);
    }

    public EventPublisher(Topics topics, Sleeper sleeper) {
        this.topics = topics;
        this.sleeper = sleeper;
    }

    /** The reference run of a scenario, as the Studio export computes it. */
    public static RunResult referenceRun(StudioScenario s) {
        return new TwinRunner(s.plan()).run(
                new TwinRunner.RunSpec(Mode.SHADOW, s.observed(), s.reference(), null, 0, Map.of(), null));
    }

    /**
     * Publishes the events of {@code run}; {@code duration} is the time the whole run takes on the wall clock, or
     * {@code null} for all at once. Returns the topic.
     */
    public String publish(TwinPlan plan, RunResult run, Duration duration) throws InterruptedException {
        String topic = EventTopic.name(plan.twin(), run.runId());
        topics.create(topic);
        List<TwinEvent> events = run.events().stream().sorted(Comparator.comparingLong(TwinEvent::tMicros)).toList();
        long spanMicros = plan.baseStepMicros() * plan.steps();
        long slept = 0;
        for (TwinEvent e : events) {
            if (duration != null && spanMicros > 0) {
                double share = Math.min(1.0, Math.max(0.0, (e.tMicros() - plan.startMicros()) / (double) spanMicros));
                long due = (long) (share * duration.toMillis());
                long wait = due - slept;
                if (wait > 0) {
                    sleeper.sleep(wait);
                    slept += wait;
                }
            }
            topics.append(topic, e.atom(), EventTopic.value(run.runId(), e), EventTopic.SCHEMA);
        }
        if (duration != null) {
            long rest = duration.toMillis() - slept;
            if (rest > 0) {
                sleeper.sleep(rest);
            }
        }
        return topic;
    }
}
