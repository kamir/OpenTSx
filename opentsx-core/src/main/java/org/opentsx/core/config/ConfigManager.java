package org.opentsx.core.config;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
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
        String trimmed = value.trim();
        try {
            return Integer.parseInt(trimmed);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + ": \"" + trimmed
                    + "\" is not an int; expected a whole number from -2147483648 to 2147483647", e);
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
        loadFromFile("config/opentsx-default.properties", false);
    }

    private void loadProfileOverrides() {
        String configFile = nonBlankOverride("opentsx.config.file", "OPENTSX_CONFIG_FILE");
        String profile = nonBlankOverride("opentsx.config.profile", "OPENTSX_CONFIG_PROFILE");
        if (configFile != null) {
            loadFromFile(configFile, true);
            return;
        }
        if (profile != null) {
            loadFromFile("config/opentsx-" + profile + ".properties", true);
        }
    }

    private String nonBlankOverride(String key, String envKey) {
        String value = getOverrideValue(key);
        if (value == null) {
            value = getOverrideValue(envKey);
        }
        if (value != null && value.isBlank()) {
            String name = System.getProperty(key) != null || System.getenv(key) != null ? key : envKey;
            throw new IllegalArgumentException(name + ": empty value; set a value or leave the key unset");
        }
        return value;
    }

    private void loadFromClasspath(String resourcePath) {
        try (InputStream is = ConfigManager.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (is != null) {
                load(is, "classpath:" + resourcePath);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("classpath:" + resourcePath + ": cannot read the file (" + e + ")", e);
        }
    }

    private void loadFromFile(String path, boolean required) {
        File file = new File(path);
        if (!file.isFile()) {
            if (required) {
                throw new IllegalArgumentException(path + ": no such file; give an existing file");
            }
            return;
        }
        try (FileInputStream fis = new FileInputStream(file)) {
            load(fis, path);
        } catch (IOException e) {
            throw new UncheckedIOException(path + ": cannot read the file (" + e + ")", e);
        }
    }

    private void load(InputStream in, String source) throws IOException {
        try {
            properties.load(in);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(source
                    + ": malformed \\uxxxx escape; write \\u followed by four hexadecimal digits", e);
        }
    }
}
