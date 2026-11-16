package org.opentsx.wayang;

import org.apache.wayang.core.mapping.Mapping;
import org.apache.wayang.core.mapping.OperatorPattern;
import org.apache.wayang.core.mapping.PlanTransformation;
import org.apache.wayang.core.mapping.ReplacementSubplanFactory;
import org.apache.wayang.core.mapping.SubplanPattern;
import org.apache.wayang.java.platform.JavaPlatform;
import org.opentsx.retrieval.clean.SeriesStream;
import org.opentsx.stats.StatsEngine;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/** Maps the OpenTSx analysis operators onto their Java execution operators. */
final class TsxOperatorMappings {

    private TsxOperatorMappings() {
    }

    static final class SeriesChainMapping implements Mapping {
        @Override
        public Collection<PlanTransformation> getTransformations() {
            TsxSeriesChain prototype = new TsxSeriesChain("", List.of(),
                    new SeriesStream.Target("", "", "", "", "", ""), List.of(), new TsxState(Map.of()));
            return List.of(new PlanTransformation(
                    SubplanPattern.createSingleton(new OperatorPattern<>("chain", prototype, false)),
                    new ReplacementSubplanFactory.OfSingleOperators<TsxSeriesChain>(
                            (matched, epoch) -> new JavaTsxSeriesChain(matched).at(epoch)),
                    JavaPlatform.getInstance()));
        }
    }

    static final class StatsWindowMapping implements Mapping {
        @Override
        public Collection<PlanTransformation> getTransformations() {
            TsxStatsWindow prototype = new TsxStatsWindow("", StatsEngine.Config.defaults(), new TsxState(Map.of()));
            return List.of(new PlanTransformation(
                    SubplanPattern.createSingleton(new OperatorPattern<>("stats", prototype, false)),
                    new ReplacementSubplanFactory.OfSingleOperators<TsxStatsWindow>(
                            (matched, epoch) -> new JavaTsxStatsWindow(matched).at(epoch)),
                    JavaPlatform.getInstance()));
        }
    }
}
