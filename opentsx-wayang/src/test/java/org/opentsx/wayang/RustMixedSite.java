package org.opentsx.wayang;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.opentsx.kafka.TopicLayout;
import org.opentsx.wayang.site.ConformanceHandshake;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

final class RustMixedSite {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();
    private static final String SITE = "s3";
    private final Path directory;
    private final Path executable;
    private final String digest;
    private final String version;
    private final String bootstrap;
    private final String job;

    RustMixedSite(Path directory, String bootstrap, String job) throws Exception {
        this.directory = directory;
        this.bootstrap = bootstrap;
        this.job = job;
        Files.createDirectory(directory, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        Path original = requiredPath("opentsx.rust.b3.runner");
        executable = directory.resolve("tsx-runner");
        byte[] bytes = Files.readAllBytes(original);
        Files.copy(original, executable);
        Files.setPosixFilePermissions(executable, PosixFilePermissions.fromString("rwx------"));
        assertArrayEquals(bytes, Files.readAllBytes(executable));
        digest = "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        String output = process(List.of(executable.toString(), "--version"), "version", null).strip();
        assertTrue(output.startsWith("tsx-runner "), output);
        version = output.substring("tsx-runner ".length());
        qualify(original);
    }

    ConformanceHandshake.QualifiedRuntime pin() {
        return new ConformanceHandshake.QualifiedRuntime(SITE, "node-" + SITE, version, digest,
                "opentsx.rust.site-state.v1", "rust");
    }

    void configure(URI endpoint, TopicLayout topics, String signal, Map<String, Path> pem,
                   KeyPair driver, KeyPair site, String compact) throws Exception {
        for (String key : List.of("ca", "certificate", "privateKey")) {
            Path copy = directory.resolve(key + ".pem");
            Files.copy(pem.get(key), copy);
            Files.setPosixFilePermissions(copy, PosixFilePermissions.fromString("rw-------"));
        }
        pem(directory.resolve("driver.pem"), "PUBLIC KEY", driver.getPublic().getEncoded());
        pem(directory.resolve("site.pem"), "PRIVATE KEY", site.getPrivate().getEncoded());
        privateWrite(directory.resolve("plan.jws"), compact);
        ObjectNode config = JSON.createObjectNode().put("schema", "opentsx.rust-site-config.v1").put("site", SITE).put("node", "node-" + SITE);
        config.putObject("driver").put("url", endpoint.toString()).put("ca", "ca.pem")
                .put("certificate", "certificate.pem").put("privateKey", "privateKey.pem");
        config.put("driverPublicKey", "driver.pem").put("sitePrivateKey", "site.pem").put("qualification", "qualification.json").put("state", "state");
        config.putArray("jobs").add(job);
        var bindings = config.putObject("bindings");
        var local = bindings.putObject("site").put("bootstrap", bootstrap);
        local.putArray("inputs").add(topics.episodes()); local.putArray("outputs").add(topics.aggregates()).add(topics.dagStatus());
        var alias = bindings.putObject("site-" + SITE).put("bootstrap", bootstrap);
        alias.putArray("inputs").add(topics.episodes()); alias.putArray("outputs");
        var remote = bindings.putObject("signal").put("bootstrap", signal);
        remote.putArray("inputs"); remote.putArray("outputs").add(topics.aggregates());
        var policy = config.putObject("policy");
        policy.putArray("domains").add(topics.domain());
        policy.putObject("inputs").putArray("site-" + SITE).add(topics.episodes());
        var outputs = policy.putObject("outputs");
        outputs.putArray("site").add("aggregates").add("dag-status");
        outputs.putArray("signal").add("aggregates");
        policy.putArray("catalogs").add("tsx-catalog/1");
        config.putObject("limits").put("records", 100).put("payloadBytes", 1_048_576).put("inputBytes", 16_777_216)
                .put("samples", 1_000_000).put("outputBytes", 16_777_216).put("series", 1024).put("stateBytes", 1_048_576);
        privateWrite(directory.resolve("config.json"), config.toString());
    }

    String conform() throws Exception {
        return process(List.of(executable.toString(), "conform", "--config", directory.resolve("config.json").toString(),
                "--job", job), "conform", null).strip();
    }

    JsonNode run(String name) throws Exception {
        return JSON.readTree(process(List.of(executable.toString(), "run", "--plan", directory.resolve("plan.jws").toString(),
                "--config", directory.resolve("config.json").toString()), name, null));
    }

    private void qualify(Path original) throws Exception {
        Path runnerLog = requiredPath("opentsx.rust.b3.testLog"), contractsLog = requiredPath("opentsx.rust.b3.contractsLog");
        Path protocol = directory.resolve("protocol"), placement = directory.resolve("placement");
        Path probe = original.resolveSibling("examples").resolve("b1_acceptance");
        Path placementBinary = original.resolveSibling("examples").resolve("b1_placement");
        process(List.of("python3", ROOT.resolve("tools/rust_b1_conformance.py").toString(), "--binary", probe.toString(),
                "--output", protocol.toString()), "protocol", bootstrap);
        RustB1Fixture.main(new String[]{"placement-prepare", bootstrap, placement.toString()});
        JsonNode rust = JSON.readTree(process(List.of(placementBinary.toString(), placement.resolve("placement.json").toString()), "placement", bootstrap));
        RustB1Fixture.main(new String[]{"placement-verify", bootstrap, placement.toString()});
        ObjectNode results = JSON.createObjectNode();
        results.set("rust", rust); results.set("java", JSON.readTree(placement.resolve("placement-verified.json").toFile()));
        JSON.writeValue(placement.resolve("result.json").toFile(), results);
        process(List.of("python3", ROOT.resolve("tools/rust_b3_qualification.py").toString(), "--runner", executable.toString(),
                "--bootstrap", bootstrap, "--test-log", runnerLog.toString(), "--test-log", contractsLog.toString(),
                "--protocol-report", protocol.resolve("result.json").toString(), "--placement-report", placement.resolve("result.json").toString(),
                "--placement-manifest", placement.resolve("placement.json").toString(), "--expires", Instant.now().plusSeconds(7200).toString(),
                "--output", directory.resolve("qualification.json").toString()), "qualification", null);
    }

    private String process(List<String> arguments, String name, String proxy) throws Exception {
        Path stdout = directory.resolve(name + ".stdout.log"), stderr = directory.resolve(name + ".stderr.log");
        ProcessBuilder builder = new ProcessBuilder(arguments).redirectOutput(stdout.toFile()).redirectError(stderr.toFile());
        if (proxy != null) builder.environment().put("KAFSCALE_BOOTSTRAP", proxy);
        Process process = builder.start();
        try {
            process.getOutputStream().close();
            if (!process.waitFor(20, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                if (!process.waitFor(30, TimeUnit.SECONDS)) throw new IOException("mixed Rust fixture process cleanup failed; inspect " + stderr);
                throw new IOException("mixed Rust fixture process timed out; inspect " + stderr);
            }
        } catch (InterruptedException error) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("mixed Rust fixture process was interrupted; inspect " + stderr, error);
        } catch (IOException error) {
            process.destroyForcibly();
            throw error;
        }
        assertEquals(0, process.exitValue(), () -> "mixed Rust fixture process failed; inspect " + stderr + ": " + readError(stderr));
        return Files.readString(stdout);
    }

    private static String readError(Path path) {
        try { return Files.readString(path); }
        catch (IOException error) { throw new java.io.UncheckedIOException(error); }
    }

    private static Path requiredPath(String property) throws IOException {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) throw new IllegalStateException(property + " is required for mixed federation acceptance");
        Path path = Path.of(value).toRealPath();
        if (!Files.isRegularFile(path)) throw new IllegalStateException(property + " must name an existing regular file");
        return path;
    }

    private static void pem(Path path, String kind, byte[] bytes) throws IOException {
        privateWrite(path, "-----BEGIN " + kind + "-----\n" + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(bytes)
                + "\n-----END " + kind + "-----\n");
    }

    private static void privateWrite(Path path, String text) throws IOException {
        Files.writeString(path, text);
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
    }
}
