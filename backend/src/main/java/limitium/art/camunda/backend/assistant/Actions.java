package limitium.art.camunda.backend.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * What the assistant may propose besides a table edit: a button of the screen, a place to go, and
 * the screen's own actions — deploy a tag, tag a release, create a release branch, commit, publish.
 * Each is checked against what the screen shows (the tag is there, the number is a version, the
 * button can be pressed) and comes back with the words of the card the person confirms. Nothing runs
 * here: the person confirms, and the screen runs it through the same code as its own button.
 */
public class Actions {

    /** The actions of the table screen, checked by {@link ProposalCheck}. */
    public static final List<String> TABLE = List.of("add_rule", "add_test", "change_cell", "remove_rule");

    /** On every screen: its bottom bar's buttons, and the places it links to. */
    public static final List<String> EVERYWHERE = List.of("press", "go");

    private static final Map<String, List<String>> BY_PAGE = Map.of(
            "application", List.of("deploy", "redeploy", "restore_release", "start_work_space"),
            "releases", List.of("tag", "create_branch"),
            "commit", List.of("commit"),
            "try", List.of("publish"),
            "table", TABLE);

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Every action the screen allows, in the order the instructions describe them. */
    public static List<String> allowed(String page) {
        List<String> all = new ArrayList<>(BY_PAGE.getOrDefault(page == null ? "" : page, List.of()));
        all.addAll(EVERYWHERE);
        return all;
    }

    private final JsonNode context;
    private final String user;

    public Actions(JsonNode context, String user) {
        this.context = context == null ? JSON.createObjectNode() : context;
        this.user = user == null || user.isBlank() ? "you" : user;
    }

    /**
     * A plain command that names a place or a button of this screen exactly — "open fee", "take me to
     * Releases", "run the tests" — is proposed by the app itself: nothing for the model to get wrong.
     * Null when the question is anything else.
     */
    public ObjectNode command(String question) {
        String q = question == null ? "" : question.trim().replaceAll("[.!?,]+$", "").replaceAll("(?i)^please[,\\s]+|[,\\s]+please$", "").replaceAll("[.!?,]+$", "").trim();
        java.util.regex.Matcher go = java.util.regex.Pattern.compile("(?i)^(?:open|go to|go back to|take me to|show me|show|navigate to)\\s+(?:the\\s+)?(.+)$").matcher(q);
        if (go.matches()) {
            String place = oneOf(go.group(1), context.path("places"));
            if (place == null) {
                place = oneOf("step: " + go.group(1), context.path("places"));
            }
            if (place != null) {
                return JSON.createObjectNode().put("type", "go").put("place", place);
            }
        }
        java.util.regex.Matcher press = java.util.regex.Pattern.compile("(?i)^(?:press|click|hit)\\s+(.+)$").matcher(q);
        String button = oneOf(press.matches() ? press.group(1) : q, context.path("buttons"));
        return button == null ? null : JSON.createObjectNode().put("type", "press").put("button", button);
    }

    /**
     * Fields the model left out, taken from the person's words and the screen's own suggestions: the
     * place or branch the question names, the branch's next version or number, the newest tag. The
     * card shows them before anything happens.
     */
    public JsonNode complete(JsonNode proposal, String question) {
        ObjectNode p = proposal.deepCopy();
        String q = question == null ? "" : question;
        String type = p.path("type").asText("");
        switch (type) {
            case "go":
                fill(p, "place", named(q, context.path("places")));
                break;
            case "press":
                fill(p, "button", named(q, context.path("buttons")));
                break;
            case "deploy":
                fill(p, "tag", firstOf(versionIn(q), context.path("tags").path(0).asText("")));
                break;
            case "publish":
                fill(p, "application", named(q, context.path("applications")));
                break;
            case "tag": {
                String branch = firstOf(branchIn(q, false), onlyUntagged());
                fill(p, "branch", branch);
                fill(p, "version", firstOf(versionIn(q), context.path("branchTips").path(p.path("branch").asText("")).path("nextVersion").asText("")));
                break;
            }
            case "create_branch": {
                fill(p, "from", firstOf(branchIn(q, true), "main"));
                String number = java.util.regex.Pattern.compile("\\b(?:release/)?(\\d+\\.\\d+(?:\\.\\d+)?)\\b").matcher(q).results()
                        .map((m) -> m.group(1)).filter((n) -> !branchIn(q, true).equals("release/" + n)).reduce((a, b) -> b).orElse("");
                fill(p, "number", firstOf(number, context.path("branchTips").path(p.path("from").asText("")).path("nextBranch").asText("").replaceFirst("^release/", "")));
                break;
            }
            default:
                break;
        }
        return p;
    }

    private static void fill(ObjectNode p, String field, String value) {
        if (p.path(field).asText("").isBlank() && value != null && !value.isBlank()) {
            p.put(field, value);
        }
    }

    private static String firstOf(String a, String b) {
        return a != null && !a.isBlank() ? a : b;
    }

    /** The longest listed name the question mentions. */
    private static String named(String question, JsonNode list) {
        String q = question.toLowerCase(java.util.Locale.ROOT);
        String best = "";
        for (JsonNode item : list) {
            String name = item.asText();
            String bare = name.replaceFirst("^step: ", "");
            if (q.contains(bare.toLowerCase(java.util.Locale.ROOT)) && name.length() > best.length()) {
                best = name;
            }
        }
        return best;
    }

    private static String versionIn(String question) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\bv(\\d+\\.\\d+\\.\\d+)\\b").matcher(question);
        return m.find() ? "v" + m.group(1) : "";
    }

    /** A branch of branchTips the question names (main only when it is allowed). */
    private String branchIn(String question, boolean mainToo) {
        String best = "";
        for (java.util.Iterator<String> it = context.path("branchTips").fieldNames(); it.hasNext(); ) {
            String b = it.next();
            if ((mainToo || !"main".equals(b)) && question.contains(b) && b.length() > best.length()) {
                best = b;
            }
        }
        return best;
    }

    /** The one release branch with commits not tagged yet, when there is exactly one. */
    private String onlyUntagged() {
        String only = null;
        for (java.util.Iterator<Map.Entry<String, JsonNode>> it = context.path("branchTips").fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            if (!"main".equals(e.getKey()) && !e.getValue().path("nextVersion").asText("").isEmpty()) {
                if (only != null) {
                    return "";
                }
                only = e.getKey();
            }
        }
        return only == null ? "" : only;
    }

    public ProposalCheck.Outcome check(JsonNode proposal) {
        List<String> problems = new ArrayList<>();
        String type = proposal.path("type").asText("");
        ObjectNode p = JSON.createObjectNode();
        p.put("type", type);
        switch (type) {
            case "press": {
                String button = oneOf(text(proposal, "button"), context.path("buttons"));
                if (button == null) {
                    problems.add(none("button", text(proposal, "button"), "buttons", context.path("buttons")));
                    break;
                }
                p.put("button", button);
                card(p, "Press " + button, button, false);
                break;
            }
            case "go": {
                String place = oneOf(text(proposal, "place"), context.path("places"));
                if (place == null) {
                    problems.add(none("place", text(proposal, "place"), "places", context.path("places")));
                    break;
                }
                p.put("place", place);
                card(p, "Open " + place, "Open " + place, false);
                break;
            }
            case "deploy": {
                String tag = version(text(proposal, "tag"));
                String app = context.path("application").asText("");
                if (tag == null || oneOf(tag, context.path("tags")) == null) {
                    problems.add(context.path("tags").isEmpty()
                            ? "there is no tag to deploy here yet: tags are made on Releases"
                            : none("tag", text(proposal, "tag"), "tags", context.path("tags")));
                    break;
                }
                p.put("tag", tag);
                card(p, "Deploy " + tag + " to " + app, "Deploy " + tag + " to " + app, true,
                        app + " will run " + tag + " (now: " + context.path("runsNow").asText("unknown") + ").",
                        "Every test runs at " + tag + " first; one failing test stops it.");
                break;
            }
            case "redeploy": {
                if (!context.path("runsSomething").asBoolean(false)) {
                    problems.add("nothing runs here yet, so there is nothing to redeploy");
                    break;
                }
                String app = context.path("application").asText("");
                card(p, "Redeploy what " + app + " runs", "Redeploy what runs", false,
                        "Sends what " + app + " runs again, for when it missed it. What it runs does not change.");
                break;
            }
            case "restore_release": {
                if (!context.path("draftRuns").asBoolean(false)) {
                    problems.add("no draft runs here, so there is no release to put back");
                    break;
                }
                String app = context.path("application").asText("");
                card(p, "Put the last release back on " + app, "Put the last release back", true,
                        app + " stops running the draft (" + context.path("runsNow").asText("") + ") and runs its last release again.");
                break;
            }
            case "start_work_space": {
                if (!context.path("canStartWorkSpace").asBoolean(false)) {
                    problems.add("a work space cannot be started here: nothing runs, or this instance only deploys");
                    break;
                }
                String slug = slug(text(proposal, "name"));
                if (slug.isEmpty()) {
                    problems.add("\"name\" is empty: give the work a short name, like `xetra-fees`");
                    break;
                }
                String kind = context.path("runsRelease").asBoolean(false) ? "hotfix" : "feature";
                p.put("name", slug);
                card(p, "Start the work space " + slug, "Start the work space", false,
                        "Becomes the branch " + kind + "/" + user + "/" + slug + ", starting from exactly what "
                                + context.path("application").asText("this application") + " runs.");
                break;
            }
            case "tag": {
                String branch = text(proposal, "branch");
                JsonNode line = context.path("branchTips").path(branch);
                String tag = version(text(proposal, "version"));
                if (line.isMissingNode() || "main".equals(branch) || !branch.startsWith("release/")) {
                    problems.add("\"branch\" must be a release branch listed under branchTips (main is never tagged); it is `" + branch + "`");
                    break;
                }
                if (tag == null) {
                    problems.add("\"version\" must be a version like `v1.0.0`; it is `" + text(proposal, "version") + "`");
                    break;
                }
                for (JsonNode taken : line.path("tags")) {
                    if (taken.asText().equals(tag)) {
                        problems.add(tag + " is taken already on " + branch + "; its next tag is " + line.path("nextVersion").asText("?"));
                    }
                }
                if (!problems.isEmpty()) {
                    break;
                }
                p.put("branch", branch);
                p.put("version", tag);
                p.put("commit", line.path("tip").asText());
                p.put("notes", text(proposal, "notes"));
                String notes = text(proposal, "notes");
                card(p, "Tag " + branch + "'s newest commit as " + tag, "Tag as " + tag, true,
                        "Commit " + short7(line.path("tip").asText()) + ": “" + line.path("tipMessage").asText("") + "”.",
                        notes.isEmpty() ? "No notes." : "Notes kept in the tag: “" + notes + "”.",
                        "Every test runs at that commit first; the tag is made only if all pass. A tag is what production deploys, and it never moves.");
                break;
            }
            case "create_branch": {
                String from = text(proposal, "from");
                JsonNode line = context.path("branchTips").path(from);
                String number = text(proposal, "number").replaceFirst("^release/", "").replaceFirst("^v", "");
                if (line.isMissingNode()) {
                    problems.add("\"from\" must be main or a release branch listed under branchTips; it is `" + from + "`");
                    break;
                }
                if (!number.matches("\\d+\\.\\d+(\\.\\d+)?")) {
                    problems.add("\"number\" must be a version like `1.1` or `1.0.1`; it is `" + text(proposal, "number") + "`");
                    break;
                }
                p.put("from", from);
                p.put("number", number);
                p.put("commit", line.path("tip").asText());
                card(p, "Create release/" + number + " from " + from + "'s newest commit", "Create branch release/" + number, true,
                        "Starts at commit " + short7(line.path("tip").asText()) + " of " + from + ".",
                        "Work for this release is merged there, and from there into every newer release branch and main.");
                break;
            }
            case "commit": {
                if (!context.path("hasChanges").asBoolean(false)) {
                    problems.add("nothing has changed since the last commit, so there is nothing to commit");
                    break;
                }
                String message = text(proposal, "message");
                if (message.isEmpty()) {
                    // "Commit it": the message is the one in the box, shown on the card before anything happens.
                    message = context.path("messageInBox").asText("").trim();
                }
                if (message.isEmpty()) {
                    problems.add("\"message\" is empty: say what changed");
                    break;
                }
                p.put("message", message.length() > 4000 ? message.substring(0, 4000) : message);
                card(p, "Commit changes", "Commit changes", false,
                        "Committed on " + context.path("commitIsMadeOnBranch").asText("this work space's branch") + "; colleagues can see it at once.");
                break;
            }
            case "publish": {
                String app = oneOf(text(proposal, "application"), context.path("applications"));
                if (app == null) {
                    problems.add(none("application", text(proposal, "application"), "applications", context.path("applications")));
                    break;
                }
                p.put("application", app);
                card(p, "Publish this work space to " + app, "Publish to " + app, true,
                        app + " runs this work space's last commit at once — for everyone using it, until a release is deployed there again.",
                        "Every test runs first.");
                break;
            }
            default:
                problems.add("\"type\" `" + type + "` is not an action of this screen");
        }
        return new ProposalCheck.Outcome(problems.isEmpty() ? p : null, problems);
    }

    // --- helpers -----------------------------------------------------------------------------------

    /** The words of the card: what happens, the button that does it, and whether it changes something shared. */
    private static void card(ObjectNode p, String title, String confirm, boolean loud, String... lines) {
        ObjectNode card = p.putObject("card");
        card.put("title", title);
        card.put("confirm", confirm);
        card.put("loud", loud);
        ArrayNode l = card.putArray("lines");
        for (String line : lines) {
            l.add(line);
        }
    }

    private static String text(JsonNode proposal, String field) {
        JsonNode v = proposal.path(field);
        return v.isMissingNode() || v.isNull() ? "" : v.asText("").trim();
    }

    /** The listed value a name means, ignoring case; null when it is not listed. */
    private static String oneOf(String wanted, JsonNode list) {
        for (JsonNode item : list) {
            if (item.asText().equalsIgnoreCase(wanted.trim())) {
                return item.asText();
            }
        }
        return null;
    }

    private static String none(String field, String value, String list, JsonNode items) {
        List<String> names = new ArrayList<>();
        for (Iterator<JsonNode> it = items.elements(); it.hasNext(); ) {
            names.add("`" + it.next().asText() + "`");
        }
        return "\"" + field + "\" is `" + value + "`, which is not under " + list + " on this screen"
                + (names.isEmpty() ? " (there are none)" : "; it can be " + String.join(", ", names));
    }

    /** "1.0.0", "v1.0.0" → "v1.0.0"; anything else → null. */
    static String version(String text) {
        String t = text == null ? "" : text.trim().replaceFirst("^[vV]", "");
        return t.matches("\\d+\\.\\d+\\.\\d+") ? "v" + t : null;
    }

    /** A name as a branch name: lower case, words joined by dashes — as the screen makes it. */
    static String slug(String text) {
        return String.valueOf(text == null ? "" : text).trim().toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9._-]+", "-").replaceAll("\\.{2,}", ".").replaceAll("-{2,}", "-")
                .replaceAll("^[-.]+|[-.]+$", "").replaceAll("\\.lock$", "");
    }

    private static String short7(String sha) {
        return sha.length() > 7 ? sha.substring(0, 7) : sha;
    }
}
