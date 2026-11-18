package org.opentsx.core.config;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Locale;
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
        String upper = key.toUpperCase(Locale.ROOT).replace('.', '_');
        if (upper.startsWith("OPENTSX_")) {
            return upper;
        }
        return "OPENTSX_" + upper;
    }

    private void loadDefaults() {
        loadFromClasspath("opentsx-default.properties");
        String defaultFile = "config/opentsx-default.properties";
        if (!Files.notExists(Path.of(defaultFile), LinkOption.NOFOLLOW_LINKS)) {
            loadFromFile(defaultFile);
        }
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
        URL resource = ConfigManager.class.getClassLoader().getResource(resourcePath);
        if (resource == null) {
            return;
        }
        Properties loaded = new Properties();
        try (InputStream is = resource.openStream()) {
            loaded.load(is);
        } catch (IOException | IllegalArgumentException failure) {
            throw new IllegalStateException("Failed to load classpath configuration: " + resourcePath, failure);
        }
        properties.putAll(loaded);
    }

    private void loadFromFile(String path) {
        Properties loaded = new Properties();
        try (InputStream fis = Files.newInputStream(Path.of(path))) {
            loaded.load(fis);
        } catch (IOException | IllegalArgumentException failure) {
            throw new IllegalStateException("Failed to load configuration file: " + path, failure);
        }
        properties.putAll(loaded);
    }
}
