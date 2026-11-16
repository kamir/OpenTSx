package org.opentsx.wayang;

import com.fasterxml.jackson.databind.node.ObjectNode;

/** One {@code opentsx.vector.v1} measuring point and its Kafka key (the canonical text of its key). */
public record TsxVectorRecord(String key, ObjectNode json) {
}
