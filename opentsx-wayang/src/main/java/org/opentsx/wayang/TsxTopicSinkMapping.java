package org.opentsx.wayang;

import org.apache.wayang.core.mapping.Mapping;
import org.apache.wayang.core.mapping.OperatorPattern;
import org.apache.wayang.core.mapping.PlanTransformation;
import org.apache.wayang.core.mapping.ReplacementSubplanFactory;
import org.apache.wayang.core.mapping.SubplanPattern;
import org.apache.wayang.java.platform.JavaPlatform;

import java.util.Collection;
import java.util.List;

/** Maps {@link TsxTopicSink} onto {@link JavaTsxTopicSink}. */
public final class TsxTopicSinkMapping implements Mapping {

    @Override
    public Collection<PlanTransformation> getTransformations() {
        return List.of(new PlanTransformation(
                SubplanPattern.createSingleton(new OperatorPattern<>("sink", new TsxTopicSink("", ""), false)),
                new ReplacementSubplanFactory.OfSingleOperators<TsxTopicSink>(
                        (matched, epoch) -> new JavaTsxTopicSink(matched).at(epoch)),
                JavaPlatform.getInstance()));
    }
}
