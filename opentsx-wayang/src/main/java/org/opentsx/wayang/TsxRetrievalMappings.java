package org.opentsx.wayang;

import org.apache.wayang.core.mapping.Mapping;
import org.apache.wayang.core.mapping.OperatorPattern;
import org.apache.wayang.core.mapping.PlanTransformation;
import org.apache.wayang.core.mapping.ReplacementSubplanFactory;
import org.apache.wayang.core.mapping.SubplanPattern;
import org.apache.wayang.java.platform.JavaPlatform;
import org.opentsx.retrieval.segment.Segmenter;
import org.opentsx.stats.DataQuality;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;

final class TsxRetrievalMappings implements Mapping {

    @Override
    public Collection<PlanTransformation> getTransformations() {
        TsxSegment segment = new TsxSegment("", Segmenter.threshold(Segmenter.Direction.ABOVE, 1, 0,
                Duration.ZERO, Duration.ZERO, Duration.ZERO, Duration.ZERO), new TsxState(Map.of()));
        TsxSaxWord sax = new TsxSaxWord(1, 2, "0".repeat(64));
        TsxDqResult dq = new TsxDqResult("job", "site", "0".repeat(64),
                DataQuality.Expectation.of(null, null, null), Set.of(DataQuality.Check.values()), OptionalLong.empty());
        return List.of(
                new PlanTransformation(SubplanPattern.createSingleton(new OperatorPattern<>("segment", segment, false)),
                        new ReplacementSubplanFactory.OfSingleOperators<TsxSegment>(
                                (matched, epoch) -> new JavaTsxSegment(matched).at(epoch)), JavaPlatform.getInstance()),
                new PlanTransformation(SubplanPattern.createSingleton(new OperatorPattern<>("sax", sax, false)),
                        new ReplacementSubplanFactory.OfSingleOperators<TsxSaxWord>(
                                (matched, epoch) -> new JavaTsxSaxWord(matched).at(epoch)), JavaPlatform.getInstance()),
                new PlanTransformation(SubplanPattern.createSingleton(new OperatorPattern<>("dq", dq, false)),
                        new ReplacementSubplanFactory.OfSingleOperators<TsxDqResult>(
                                (matched, epoch) -> new JavaTsxDqResult(matched).at(epoch)), JavaPlatform.getInstance()));
    }
}
