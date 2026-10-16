package limitium.art.camunda.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import limitium.art.camunda.evaluator.diff.DmnDiff;
import limitium.art.camunda.evaluator.diff.DmnDiffer;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Covers the content-based diff and merge endpoints, which need neither git nor Kafka. */
class DiffApiIT {

    private static final int PORT = 18081;
    private static final String BASE = "http://localhost:" + PORT;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static ConfigurableApplicationContext app;

    private static final String LEFT = dmn("Rule_1", "&lt; 10", "\"LOW\"");
    private static final String RIGHT = dmn("Rule_1", "&lt; 10", "\"HIGH\"");

    private static String dmn(String ruleId, String input, String output) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<definitions id=\"Defs\" name=\"Defs\" namespace=\"http://camunda.org/schema/1.0/dmn\"\n"
                + "  xmlns=\"https://www.omg.org/spec/DMN/20191111/MODEL/\">\n"
                + "  <decision id=\"Decision_1\" name=\"Decision\">\n"
                + "    <decisionTable id=\"DecisionTable_1\" hitPolicy=\"UNIQUE\">\n"
                + "      <input id=\"Input_1\">\n"
                + "        <inputExpression id=\"InputExpression_1\" typeRef=\"number\"><text>amount</text></inputExpression>\n"
                + "      </input>\n"
                + "      <output id=\"Output_1\" name=\"result\" typeRef=\"string\"/>\n"
                + "      <rule id=\"" + ruleId + "\">\n"
                + "        <inputEntry id=\"InputEntry_1\"><text>" + input + "</text></inputEntry>\n"
                + "        <outputEntry id=\"OutputEntry_1\"><text>" + output + "</text></outputEntry>\n"
                + "      </rule>\n"
                + "    </decisionTable>\n"
                + "  </decision>\n"
                + "</definitions>\n";
    }

    @BeforeAll
    static void startServer() {
        System.setProperty("dmn.data.root", Path.of("build/tmp/diff-it").toString());
        System.setProperty("server.port", String.valueOf(PORT));
        app = SpringApplication.run(CamundaEvalApplication.class);
    }

    @AfterAll
    static void stopServer() {
        if (app != null) {
            app.close();
        }
        System.clearProperty("dmn.data.root");
        System.clearProperty("server.port");
    }

    @Test
    @DisplayName("the diff model serializes from public fields alone, with no Jackson annotations")
    void diffSerializesWithoutAnnotations() throws Exception {
        DmnDiff diff = DmnDiffer.diff(LEFT, RIGHT, "before", "after");
        JsonNode json = MAPPER.readTree(MAPPER.writeValueAsString(diff));

        assertEquals("before", json.get("leftLabel").asText());
        assertFalse(json.get("identical").asBoolean());
        assertTrue(json.get("summary").get("rulesChanged").asInt() >= 1);

        JsonNode rule = json.get("decisions").get(0).get("rules").get(0);
        assertEquals("CHANGED", rule.get("change").asText(), "enums serialize by name");
        assertEquals("id", rule.get("matchedBy").asText());
        assertEquals("\"LOW\"", rule.get("outputs").get(0).get("left").asText());
        assertEquals("\"HIGH\"", rule.get("outputs").get(0).get("right").asText());
    }

    @Test
    void diffEndpointReturnsSemanticDiff() throws Exception {
        String body = MAPPER.writeValueAsString(new java.util.HashMap<String, String>() {{
            put("leftXml", LEFT);
            put("rightXml", RIGHT);
            put("leftLabel", "v1");
            put("rightLabel", "v2");
        }});

        JsonNode json = MAPPER.readTree(post("/api/diff", body));
        assertFalse(json.get("identical").asBoolean());
        assertEquals("v1", json.get("leftLabel").asText());
        assertEquals(1, json.get("summary").get("rulesChanged").asInt());
    }

    @Test
    void diffEndpointRejectsMissingContent() throws Exception {
        String response = post("/api/diff", "{\"leftXml\":\"<x/>\"}");
        assertTrue(response.contains("required"), "expected a validation message, got: " + response);
    }

    @Test
    @DisplayName("merge-preview reports the contested cell rather than forcing a whole-file choice")
    void mergePreviewReportsCellConflict() throws Exception {
        String ours = dmn("Rule_1", "&lt; 10", "\"OURS\"");
        String theirs = dmn("Rule_1", "&lt; 10", "\"THEIRS\"");

        String body = MAPPER.writeValueAsString(new java.util.HashMap<String, String>() {{
            put("baseXml", LEFT);
            put("oursXml", ours);
            put("theirsXml", theirs);
        }});

        JsonNode json = MAPPER.readTree(post("/api/diff/merge-preview", body));
        assertFalse(json.get("clean").asBoolean());
        assertEquals(1, json.get("conflicts").size());

        JsonNode conflict = json.get("conflicts").get(0);
        assertEquals("CELL", conflict.get("kind").asText());
        assertEquals("\"OURS\"", conflict.get("ours").asText());
        assertEquals("\"THEIRS\"", conflict.get("theirs").asText());
    }

    @Test
    void mergePreviewAppliesACleanChange() throws Exception {
        String body = MAPPER.writeValueAsString(new java.util.HashMap<String, String>() {{
            put("baseXml", LEFT);
            put("oursXml", LEFT);
            put("theirsXml", RIGHT);
        }});

        JsonNode json = MAPPER.readTree(post("/api/diff/merge-preview", body));
        assertTrue(json.get("clean").asBoolean());
        assertTrue(json.get("mergedXml").asText().contains("\"HIGH\""));
    }

    private static String post(String path, String json) throws Exception {
        try (CloseableHttpClient client = HttpClients.createDefault()) {
            HttpPost request = new HttpPost(BASE + path);
            request.setEntity(new StringEntity(json, ContentType.APPLICATION_JSON));
            return client.execute(request, response -> EntityUtils.toString(response.getEntity()));
        }
    }
}
