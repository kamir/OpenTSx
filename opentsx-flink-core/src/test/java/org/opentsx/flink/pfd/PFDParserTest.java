package org.opentsx.flink.pfd;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;
import static org.junit.Assert.*;

public class PFDParserTest {

    @Test
    public void testParsePFD() throws Exception {
        String json = "{\n" +
                "  \"name\": \"Test Flow\",\n" +
                "  \"version\": \"1.0\",\n" +
                "  \"input\": {\n" +
                "    \"type\": \"KAFKA\",\n" +
                "    \"topic\": \"in-topic\",\n" +
                "    \"schema\": \"Observation\"\n" +
                "  },\n" +
                "  \"steps\": [\n" +
                "    {\n" +
                "      \"id\": \"step1\",\n" +
                "      \"operation\": \"NORMALIZE\",\n" +
                "      \"params\": {\"method\": \"z-score\"}\n" +
                "    }\n" +
                "  ],\n" +
                "  \"output\": {\n" +
                "    \"type\": \"KAFKA\",\n" +
                "    \"topic\": \"out-topic\",\n" +
                "    \"path\": \"/tmp/out\"\n" +
                "  }\n" +
                "}";

        ObjectMapper mapper = new ObjectMapper();
        PFDDefinition pfd = mapper.readValue(json, PFDDefinition.class);

        assertEquals("Test Flow", pfd.name);
        assertEquals("KAFKA", pfd.input.type);
        assertEquals(1, pfd.steps.size());
        assertEquals("NORMALIZE", pfd.steps.get(0).operation);
    }
}
