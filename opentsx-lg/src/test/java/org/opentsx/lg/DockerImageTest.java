package org.opentsx.lg;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DockerImageTest {

    private static final String MOUNTED_PROFILE = "/etc/opentsx/config/opentsx-local.props";

    private static List<Path> files(String root, String name) throws Exception {
        try (Stream<Path> files = Files.walk(Path.of(root))) {
            return files.filter(f -> f.getFileName().toString().equals(name)).toList();
        }
    }

    @Test
    void theDockerProfileCopiesNoKafkaClientProfileAndReadsTheMountedOne() throws Exception {
        String pom = Files.readString(Path.of("pom.xml"));

        assertFalse(pom.contains("ccloud.props"));
        Matcher source = Pattern.compile("<source>([^<]*)</source>").matcher(pom);
        while (source.find()) {
            assertFalse(source.group(1).endsWith(".props"), source.group(1));
        }
        assertTrue(pom.contains("<OPENTSX_PRIMARY_CLUSTER_CLIENT_CFG_FILE_NAME>" + MOUNTED_PROFILE
                + "</OPENTSX_PRIMARY_CLUSTER_CLIENT_CFG_FILE_NAME>"));
    }

    @Test
    void noDockerfileNamesTheCloudProfileAndEachReadsTheMountedOne() throws Exception {
        List<Path> dockerfiles = files("src", "Dockerfile");

        assertFalse(dockerfiles.isEmpty());
        for (Path dockerfile : dockerfiles) {
            String text = Files.readString(dockerfile);
            assertFalse(text.contains("ccloud.props"), dockerfile.toString());
            assertTrue(text.contains("ENV OPENTSX_PRIMARY_CLUSTER_CLIENT_CFG_FILE_NAME " + MOUNTED_PROFILE),
                    dockerfile.toString());
        }
    }

    @Test
    void theJarCarriesNoKafkaClientProfile() throws Exception {
        try (Stream<Path> resources = Files.walk(Path.of("src/main/resources"))) {
            List<Path> profiles = resources.filter(f -> f.getFileName().toString().endsWith(".props")).toList();

            assertTrue(profiles.isEmpty(), profiles.toString());
        }
    }

    @Test
    void thePomAndEachDockerfilePinTheirBaseImage() throws Exception {
        Matcher from = Pattern.compile("<from>([^<]*)</from>").matcher(Files.readString(Path.of("pom.xml")));
        assertTrue(from.find());
        assertPinned(from.group(1), "pom.xml");

        for (Path dockerfile : files("src", "Dockerfile")) {
            Matcher base = Pattern.compile("(?m)^FROM\\s+(\\S+)").matcher(Files.readString(dockerfile));
            assertTrue(base.find(), dockerfile.toString());
            assertPinned(base.group(1), dockerfile.toString());
        }
    }

    private static void assertPinned(String image, String where) {
        int tag = image.lastIndexOf(':');
        assertTrue(tag > image.lastIndexOf('/') && tag < image.length() - 1, where + ": " + image);
        assertNotEquals("latest", image.substring(tag + 1), where + ": " + image);
    }
}
