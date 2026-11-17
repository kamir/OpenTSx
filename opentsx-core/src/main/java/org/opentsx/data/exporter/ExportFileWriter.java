package org.opentsx.data.exporter;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.util.Objects;

public final class ExportFileWriter {

    private ExportFileWriter() {}

    public static void write(File file, String text) throws IOException {
        ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(text));
        byte[] bytes = new byte[encoded.remaining()];
        encoded.get(bytes);
        write(file, bytes);
    }

    public static void write(File file, byte[] bytes) throws IOException {
        Path target = Objects.requireNonNull(file, "Export destination is required").toPath().toAbsolutePath();
        Path temporary = null;
        try {
            if (target.getParent() == null) {
                throw new IOException("Export destination must name a file: " + target);
            }
            if (Files.isSymbolicLink(target)) {
                throw new IOException("Export destination must not be a symbolic link: " + target);
            }
            Files.createDirectories(target.getParent());
            temporary = Files.createTempFile(target.getParent(), ".opentsx-export-", ".tmp");
            Files.write(temporary, bytes);
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                PosixFileAttributeView permissions = Files.getFileAttributeView(target,
                        PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
                if (permissions != null) {
                    Files.setPosixFilePermissions(temporary, permissions.readAttributes().permissions());
                }
            }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException failure) {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException | RuntimeException cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
            }
            throw failure;
        }
    }
}
