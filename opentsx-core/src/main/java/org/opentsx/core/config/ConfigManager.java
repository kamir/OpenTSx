package org.opentsx.core.config;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Properties;

public final class ConfigManager {

    private static final ConfigManager INSTANCE = new ConfigManager();

    private final Properties properties = new Properties();

    private ConfigManager() {
        loadDefaults();
        loadProfileOverrides();
    }

    public static ConfigManager getInstance() {
        return INSTANCE;
    }

    public String getString(String key, String defaultValue) {
        String value = getOverrideValue(key);
        if (value != null) {
            return value;
        }
        value = properties.getProperty(key);
        return value != null ? value : defaultValue;
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        String value = getString(key, null);
        if (value == null) {
            return defaultValue;
        }
        return Boolean.parseBoolean(value);
    }

    public int getInt(String key, int defaultValue) {
        String value = getString(key, null);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private String getOverrideValue(String key) {
        String sysProp = System.getProperty(key);
        if (sysProp != null) {
            return sysProp;
        }
        String envDirect = System.getenv(key);
        if (envDirect != null) {
            return envDirect;
        }
        String envMapped = System.getenv(mapEnvKey(key));
        if (envMapped != null) {
            return envMapped;
        }
        return null;
    }

    private String mapEnvKey(String key) {
        String upper = key.toUpperCase().replace('.', '_');
        if (upper.startsWith("OPENTSX_")) {
            return upper;
        }
        return "OPENTSX_" + upper;
    }

    private void loadDefaults() {
        loadFromClasspath("opentsx-default.properties");
        loadFromFile("config/opentsx-default.properties");
    }

    private void loadProfileOverrides() {
        String configFile = getOverrideValue("opentsx.config.file");
        if (configFile == null) {
            configFile = getOverrideValue("OPENTSX_CONFIG_FILE");
        }
        String profile = getOverrideValue("opentsx.config.profile");
        if (profile == null) {
            profile = getOverrideValue("OPENTSX_CONFIG_PROFILE");
        }
        if (configFile != null) {
            loadFromFile(configFile);
            return;
        }
        if (profile != null) {
            loadFromFile("config/opentsx-" + profile + ".properties");
        }
    }

    private void loadFromClasspath(String resourcePath) {
        try (InputStream is = ConfigManager.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (is != null) {
                properties.load(is);
            }
        } catch (Exception ignored) {
        }
    }

    private void loadFromFile(String path) {
        File file = new File(path);
        if (!file.exists() || !file.canRead()) {
            return;
        }
        try (FileInputStream fis = new FileInputStream(file)) {
            properties.load(fis);
        } catch (Exception ignored) {
        }
    }
}
