package org.opentsx.tsa;

import org.opentsx.util.OpenTSxClusterLink;

import java.util.Properties;

public class TSxStreamingAppHelper {

    public static Properties getKafkaClientProperties() {

        /**
         * Read environment variables to configure the tool ...
         */
        OpenTSxClusterLink.init();
        return OpenTSxClusterLink.getClientProperties();

    }

}
