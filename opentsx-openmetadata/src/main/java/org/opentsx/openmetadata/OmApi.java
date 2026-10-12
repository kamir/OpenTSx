package org.opentsx.openmetadata;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Optional;

/** The few OpenMetadata REST calls the publisher needs. Paths are relative to {@code /api}, e.g. {@code /v1/tables}. */
public interface OmApi {

    /** Create-or-update ({@code PUT}); returns the entity. */
    JsonNode put(String path, JsonNode body);

    /** {@code POST}; returns the response body (may be empty). */
    JsonNode post(String path, JsonNode body);

    /** {@code GET}; empty on 404. */
    Optional<JsonNode> get(String path);
}
