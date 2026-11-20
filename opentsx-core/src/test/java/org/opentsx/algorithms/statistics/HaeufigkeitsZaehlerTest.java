package org.opentsx.algorithms.statistics;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opentsx.data.series.TimeSeriesObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class HaeufigkeitsZaehlerTest {

    @TempDir
    Path home;

    @Test
    void theDefaultFolderIsBelowTheUserHome() throws Exception {
        String original = System.getProperty("user.home");
        HaeufigkeitsZaehler histogram;
        try {
            System.setProperty("user.home", home.toString());
            histogram = new HaeufigkeitsZaehler("lengths");
        } finally {
            System.setProperty("user.home", original);
        }

        histogram.store();

        assertTrue(Files.isRegularFile(home.resolve("opentsx").resolve("histograms").resolve("lengths")));
    }

    @Test
    void aFailingStoreClosesItsFile() throws Exception {
        HaeufigkeitsZaehler histogram = new HaeufigkeitsZaehler("lengths");
        histogram.folder = home + "/";
        histogram.mr = new TimeSeriesObject() {
            @Override
            public String toString() {
                throw new IllegalStateException("toString fails");
            }
        };

        assertThrows(IllegalStateException.class, histogram::store);

        assertFalse(isOpen(home.resolve("lengths")));
    }

    private static boolean isOpen(Path file) throws IOException {
        Path fds = Paths.get("/proc/self/fd");
        assumeTrue(Files.isDirectory(fds), "needs /proc/self/fd");
        Path real = file.toRealPath();
        try (Stream<Path> links = Files.list(fds)) {
            return links.anyMatch(fd -> real.equals(target(fd)));
        }
    }

    private static Path target(Path fd) {
        try {
            return fd.resolveSibling(Files.readSymbolicLink(fd));
        } catch (IOException gone) {
            return null;
        }
    }
}
