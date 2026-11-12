package limitium.art.camunda.backend.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import limitium.art.camunda.backend.config.AuthProperties;
import limitium.art.camunda.backend.controller.AssistantController;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The conversation with the model, against a stand-in server that answers what each test queues. */
class AssistantControllerTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private HttpServer server;
    private final Deque<String> replies = new ArrayDeque<>();
    private final List<String> asked = new ArrayList<>();
    private AssistantProperties props;
    private AssistantController controller;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", (exchange) -> {
            asked.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            ObjectNode body = JSON.createObjectNode();
            body.putArray("choices").addObject().putObject("message").put("role", "assistant").put("content", replies.isEmpty() ? "" : replies.pop());
            byte[] out = JSON.writeValueAsBytes(body);
            exchange.sendResponseHeaders(200, out.length);
            try (OutputStream o = exchange.getResponseBody()) {
                o.write(out);
            }
        });
        server.start();
        props = new AssistantProperties();
        props.setUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
        controller = controller(props);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static AssistantController controller(AssistantProperties props) {
        AuthProperties auth = new AuthProperties();
        auth.setAllowAnonymous(true);
        auth.setAnonymousUser("alice");
        AssistantController c = new AssistantController(props, new ChatModel(props), auth);
        ReflectionTestUtils.setField(c, "role", "author");
        ReflectionTestUtils.setField(c, "env", "dev");
        ReflectionTestUtils.setField(c, "allowDraftPublish", true);
        return c;
    }

    private Map<?, ?> ask(String page, String question) throws Exception {
        AssistantController.AskRequest r = new AssistantController.AskRequest();
        r.page = page;
        r.context = JSON.readTree(ProposalCheckTest.CONTEXT);
        r.dmnXml = ProposalCheckTest.XML;
        AssistantController.Turn t = new AssistantController.Turn();
        t.role = "user";
        t.content = question;
        r.messages = List.of(t);
        ResponseEntity<?> response = controller.ask(r, new MockHttpServletRequest());
        assertEquals(200, response.getStatusCode().value(), String.valueOf(response.getBody()));
        return (Map<?, ?>) response.getBody();
    }

    @Test
    @DisplayName("the model is told the app, the screen, what it shows and who asks, and asked for JSON")
    void instructions() throws Exception {
        replies.add("{\"answer\":\"Rule 1 gives US-LRG.\",\"proposal\":null}");
        Map<?, ?> body = ask("table", "what does rule 1 do?");
        assertEquals("Rule 1 gives US-LRG.", body.get("answer"));
        String request = asked.get(0);
        assertTrue(request.contains("\"response_format\":{\"type\":\"json_object\"}"), request);
        String system = JSON.readTree(request).path("messages").path(0).path("content").asText();
        assertTrue(system.contains("A domain's tables, in a work space"), "the table screen's guide");
        assertTrue(system.contains("Rule 1 — when country \"US\", amount >= 1000000 → gives feeCode \"US-LRG\", feeCalculatorId \"TIERED\"   [this is rule 1]"), system);
        assertTrue(system.contains("Rule 2 — when anything (every condition is -)"), system);
        assertTrue(system.contains("First matching rule wins (FIRST)"), "the hit policy in words");
        assertTrue(system.contains("the person asking: alice"), "who asks comes from the server");
        assertTrue(system.contains("\"type\": \"add_rule\""), "the table screen may propose");
        assertTrue(!system.contains("{{"), "every part filled in");
        // The question goes with the lines of the rules it names.
        String question = JSON.readTree(request).path("messages").path(1).path("content").asText();
        assertTrue(question.startsWith("what does rule 1 do?"), question);
        assertTrue(question.contains("- rule 1 of Fee Code: when country \"US\", amount >= 1000000 → gives feeCode \"US-LRG\""), question);
    }

    @Test
    @DisplayName("a refused proposal goes back to the model once with the engine's reason; the corrected one is returned")
    void repairRound() throws Exception {
        replies.add("{\"answer\":\"Here it is.\",\"proposal\":{\"type\":\"add_rule\",\"table\":\"Fee Code\",\"position\":1,\"when\":{\"country\":\"DE\"},\"then\":{\"feeCode\":\"\\\"DE\\\"\",\"feeCalculatorId\":\"\\\"BPS-FLAT\\\"\"}}}");
        replies.add("```json\n{\"answer\":\"Here it is, quoted.\",\"proposal\":{\"type\":\"add_rule\",\"table\":\"Fee Code\",\"position\":1,\"when\":{\"country\":\"\\\"DE\\\"\"},\"then\":{\"feeCode\":\"\\\"DE\\\"\",\"feeCalculatorId\":\"\\\"BPS-FLAT\\\"\"}}}\n```");
        Map<?, ?> body = ask("table", "add a rule for Germany");
        assertEquals(2, asked.size());
        String second = asked.get(1);
        assertTrue(second.contains("The app refused that proposal") && second.contains("text goes in double quotes"), second);
        assertEquals("Here it is, quoted.", body.get("answer"));
        assertNotNull(body.get("proposal"));
        assertNotNull(body.get("turn"), "the reply to send back as this turn");
    }

    @Test
    @DisplayName("refused twice: no proposal, and the reasons are said")
    void refusedTwice() throws Exception {
        String bad = "{\"answer\":\"Here.\",\"proposal\":{\"type\":\"add_rule\",\"table\":\"Fee Code\",\"when\":{\"amount\":\"= 5\"},\"then\":{\"feeCode\":\"\\\"X\\\"\",\"feeCalculatorId\":\"null\"}}}";
        replies.add(bad);
        replies.add(bad);
        Map<?, ?> body = ask("table", "add a rule");
        assertNull(body.get("proposal"));
        assertTrue(String.valueOf(body.get("refused")).contains("amount"), String.valueOf(body.get("refused")));
    }

    @Test
    @DisplayName("an action the screen does not have is dropped, whatever the model says; the screen's own are described")
    void onlyTheScreensActions() throws Exception {
        replies.add("{\"answer\":\"Added.\",\"proposal\":{\"type\":\"add_rule\",\"table\":\"Fee Code\",\"when\":{},\"then\":{\"feeCode\":\"\\\"X\\\"\",\"feeCalculatorId\":\"null\"}}}");
        Map<?, ?> body = ask("releases", "add a rule");
        assertNull(body.get("proposal"));
        String system = JSON.readTree(asked.get(0)).path("messages").path(0).path("content").asText();
        assertTrue(system.contains("## tag") && system.contains("## create_branch") && system.contains("## press"), "the releases actions");
        assertTrue(!system.contains("## add_rule") && !system.contains("## deploy"), "no other screen's actions");
        assertTrue(!system.contains("Writing cells (FEEL"), "cells only on a table's screen");
        assertTrue(system.contains("**Releases**"), "the releases guide");
    }

    @Test
    @DisplayName("an action of the screen comes back checked, with its card")
    void screenAction() throws Exception {
        replies.add("{\"answer\":\"Tagging it.\",\"proposal\":{\"type\":\"tag\",\"branch\":\"release/1.0\",\"version\":\"v1.0.0\"}}");
        AssistantController.AskRequest r = new AssistantController.AskRequest();
        r.page = "releases";
        r.context = JSON.readTree("{\"branchTips\":{\"release/1.0\":{\"tip\":\"bb1bb8c\",\"nextVersion\":\"v1.0.0\",\"tags\":[]}}}");
        AssistantController.Turn t = new AssistantController.Turn();
        t.role = "user";
        t.content = "tag it";
        r.messages = List.of(t);
        Map<?, ?> body = (Map<?, ?>) controller.ask(r, new MockHttpServletRequest()).getBody();
        String proposal = JSON.writeValueAsString(body.get("proposal"));
        assertTrue(proposal.contains("\"confirm\":\"Tag as v1.0.0\"") && proposal.contains("\"commit\":\"bb1bb8c\""), proposal);
    }

    @Test
    @DisplayName("a commit message's first line: one line, at most 72 characters, without quotes")
    void headline() {
        assertEquals("Fee Code: German XETRA trades get DE-XETRA", AssistantController.headline("\"Fee Code: German XETRA trades get DE-XETRA.\"\nmore"));
        String longer = AssistantController.headline("Fee Code: German trades on XETRA now get their own fee code DE-XETRA with the flat calculator");
        assertTrue(longer.length() <= 72 && longer.endsWith("…"), longer);
    }

    @Test
    @DisplayName("an answer closed early at an unescaped quote is asked for again")
    void cutShortAnswer() throws Exception {
        replies.add("{\"answer\": \"A test case for rule 4 of \" }");
        replies.add("{\"answer\": \"A test case for rule 4 of Fee Code: GB, LSE.\", \"proposal\": null}");
        assertEquals("A test case for rule 4 of Fee Code: GB, LSE.", ask("table", "write a test case for rule 4").get("answer"));
        assertTrue(asked.get(1).contains("cut off at a double quote"), asked.get(1));
    }

    @Test
    @DisplayName("not JSON: asked once more, then the text itself is the answer")
    void notJson() throws Exception {
        replies.add("Sure! Press Commit changes.");
        replies.add("<think>hmm</think>{\"answer\":\"Press **Commit changes**.\",\"proposal\":null}");
        assertEquals("Press **Commit changes**.", ask("commit", "how do I commit?").get("answer"));
    }

    @Test
    @DisplayName("no model configured, or none answering: said, with what to set")
    void unavailable() throws Exception {
        AssistantProperties off = new AssistantProperties();
        AssistantController.AskRequest r = new AssistantController.AskRequest();
        assertEquals(503, controller(off).ask(r, new MockHttpServletRequest()).getStatusCode().value());

        server.stop(0);
        AssistantController.Turn t = new AssistantController.Turn();
        t.role = "user";
        t.content = "hello";
        r.messages = List.of(t);
        ResponseEntity<?> down = controller.ask(r, new MockHttpServletRequest());
        assertEquals(502, down.getStatusCode().value());
        assertTrue(String.valueOf(down.getBody()).contains("cannot be reached"), String.valueOf(down.getBody()));
    }
}
