package org.opentsx.market;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opentsx.market.archive.MosmixRawArchive;
import org.opentsx.market.source.Fetcher;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MosmixRawArchiveTest {

    static byte[] kmz(String issue) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(b)) {
            z.putNextEntry(new ZipEntry("MOSMIX_L_" + issue + ".kml"));
            z.write(("<kml><dwd:ProductDefinition><dwd:IssueTime>" + issue + "</dwd:IssueTime>"
                    + "</dwd:ProductDefinition></kml>").getBytes(StandardCharsets.UTF_8));
        }
        return b.toByteArray();
    }

    @Test
    void keepsEachIssueOnceWithAnIndexLine(@TempDir Path dir) throws Exception {
        byte[][] current = {kmz("2026-10-12T09:00:00.000Z")};
        Fetcher f = new Fetcher() {
            @Override
            public String get(String uri, Map<String, String> headers) {
                throw new UnsupportedOperationException();
            }

            @Override
            public byte[] getBytes(String uri) {
                assertTrue(uri.endsWith("/10579/kml/MOSMIX_L_LATEST_10579.kmz"));
                return current[0];
            }
        };
        MosmixRawArchive a = new MosmixRawArchive(dir, f);
        Instant now = Instant.parse("2026-10-12T10:00:00Z");
        MosmixRawArchive.Result r = a.collect(List.of("10579"), now).get(0);
        assertTrue(r.added());
        assertEquals("2026-10-12T09:00:00Z", r.issue());
        assertEquals(dir.resolve("dwd-mosmix/raw/10579/2026-10-12T090000Z.kmz"), r.file());
        assertFalse(a.collect(List.of("10579"), now).get(0).added());
        current[0] = kmz("2026-10-12T15:00:00.000Z");
        assertTrue(a.collect(List.of("10579"), now).get(0).added());
        List<String> index = Files.readAllLines(dir.resolve("dwd-mosmix/raw/index.tsv"));
        assertEquals(3, index.size());   // header + two issues
        assertTrue(index.get(1).contains("CC BY 4.0"));
    }
}
