package org.opentsx.core.config;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

public final class ConfigManagerProbe {

    static final String CONTENT_MARKER = "synthetic-property-value-not-for-error-output";
    private static final String DEFAULT_RESOURCE = "opentsx-default.properties";
    private static final String WORKING_DEFAULT = "config/opentsx-default.properties";
    private static final String INITIAL = "atomic.key=old\nretained.key=retained\n";
    private static final String UPDATE = "atomic.key=" + CONTENT_MARKER + "\nadded.key=added\n";
    private static final String MALFORMED = UPDATE + "malformed=\\u12XZ\n";

    public static void main(String[] arguments) throws Exception {
        Locale.setDefault(Locale.ROOT);
        String scenario = arguments[0];
        if (scenario.startsWith("explicit-file-")) {
            if (scenario.equals("explicit-file-source-precedence")) sourcePrecedence();
            else selectedFileFailure(scenario.substring("explicit-file-".length()));
        } else if (scenario.equals("profile-source-precedence")) {
            profilePrecedence();
        } else if (scenario.startsWith("explicit-profile-")) {
            selectedProfileFailure(scenario.substring("explicit-profile-".length()));
        } else if (scenario.startsWith("working-default-")) {
            workingDefaultFailure(scenario.substring("working-default-".length()));
        } else if (scenario.startsWith("classpath-default-")) {
            classpathDefaultFailure(scenario.substring("classpath-default-".length()));
        } else if (scenario.startsWith("classpath-") && scenario.endsWith("-atomic")) {
            classpathAtomic(scenario.substring("classpath-".length(), scenario.length() - "-atomic".length()));
        } else if (scenario.startsWith("file-") && scenario.endsWith("-atomic")) {
            fileAtomic(scenario.substring("file-".length(), scenario.length() - "-atomic".length()));
        } else if (scenario.startsWith("turkish-")) {
            turkishLookup(scenario);
        } else {
            switch (scenario) {
                case "optional-defaults-absent" -> optionalDefaults();
                case "canonical-selector-before-jvm-alias" -> canonicalSelector();
                case "uppercase-selector-alias" -> uppercaseAlias();
                case "environment-and-live-jvm" -> environmentAndJvm();
                case "classpath-commit-after-close" -> commitAfterClose();
                default -> throw new AssertionError("Unknown probe scenario");
            }
        }
        System.out.println("Configuration probe passed");
    }

    private static void optionalDefaults() throws Exception {
        try (ResourceLoader loader = new ResourceLoader()) {
            Object manager = manager(loader);
            check("fallback".equals(get(manager, "missing.fixture", "fallback")), "Missing optional default rejected");
        }
    }

    private static void sourcePrecedence() throws Exception {
        write(WORKING_DEFAULT, "fixture.setting=working\nworking.only=present\ndefault.overlap=working\n");
        write("selected.properties", "fixture.setting=selected\n");
        System.setProperty("opentsx.config.file", "selected.properties");
        System.setProperty("opentsx.config.profile", "missing");
        try (ResourceLoader loader = new ResourceLoader()) {
            loader.sources.put(DEFAULT_RESOURCE, source("valid",
                    "fixture.setting=classpath\nclasspath.only=present\ndefault.overlap=classpath\n"));
            Object manager = manager(loader);
            check("selected".equals(get(manager, "fixture.setting", null)), "Explicit source precedence changed");
            check("present".equals(get(manager, "working.only", null)), "Working defaults were lost");
            check("present".equals(get(manager, "classpath.only", null)), "Classpath defaults were lost");
            check("working".equals(get(manager, "default.overlap", null)), "Default source precedence changed");
        }
    }

    private static void profilePrecedence() throws Exception {
        write(WORKING_DEFAULT, "fixture.setting=working\nworking.only=present\nlatin.file=caf\u00e9\n");
        write("config/opentsx-fixture.properties", "fixture.setting=profile\n");
        System.setProperty("opentsx.config.profile", "fixture");
        try (ResourceLoader loader = new ResourceLoader()) {
            loader.sources.put(DEFAULT_RESOURCE, source("valid", "fixture.setting=classpath\nlatin.classpath=caf\u00e9\n"));
            Object manager = manager(loader);
            check("profile".equals(get(manager, "fixture.setting", null)), "Profile precedence changed");
            check("present".equals(get(manager, "working.only", null)), "Working defaults were lost");
            check("caf\u00e9".equals(get(manager, "latin.file", null)), "File stream encoding changed");
            check("caf\u00e9".equals(get(manager, "latin.classpath", null)), "Classpath stream encoding changed");
        }
    }

    private static void canonicalSelector() throws Exception {
        write("environment-selected.properties", "fixture.setting=canonical\n");
        System.setProperty("OPENTSX_CONFIG_FILE", "missing-alias.properties");
        try (ResourceLoader loader = new ResourceLoader()) {
            check("canonical".equals(get(manager(loader), "fixture.setting", null)), "Canonical selector priority changed");
        }
    }

    private static void uppercaseAlias() throws Exception {
        write("config/opentsx-fixture.properties", "fixture.setting=alias\n");
        System.setProperty("OPENTSX_CONFIG_PROFILE", "fixture");
        try (ResourceLoader loader = new ResourceLoader()) {
            check("alias".equals(get(manager(loader), "fixture.setting", null)), "Uppercase selector alias was lost");
        }
    }

    private static void environmentAndJvm() throws Exception {
        write(WORKING_DEFAULT, "fixture.id=file\n");
        try (ResourceLoader loader = new ResourceLoader()) {
            Object manager = manager(loader);
            check("literal".equals(get(manager, "fixture.id", null)), "Literal environment priority changed");
            check("second-mapped".equals(get(manager, "second.id", null)), "Environment mapping changed");
            check("second-mapped".equals(get(manager, "opentsx.second.id", null)), "Existing environment prefix was doubled");
            System.setProperty("fixture.id", "first-jvm");
            check("first-jvm".equals(get(manager, "fixture.id", null)), "JVM override was ignored");
            System.setProperty("fixture.id", "second-jvm");
            check("second-jvm".equals(get(manager, "fixture.id", null)), "JVM override was cached");
            System.clearProperty("fixture.id");
            check("literal".equals(get(manager, "fixture.id", null)), "Cleared JVM override was retained");
        }
    }

    private static void selectedFileFailure(String kind) throws Exception {
        String path = kind.equals("empty") ? "" : "selected.properties";
        prepareFailurePath(path, kind);
        System.setProperty("opentsx.config.file", path);
        try (ResourceLoader loader = new ResourceLoader()) {
            assertInitializationFailure(loader, "Failed to load configuration file: " + path,
                    kind.equals("malformed") ? IllegalArgumentException.class : IOException.class, null);
        }
    }

    private static void selectedProfileFailure(String kind) throws Exception {
        String path = "config/opentsx-fixture.properties";
        prepareFailurePath(path, kind);
        System.setProperty("opentsx.config.profile", "fixture");
        try (ResourceLoader loader = new ResourceLoader()) {
            assertInitializationFailure(loader, "Failed to load configuration file: " + path,
                    kind.equals("malformed") ? IllegalArgumentException.class : IOException.class, null);
        }
    }

    private static void workingDefaultFailure(String kind) throws Exception {
        prepareFailurePath(WORKING_DEFAULT, kind);
        try (ResourceLoader loader = new ResourceLoader()) {
            assertInitializationFailure(loader, "Failed to load configuration file: " + WORKING_DEFAULT,
                    kind.equals("malformed") ? IllegalArgumentException.class : IOException.class, null);
        }
    }

    private static void classpathDefaultFailure(String kind) throws Exception {
        Source source = source(kind, kind.equals("parse") ? MALFORMED : UPDATE);
        try (ResourceLoader loader = new ResourceLoader()) {
            loader.sources.put(DEFAULT_RESOURCE, source);
            assertInitializationFailure(loader, "Failed to load classpath configuration: " + DEFAULT_RESOURCE,
                    kind.equals("parse") ? IllegalArgumentException.class : IOException.class,
                    kind.equals("parse") ? null : source.failure);
            check(kind.equals("open") || source.closed, "Classpath failure left its stream open");
        }
    }

    private static void classpathAtomic(String kind) throws Exception {
        try (ResourceLoader loader = new ResourceLoader()) {
            loader.sources.put(DEFAULT_RESOURCE, source("valid", INITIAL));
            Object manager = manager(loader);
            Properties properties = properties(manager);
            if (kind.equals("absent")) {
                loader.sources.remove(DEFAULT_RESOURCE);
                check(invokeSource(manager, "loadFromClasspath", DEFAULT_RESOURCE) == null,
                        "Missing optional classpath resource failed");
                assertUnchanged(properties);
                return;
            }
            Source source = source(kind, kind.equals("parse") ? MALFORMED : UPDATE);
            loader.sources.put("fixture.properties", source);
            Throwable failure = invokeSource(manager, "loadFromClasspath", "fixture.properties");
            assertUnchanged(properties);
            assertFailure(failure, "Failed to load classpath configuration: fixture.properties",
                    kind.equals("parse") ? IllegalArgumentException.class : IOException.class,
                    kind.equals("parse") ? null : source.failure);
            check(kind.equals("open") || source.closed, "Classpath load left its stream open");
        }
    }

    private static void fileAtomic(String kind) throws Exception {
        String path = "atomic.properties";
        prepareFailurePath(path, kind.equals("parse") ? "malformed" : kind);
        try (ResourceLoader loader = new ResourceLoader()) {
            loader.sources.put(DEFAULT_RESOURCE, source("valid", INITIAL));
            Object manager = manager(loader);
            Properties properties = properties(manager);
            Throwable failure = invokeSource(manager, "loadFromFile", path);
            assertUnchanged(properties);
            assertFailure(failure, "Failed to load configuration file: " + path,
                    kind.equals("parse") ? IllegalArgumentException.class : IOException.class, null);
        }
    }

    private static void commitAfterClose() throws Exception {
        try (ResourceLoader loader = new ResourceLoader()) {
            loader.sources.put(DEFAULT_RESOURCE, source("valid", INITIAL));
            Object manager = manager(loader);
            Properties properties = properties(manager);
            Source source = source("valid", UPDATE);
            source.onClose = () -> assertUnchanged(properties);
            loader.sources.put("fixture.properties", source);
            Throwable failure = invokeSource(manager, "loadFromClasspath", "fixture.properties");
            check(failure == null, "Valid classpath update did not complete");
            check(source.closed, "Classpath update committed without closing");
            check(CONTENT_MARKER.equals(properties.getProperty("atomic.key")), "Closed source was not committed");
            check("added".equals(properties.getProperty("added.key")), "Closed source additions were not committed");
            check("retained".equals(properties.getProperty("retained.key")), "Unchanged properties were removed");
        }
    }

    private static void turkishLookup(String scenario) throws Exception {
        Locale.setDefault(Locale.forLanguageTag("tr-TR"));
        try (ResourceLoader loader = new ResourceLoader()) {
            Object manager = manager(loader);
            String key = switch (scenario) {
                case "turkish-mapped-key" -> "group.id";
                case "turkish-prefixed-key" -> "opentsx.integration.id";
                case "turkish-uppercase-prefix" -> "OPENTSX.integration.id";
                case "turkish-dot-mapping" -> "fixture.integration.id";
                default -> "integration.id";
            };
            if (scenario.equals("turkish-live-jvm")) {
                System.setProperty(key, "live-jvm");
                check("live-jvm".equals(get(manager, key, null)), "Live JVM override was ignored");
                System.clearProperty(key);
            }
            String expected = switch (scenario) {
                case "turkish-literal-env" -> "literal";
                case "turkish-dot-mapping" -> "fixture-integration-mapped";
                case "turkish-mapped-key" -> "group-mapped";
                default -> "integration-mapped";
            };
            check(expected.equals(get(manager, key, null)), "Turkish locale changed environment lookup");
        }
    }

    private static void prepareFailurePath(String path, String kind) throws IOException {
        if (kind.equals("directory")) Files.createDirectories(Path.of(path));
        if (kind.equals("malformed")) write(path, MALFORMED);
    }

    private static void write(String path, String text) throws IOException {
        Path file = Path.of(path);
        if (file.getParent() != null) Files.createDirectories(file.getParent());
        Files.write(file, text.getBytes(StandardCharsets.ISO_8859_1));
    }

    private static Object manager(ResourceLoader loader) throws Exception {
        Class<?> type = Class.forName(ConfigManager.class.getName(), true, loader);
        return type.getMethod("getInstance").invoke(null);
    }

    private static String get(Object manager, String key, String defaultValue) throws Exception {
        return (String) manager.getClass().getMethod("getString", String.class, String.class)
                .invoke(manager, key, defaultValue);
    }

    private static Properties properties(Object manager) throws Exception {
        var field = manager.getClass().getDeclaredField("properties");
        field.setAccessible(true);
        return (Properties) field.get(manager);
    }

    private static Throwable invokeSource(Object manager, String name, String source) throws Exception {
        var method = manager.getClass().getDeclaredMethod(name, String.class);
        method.setAccessible(true);
        try {
            method.invoke(manager, source);
            return null;
        } catch (InvocationTargetException failure) {
            return failure.getCause();
        }
    }

    private static void assertInitializationFailure(ResourceLoader loader, String message,
                                                    Class<?> causeType, Throwable original) throws Exception {
        Throwable failure = null;
        try {
            manager(loader);
        } catch (ExceptionInInitializerError wrapper) {
            failure = wrapper.getCause();
        }
        assertFailure(failure, message, causeType, original);
    }

    private static void assertFailure(Throwable failure, String message, Class<?> causeType, Throwable original) {
        check(failure instanceof IllegalStateException, "Configuration source failure was not reported");
        check(message.equals(failure.getMessage()), "Configuration failure omitted its source context");
        check(causeType.isInstance(failure.getCause()), "Configuration failure lost its original cause");
        if (original != null) check(original == failure.getCause(), "Configuration I/O cause was replaced");
        check(!failure.toString().contains(CONTENT_MARKER), "Configuration failure included property content");
    }

    private static void assertUnchanged(Properties properties) {
        check(properties.size() == 2, "A failed source partially added properties");
        check("old".equals(properties.getProperty("atomic.key")), "A failed source changed an existing property");
        check("retained".equals(properties.getProperty("retained.key")), "A failed source removed an existing property");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static Source source(String kind, String text) {
        return new Source(kind, text.getBytes(StandardCharsets.ISO_8859_1));
    }

    private static final class ResourceLoader extends URLClassLoader {
        final Map<String, Source> sources = new HashMap<>();

        ResourceLoader() {
            super(new URL[]{ConfigManager.class.getProtectionDomain().getCodeSource().getLocation()}, null);
        }

        @Override
        public URL getResource(String name) {
            Source source = sources.get(name);
            return source == null ? null : source.url(name);
        }
    }

    private static final class Source {
        final String kind;
        final byte[] bytes;
        final IOException failure;
        boolean closed;
        Runnable onClose;

        Source(String kind, byte[] bytes) {
            this.kind = kind;
            this.bytes = bytes;
            failure = new IOException("Fixture " + kind + " failure");
        }

        URL url(String name) {
            try {
                return new URL(null, "fixture:" + name, new URLStreamHandler() {
                    @Override
                    protected URLConnection openConnection(URL url) {
                        return new URLConnection(url) {
                            @Override
                            public void connect() {}

                            @Override
                            public InputStream getInputStream() throws IOException {
                                if (kind.equals("open")) throw failure;
                                return stream();
                            }
                        };
                    }
                });
            } catch (IOException failure) {
                throw new AssertionError("Synthetic resource URL failed", failure);
            }
        }

        InputStream stream() {
            return new InputStream() {
                final ByteArrayInputStream delegate = new ByteArrayInputStream(bytes);
                int reads;

                @Override
                public int read() throws IOException {
                    byte[] value = new byte[1];
                    int count = read(value, 0, 1);
                    return count < 0 ? -1 : value[0] & 0xff;
                }

                @Override
                public int read(byte[] buffer, int offset, int length) throws IOException {
                    if (kind.equals("read") && reads++ > 0) throw failure;
                    return delegate.read(buffer, offset, length);
                }

                @Override
                public void close() throws IOException {
                    closed = true;
                    if (onClose != null) onClose.run();
                    if (kind.equals("close")) throw failure;
                    delegate.close();
                }
            };
        }
    }
}
