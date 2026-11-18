package org.opentsx.wayang;

import org.junit.jupiter.api.extension.AnnotatedElementContext;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.io.TempDirFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

final class FederationLogDirectory implements TempDirFactory {
    @Override
    public Path createTempDirectory(AnnotatedElementContext elementContext, ExtensionContext extensionContext)
            throws IOException {
        Path root = Files.createDirectories(Path.of("target", "federation-test-logs").toAbsolutePath());
        return Files.createTempDirectory(root, "junit-");
    }
}
