package org.opentsx.core;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.CommonConfigurationKeysPublic;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class TSBucketLocalFilesTest {

    @TempDir
    Path directory;

    @Test
    void localFilesComeFromTheSourceFolderOfTheCaller() {
        Configuration configuration = new Configuration(false);
        configuration.set(CommonConfigurationKeysPublic.FS_DEFAULT_NAME_KEY,
                CommonConfigurationKeysPublic.FS_DEFAULT_NAME_DEFAULT);
        TSBucket bucket = new TSBucket(configuration);
        Path source = directory.resolve("missing-source");

        IOException e = assertThrows(IOException.class,
                () -> bucket.createBucketFromLocalFilesInDirectory(source.toString(), "group", 1));

        assertEquals(source.resolve("group") + ": cannot list the files; expected a readable folder", e.getMessage());
    }
}
