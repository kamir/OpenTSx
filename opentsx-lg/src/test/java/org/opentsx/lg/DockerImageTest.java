package org.opentsx.lg;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
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
    private static final Pattern VARIABLE = Pattern.compile("(?m)^[ \\t]*(?:export[ \\t]+)?([A-Za-z_][A-Za-z0-9_]*)[ \\t]*[=:]");
    private static final Pattern VERSION_KEY = Pattern.compile("(?:version|\"version\"|'version')[ \\t]*:");

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

    private static String bashCode(String line) {
        char quote = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quote == 0 && c == '#' && (i == 0 || Character.isWhitespace(line.charAt(i - 1))
                    || ";&|()<>".indexOf(line.charAt(i - 1)) >= 0)) {
                return line.substring(0, i);
            }
            if (c == '\\' && quote != '\'') {
                i++;
            } else if (quote == 0 && (c == '"' || c == '\'')) {
                quote = c;
            } else if (c == quote) {
                quote = 0;
            }
        }
        return line;
    }

    /**
     * The line without its YAML comment: a # after a blank outside a quoted scalar. A quote opens a scalar only where
     * one starts (after the indentation, "- ", ": ", "? ", "[", "{" or ","); elsewhere, as in it's, it is a character.
     */
    private static String yamlCode(String line) {
        char quote = 0;
        boolean scalarStarts = true;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quote == 0) {
                if (c == '#' && (i == 0 || Character.isWhitespace(line.charAt(i - 1)))) {
                    return line.substring(0, i);
                }
                if (scalarStarts && (c == '"' || c == '\'')) {
                    quote = c;
                } else if (!Character.isWhitespace(c)) {
                    boolean indicator = "-?:".indexOf(c) >= 0
                            && (i + 1 == line.length() || Character.isWhitespace(line.charAt(i + 1)));
                    scalarStarts = indicator || "[{,".indexOf(c) >= 0;
                }
            } else if (quote == '"' && c == '\\') {
                i++;
            } else if (c == quote) {
                if (quote == '\'' && i + 1 < line.length() && line.charAt(i + 1) == '\'') {
                    i++;
                } else {
                    quote = 0;
                    scalarStarts = false;
                }
            }
        }
        return line;
    }

    private static List<String> variables(String env) {
        List<String> names = new ArrayList<>();
        Matcher name = VARIABLE.matcher(env);
        while (name.find()) {
            names.add(name.group(1));
        }
        return names;
    }

    private static boolean reads(String compose, String name) {
        String text = compose.lines().map(DockerImageTest::yamlCode).collect(Collectors.joining("\n"));
        return Pattern.compile("(?<!\\$)(?:\\$\\$)*\\$(?:\\{" + name + "[}:?+-]|" + name + "(?![A-Za-z0-9_]))")
                .matcher(text).find();
    }

    private static List<String> versionKeys(String where, String compose) {
        List<String> problems = new ArrayList<>();
        List<String> lines = compose.lines().toList();
        int root = -1;
        for (int n = 1; n <= lines.size(); n++) {
            String line = yamlCode(lines.get(n - 1)).stripTrailing();
            if (line.isEmpty() || line.equals("---") || line.startsWith("%")) {
                continue;
            }
            int indent = line.length() - line.stripLeading().length();
            if (root < 0) {
                root = indent;
            }
            if (indent == root && VERSION_KEY.matcher(line.substring(indent)).lookingAt()) {
                problems.add(where + ":" + n + ": version: obsolete; delete the line");
            }
        }
        return problems;
    }

    private static List<String> calls(String where, String script, String command, String problem) {
        Pattern call = Pattern.compile("(?:^|[\\s;&|()`\"'=]|\\$\\{[A-Za-z_][A-Za-z0-9_]*:?[-=?+])"
                + "(?:(?:/|\\.\\.?/|~[^\\s;&|()<>`\"'/]*/|\\$\\{?[A-Za-z_][A-Za-z0-9_]*}?/)(?:[^\\s;&|()<>`\"'/]+/)*)?"
                + Pattern.quote(command) + "(?=$|[\\s;&|()<>`\"'}])");
        List<String> problems = new ArrayList<>();
        List<String> lines = script.lines().toList();
        for (int n = 1; n <= lines.size(); n++) {
            if (call.matcher(bashCode(lines.get(n - 1))).find()) {
                problems.add(where + ":" + n + ": " + command + ": " + problem);
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
    void theChecksFindAnUnreadVariableAVersionKeyAndACallWhereverTheyStand() {
        String compose = "services:\n"
                + "  x:\n"
                + "    labels:\n"
                + "      version: \"1\"\n"
                + "    extra_hosts:\n"
                + "      - \"a:${BRACED}\"\n"
                + "      - \"b:$PLAIN\"\n"
                + "      - \"c:${WITH_DEFAULT:-x}\"\n"
                + "      - \"d:$$ESCAPED $$$AFTER_ESCAPE\"\n"
                + "      # - \"e:${COMMENTED}\"\n"
                + "      - \"f:${PREFIX_LONGER} #${QUOTED}\" # ${TRAILING}\n"
                + "    command: echo it's up # ${OLD}\n"
                + "    entrypoint: 'sh -c \"echo #${IN_SINGLE}\"'\n"
                + "    working_dir: 'it''s #${IN_ESCAPED}'\n";
        String env = "BRACED=1\n"
                + "export PLAIN=2\n"
                + "  WITH_DEFAULT = 3\n"
                + "ESCAPED=4\n"
                + "AFTER_ESCAPE=5\n"
                + "COMMENTED=6\n"
                + "PREFIX: 7\n"
                + "QUOTED=8\n"
                + "TRAILING=9\n"
                + "# NOTE=10\n"
                + "OLD=11\n"
                + "IN_SINGLE=12\n"
                + "IN_ESCAPED=13\n";
        String demo = "#!/usr/bin/env bash\n"
                + "# docker-compose is gone\n"
                + "cd src/main/docker-compose/ping-tool-cons || exit 1\n"
                + "docker compose --env-file .env-inhouse up -d\n"
                + "sudo docker-compose up -d\n"
                + "cd x || exit 1; docker-compose up\n"
                + "echo \"$(docker-compose ps)\"\n"
                + "/usr/local/bin/docker-compose -f docker-compose.yml up\n"
                + "COMPOSE=docker-compose\n"
                + "ls src/main/docker-compose docker-compose.yml docker-compose-plugin\n"
                + "true;# docker-compose was replaced\n"
                + "ls x|# docker-compose\n"
                + "${COMPOSE:-docker-compose} up\n"
                + "./docker-compose up\n"
                + "~/bin/docker-compose up\n"
                + "$HOME/bin/docker-compose up\n"
                + "\"${HOME}\"/bin/docker-compose up\n"
                + "../docker-compose up\n";
        String bin = "#!/bin/bash\n"
                + "nohup Xvfb :99 &\n"
                + "/usr/bin/Xvfb :99 &\n"
                + "exec Xvfb :99\n"
                + "export DISPLAY=:99 # Xvfb runs elsewhere\n"
                + "java -cp app.jar XvfbProbe\n"
                + "wait;# Xvfb stopped\n"
                + "${XVFB:-Xvfb} :99 &\n"
                + "$BIN/Xvfb :99 &\n"
                + "./Xvfb :99 &\n"
                + "ls opt/Xvfb\n";

        assertEquals(List.of("BRACED", "PLAIN", "WITH_DEFAULT", "ESCAPED", "AFTER_ESCAPE", "COMMENTED", "PREFIX",
                "QUOTED", "TRAILING", "OLD", "IN_SINGLE", "IN_ESCAPED"), variables(env));
        assertEquals(List.of("ESCAPED", "COMMENTED", "PREFIX", "TRAILING", "OLD"),
                variables(env).stream().filter(name -> !reads(compose, name)).toList());
        assertEquals(List.of(), versionKeys("a.yml", compose));
        assertEquals(List.of("a.yml:1: version: obsolete; delete the line"),
                versionKeys("a.yml", "version: \"3\"\nservices: {}\n"));
        assertEquals(List.of("a.yml:2: version: obsolete; delete the line"),
                versionKeys("a.yml", "# version: \"3\"\n  version: '3'\n  services: {}\n"));
        assertEquals(List.of("a.yml:3: version: obsolete; delete the line"),
                versionKeys("a.yml", "---\nservices: {}\n\"version\" : \"3\"\n"));
        String v1 = "docker-compose: the v1 binary; call docker compose";
        assertEquals(List.of("run.sh:5: " + v1, "run.sh:6: " + v1, "run.sh:7: " + v1, "run.sh:8: " + v1,
                "run.sh:9: " + v1, "run.sh:13: " + v1, "run.sh:14: " + v1, "run.sh:15: " + v1, "run.sh:16: " + v1,
                "run.sh:17: " + v1, "run.sh:18: " + v1),
                calls("run.sh", demo, "docker-compose", "the v1 binary; call docker compose"));
        String xvfb = "Xvfb: not in the image; delete the line";
        assertEquals(List.of("run.sh:2: " + xvfb, "run.sh:3: " + xvfb, "run.sh:4: " + xvfb, "run.sh:8: " + xvfb,
                "run.sh:9: " + xvfb, "run.sh:10: " + xvfb),
                calls("run.sh", bin, "Xvfb", "not in the image; delete the line"));
    }

    @Test
    void eachVariableOfTheEnvFilesOfThePingToolsIsReadByItsComposeFile() throws Exception {
        Path compose = Path.of("src/main/docker-compose");
        List<String> unused = new ArrayList<>();
        int variables = 0;
        for (String tool : List.of("ping-tool-cons", "ping-tool-prod")) {
            String file = Files.readString(compose.resolve(tool).resolve("docker-compose.yml"));
            for (String env : List.of(".env", ".env-inhouse")) {
                for (String name : variables(Files.readString(compose.resolve(tool).resolve(env)))) {
                    variables++;
                    if (!reads(file, name)) {
                        unused.add(tool + "/" + env + ": " + name);
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
            problems.addAll(versionKeys(file.toString(), Files.readString(file)));
        }
        String demo = Files.readString(Path.of("run_demo_docker_compose.sh"));
        problems.addAll(calls("run_demo_docker_compose.sh", demo, "docker-compose", "the v1 binary; call docker compose"));

        assertEquals(2, composeFiles.size(), composeFiles.toString());
        assertEquals(List.of(), problems);
        assertEquals(2, demo.lines().filter(line -> line.startsWith("docker compose ")).count(), demo);
    }

    @Test
    void theScriptsThePingToolsRunStartNoXvfb() throws Exception {
        List<String> problems = new ArrayList<>();
        try (Stream<Path> scripts = Files.list(Path.of("src/main/docker-compose/bin"))) {
            for (Path script : scripts.filter(f -> f.toString().endsWith(".sh")).sorted().toList()) {
                problems.addAll(calls(script.toString(), Files.readString(script), "Xvfb", "not in the image; delete the line"));
            }
        }

        assertEquals(List.of(), problems);
    }

    @Test
    void eachLatencyProbeScriptNamesTheStepItRunsInItsBanner() throws Exception {
        Pattern banner = Pattern.compile("(?m)^echo \" Start the LATENCY analysis \\.\\.\\. (\\S+) ?\"$");
        Pattern step = Pattern.compile("org\\.opentsx\\.lg\\.kping\\.EventFlowAnalysis(\\w+)");
        List<String> problems = new ArrayList<>();
        List<Path> scripts = new ArrayList<>();
        for (String folder : List.of("bin", "src/main/docker-compose/bin")) {
            try (Stream<Path> files = Files.list(Path.of(folder))) {
                files.filter(f -> f.getFileName().toString().startsWith("run_LatencyProbe_")).forEach(scripts::add);
            }
        }
        scripts.sort(null);
        for (Path script : scripts) {
            String text = Files.readString(script);
            Matcher named = banner.matcher(text);
            Matcher runs = step.matcher(text);
            assertTrue(named.find() && runs.find(), script.toString());
            String expected = runs.group(1).toUpperCase(Locale.ROOT);
            if (!named.group(1).equals(expected)) {
                problems.add(script + ": " + named.group(1) + ": not the step of EventFlowAnalysis" + runs.group(1)
                        + "; write " + expected);
            }
        }

        assertEquals(8, scripts.size(), scripts.toString());
        assertEquals(List.of(), problems);
    }

    private static void assertPinned(String image, String where) {
        int tag = image.lastIndexOf(':');
        assertTrue(tag > image.lastIndexOf('/') && tag < image.length() - 1, where + ": " + image);
        assertNotEquals("latest", image.substring(tag + 1), where + ": " + image);
    }
}
