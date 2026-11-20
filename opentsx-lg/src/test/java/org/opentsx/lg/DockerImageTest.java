package org.opentsx.lg;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DockerImageTest {

    private static final String MOUNTED_PROFILE = "/etc/opentsx/config/opentsx-local.props";
    private static final Pattern LEGACY_ENV = Pattern.compile("(?i)ENV\\s+[^\\s=]+(\\s|$)");
    private static final Pattern SHELL_FORM = Pattern.compile("(?i)(CMD|ENTRYPOINT)\\s+[^\\s\\[]");
    private static final Pattern EMPTY_NAME = Pattern.compile("\\s*-\\s*name:\\s*");

    private static List<Path> files(String root, String name) throws Exception {
        try (Stream<Path> files = Files.walk(Path.of(root))) {
            return files.filter(f -> f.getFileName().toString().equals(name)).toList();
        }
    }

    private static Map<Integer, String> logicalLines(String text) {
        Map<Integer, String> logical = new LinkedHashMap<>();
        List<String> lines = text.lines().toList();
        StringBuilder joined = new StringBuilder();
        int first = 1;
        for (int n = 1; n <= lines.size(); n++) {
            String line = lines.get(n - 1).strip();
            if (line.isEmpty() || line.startsWith("#")) {
                if (joined.isEmpty()) {
                    first = n + 1;
                }
                continue;
            }
            boolean continued = line.endsWith("\\");
            joined.append(joined.isEmpty() ? "" : " ")
                    .append(continued ? line.substring(0, line.length() - 1).strip() : line);
            if (!continued) {
                logical.put(first, joined.toString());
                joined.setLength(0);
                first = n + 1;
            }
        }
        if (!joined.isEmpty()) {
            logical.put(first, joined.toString());
        }
        return logical;
    }

    private static List<String> dockerfileProblems(String where, String dockerfile) {
        List<String> problems = new ArrayList<>();
        for (Map.Entry<Integer, String> instruction : logicalLines(dockerfile).entrySet()) {
            int n = instruction.getKey();
            String line = instruction.getValue();
            if (LEGACY_ENV.matcher(line).lookingAt()) {
                problems.add(where + ":" + n + ": " + line + ": legacy form; write ENV <key>=<value>");
            }
            if (SHELL_FORM.matcher(line).lookingAt()) {
                problems.add(where + ":" + n + ": " + line
                        + ": shell form; write the exec form [\"<executable>\", \"<argument>\"]");
            }
        }
        return problems;
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
            assertTrue(text.contains("ENV OPENTSX_PRIMARY_CLUSTER_CLIENT_CFG_FILE_NAME=" + MOUNTED_PROFILE),
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

    @Test
    void theChecksFindTheLegacyEnvFormAndTheShellForm() {
        String dockerfile = "FROM eclipse-temurin:21.0.8_9-jre-noble\n"
                + "ENV DISPLAY :99\n"
                + "ENV OPENTSX_SHOW_GUI=false\n"
                + "env A b\n"
                + "CMD java -jar /app.jar\n"
                + "CMD [\"java\", \"-jar\", \"/app.jar\"]\n"
                + "ENTRYPOINT /run.sh\n"
                + "ENTRYPOINT [\"/run.sh\"]\n"
                + "ENV \\\n"
                + "A=1 \\\n"
                + "B=2\n"
                + "CMD \\\n"
                + "[\"java\", \"-jar\", \"/app.jar\"]\n"
                + "ENV \\\n"
                + "A 1\n"
                + "# note C:\\\n"
                + "CMD java -jar /app.jar\n"
                + "ENV \\\n"
                + "\n"
                + "C 3\n";
        String execForm = "shell form; write the exec form [\"<executable>\", \"<argument>\"]";

        assertEquals(List.of(
                "Dockerfile:2: ENV DISPLAY :99: legacy form; write ENV <key>=<value>",
                "Dockerfile:4: env A b: legacy form; write ENV <key>=<value>",
                "Dockerfile:5: CMD java -jar /app.jar: " + execForm,
                "Dockerfile:7: ENTRYPOINT /run.sh: " + execForm,
                "Dockerfile:14: ENV A 1: legacy form; write ENV <key>=<value>",
                "Dockerfile:17: CMD java -jar /app.jar: " + execForm,
                "Dockerfile:18: ENV C 3: legacy form; write ENV <key>=<value>"),
                dockerfileProblems("Dockerfile", dockerfile));
    }

    @Test
    void eachDockerfileSetsKeyValueVariablesAndRunsItsCommandInExecForm() throws Exception {
        List<Path> dockerfiles = files("src", "Dockerfile");
        List<String> problems = new ArrayList<>();
        for (Path dockerfile : dockerfiles) {
            problems.addAll(dockerfileProblems(dockerfile.toString(), Files.readString(dockerfile)));
        }

        assertFalse(dockerfiles.isEmpty());
        assertEquals(List.of(), problems);
    }

    @Test
    void noKubernetesManifestHasAListEntryWithAnEmptyName() throws Exception {
        List<Path> manifests;
        try (Stream<Path> files = Files.walk(Path.of("src/main/k8s"))) {
            manifests = files.filter(f -> f.getFileName().toString().matches(".*\\.ya?ml")).toList();
        }
        List<String> problems = new ArrayList<>();
        for (Path manifest : manifests) {
            List<String> lines = Files.readAllLines(manifest);
            for (int n = 1; n <= lines.size(); n++) {
                if (EMPTY_NAME.matcher(lines.get(n - 1)).matches()) {
                    problems.add(manifest + ":" + n + ": name: empty value; give the entry a name or delete it");
                }
            }
        }

        assertTrue(manifests.contains(Path.of("src/main/k8s/krake-app/echo-demo.yaml")), manifests.toString());
        assertEquals(List.of(), problems);
    }

    private static void assertPinned(String image, String where) {
        int tag = image.lastIndexOf(':');
        assertTrue(tag > image.lastIndexOf('/') && tag < image.length() - 1, where + ": " + image);
        assertNotEquals("latest", image.substring(tag + 1), where + ": " + image);
    }
}
