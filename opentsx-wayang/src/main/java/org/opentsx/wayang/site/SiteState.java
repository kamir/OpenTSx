package org.opentsx.wayang.site;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;

final class SiteState implements AutoCloseable {
    final Path directory;
    final ObjectNode value;
    private final FileChannel channel;
    private final FileLock lock;
    private boolean failed;

    SiteState(Path directory) {
        try {
            this.directory = directory.toAbsolutePath().normalize();
            if (Files.isSymbolicLink(this.directory)) throw new IOException("site state directory is a symbolic link");
            Files.createDirectories(this.directory);
            Files.setPosixFilePermissions(this.directory, PosixFilePermissions.fromString("rwx------"));
            Path lockPath = this.directory.resolve("site.lock");
            if (Files.isSymbolicLink(lockPath)) throw new IOException("site lock is a symbolic link");
            channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            Files.setPosixFilePermissions(lockPath, PosixFilePermissions.fromString("rw-------"));
            FileLock acquired;
            try { acquired = channel.tryLock(); }
            catch (RuntimeException | IOException error) { channel.close(); throw error; }
            if (acquired == null) { channel.close(); throw new IOException("site state is already owned"); }
            lock = acquired;
            Path path = this.directory.resolve("state.json");
            try {
                if (Files.isSymbolicLink(path)) throw new IOException("site journal is a symbolic link");
                value = Files.exists(path) ? (ObjectNode) SiteJson.parse(SiteJson.read(path, 16 * 1024 * 1024), 16 * 1024 * 1024)
                        : SiteJson.JSON.createObjectNode();
            } catch (RuntimeException | IOException error) { lock.release(); channel.close(); throw error; }
        } catch (IOException error) { throw new UncheckedIOException("cannot open durable site state", error); }
    }

    void check() {
        if (failed || !lock.isValid()) throw new IllegalStateException("durable site journal requires recovery");
    }

    void save() {
        check();
        try { write(directory.resolve("state.json"), value); }
        catch (RuntimeException error) { failed = true; throw error; }
    }

    static void write(Path path, JsonNode value) {
        try {
            if (Files.isSymbolicLink(path) || Files.isSymbolicLink(path.getParent())) throw new IOException("site state path is a symbolic link");
            byte[] encoded = SiteJson.bytes(value);
            if (encoded.length > 16 * 1024 * 1024) throw new IOException("site journal capacity exceeded");
            Files.createDirectories(path.getParent());
            Path temporary = Files.createTempFile(path.getParent(), ".site-", ".tmp");
            try {
                Files.setPosixFilePermissions(temporary, PosixFilePermissions.fromString("rw-------"));
                Files.write(temporary, encoded);
                try (FileChannel file = FileChannel.open(temporary, StandardOpenOption.WRITE)) { file.force(true); }
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                try (FileChannel parent = FileChannel.open(path.getParent(), StandardOpenOption.READ)) { parent.force(true); }
            } finally { Files.deleteIfExists(temporary); }
        } catch (IOException error) { throw new UncheckedIOException("cannot persist site state", error); }
    }

    @Override public void close() {
        try { lock.release(); channel.close(); }
        catch (IOException error) { throw new UncheckedIOException("cannot close site state", error); }
    }
}
