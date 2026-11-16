package org.opentsx.wayang;

import org.apache.wayang.core.api.Configuration;
import org.apache.wayang.core.mapping.Mapping;
import org.apache.wayang.core.optimizer.channels.ChannelConversion;
import org.apache.wayang.core.platform.Platform;
import org.apache.wayang.core.plugin.Plugin;
import org.apache.wayang.core.util.ReflectionUtils;
import org.apache.wayang.java.channels.ChannelConversions;
import org.apache.wayang.java.mapping.CollectionSourceMapping;
import org.apache.wayang.java.mapping.LocalCallbackSinkMapping;
import org.apache.wayang.java.mapping.MapMapping;
import org.apache.wayang.java.mapping.UnionAllMapping;
import org.apache.wayang.java.platform.JavaPlatform;

import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * The Java platform of Wayang with only the mappings OpenTSx uses. {@code Java.basicPlugin()} also registers the
 * Amazon S3, Google Cloud Storage and Azure mappings, which load those SDKs; OpenTSx excludes them (D-T9 in
 * EVOLUTION/TASK-020-wayang-ts-operators.md).
 */
public final class TsxJavaPlugin implements Plugin {

    static final String DEFAULTS = "wayang-tsx-defaults.properties";

    @Override
    public Collection<Mapping> getMappings() {
        return List.of(new CollectionSourceMapping(), new MapMapping(), new LocalCallbackSinkMapping(),
                new TsxTopicSourceMapping(), new TsxTopicSinkMapping(), new TsxOperatorMappings.SeriesChainMapping(),
                new TsxOperatorMappings.StatsWindowMapping(), new UnionAllMapping(),
                new TsxVectorMappings.AlignVectorMapping(), new TsxVectorMappings.VectorSinkMapping());
    }

    @Override
    public Collection<ChannelConversion> getChannelConversions() {
        return ChannelConversions.ALL;
    }

    @Override
    public Collection<Platform> getRequiredPlatforms() {
        return Set.of(JavaPlatform.getInstance());
    }

    @Override
    public void setProperties(Configuration configuration) {
        configuration.load(ReflectionUtils.loadResource(DEFAULTS));
    }
}
