package org.opentsx.core.pfd;

import java.util.Map;

public final class PFDValidation {

    private PFDValidation() {}

    public static void validateOperation(String operation, Map<String, Object> params) {
        if (!"NORMALIZE".equals(operation)) {
            throw new IllegalArgumentException("Unsupported PFD operation: " + operation);
        }
        if (params != null) {
            for (String key : params.keySet()) {
                if (!"method".equals(key)) {
                    throw new IllegalArgumentException("Unsupported NORMALIZE parameter: " + key);
                }
            }
            if (params.containsKey("method") && !"center".equals(params.get("method"))) {
                throw new IllegalArgumentException("NORMALIZE supports only method center");
            }
        }
    }

    public static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
    }
}
