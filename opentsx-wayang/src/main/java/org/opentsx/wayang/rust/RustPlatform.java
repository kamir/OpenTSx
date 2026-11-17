package org.opentsx.wayang.rust;

import org.apache.wayang.core.api.Configuration;
import org.apache.wayang.core.optimizer.costs.LoadProfileToTimeConverter;
import org.apache.wayang.core.optimizer.costs.LoadToTimeConverter;
import org.apache.wayang.core.optimizer.costs.TimeToCostConverter;
import org.apache.wayang.core.platform.Executor;
import org.apache.wayang.core.platform.Platform;
import org.apache.wayang.core.util.ReflectionUtils;

public final class RustPlatform extends Platform {
    static final String DEFAULTS = "wayang-rust-defaults.properties";
    private static final RustPlatform INSTANCE = new RustPlatform();

    private RustPlatform() {
        super("OpenTSx Rust", "rust");
    }

    public static RustPlatform getInstance() {
        return INSTANCE;
    }

    @Override
    protected void configureDefaults(Configuration configuration) {
        configuration.load(ReflectionUtils.loadResource(DEFAULTS));
    }

    @Override
    public Executor.Factory getExecutorFactory() {
        return RustExecutor::new;
    }

    @Override
    public LoadProfileToTimeConverter createLoadProfileToTimeConverter(Configuration configuration) {
        return LoadProfileToTimeConverter.createDefault(
                LoadToTimeConverter.createLinearCoverter(configuration.getDoubleProperty("wayang.rust.cpu.ms-per-unit")),
                LoadToTimeConverter.createLinearCoverter(0), LoadToTimeConverter.createLinearCoverter(0),
                (cpu, disk, network) -> cpu.plus(disk).plus(network));
    }

    @Override
    public TimeToCostConverter createTimeToCostConverter(Configuration configuration) {
        return new TimeToCostConverter(configuration.getDoubleProperty("wayang.rust.costs.fix"),
                configuration.getDoubleProperty("wayang.rust.costs.per-ms"));
    }
}
