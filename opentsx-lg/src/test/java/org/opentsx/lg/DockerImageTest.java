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
    void noManifestHasAListEntryWithAnEmptyName() throws Exception {
        List<Path> manifests;
        try (Stream<Path> files = Files.walk(Path.of("src/main"))) {
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

        assertTrue(manifests.containsAll(List.of(Path.of("src/main/docker-compose/ping-tool-cons/docker-compose.yml"),
                Path.of("src/main/docker-compose/ping-tool-prod/docker-compose.yml"))), manifests.toString());
        assertEquals(List.of(), problems);
    }

    @Test
    void eachEnvFileOfThePingToolsNamesAClientProfileOfTheMountedFolder() throws Exception {
        Path compose = Path.of("src/main/docker-compose");
        Pattern context = Pattern.compile("(?m)^ENV_CONTEXT=/etc/opentsx/config/(\\S+)$");
        List<String> profiles = new ArrayList<>();
        for (String tool : List.of("ping-tool-cons", "ping-tool-prod")) {
            assertTrue(Files.readString(compose.resolve(tool).resolve("docker-compose.yml"))
                    .contains("- ./../config/:/etc/opentsx/config/"), tool);
            for (String env : List.of(".env", ".env-inhouse")) {
                Matcher profile = context.matcher(Files.readString(compose.resolve(tool).resolve(env)));
                assertTrue(profile.find(), tool + "/" + env);
                assertTrue(Files.isRegularFile(compose.resolve("config").resolve(profile.group(1))),
                        tool + "/" + env + ": " + profile.group(1));
                profiles.add(profile.group(1));
            }
        }

        assertEquals(List.of("opentsx-local.props", "cpl.props", "opentsx-local.props", "cpl.props"), profiles);
    }

    @Test
    void eachVariableOfTheEnvFilesOfThePingToolsIsReadByItsComposeFile() throws Exception {
        Path compose = Path.of("src/main/docker-compose");
        Pattern variable = Pattern.compile("(?m)^([A-Za-z_][A-Za-z0-9_]*)=");
        List<String> unused = new ArrayList<>();
        int variables = 0;
        for (String tool : List.of("ping-tool-cons", "ping-tool-prod")) {
            String file = Files.readString(compose.resolve(tool).resolve("docker-compose.yml"));
            for (String env : List.of(".env", ".env-inhouse")) {
                Matcher name = variable.matcher(Files.readString(compose.resolve(tool).resolve(env)));
                while (name.find()) {
                    variables++;
                    if (!file.contains("${" + name.group(1) + "}")) {
                        unused.add(tool + "/" + env + ": " + name.group(1));
                    }
                }
            }
        }

        assertEquals(16, variables - unused.size());
        assertEquals(List.of(), unused);
    }

    @Test
    void theComposeFilesHaveNoVersionKeyAndTheDemoScriptRunsDockerCompose() throws Exception {
        List<Path> composeFiles = files("src/main", "docker-compose.yml");
        List<String> problems = new ArrayList<>();
        for (Path file : composeFiles) {
            List<String> lines = Files.readAllLines(file);
            for (int n = 1; n <= lines.size(); n++) {
                if (lines.get(n - 1).startsWith("version:")) {
                    problems.add(file + ":" + n + ": version: obsolete; delete the line");
                }
            }
        }
        List<String> demo = Files.readAllLines(Path.of("run_demo_docker_compose.sh"));
        for (int n = 1; n <= demo.size(); n++) {
            if (demo.get(n - 1).strip().startsWith("docker-compose")) {
                problems.add("run_demo_docker_compose.sh:" + n + ": docker-compose: the v1 binary; call docker compose");
            }
        }

        assertEquals(2, composeFiles.size(), composeFiles.toString());
        assertEquals(List.of(), problems);
        assertEquals(2, demo.stream().filter(line -> line.startsWith("docker compose ")).count(), demo.toString());
    }

    @Test
    void theScriptsThePingToolsRunStartNoXvfb() throws Exception {
        List<String> problems = new ArrayList<>();
        try (Stream<Path> scripts = Files.list(Path.of("src/main/docker-compose/bin"))) {
            for (Path script : scripts.filter(f -> f.toString().endsWith(".sh")).sorted().toList()) {
                List<String> lines = Files.readAllLines(script);
                for (int n = 1; n <= lines.size(); n++) {
                    if (lines.get(n - 1).strip().startsWith("Xvfb")) {
                        problems.add(script + ":" + n + ": Xvfb: not in the image; delete the line");
                    }
                }
            }
        }

        assertEquals(List.of(), problems);
    }

    private static void assertPinned(String image, String where) {
        int tag = image.lastIndexOf(':');
        assertTrue(tag > image.lastIndexOf('/') && tag < image.length() - 1, where + ": " + image);
        assertNotEquals("latest", image.substring(tag + 1), where + ": " + image);
    }
}
