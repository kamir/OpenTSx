package org.opentsx.wayang;

import java.util.Map;
import java.util.Set;

/**
 * What one increment reads from one topic: {@code [from, to)} per partition, and the schema fingerprints the plan
 * allows on it (S3.2 of docs/datamodel-2026/20-WAYANG-TS-OPERATORS-SPEC.md).
 *
 * @param ranges       partition to {@code {from, toExclusive}}
 * @param fingerprints CRC-64-AVRO fingerprints as 16 lower-case hex digits
 */
public record TsxRead(String bootstrap, String topic, Map<Integer, long[]> ranges, Set<String> fingerprints, String job,
                      int planVersion) {
}
