package org.opentsx.wayang.site;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public final class EphemeralSiteTlsFixture implements AutoCloseable {
    private final Path directory;
    private final char[] password;
    private final Map<String, String> certificateSites = new HashMap<>();
    private final Map<String, SSLContext> clients = new HashMap<>();
    private final SSLContext server;
    private final SSLContext anonymous;

    public static EphemeralSiteTlsFixture create(String... sites) throws Exception {
        return new EphemeralSiteTlsFixture(Set.of(sites));
    }

    private EphemeralSiteTlsFixture(Set<String> sites) throws Exception {
        if (sites.isEmpty() || sites.contains("server") || sites.stream().anyMatch(site -> !site.matches("[a-z0-9][a-z0-9-]{0,62}"))) {
            throw new IllegalArgumentException("valid test sites are required");
        }
        directory = Files.createTempDirectory("opentsx-site-tls-");
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        password = Base64.getUrlEncoder().withoutPadding().encodeToString(random).toCharArray();
        try {
            generate("server", "CN=localhost", "SAN=dns:localhost,ip:127.0.0.1");
            Path trust = directory.resolve("trust.p12");
            for (String site : sites) generate(site, "CN=" + site, null);
            for (String name : java.util.stream.Stream.concat(java.util.stream.Stream.of("server"), sites.stream()).toList()) {
                Path certificate = directory.resolve(name + ".crt");
                keytool("-exportcert", "-alias", name, "-keystore", directory.resolve(name + ".p12").toString(),
                        "-storetype", "PKCS12", "-storepass:env", "OPENTSX_TEST_STORE_PASSWORD", "-file", certificate.toString());
                keytool("-importcert", "-noprompt", "-alias", name, "-keystore", trust.toString(), "-storetype", "PKCS12",
                        "-storepass:env", "OPENTSX_TEST_STORE_PASSWORD", "-file", certificate.toString());
                if (!name.equals("server")) certificateSites.put(SiteJson.hash(Files.readAllBytes(certificate)), name);
            }
            server = context(directory.resolve("server.p12"), trust);
            anonymous = context(null, trust);
            for (String site : sites) clients.put(site, context(directory.resolve(site + ".p12"), trust));
        } catch (Exception error) { close(); throw error; }
    }

    private void generate(String name, String subject, String extension) throws Exception {
        List<String> args = new ArrayList<>(List.of("-genkeypair", "-alias", name, "-keyalg", "Ed25519", "-dname", subject,
                "-validity", "2", "-keystore", directory.resolve(name + ".p12").toString(), "-storetype", "PKCS12",
                "-storepass:env", "OPENTSX_TEST_STORE_PASSWORD", "-keypass:env", "OPENTSX_TEST_STORE_PASSWORD"));
        if (extension != null) { args.add("-ext"); args.add(extension); }
        keytool(args.toArray(String[]::new));
    }

    private void keytool(String... arguments) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "keytool").toString());
        command.addAll(List.of(arguments));
        ProcessBuilder builder = new ProcessBuilder(command).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD);
        builder.environment().put("OPENTSX_TEST_STORE_PASSWORD", new String(password));
        Process process = builder.start();
        if (!process.waitFor(30, TimeUnit.SECONDS)) { process.destroyForcibly(); throw new IOException("test certificate creation timed out"); }
        if (process.exitValue() != 0) throw new IOException("test certificate creation failed");
        try (var paths = Files.list(directory)) {
            for (Path path : paths.toList()) Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
        }
    }

    private SSLContext context(Path keys, Path trust) throws Exception {
        javax.net.ssl.KeyManager[] identity = new javax.net.ssl.KeyManager[0];
        if (keys != null) {
            KeyStore keyStore = KeyStore.getInstance("PKCS12");
            try (var input = Files.newInputStream(keys)) { keyStore.load(input, password); }
            KeyManagerFactory managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            managers.init(keyStore, password);
            identity = managers.getKeyManagers();
        }
        KeyStore trusted = KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(trust)) { trusted.load(input, password); }
        TrustManagerFactory trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManagers.init(trusted);
        SSLContext context = SSLContext.getInstance("TLSv1.3");
        context.init(identity, trustManagers.getTrustManagers(), null);
        return context;
    }

    public SSLContext serverContext() { return server; }
    public SSLContext anonymousClientContext() { return anonymous; }
    public SSLContext clientContext(String site) {
        SSLContext context = clients.get(site);
        if (context == null) throw new IllegalArgumentException("test client is unknown");
        return context;
    }
    public Map<String, String> certificateSites() { return Map.copyOf(certificateSites); }

    public Map<String, Path> clientPem(String site) throws Exception {
        if (!clients.containsKey(site)) throw new IllegalArgumentException("test client is unknown");
        Path output = directory.resolve(site + "-pem");
        Files.createDirectory(output);
        Files.setPosixFilePermissions(output, PosixFilePermissions.fromString("rwx------"));
        KeyStore keys = KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(directory.resolve(site + ".p12"))) { keys.load(input, password); }
        Path ca = output.resolve("ca.pem"), certificate = output.resolve("client.pem"), key = output.resolve("client-key.pem");
        writePem(ca, "CERTIFICATE", Files.readAllBytes(directory.resolve("server.crt")));
        writePem(certificate, "CERTIFICATE", keys.getCertificate(site).getEncoded());
        writePem(key, "PRIVATE KEY", keys.getKey(site, password).getEncoded());
        return Map.of("ca", ca, "certificate", certificate, "privateKey", key);
    }

    private static void writePem(Path path, String type, byte[] bytes) throws IOException {
        String encoded = Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(bytes);
        Files.writeString(path, "-----BEGIN " + type + "-----\n" + encoded + "\n-----END " + type + "-----\n");
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
    }

    @Override public void close() throws IOException {
        java.util.Arrays.fill(password, '\0');
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }
}
