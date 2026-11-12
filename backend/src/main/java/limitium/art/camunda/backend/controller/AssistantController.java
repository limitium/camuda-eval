package limitium.art.camunda.backend.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import limitium.art.camunda.backend.assistant.Actions;
import limitium.art.camunda.backend.assistant.AssistantPrompt;
import limitium.art.camunda.backend.assistant.AssistantProperties;
import limitium.art.camunda.backend.assistant.ChatModel;
import limitium.art.camunda.backend.assistant.ProposalCheck;
import limitium.art.camunda.backend.assistant.Review;
import limitium.art.camunda.backend.config.AuthProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The assistant: a question about the screen the person has open, answered by the configured model.
 *
 * What the model may do is enforced here, not only asked of it: it answers in words, and on a
 * domain's tables it may propose one rule or one test case. A proposal is checked by the engine
 * (the model gets one chance to correct what was refused) and comes back in the shape the screen
 * applies — one rule, or one case — for the person to add or ignore. Nothing is stored or changed
 * by this endpoint.
 */
@RestController
@RequestMapping("/api/assistant")
public class AssistantController {

    private static final Logger LOG = LoggerFactory.getLogger(AssistantController.class);
    private static final int MAX_MESSAGE = 4000;
    /** Corrections the model gets for a refused proposal before it is given up. */
    private static final int REPAIRS = 2;

    public static class Turn {
        public String role;
        public String content;
    }

    public static class AskRequest {
        /** Which screen: one of {@link AssistantPrompt#PAGES}. */
        public String page;
        /** What the screen shows, as facts. */
        public JsonNode context;
        /** The conversation so far, the question last. */
        public List<Turn> messages;
        /** The domain as it is on screen, to run a proposed test case on; never sent to the model. */
        public String dmnXml;
        /** A task beyond a question: "review" (the work space's changes) or "commit_message". */
        public String task;
        /** The message box as the person left it, for "commit_message": their words are kept. */
        public String draft;
    }

    private final AssistantProperties props;
    private final ChatModel model;
    private final AuthProperties auth;
    private final AssistantPrompt prompt;
    private final ObjectMapper json = new ObjectMapper();

    @Value("${dmn.role:author}")
    private String role;

    @Value("${dmn.deploy.env:dev}")
    private String env;

    @Value("${dmn.deploy.allow-draft-publish:false}")
    private boolean allowDraftPublish;

    private final Review review;

    @org.springframework.beans.factory.annotation.Autowired
    public AssistantController(AssistantProperties props, ChatModel model, AuthProperties auth, Review review) {
        this.props = props;
        this.model = model;
        this.auth = auth;
        this.review = review;
        this.prompt = new AssistantPrompt(props.getInstructionsDir());
    }

    /** Without reviews: an instance that keeps no work spaces, and tests. */
    public AssistantController(AssistantProperties props, ChatModel model, AuthProperties auth) {
        this(props, model, auth, null);
    }

    @PostMapping
    public ResponseEntity<?> ask(@RequestBody AskRequest request, HttpServletRequest http) {
        if (!props.isEnabled()) {
            return refuse(HttpStatus.SERVICE_UNAVAILABLE, "NOT_SET_UP", "this instance has no assistant: set dmn.assistant.url to a Qwen server's OpenAI-compatible address");
        }
        List<ChatModel.Message> conversation = conversation(request == null ? null : request.messages);
        if (conversation.isEmpty() || !"user".equals(conversation.get(conversation.size() - 1).role)) {
            return refuse(HttpStatus.BAD_REQUEST, "NO_QUESTION", "ask a question");
        }
        String page = AssistantPrompt.page(request.page);
        String user = user(http);
        List<ChatModel.Message> messages = new ArrayList<>();
        String system = prompt.build(page, request.context, instance(user));
        // What the model is told, word for word: logging.level.limitium.art.camunda.backend.controller.AssistantController=DEBUG
        LOG.debug("assistant instructions for {}:\n{}", page, system);
        messages.add(new ChatModel.Message("system", system));
        messages.addAll(conversation.subList(0, conversation.size() - 1));
        String question = conversation.get(conversation.size() - 1).content;

        long started = System.nanoTime();
        try {
            if ("commit_message".equals(request.task) || ("commit".equals(page) && COMMIT_MESSAGE.matcher(question).find())) {
                return commitMessage(request, messages, user, started);
            }
            if ("review".equals(request.task) || (page != null && REVIEWS.contains(page) && REVIEW.matcher(question).find())) {
                return review(request, question, messages, started);
            }
            Actions actions = new Actions(request.context, user);
            ObjectNode command = actions.command(question);
            if (command != null && Actions.allowed(page).contains(command.path("type").asText())) {
                ProposalCheck.Outcome direct = actions.check(command);
                if (direct.ok()) {
                    return ResponseEntity.ok(answer(direct.proposal.path("card").path("title").asText() + ": confirm below.", direct.proposal, null, started));
                }
            }
            // The question goes with the screen's situation and the rules it names: a small model attends
            // to the last message, and reads a rule number off a quoted line better than off a list.
            messages.add(new ChatModel.Message("user", prompt.withFacts(question, page, request.context)));
            JsonNode reply = reply(messages);
            if (reply.has("plain")) {
                return ResponseEntity.ok(answer(reply.path("plain").asText(), null, null, started));
            }

            JsonNode proposed = reply.path("proposal");
            if (proposed.isTextual() && Actions.allowed(page).contains(proposed.asText())) {
                // "proposal": "commit" — the action named but not written out: checked like any, and corrected if need be.
                proposed = json.createObjectNode().put("type", proposed.asText());
            }
            if (!proposed.isObject()) {
                return ResponseEntity.ok(answer(reply.path("answer").asText(""), null, null, started));
            }
            List<String> allowed = Actions.allowed(page);
            if (!allowed.contains(proposed.path("type").asText(""))) {
                // Not an action of this screen, whatever the model says: nothing to confirm.
                return ResponseEntity.ok(answer(reply.path("answer").asText("")
                        + "\n\n(That cannot be done from this screen.)", null, null, started));
            }

            ProposalCheck check = new ProposalCheck(request.context, request.dmnXml);
            Integer askedRule = askedRule(question);
            proposed = actions.complete(proposed, question);
            ProposalCheck.Outcome outcome = checked(proposed, check, actions, askedRule);
            JsonNode last = reply;
            for (int round = 0; !outcome.ok() && round < REPAIRS; round++) {
                // The app's reasons go back to the model; it corrects what was refused.
                messages.add(new ChatModel.Message("assistant", json.writeValueAsString(last)));
                messages.add(new ChatModel.Message("user", "The app refused that proposal:\n- " + String.join("\n- ", outcome.problems)
                        + "\nCorrect only what was refused and reply again with the whole JSON object; \"proposal\" is an object with"
                        + " \"type\" and every field its action needs, as in the examples. Write \"answer\" for the person"
                        + " as if for the first time: do not mention this correction."));
                JsonNode repaired = parse(model.complete(messages));
                JsonNode again = repaired == null ? null : repaired.path("proposal");
                if (again != null && again.isTextual() && allowed.contains(again.asText())) {
                    again = json.createObjectNode().put("type", again.asText());
                }
                if (again == null || !again.isObject() || !allowed.contains(again.path("type").asText(""))) {
                    break;
                }
                last = repaired;
                outcome = checked(actions.complete(again, question), check, actions, askedRule);
            }
            if (!outcome.ok()) {
                return ResponseEntity.ok(refused(last.path("answer").asText(""), outcome.problems, started));
            }
            return ResponseEntity.ok(answer(last.path("answer").asText(""), outcome.proposal, last, started));
        } catch (Review.Unavailable e) {
            return ResponseEntity.ok(answer("There is nothing to review here: " + e.getMessage() + ".", null, null, started));
        } catch (ChatModel.Failed e) {
            return refuse(HttpStatus.BAD_GATEWAY, "MODEL_FAILED", "the assistant did not answer: " + e.getMessage());
        } catch (Exception e) {
            return refuse(HttpStatus.INTERNAL_SERVER_ERROR, "FAILED", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    /** On the commit screen: asked for the message, the message is written the same way as by its button. */
    private static final java.util.regex.Pattern COMMIT_MESSAGE = java.util.regex.Pattern.compile(
            "(?i)\\b(write|suggest|propose|make|draft|improve|give)\\b.*\\bmessage\\b|\\bmessage\\b.*\\bsay\\b");

    /** The screens where "review my changes" means the work space's changes. */
    private static final java.util.Set<String> REVIEWS = java.util.Set.of("space", "commit", "test", "try", "merge", "table");
    private static final java.util.regex.Pattern REVIEW = java.util.regex.Pattern.compile(
            "(?i)\\breview\\b|check (my|the|these|our) changes|what (did|have) i change");

    /** The model's reply as JSON; asked again when it is not, or when JSON mode cut it short. {"plain": …} when it never is. */
    private JsonNode reply(List<ChatModel.Message> messages) {
        String raw = model.complete(messages);
        JsonNode reply = parse(raw);
        if (reply == null) {
            // Once more, saying what was wrong; then whatever it said is the answer.
            messages.add(new ChatModel.Message("assistant", raw));
            messages.add(new ChatModel.Message("user", "That was not one JSON object. Reply again with only the JSON object described in the reply format."));
            raw = model.complete(messages);
            reply = parse(raw);
            messages.remove(messages.size() - 1);
            messages.remove(messages.size() - 1);
            if (reply == null) {
                return json.createObjectNode().put("plain", plainText(raw));
            }
        }
        if (cutShort(reply)) {
            // JSON mode closes the answer at an unescaped quote ("… of " and nothing more): once more.
            messages.add(new ChatModel.Message("assistant", raw));
            messages.add(new ChatModel.Message("user", "Your answer was cut off at a double quote. Reply again with the whole JSON object;"
                    + " inside \"answer\" write a double quote as \\\" or leave names unquoted."));
            JsonNode again = parse(model.complete(messages));
            messages.remove(messages.size() - 1);
            messages.remove(messages.size() - 1);
            if (again != null && !cutShort(again)) {
                reply = again;
            }
        }
        return reply;
    }

    private static ProposalCheck.Outcome checked(JsonNode proposal, ProposalCheck table, Actions actions, Integer askedRule) {
        return Actions.TABLE.contains(proposal.path("type").asText("")) ? reaches(table.check(proposal), askedRule) : actions.check(proposal);
    }

    // --- reviewing a work space, and writing its commit message ------------------------------------

    /**
     * The work space's changes, reviewed by the app (what changed; what the engine says about it),
     * summed up by the model. The findings come back as they are, so the screen shows the facts
     * beside the summary rather than only the model's words for them.
     */
    private ResponseEntity<?> review(AskRequest request, String question, List<ChatModel.Message> messages, long started) throws Exception {
        if (review == null) {
            throw new Review.Unavailable("this instance keeps no work spaces");
        }
        Review.Result r = review.of(request.context == null ? null : request.context.path("workSpace").path("branch").asText(null), false);
        Map<String, Object> body;
        if (r.changes.isEmpty()) {
            body = answer("There is nothing to review: this work space has no changes, and no commits waiting to be merged.", null, null, started);
        } else {
            StringBuilder facts = new StringBuilder("The app reviewed " + r.scopeText + ".\nWhat changed:\n");
            r.changes.forEach((c) -> facts.append("- ").append(c).append('\n'));
            facts.append(r.testsTotal == 0 ? "Tests: none ran.\n" : "Tests: " + r.testsPassed + " of " + r.testsTotal + " test cases pass.\n");
            facts.append(r.findings.isEmpty() ? "Findings: none — every changed rule can apply, and no test case fails.\n" : "Findings:\n");
            r.findings.forEach((f) -> facts.append("- ").append(f.severity).append(": ").append(f.text).append('\n'));
            messages.add(new ChatModel.Message("user", question + "\n\n(From the app, not the person:\n" + facts
                    + ")\nSum this review up for the person in a few sentences: first what to fix (the problems), then what to look at (the warnings),"
                    + " then what changed, in plain words. Say only what the app found; add no findings of your own. \"proposal\": null."));
            JsonNode reply = reply(messages);
            body = answer(reply.has("plain") ? reply.path("plain").asText() : reply.path("answer").asText(""), null, null, started);
        }
        body.put("review", r);
        return ResponseEntity.ok(body);
    }

    /**
     * A commit message for the uncommitted changes: the model writes the first line, a headline; the
     * app lists the changes under it, exactly, with the test result — so the message never claims a
     * change that was not made. The person's own words in the message box are kept as the why.
     */
    private ResponseEntity<?> commitMessage(AskRequest request, List<ChatModel.Message> messages, String user, long started) throws Exception {
        if (review == null) {
            throw new Review.Unavailable("this instance keeps no work spaces");
        }
        Review.Result r = review.of(request.context == null ? null : request.context.path("workSpace").path("branch").asText(null), true);
        if (r.changes.isEmpty()) {
            return ResponseEntity.ok(answer("Nothing has changed since the last commit, so there is no message to write.", null, null, started));
        }
        String draft = request.draft == null ? "" : request.draft.trim();
        StringBuilder facts = new StringBuilder();
        r.changes.forEach((c) -> facts.append("- ").append(c).append('\n'));
        messages.add(new ChatModel.Message("user", "Write the first line of the commit message for these changes: one line, at most 72 characters,"
                + " saying what changed in the rules like a headline — for example `Fee Code: German XETRA trades get DE-XETRA`."
                + " The line says WHAT changed in the rules, from the list below — not why; when several things changed, the most important one, or what they have in common."
                + (draft.isEmpty() ? "" : " The person's own note, “" + draft + "”, is kept under the line as the why: do not repeat it in the line.")
                + " Reply {\"answer\": \"<the line>\", \"proposal\": null}.\n\n(From the app, not the person — the changes:\n" + facts + ")"));
        JsonNode reply = reply(messages);
        String subject = headline(reply.has("plain") ? reply.path("plain").asText() : reply.path("answer").asText(""));
        if (subject.isEmpty()) {
            subject = r.changes.get(0);
        }
        StringBuilder message = new StringBuilder(headline(subject)).append("\n\n");
        if (!draft.isEmpty() && !draft.equalsIgnoreCase(subject)) {
            message.append(draft).append("\n\n");
        }
        r.changes.forEach((c) -> message.append("- ").append(c).append('\n'));
        if (r.testsTotal > 0) {
            message.append(r.testsPassed == r.testsTotal ? "\nTests: all " + r.testsTotal + " cases pass."
                    : "\nTests: " + (r.testsTotal - r.testsPassed) + " of " + r.testsTotal + " cases fail.");
        }
        // Offered as a commit to confirm, or only to put in the message box.
        ObjectNode commit = json.createObjectNode().put("type", "commit").put("message", message.toString().trim());
        ProposalCheck.Outcome offered = new Actions(request.context, user).check(commit);
        Map<String, Object> body = answer("A message for these changes:\n\n" + message.toString().trim(), offered.ok() ? offered.proposal : null, null, started);
        body.put("message", message.toString().trim());
        body.put("review", r);
        return ResponseEntity.ok(body);
    }

    /** One line of at most 72 characters, cut at a word; quotes, backticks and a closing period dropped. */
    public static String headline(String text) {
        String line = String.valueOf(text).trim().split("\\R", 2)[0].trim()
                .replaceAll("^[\"'`“”]+|[\"'`“”]+$", "").replaceAll("\\.$", "").trim();
        if (line.length() <= 72) {
            return line;
        }
        int cut = line.lastIndexOf(' ', 71);
        return (cut > 40 ? line.substring(0, cut) : line.substring(0, 71)).trim() + "…";
    }

    /** The one rule a question is about ("a test case for rule 4"); null when it names none or several. */
    static Integer askedRule(String question) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?i)\\brule\\s*#?(\\d+)\\b").matcher(question == null ? "" : question);
        Integer found = null;
        while (m.find()) {
            Integer n = Integer.valueOf(m.group(1));
            if (found != null && !found.equals(n)) {
                return null;
            }
            found = n;
        }
        return found;
    }

    /**
     * A test case asked for "rule N" must reach rule N: the engine says which rule it reaches, and a
     * case that reaches another is sent back like any other refusal.
     */
    private static ProposalCheck.Outcome reaches(ProposalCheck.Outcome outcome, Integer askedRule) {
        if (askedRule == null || !outcome.ok() || !ProposalCheck.ADD_TEST.equals(outcome.proposal.path("type").asText())) {
            return outcome;
        }
        JsonNode rules = outcome.proposal.path("today").path("rules");
        if (!rules.isArray() || rules.isEmpty()) {
            return outcome;
        }
        for (JsonNode r : rules) {
            if (r.asInt() == askedRule) {
                return outcome;
            }
        }
        List<String> problems = new ArrayList<>();
        problems.add("the person asked about rule " + askedRule + ", but with these values the table reaches rule " + rules.get(0).asInt()
                + ": \"given\" must be values rule " + askedRule + " matches (read its \"Rule " + askedRule + " —\" line), and \"expect\" its results");
        return new ProposalCheck.Outcome(null, problems);
    }

    private String user(HttpServletRequest http) {
        String user = http.getHeader(auth.getUserHeader());
        if ((user == null || user.isBlank()) && auth.isAllowAnonymous()) {
            user = auth.getAnonymousUser();
        }
        return user == null || user.isBlank() ? null : user.trim();
    }

    /** Who is asking and what this instance is — from the server's own settings, not from the screen. */
    private String instance(String user) {
        boolean deployer = "deployer".equals(role);
        return "- environment: " + env + "\n"
                + "- this instance: " + (deployer ? "deploy only (no work spaces, no editing, no release branches or tags made here; it deploys tags)" : "authoring (work spaces, releases, deploying)") + "\n"
                + "- trying a work space in an application here (Try in " + env + "): " + (allowDraftPublish ? "allowed" : "not allowed") + "\n"
                + "- the person asking: " + (user == null ? "unknown" : user);
    }

    /** The last turns, user and assistant only, each cut to a sensible length. */
    private List<ChatModel.Message> conversation(List<Turn> turns) {
        List<ChatModel.Message> out = new ArrayList<>();
        if (turns == null) {
            return out;
        }
        int keep = Math.max(1, props.getHistoryTurns() * 2);
        for (Turn t : turns.subList(Math.max(0, turns.size() - keep), turns.size())) {
            if (t == null || t.content == null || t.content.isBlank() || !("user".equals(t.role) || "assistant".equals(t.role))) {
                continue;
            }
            String c = t.content.length() > MAX_MESSAGE ? t.content.substring(0, MAX_MESSAGE) + "…" : t.content;
            out.add(new ChatModel.Message(t.role, c));
        }
        return out;
    }

    /** The JSON object in what the model said: thinking, code fences and chatter around it are dropped. */
    JsonNode parse(String raw) {
        if (raw == null) {
            return null;
        }
        String text = raw.replaceAll("(?s)<think>.*?</think>", "").trim();
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            JsonNode node = json.readTree(text.substring(start, end + 1));
            return node.isObject() && node.path("answer").isTextual() ? node : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** An answer the grammar closed early: it stops on a space, mid-sentence, with nothing proposed. */
    static boolean cutShort(JsonNode reply) {
        String answer = reply.path("answer").asText("");
        return !reply.path("proposal").isObject() && (answer.isBlank() || Character.isWhitespace(answer.charAt(answer.length() - 1)));
    }

    private static String plainText(String raw) {
        return raw == null ? "" : raw.replaceAll("(?s)<think>.*?</think>", "").trim();
    }

    private Map<String, Object> answer(String text, ObjectNode proposal, JsonNode turn, long started) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("answer", text);
        body.put("proposal", proposal);
        // What to send back as this turn next time, so the model sees its own earlier replies as it wrote them.
        body.put("turn", turn == null ? null : turn.toString());
        body.put("model", props.getModel());
        body.put("seconds", Math.round((System.nanoTime() - started) / 1e8) / 10.0);
        return body;
    }

    private Map<String, Object> refused(String text, List<String> problems, long started) {
        Map<String, Object> body = answer(text, null, null, started);
        body.put("refused", problems);
        return body;
    }

    private static ResponseEntity<Map<String, Object>> refuse(HttpStatus status, String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("error", message);
        return ResponseEntity.status(status).body(body);
    }
}
