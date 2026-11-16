package org.opentsx.wayang;

import org.apache.wayang.core.mapping.Mapping;
import org.apache.wayang.core.mapping.OperatorPattern;
import org.apache.wayang.core.mapping.PlanTransformation;
import org.apache.wayang.core.mapping.ReplacementSubplanFactory;
import org.apache.wayang.core.mapping.SubplanPattern;
import org.apache.wayang.java.platform.JavaPlatform;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Maps {@link TsxTopicSource} onto {@link JavaTsxTopicSource}. */
public final class TsxTopicSourceMapping implements Mapping {

    @Override
    public Collection<PlanTransformation> getTransformations() {
        TsxTopicSource prototype = new TsxTopicSource(new TsxRead("", "", Map.of(), Set.of(), "", 0));
        return List.of(new PlanTransformation(
                SubplanPattern.createSingleton(new OperatorPattern<>("source", prototype, false)),
                new ReplacementSubplanFactory.OfSingleOperators<TsxTopicSource>(
                        (matched, epoch) -> new JavaTsxTopicSource(matched).at(epoch)),
                JavaPlatform.getInstance()));
    }
}
