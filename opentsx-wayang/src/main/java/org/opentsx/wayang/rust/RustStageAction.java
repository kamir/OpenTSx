package org.opentsx.wayang.rust;

import com.fasterxml.jackson.databind.JsonNode;

@FunctionalInterface
public interface RustStageAction {
    JsonNode execute();
}
