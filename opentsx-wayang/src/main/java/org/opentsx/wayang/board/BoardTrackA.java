package org.opentsx.wayang.board;

import org.opentsx.kafka.KafScaleClients;
import org.opentsx.kafka.TopicLayout;
import org.opentsx.model.OpenTsxAvro;
import org.opentsx.model.XxHash64;
import org.opentsx.model.v2.Episode;
import org.opentsx.wayang.DagToWayang;
import org.opentsx.wayang.TsxCheckpoint;
import org.opentsx.wayang.TsxCompileContext;
import org.opentsx.wayang.TsxIncrement;
import org.opentsx.wayang.TsxStatus;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

/**
 * Track A of one B4.2 condition: this JVM runs the chain's DAG on Wayang in increments over the paced feed until the
 * duration has passed. The measured process is this JVM; start it with an explicit heap ({@code -Xmx}).
 */
public final class BoardTrackA {

    private BoardTrackA() {
    }

    public static long run(String bootstrap, TopicLayout layout, String chain, int series, long seed, Path catalog,
                           Path checkpoint, Duration interval, Duration duration, Path lagFile, BoardEvents events)
            throws Exception {
        String text = BoardChains.dag(chain, layout, new BoardFeed(series, seed).seriesIds());
        DagToWayang compiled = DagToWayang.compile(text, layout, catalog, TsxCompileContext.local());
        TsxIncrement.Plan plan = compiled.incrementPlan(bootstrap);
        TsxIncrement.Config config = new TsxIncrement.Config(bootstrap, "board-" + chain, 1, compiled.specHash(),
                layout.episodes(), Set.of(XxHash64.hex(OpenTsxAvro.fingerprint(Episode.getClassSchema()))), "earliest",
                checkpoint);
        TsxIncrement.Plan observed = (source, state) -> {
            if (source.read().ranges().values().stream().anyMatch(r -> r[1] > r[0])) events.firstFetch();
            return plan.build(source, state);
        };
        long deadline = System.nanoTime() + duration.toNanos();
        String specHash = compiled.specHash();
        try (OutputWatch watch = new OutputWatch(bootstrap, BoardChains.output(chain, layout), events);
             LagWatch lag = lagFile == null ? null : new LagWatch(bootstrap, layout.episodes(), lagFile,
                     begin -> TsxCheckpoint.load(checkpoint, specHash)
                             .map(c -> c.next().values().stream().mapToLong(Long::longValue).sum()).orElse(begin), events);
             TsxStatus status = new TsxStatus(bootstrap, layout.dagStatus())) {
            long records = TsxIncrement.loop(config, observed, status, interval, () -> System.nanoTime() >= deadline);
            events.emit("stopped", Map.of("records", records));
            return records;
        }
    }

    /** Creates the input, output and status topics of the run when they are missing; existing topics stay. */
    public static void provision(String bootstrap, TopicLayout layout, int partitions) {
        try (var admin = KafScaleClients.admin(bootstrap)) {
            layout.provision(admin, partitions, (short) 1);
            layout.provisionDagTopics(admin, (short) 1);
            for (String chain : BoardChains.CHAINS) {
                String topic = BoardChains.output(chain, layout);
                if (!layout.all().contains(topic)) layout.provisionTopic(admin, topic, (short) 1);
            }
        }
    }
}
