package limitium.art.camunda.backend;

import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.entity.mime.MultipartEntityBuilder;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import static org.awaitility.Awaitility.await;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.*;

public class ServerIT {
    static ConfigurableApplicationContext app;
    static final int port = 18080;
    static final Path tempRoot = Path.of("build/tmp/backend-it");

    @BeforeAll
    static void start() throws Exception {
        cleanDirectory(tempRoot);
        Files.createDirectories(tempRoot);
        System.setProperty("dmn.data.root", tempRoot.toString());
        System.setProperty("server.port", String.valueOf(port));
        app = SpringApplication.run(CamundaEvalApplication.class);
    }

    @AfterAll
    static void stop() {
        if (app != null) app.close();
    }

    @Test
    void crudAndRunFlow() throws Exception {
        try (CloseableHttpClient http = HttpClients.createDefault()) {
            // Upload DMN and YAML
            String dmnName = "it-decision.dmn";
            String yamlName = "it-decision.yaml";
            byte[] dmnBytes = DMN_CONTENT.getBytes(StandardCharsets.UTF_8);
            byte[] yamlBytes = YAML_CONTENT.getBytes(StandardCharsets.UTF_8);
            uploadFile(http, url("/api/dmn"), dmnName, dmnBytes);
            // YAML create endpoint removed; ensure PUT works after DMN created
            // PUT YAML
            putRaw(http, url("/api/yaml/" + yamlName), yamlBytes);

            // List DMN
            String dmnList = get(http, url("/api/dmn"));
            assertTrue(dmnList.contains(dmnName));

            // Trigger run
            String runId = triggerRun(http, dmnName);
            assertNotNull(runId);

            // Await finish
            await().atMost(30, SECONDS).until(() -> get(http, url("/api/runs/" + runId + "/status")).contains("\"state\" : \"finished\""));

            String log = get(http, url("/api/runs/" + runId + "/log"));
            assertTrue(log.contains("OK"));
        }
    }

    private static String triggerRun(CloseableHttpClient http, String dmn) throws IOException {
        HttpPost post = new HttpPost(url("/api/runs"));
        HttpEntity entity = MultipartEntityBuilder.create()
                .addTextBody("fileName", dmn.substring(0, dmn.lastIndexOf('.'))) // Remove .dmn extension
                .addBinaryBody("dmnContent", DMN_CONTENT.getBytes(StandardCharsets.UTF_8), ContentType.APPLICATION_XML, dmn)
                .addBinaryBody("yamlContent", YAML_CONTENT.getBytes(StandardCharsets.UTF_8), ContentType.TEXT_PLAIN, dmn.substring(0, dmn.lastIndexOf('.')) + ".yaml")
                .build();
        post.setEntity(entity);
        var resp = http.execute(post);
        try {
            String body;
            try {
                body = EntityUtils.toString(resp.getEntity());
            } catch (org.apache.hc.core5.http.ParseException e) {
                throw new IOException(e);
            }
            // naive extraction of id
            int idx = body.indexOf("\"id\"");
            int start = body.indexOf('"', idx + 4);
            int end = body.indexOf('"', start + 1);
            return body.substring(start + 1, end);
        } finally {
            resp.close();
        }
    }

    private static void uploadFile(CloseableHttpClient http, String url, String name, byte[] bytes) throws IOException {
        HttpPost post = new HttpPost(url + "?name=" + name);
        HttpEntity entity = MultipartEntityBuilder.create()
                .addBinaryBody("file", bytes, ContentType.DEFAULT_BINARY, name)
                .build();
        post.setEntity(entity);
        var resp = http.execute(post);
        EntityUtils.consumeQuietly(resp.getEntity());
        resp.close();
    }

    private static void putRaw(CloseableHttpClient http, String url, byte[] bytes) throws IOException {
        var put = new org.apache.hc.client5.http.classic.methods.HttpPut(url);
        put.setEntity(new org.apache.hc.core5.http.io.entity.ByteArrayEntity(bytes, org.apache.hc.core5.http.ContentType.APPLICATION_OCTET_STREAM));
        var resp = http.execute(put);
        EntityUtils.consumeQuietly(resp.getEntity());
        resp.close();
    }

    private static String get(CloseableHttpClient http, String url) throws IOException {
        var resp = http.execute(new HttpGet(url));
        try {
            try {
                return EntityUtils.toString(resp.getEntity(), StandardCharsets.UTF_8);
            } catch (org.apache.hc.core5.http.ParseException e) {
                throw new IOException(e);
            }
        } finally {
            resp.close();
        }
    }

    private static String url(String path) { return "http://localhost:" + port + path; }

    private static void cleanDirectory(Path root) throws Exception {
        if (Files.notExists(root)) return;
        try (var walk = Files.walk(root)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (Exception ignored) {}
            });
        }
    }

    private static final String DMN_CONTENT = """
<?xml version=\"1.0\" encoding=\"UTF-8\"?>
<definitions id=\"TypedDecisions\" name=\"Typed Decisions\"
  namespace=\"http://camunda.org/schema/1.0/dmn\"
  xmlns=\"https://www.omg.org/spec/DMN/20191111/MODEL/\"
  xmlns:feel=\"https://www.omg.org/spec/DMN/20191111/FEEL/\"
  xmlns:camunda=\"http://camunda.org/schema/1.0/dmn\">\n\n  <decision id=\"StrDecision\" name=\"Str Decision\">\n    <decisionTable id=\"StrDecisionTable\" hitPolicy=\"FIRST\">\n      <input id=\"Input_any\" label=\"Any\">\n        <inputExpression id=\"InputExpression_any\" typeRef=\"string\">\n          <text>x</text>\n        </inputExpression>\n      </input>\n      <output id=\"Output_str\" label=\"Str\" name=\"strOut\" typeRef=\"string\"/>\n      <rule id=\"Rule_str\">\n        <inputEntry id=\"InputEntry_any2\"><text>-</text></inputEntry>\n        <outputEntry id=\"OutputEntry_str\"><text>\"OK\"</text></outputEntry>\n      </rule>\n    </decisionTable>\n  </decision>\n\n</definitions>\n""";

    private static final String YAML_CONTENT = """
tests:
  - decision: StrDecision
    columns: [scenario,  x,        "=> strOut"]
    rows:
           - [any input, anything, OK]
""";
}
