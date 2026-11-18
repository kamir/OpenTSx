package org.opentsx.wayang;

import org.apache.wayang.core.api.Configuration;
import org.apache.wayang.core.mapping.Mapping;
import org.apache.wayang.core.mapping.OperatorPattern;
import org.apache.wayang.core.mapping.PlanTransformation;
import org.apache.wayang.core.mapping.ReplacementSubplanFactory;
import org.apache.wayang.core.mapping.SubplanPattern;
import org.apache.wayang.core.optimizer.channels.ChannelConversion;
import org.apache.wayang.core.optimizer.channels.DefaultChannelConversion;
import org.apache.wayang.core.platform.Platform;
import org.apache.wayang.core.plugin.Plugin;
import org.apache.wayang.core.util.ReflectionUtils;
import org.apache.wayang.java.channels.CollectionChannel;

import java.util.Collection;
import java.util.List;
import java.util.Set;

public final class TsxSitePlugin implements Plugin {
    @Override
    public Collection<Mapping> getMappings() {
        return List.of(() -> List.of(new PlanTransformation(
                SubplanPattern.createSingleton(new OperatorPattern<>("sites", new TsxSiteStage(0, List::of), false)),
                new ReplacementSubplanFactory.OfSingleOperators<TsxSiteStage>(
                        (matched, epoch) -> new TsxSiteExecution(matched).at(epoch)), TsxSitePlatform.getInstance())));
    }

    @Override
    public Collection<ChannelConversion> getChannelConversions() {
        return List.of(new DefaultChannelConversion(AggregateChannel.DESCRIPTOR, CollectionChannel.DESCRIPTOR,
                JavaAggregateRead::new));
    }

    @Override
    public Collection<Platform> getRequiredPlatforms() {
        return Set.of(TsxSitePlatform.getInstance());
    }

    @Override
    public void setProperties(Configuration configuration) {
        configuration.load(ReflectionUtils.loadResource(TsxSitePlatform.DEFAULTS));
    }
}
