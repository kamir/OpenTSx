package org.opentsx.flink.pfd;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public class PFDDefinition {
    public String name;
    public String version;
    public PFDInput input;
    public List<PFDStep> steps;
    public PFDOutput output;

    public static class PFDInput {
        public String type;
        public String topic;
        public String schema;
    }

    public static class PFDOutput {
        public String type;
        public String topic;
        public String path;
    }
}
