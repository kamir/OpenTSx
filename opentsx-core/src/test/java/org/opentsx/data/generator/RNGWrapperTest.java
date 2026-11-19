package org.opentsx.data.generator;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class RNGWrapperTest {

    @Test
    void poissonSamplesHaveTheRequestedMean() {
        RNGWrapper.init();
        int n = 10_000;
        double sum = 0;
        for (int i = 0; i < n; i++) {
            sum += RNGWrapper.getStdPoisson(0.2);
        }
        assertEquals(0.2, sum / n, 0.02);
    }

    @Test
    void aFreshGeneratorDrawsTheSameSeriesWithoutInit() throws Exception {
        List<?> first = gaussianSeries(null);
        assertEquals(16, first.size());
        assertEquals(first, gaussianSeries(null));
        assertEquals(first, gaussianSeries(1L));
    }

    @Test
    void aSeedGivesTheSameSeriesInEveryFreshGenerator() throws Exception {
        assertEquals(gaussianSeries(7L), gaussianSeries(7L));
        assertNotEquals(gaussianSeries(7L), gaussianSeries(8L));
    }

    @Test
    void theReactorSourcesHoldNoStdlibPackage() throws IOException {
        Path root = Path.of("..");
        Matcher modules = Pattern.compile("<module>([^<]+)</module>")
                .matcher(Files.readString(root.resolve("pom.xml")));
        Pattern use = Pattern.compile("\\bpackage\\s+stdlib\\b|\\bimport\\s+(static\\s+)?stdlib\\.|\\bstdlib\\.[A-Z]");
        List<String> found = new ArrayList<>();
        while (modules.find()) {
            Path src = root.resolve(modules.group(1)).resolve("src");
            if (!Files.isDirectory(src)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(src)) {
                for (Path file : (Iterable<Path>) files::iterator) {
                    String name = file.getFileName().toString();
                    if (Files.isDirectory(file) && name.equals("stdlib")) {
                        found.add(root.relativize(file).toString());
                    } else if ((name.endsWith(".java") || name.endsWith(".scala"))
                            && use.matcher(Files.readString(file)).find()) {
                        found.add(root.relativize(file).toString());
                    }
                }
            }
        }
        assertEquals(List.of(), found);
    }

    private static List<?> gaussianSeries(Long seed) throws Exception {
        try (URLClassLoader loader = new URLClassLoader(testClassPath(), ClassLoader.getPlatformClassLoader())) {
            if (seed != null) {
                loader.loadClass(RNGWrapper.class.getName()).getMethod("init", long.class).invoke(null, seed);
            }
            Class<?> series = loader.loadClass("org.opentsx.data.series.TimeSeriesObject");
            Object row = series.getMethod("getGaussianDistribution", int.class, double.class, double.class)
                    .invoke(null, 16, 0.0, 1.0);
            return new ArrayList<>((List<?>) series.getField("yValues").get(row));
        }
    }

    private static URL[] testClassPath() throws MalformedURLException {
        String[] entries = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"))
                .split(File.pathSeparator);
        URL[] urls = new URL[entries.length];
        for (int i = 0; i < entries.length; i++) {
            urls[i] = new File(entries[i]).toURI().toURL();
        }
        return urls;
    }
}
