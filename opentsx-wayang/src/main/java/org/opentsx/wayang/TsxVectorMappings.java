package org.opentsx.wayang;

import org.apache.wayang.core.mapping.Mapping;
import org.apache.wayang.core.mapping.OperatorPattern;
import org.apache.wayang.core.mapping.PlanTransformation;
import org.apache.wayang.core.mapping.ReplacementSubplanFactory;
import org.apache.wayang.core.mapping.SubplanPattern;
import org.apache.wayang.java.platform.JavaPlatform;
import org.opentsx.retrieval.vector.VectorAligner;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Maps {@link TsxAlignVector}, {@link TsxPair} and {@link TsxVectorSink} onto their Java execution operators. */
final class TsxVectorMappings {

    private TsxVectorMappings() {
    }

    static final class AlignVectorMapping implements Mapping {
        @Override
        public Collection<PlanTransformation> getTransformations() {
            TsxAlignVector prototype = new TsxAlignVector("", "", "", "", new VectorAligner.Params(Duration.ofSeconds(60),
                    "series", VectorAligner.Agg.MEAN, false, Duration.ofSeconds(60), false),
                    Map.of("x", Set.of("x")), 0, false, new TsxState(Map.of()));
            return List.of(new PlanTransformation(
                    SubplanPattern.createSingleton(new OperatorPattern<>("align", prototype, false)),
                    new ReplacementSubplanFactory.OfSingleOperators<TsxAlignVector>(
                            (matched, epoch) -> new JavaTsxAlignVector(matched).at(epoch)),
                    JavaPlatform.getInstance()));
        }
    }

    static final class PairMapping implements Mapping {
        @Override
        public Collection<PlanTransformation> getTransformations() {
            TsxPair prototype = new TsxPair("", "pair.xcorr", Map.of(), Set.of(), Set.of(), "", new TsxState(Map.of()));
            return List.of(new PlanTransformation(
                    SubplanPattern.createSingleton(new OperatorPattern<>("pair", prototype, false)),
                    new ReplacementSubplanFactory.OfSingleOperators<TsxPair>(
                            (matched, epoch) -> new JavaTsxPair(matched).at(epoch)),
                    JavaPlatform.getInstance()));
        }
    }

    static final class VectorSinkMapping implements Mapping {
        @Override
        public Collection<PlanTransformation> getTransformations() {
            return List.of(new PlanTransformation(
                    SubplanPattern.createSingleton(new OperatorPattern<>("sink", new TsxVectorSink("", ""), false)),
                    new ReplacementSubplanFactory.OfSingleOperators<TsxVectorSink>(
                            (matched, epoch) -> new JavaTsxVectorSink(matched).at(epoch)),
                    JavaPlatform.getInstance()));
        }
    }
}
