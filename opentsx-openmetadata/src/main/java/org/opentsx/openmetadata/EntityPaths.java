package org.opentsx.openmetadata;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * How OpenMetadata derives the fully qualified name of an entity created via {@code PUT <path>} - used by the dry-run
 * client and the test server, so the publisher can be exercised without a running OpenMetadata.
 */
public final class EntityPaths {

    private static final Pattern COLUMN_LINK = Pattern.compile("^<#E::table::(.+)::columns::([^:>]+)>$");

    private EntityPaths() {
    }

    public static Optional<String> fqnOf(String path, JsonNode body) {
        String name = body.path("name").asText();
        String fqn = switch (path) {
            case "/v1/services/databaseServices", "/v1/services/messagingServices", "/v1/services/pipelineServices",
                 "/v1/classifications", "/v1/glossaries", "/v1/dataQuality/testDefinitions" -> Fqn.quote(name);
            case "/v1/databases", "/v1/topics", "/v1/pipelines" -> Fqn.child(body.path("service").asText(), name);
            case "/v1/databaseSchemas" -> Fqn.child(body.path("database").asText(), name);
            case "/v1/tables" -> Fqn.child(body.path("databaseSchema").asText(), name);
            case "/v1/tags" -> Fqn.child(body.hasNonNull("parent") ? body.get("parent").asText()
                    : body.path("classification").asText(), name);
            case "/v1/glossaryTerms" -> Fqn.child(body.hasNonNull("parent") ? body.get("parent").asText()
                    : body.path("glossary").asText(), name);
            case "/v1/dataQuality/testSuites/basic" -> name;
            case "/v1/dataQuality/testCases" -> {
                Matcher m = COLUMN_LINK.matcher(body.path("entityLink").asText());
                yield m.matches() ? m.group(1) + "." + Fqn.quote(m.group(2)) + "." + Fqn.quote(name)
                        : body.path("entityLink").asText() + "." + Fqn.quote(name);
            }
            default -> null;
        };
        return Optional.ofNullable(fqn);
    }

    /** Entity type of an entity created via {@code PUT <path>} (as used in entity references). */
    public static Optional<String> typeOf(String path) {
        return Optional.ofNullable(switch (path) {
            case "/v1/services/databaseServices" -> "databaseService";
            case "/v1/services/messagingServices" -> "messagingService";
            case "/v1/services/pipelineServices" -> "pipelineService";
            case "/v1/databases" -> "database";
            case "/v1/databaseSchemas" -> "databaseSchema";
            case "/v1/tables" -> "table";
            case "/v1/topics" -> "topic";
            case "/v1/pipelines" -> "pipeline";
            case "/v1/classifications" -> "classification";
            case "/v1/tags" -> "tag";
            case "/v1/glossaries" -> "glossary";
            case "/v1/glossaryTerms" -> "glossaryTerm";
            case "/v1/dataQuality/testDefinitions" -> "testDefinition";
            case "/v1/dataQuality/testSuites/basic" -> "testSuite";
            case "/v1/dataQuality/testCases" -> "testCase";
            default -> null;
        });
    }
}
