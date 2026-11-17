package org.opentsx.wayang;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DagEnumListValidationTest {

    @Test
    void validatesEveryMemberOfAnEnumeratedList(@TempDir Path catalog) throws Exception {
        Files.writeString(catalog.resolve("dq.result.yaml"), """
                schema: opentsx.processor.v1
                id: dq.result
                version: 1
                kind: transformer
                title: Data-quality findings
                inputs: [{name: in, type: stats}]
                outputs: [{name: out, type: events}]
                params: [{name: checks, type: list, values: [GAPS, FRESHNESS]}]
                runtimes: {wayang: {status: available}}
                """);
        ObjectMapper json = new ObjectMapper();
        for (String checks : List.of("[\"OTHER\"]", "[1]", "[\"GAPS\",\"OTHER\"]")) {
            JsonNode dag = json.readTree("{\"nodes\":[{\"id\":\"dq\",\"op\":\"dq.result\",\"params\":{\"checks\":" + checks + "}}]}");
            assertEquals("node dq: parameter checks must be one of [GAPS, FRESHNESS]",
                    assertThrows(IllegalArgumentException.class, () -> DagToWayang.parameters(dag, catalog)).getMessage());
        }
        for (String checks : List.of("[]", "[\"GAPS\",\"FRESHNESS\"]")) {
            JsonNode dag = json.readTree("{\"nodes\":[{\"id\":\"dq\",\"op\":\"dq.result\",\"params\":{\"checks\":" + checks + "}}]}");
            assertEquals(dag, DagToWayang.parameters(dag, catalog));
        }
    }
}
