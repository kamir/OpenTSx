package org.opentsx.wayang;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SiteConformanceChecksTest {
    private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();

    @Test void sharedWireAndSeriesChecksExecuteTheirCurrentReferenceFiles() throws Exception {
        assertTrue(SiteConformanceChecks.wire(ROOT).contains("shared single-object wire records"));
        assertTrue(SiteConformanceChecks.series(ROOT).contains("xxh64 vectors"));
    }

    @Test void everyCatalogBindingAndExistingOperatorVectorCheckExecutes() throws Exception {
        assertTrue(SiteConformanceChecks.operators(ROOT).contains("all 16 catalog bindings"));
    }

    @Test void runtimeIdentityUsesLoadedBytesAndTheRepositoryVersion() throws Exception {
        String digest = SiteConformanceChecks.artifactDigest();
        assertTrue(digest.matches("sha256:[0-9a-f]{64}"));
        assertEquals(digest, SiteConformanceChecks.artifactDigest());
        assertEquals("3.1.0", SiteConformanceChecks.version(ROOT));
    }
}
