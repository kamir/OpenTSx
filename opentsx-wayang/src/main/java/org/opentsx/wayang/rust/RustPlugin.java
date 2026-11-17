package org.opentsx.wayang.rust;

import org.apache.wayang.core.api.Configuration;
import org.apache.wayang.core.mapping.Mapping;
import org.apache.wayang.core.optimizer.channels.ChannelConversion;
import org.apache.wayang.core.platform.Platform;
import org.apache.wayang.core.plugin.Plugin;
import org.apache.wayang.core.util.ReflectionUtils;

import java.util.Collection;
import java.util.List;
import java.util.Set;

public final class RustPlugin implements Plugin {
    @Override
    public Collection<Mapping> getMappings() {
        return List.of(new RustMappings());
    }

    @Override
    public Collection<ChannelConversion> getChannelConversions() {
        return List.of();
    }

    @Override
    public Collection<Platform> getRequiredPlatforms() {
        return Set.of(RustPlatform.getInstance());
    }

    @Override
    public void setProperties(Configuration configuration) {
        configuration.load(ReflectionUtils.loadResource(RustPlatform.DEFAULTS));
    }
}
