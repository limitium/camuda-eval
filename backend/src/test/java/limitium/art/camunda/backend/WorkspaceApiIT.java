package limitium.art.camunda.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.hc.client5.http.classic.methods.HttpDelete;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.classic.methods.HttpPut;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.entity.mime.MultipartEntityBuilder;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The content API with the git layer on: working spaces, identity, and non-destructive saves. */
class WorkspaceApiIT {

    private static final int PORT = 18082;
    private static final String BASE = "http://localhost:" + PORT;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Path ROOT = Path.of("build/tmp/workspace-it");

    private static ConfigurableApplicationContext app;

    private static final String DMN = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<definitions id=\"Defs\" name=\"Defs\" namespace=\"http://camunda.org/schema/1.0/dmn\"\n"
            + "  xmlns=\"https://www.omg.org/spec/DMN/20191111/MODEL/\">\n"
            + "  <decision id=\"Pricing\" name=\"Pricing\">\n"
            + "    <decisionTable id=\"DT\" hitPolicy=\"UNIQUE\">\n"
            + "      <input id=\"in\"><inputExpression id=\"ie\" typeRef=\"number\"><text>amount</text></inputExpression></input>\n"
            + "      <output id=\"out\" name=\"discount\" typeRef=\"string\"/>\n"
            + "      <rule id=\"r1\"><inputEntry id=\"e1\"><text>-</text></inputEntry>"
            + "<outputEntry id=\"e2\"><text>\"0%\"</text></outputEntry></rule>\n"
            + "    </decisionTable>\n"
            + "  </decision>\n"
            + "</definitions>\n";

    @BeforeAll
    static void start() throws Exception {
        if (Files.exists(ROOT)) {
            try (var walk = Files.walk(ROOT)) {
                for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(p);
                }
            }
        }
        System.setProperty("dmn.data.root", ROOT.toString());
        System.setProperty("server.port", String.valueOf(PORT));
        System.setProperty("dmn.git.enabled", "true");
        app = SpringApplication.run(CamundaEvalApplication.class);
    }

    @AfterAll
    static void stop() {
        if (app != null) {
            app.close();
        }
        // Other ITs share this JVM; a leaked git flag would silently change what they test.
        System.clearProperty("dmn.git.enabled");
        System.clearProperty("dmn.data.root");
        System.clearProperty("server.port");
    }

    // --- identity ---------------------------------------------------------------------------------

    @Test
    @DisplayName("a request without the proxy's identity header is refused, not given a default identity")
    void missingIdentityIs401() throws Exception {
        Response response = send(json(new HttpPost(BASE + "/api/workspaces"),
                "{\"name\":\"anon\",\"baseline\":\"main\"}"), null);
        assertEquals(401, response.status, response.body);
    }

    // --- selecting a working space ----------------------------------------------------------------

    @Test
    @DisplayName("no ws is an honest 400, not a silent fallback to main")
    void missingWorkspaceIs400() throws Exception {
        Response response = send(new HttpGet(BASE + "/api/dmn"), "alice");
        assertEquals(400, response.status, response.body);
        assertTrue(response.body.contains("ws is required"), response.body);
    }

    @Test
    void unknownWorkspaceIs404() throws Exception {
        Response response = send(new HttpGet(BASE + "/api/dmn?ws=" + enc("feature/alice/gone")), "alice");
        assertEquals(404, response.status, response.body);
    }

    @Test
    @DisplayName("a file name from the URL cannot walk out of the working space")
    void traversalIsRefused() throws Exception {
        String ws = createWorkspace("alice", "traversal");
        Response response = send(new HttpGet(BASE + "/api/dmn/..%2F..%2Fpom.dmn?ws=" + enc(ws)), "alice");
        assertTrue(response.status == 400 || response.status == 404,
                "must not serve a file outside the working space, got " + response.status);
    }

    // --- the full edit loop -----------------------------------------------------------------------

    @Test
    @DisplayName("create, upload, load with ETag, save with If-Match, commit")
    void editLoop() throws Exception {
        String ws = createWorkspace("alice", "q4");

        assertEquals(200, upload(ws, "pricing.dmn", "alice").status);

        Response loaded = send(new HttpGet(BASE + "/api/dmn/pricing.dmn?ws=" + enc(ws)), "alice");
        assertEquals(200, loaded.status);
        assertNotNull(loaded.etag, "a read must hand back the version a later save will present");

        String edited = DMN.replace("\"0%\"", "\"5%\"");
        Response saved = send(withIfMatch(xml(new HttpPut(BASE + "/api/dmn/pricing.dmn?ws=" + enc(ws)), edited),
                loaded.etag), "alice");
        assertEquals(200, saved.status, saved.body);
        assertTrue(!loaded.etag.equals(saved.etag), "new content, new version");

        // The skeleton test spec came with the upload, shaped from the table.
        Response spec = send(new HttpGet(BASE + "/api/yaml/pricing.yaml?ws=" + enc(ws)), "alice");
        assertEquals(200, spec.status);
        assertTrue(spec.body.contains("\"=> discount\""), spec.body);

        Response commit = send(json(new HttpPost(BASE + "/api/workspaces/" + ws + "/commit"),
                "{\"message\":\"Q4 discount\"}"), "alice");
        assertEquals(200, commit.status, commit.body);
        assertNotNull(MAPPER.readTree(commit.body).get("commit").asText());
    }

    @Test
    @DisplayName("the app describes itself; changes and the last saved version are readable per space")
    void appChangesAndSavedVersion() throws Exception {
        Response app = send(new HttpGet(BASE + "/api/app"), "alice");
        assertEquals(200, app.status);
        assertEquals("alice", MAPPER.readTree(app.body).get("user").asText());
        assertTrue(MAPPER.readTree(app.body).get("gitEnabled").asBoolean());

        Response baselines = send(new HttpGet(BASE + "/api/workspaces/baselines"), "alice");
        assertEquals(200, baselines.status);
        assertTrue(baselines.body.contains("\"ref\":\"main\""), baselines.body);

        Response tidy = send(new HttpGet(BASE + "/api/cleanup"), "alice");
        assertEquals(200, tidy.status, tidy.body);
        assertTrue(tidy.body.contains("\"plan\"") && tidy.body.contains("\"archived\""), tidy.body);
        Response nothing = send(json(new HttpPost(BASE + "/api/cleanup/apply"), "{\"items\":{}}"), "alice");
        assertEquals(400, nothing.status, "choosing nothing is not a tidy-up");
        Response stale = send(json(new HttpPost(BASE + "/api/cleanup/apply"),
                "{\"items\":{\"feature/alice/never-shown\":\"0000000000000000000000000000000000000000\"}}"), "alice");
        assertEquals(409, stale.status, "an item not in the current plan refuses the whole run");
        assertTrue(stale.body.contains("PLAN_CHANGED"), stale.body);

        Response windowed = send(new HttpGet(BASE + "/api/workspaces/history?days=42"), "alice");
        assertEquals(200, windowed.status);
        assertTrue(windowed.body.contains("\"lines\":") && windowed.body.contains("\"from\":"), windowed.body);

        Response history = send(new HttpGet(BASE + "/api/workspaces/history"), "alice");
        assertEquals(200, history.status);
        assertTrue(history.body.contains("\"lane\":\"main\"") && history.body.contains("\"tips\":[\"main\"]"), history.body);

        String ws = createWorkspace("alice", "changes");
        assertEquals(200, upload(ws, "fees.dmn", "alice").status);

        Response changes = send(new HttpGet(BASE + "/api/workspaces/" + ws + "/changes"), "alice");
        assertEquals(200, changes.status);
        assertTrue(changes.body.contains("dmn/fees.dmn") && changes.body.contains("ADDED"), changes.body);

        Response unsaved = send(new HttpGet(BASE + "/api/workspaces/" + ws + "/saved?path=dmn/fees.dmn"), "alice");
        assertEquals(404, unsaved.status, "a file new since the last version has no saved form");

        send(json(new HttpPost(BASE + "/api/workspaces/" + ws + "/commit"), "{\"message\":\"fees\"}"), "alice");
        Response saved = send(new HttpGet(BASE + "/api/workspaces/" + ws + "/saved?path=dmn/fees.dmn"), "alice");
        assertEquals(200, saved.status, saved.body);
        assertTrue(saved.body.contains("<definitions"), saved.body);
        Response after = send(new HttpGet(BASE + "/api/workspaces/" + ws + "/changes"), "alice");
        assertEquals("{\"files\":[]}", after.body);
    }

    // --- concurrent edits are blocking and non-destructive -----------------------------------------

    @Test
    @DisplayName("a save built on a version someone else replaced is refused, and nothing is overwritten")
    void staleSaveIsRefused() throws Exception {
        String ws = createWorkspace("alice", "stale");
        upload(ws, "pricing.dmn", "alice");
        String url = BASE + "/api/dmn/pricing.dmn?ws=" + enc(ws);

        String aliceSaw = send(new HttpGet(url), "alice").etag;
        String bobSaw = send(new HttpGet(url), "bob").etag;

        String alicesEdit = DMN.replace("\"0%\"", "\"7%\"");
        assertEquals(200, send(withIfMatch(xml(new HttpPut(url), alicesEdit), aliceSaw), "alice").status);

        Response bob = send(withIfMatch(xml(new HttpPut(url), DMN.replace("\"0%\"", "\"6%\"")), bobSaw), "bob");
        assertEquals(412, bob.status, bob.body);
        assertEquals("STALE", MAPPER.readTree(bob.body).get("code").asText());

        Response now = send(new HttpGet(url), "alice");
        assertTrue(now.body.contains("\"7%\""), "alice's save must survive bob's stale one: " + now.body);
    }

    @Test
    @DisplayName("omitting If-Match cannot be used to force a save")
    void savingWithoutPreconditionIsRefused() throws Exception {
        String ws = createWorkspace("alice", "noprecondition");
        upload(ws, "pricing.dmn", "alice");
        String url = BASE + "/api/dmn/pricing.dmn?ws=" + enc(ws);

        assertEquals(428, send(xml(new HttpPut(url), DMN), "alice").status);
        assertEquals(428, send(withIfMatch(xml(new HttpPut(url), DMN), "*"), "alice").status,
                "a wildcard asserts nothing about what the caller has seen");
    }

    @Test
    @DisplayName("uploading over an existing file is refused where editing is shared")
    void uploadCannotReplace() throws Exception {
        String ws = createWorkspace("alice", "reupload");
        assertEquals(200, upload(ws, "pricing.dmn", "alice").status);
        assertEquals(409, upload(ws, "pricing.dmn", "bob").status);
    }

    // --- canonical test specs ----------------------------------------------------------------------

    @Test
    @DisplayName("a test spec is stored canonically and the response says what was stored")
    void specIsCanonicalOnSave() throws Exception {
        String ws = createWorkspace("alice", "canonical");
        upload(ws, "pricing.dmn", "alice");
        String url = BASE + "/api/yaml/pricing.yaml?ws=" + enc(ws);
        String etag = send(new HttpGet(url), "alice").etag;

        String sloppy = "tests:\n  - decision: Pricing\n    columns: [scenario, amount, \"=> discount\"]\n"
                + "    rows:\n      - [small order,50,\"0%\"]\n";
        Response saved = send(withIfMatch(text(new HttpPut(url), sloppy), etag), "alice");
        assertEquals(200, saved.status, saved.body);

        JsonNode body = MAPPER.readTree(saved.body);
        assertTrue(body.get("canonicalized").asBoolean());
        String stored = body.get("content").asText();
        assertTrue(stored.contains("[small order, 50,"), "aligned on the way in:\n" + stored);

        // Saving the canonical text again is a no-op: same bytes, same version.
        Response again = send(withIfMatch(text(new HttpPut(url), stored), saved.etag), "alice");
        assertEquals(saved.etag, again.etag);
    }

    @Test
    @DisplayName("removing a file needs the version seen; a stale or unknown space is refused")
    void guardedRemoveAndGoneSpace() throws Exception {
        String ws = createWorkspace("alice", "guarded");
        assertEquals(200, upload(ws, "fees.dmn", "alice").status);
        assertEquals(409, upload(ws, "fees.dmn", "bob").status, "a second upload of the same name is refused");

        String url = BASE + "/api/dmn/fees.dmn?ws=" + enc(ws);
        Response seen = send(new HttpGet(url), "alice");
        Response edited = send(withIfMatch(xml(new HttpPut(url), DMN.replace("\"0%\"", "\"1%\"")), seen.etag), "bob");
        assertEquals(200, edited.status, edited.body);

        assertEquals(428, send(new HttpDelete(url), "alice").status, "no version named: refused");
        assertEquals(412, send(withIfMatch(new HttpDelete(url), seen.etag), "alice").status,
                "bob changed it since alice looked: refused");
        assertEquals(204, send(withIfMatch(new HttpDelete(url), edited.etag), "alice").status);
        assertEquals(412, send(withIfMatch(xml(new HttpPut(url), DMN), edited.etag), "bob").status,
                "a late save does not bring the file back");

        String gone = "feature/alice/never-made";
        assertEquals(404, send(new HttpGet(BASE + "/api/dmn/fees.dmn?ws=" + enc(gone)), "alice").status);
    }

    // --- helpers -----------------------------------------------------------------------------------

    private static String createWorkspace(String user, String name) throws Exception {
        Response created = send(json(new HttpPost(BASE + "/api/workspaces"),
                "{\"name\":\"" + name + "\",\"baseline\":\"main\"}"), user);
        assertEquals(201, created.status, created.body);
        return MAPPER.readTree(created.body).get("id").asText();
    }

    private static Response upload(String ws, String fileName, String user) throws Exception {
        HttpPost post = new HttpPost(BASE + "/api/dmn?ws=" + enc(ws) + "&name=" + fileName);
        post.setEntity(MultipartEntityBuilder.create()
                .addBinaryBody("file", DMN.getBytes(StandardCharsets.UTF_8),
                        ContentType.APPLICATION_XML, fileName)
                .build());
        return send(post, user);
    }

    private static HttpUriRequestBase json(HttpUriRequestBase request, String body) {
        request.setEntity(new StringEntity(body, ContentType.APPLICATION_JSON));
        return request;
    }

    private static HttpUriRequestBase xml(HttpUriRequestBase request, String body) {
        request.setEntity(new StringEntity(body, ContentType.APPLICATION_XML));
        return request;
    }

    private static HttpUriRequestBase text(HttpUriRequestBase request, String body) {
        request.setEntity(new StringEntity(body, ContentType.TEXT_PLAIN.withCharset(StandardCharsets.UTF_8)));
        return request;
    }

    private static HttpUriRequestBase withIfMatch(HttpUriRequestBase request, String etag) {
        request.setHeader("If-Match", etag);
        return request;
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static Response send(HttpUriRequestBase request, String user) throws Exception {
        if (user != null) {
            request.setHeader("X-Forwarded-User", user);
        }
        try (CloseableHttpClient client = HttpClients.createDefault()) {
            return client.execute(request, response -> {
                Header etag = response.getFirstHeader("ETag");
                String body = response.getEntity() == null ? "" : EntityUtils.toString(response.getEntity());
                return new Response(response.getCode(), body, etag == null ? null : etag.getValue());
            });
        }
    }

    private record Response(int status, String body, String etag) {
    }
}
