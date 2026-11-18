package org.opentsx.wayang;

import org.apache.wayang.core.api.Configuration;
import org.apache.wayang.core.optimizer.costs.LoadProfileToTimeConverter;
import org.apache.wayang.core.optimizer.costs.LoadToTimeConverter;
import org.apache.wayang.core.optimizer.costs.TimeToCostConverter;
import org.apache.wayang.core.platform.Executor;
import org.apache.wayang.core.platform.Platform;
import org.apache.wayang.core.util.ReflectionUtils;

public final class TsxSitePlatform extends Platform {
    static final String DEFAULTS = "wayang-tsx-site-defaults.properties";
    private static final TsxSitePlatform INSTANCE = new TsxSitePlatform();

    private TsxSitePlatform() {
        super("OpenTSx JVM sites", "tsx-site");
    }

    public static TsxSitePlatform getInstance() {
        return INSTANCE;
    }

    @Override
    protected void configureDefaults(Configuration configuration) {
        configuration.load(ReflectionUtils.loadResource(DEFAULTS));
    }

    @Override
    public Executor.Factory getExecutorFactory() {
        return TsxSiteExecutor::new;
    }

    @Override
    public LoadProfileToTimeConverter createLoadProfileToTimeConverter(Configuration configuration) {
        return LoadProfileToTimeConverter.createDefault(
                LoadToTimeConverter.createLinearCoverter(configuration.getDoubleProperty("wayang.tsx-site.cpu.ms-per-unit")),
                LoadToTimeConverter.createLinearCoverter(0), LoadToTimeConverter.createLinearCoverter(0),
                (cpu, disk, network) -> cpu.plus(disk).plus(network));
    }

    @Override
    public TimeToCostConverter createTimeToCostConverter(Configuration configuration) {
        return new TimeToCostConverter(configuration.getDoubleProperty("wayang.tsx-site.costs.fix"),
                configuration.getDoubleProperty("wayang.tsx-site.costs.per-ms"));
    }
}
