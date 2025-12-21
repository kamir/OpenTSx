package org.opentsx.flink.pfd;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public class PFDStep {
    public String id;
    public String operation;
    public Map<String, Object> params;
}
