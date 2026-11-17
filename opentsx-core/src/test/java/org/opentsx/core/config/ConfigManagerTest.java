package org.opentsx.core.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ConfigManagerTest {

    @TempDir
    Path dir;

    @BeforeAll
    static void initializeTheSharedInstanceWithoutOverrides() {
        ConfigManager.getInstance();
    }

    @AfterEach
    void clearOverrides() {
        System.clearProperty("opentsx.config.file");
        System.clearProperty("opentsx.config.profile");
        System.clearProperty("OPENTSX_CONFIG_PROFILE");
        System.clearProperty("opentsx.test.port");
    }

    @Test
    void anExplicitMissingConfigFileFailsNamingThePath() {
        String path = dir.resolve("prod.propeties").toString();
        System.setProperty("opentsx.config.file", path);
        Throwable e = assertThrows(InvocationTargetException.class, ConfigManagerTest::load).getCause();
        assertInstanceOf(IllegalArgumentException.class, e);
        assertEquals(path + ": no such file; give an existing file", e.getMessage());
    }

    @Test
    void aMissingProfileFileFailsNamingThePath() {
        System.setProperty("opentsx.config.profile", "nosuchprofile");
        Throwable e = assertThrows(InvocationTargetException.class, ConfigManagerTest::load).getCause();
        assertInstanceOf(IllegalArgumentException.class, e);
        assertEquals("config/opentsx-nosuchprofile.properties: no such file; give an existing file", e.getMessage());
    }

    @Test
    void anEmptyConfigFileValueFailsNamingTheKey() {
        System.setProperty("opentsx.config.file", "");
        Throwable e = assertThrows(InvocationTargetException.class, ConfigManagerTest::load).getCause();
        assertInstanceOf(IllegalArgumentException.class, e);
        assertEquals("opentsx.config.file: empty value; set a value or leave the key unset", e.getMessage());
    }

    @Test
    void aBlankProfileValueFailsNamingTheVariable() {
        System.setProperty("OPENTSX_CONFIG_PROFILE", " ");
        Throwable e = assertThrows(InvocationTargetException.class, ConfigManagerTest::load).getCause();
        assertInstanceOf(IllegalArgumentException.class, e);
        assertEquals("OPENTSX_CONFIG_PROFILE: empty value; set a value or leave the key unset", e.getMessage());
    }

    @Test
    void aConfigFileThatDoesNotLoadFailsNamingThePathAndKeepsTheCause() throws Exception {
        Path file = dir.resolve("broken.properties");
        Files.writeString(file, "opentsx.test.port=\\uZZZZ\n");
        System.setProperty("opentsx.config.file", file.toString());
        Throwable e = assertThrows(InvocationTargetException.class, ConfigManagerTest::load).getCause();
        assertInstanceOf(IllegalArgumentException.class, e);
        assertEquals(file + ": malformed \\uxxxx escape; write \\u followed by four hexadecimal digits", e.getMessage());
        assertNotNull(e.getCause());
    }

    @Test
    void anExplicitConfigFileIsRead() throws Exception {
        Path file = dir.resolve("ok.properties");
        Files.writeString(file, "opentsx.test.port=9092\n");
        System.setProperty("opentsx.config.file", file.toString());
        assertEquals(9092, load().getInt("opentsx.test.port", 1));
    }

    @Test
    void aMalformedIntFailsNamingTheKeyAndTheValue() {
        System.setProperty("opentsx.test.port", "abc");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ConfigManager.getInstance().getInt("opentsx.test.port", 1));
        assertEquals("opentsx.test.port: \"abc\" is not an int; expected a whole number from -2147483648 to 2147483647",
                e.getMessage());
        assertInstanceOf(NumberFormatException.class, e.getCause());
    }

    @Test
    void anUnsetIntUsesTheDefault() {
        assertEquals(7, ConfigManager.getInstance().getInt("opentsx.test.port", 7));
    }

    private static ConfigManager load() throws Exception {
        Constructor<ConfigManager> constructor = ConfigManager.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }
}
