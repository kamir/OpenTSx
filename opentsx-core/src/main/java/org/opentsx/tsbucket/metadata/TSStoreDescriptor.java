package org.opentsx.tsbucket.metadata;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;

public class TSStoreDescriptor {

    private static final Logger LOG = LoggerFactory.getLogger(TSStoreDescriptor.class);

    /**
     * Returns opentsx/plots/ below user.home. The folder is created when a chart or table is written into it.
     */
    public static String _getPathFor_TSCharts() {

        File f = new File( System.getProperty("user.home" ) + "/opentsx/plots/" );

        LOG.debug("chart folder path={}", f.getAbsolutePath());

        return f.getAbsolutePath() + "/";

    }

}
