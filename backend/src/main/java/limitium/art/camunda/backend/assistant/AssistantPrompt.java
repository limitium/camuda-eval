package limitium.art.camunda.backend.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The model's instructions, built here and nowhere else: what the app is, which screen the person
 * has open and what it is for, what that screen shows now, and what the model may answer with. The
 * screen sends only facts; every instruction comes from the files under {@code assistant/}, so no
 * page can tell the model what it is allowed to do.
 */
public class AssistantPrompt {

    /** The screens with a guide of their own; any other is described from its facts alone. */
    public static final Set<String> PAGES = Set.of(
            "applications", "application", "work", "new-work", "space", "table", "test", "commit", "try",
            "merge", "pull", "releases", "commit-view", "running-table", "tidy");

    /** Only on a domain's tables in a work space may the model propose a rule or a test case. */
    public static final String PROPOSING_PAGE = "table";

    private final java.nio.file.Path overrides;

    public AssistantPrompt() {
        this(null);
    }

    /** @param overrides a folder whose files replace the built-in ones of the same name; null for none */
    public AssistantPrompt(String overrides) {
        this.overrides = overrides == null || overrides.isBlank() ? null : java.nio.file.Paths.get(overrides);
    }

    private static final int MAX_RULES = 80;
    private static final int MAX_CASES = 40;
    private static final int MAX_TEXT = 200;

    private static final Map<String, String> HIT_POLICIES = Map.of(
            "UNIQUE", "Only one rule may match (UNIQUE): two rules matching the same values is an error",
            "FIRST", "First matching rule wins (FIRST): rules are tried top to bottom, the first that matches gives the result",
            "ANY", "Any may match, all must agree (ANY): matching rules must give the same result",
            "COLLECT", "Collect every match (COLLECT): every matching rule's result",
            "RULE ORDER", "Every match, in rule order (RULE ORDER)",
            "PRIORITY", "Highest-priority result wins (PRIORITY) — this engine cannot run it",
            "OUTPUT ORDER", "Every match, by result priority (OUTPUT ORDER) — this engine cannot run it");

    private static final Map<String, String> AGGREGATIONS = Map.of(
            "SUM", "added up", "COUNT", "counted", "MIN", "the smallest", "MAX", "the largest");

    public static boolean proposes(String page) {
        return PROPOSING_PAGE.equals(page);
    }

    /** The page as the screen named it, if it is one of the known screens; otherwise none. */
    public static String page(String requested) {
        return requested != null && PAGES.contains(requested) ? requested : null;
    }

    public String build(String page, JsonNode context, String instance) {
        String guide = page == null
                ? "A screen of the app without a guide of its own; what it shows is listed below."
                : instructions("pages/" + page + ".md");
        return instructions("system.md")
                .replace("{{instance}}", instance)
                .replace("{{page}}", guide.trim())
                .replace("{{screen}}", screen(page, context).trim())
                .replace("{{format}}", format(page).trim());
    }

    /** The reply format with this screen's actions, and on a table screen the rules for table edits and cells. */
    private String format(String page) {
        String all = instructions("actions.md");
        StringBuilder actions = new StringBuilder();
        for (String type : Actions.allowed(page)) {
            int at = all.indexOf("## " + type + "\n");
            if (at < 0) {
                continue;
            }
            int end = all.indexOf("\n## ", at + 3);
            actions.append(all, at, end < 0 ? all.length() : end + 1).append('\n');
        }
        String format = instructions("format.md").replace("{{actions}}", actions.toString().trim());
        if (proposes(page)) {
            format += "\n\n" + instructions("table-proposals.md").trim() + "\n\n" + instructions("feel.md").trim();
        }
        return format;
    }

    /**
     * The question as sent to the model: the person's words, then — marked as the app's — the screen's
     * situation and the line of every rule the question names ("rule 4", "rule 2 of Fee Calculator").
     */
    public String withFacts(String question, String page, JsonNode context) {
        StringBuilder notes = new StringBuilder();
        if (context != null && context.path("now").isTextual() && !context.path("now").asText().isBlank()) {
            notes.append("- NOW: ").append(context.path("now").asText().trim()).append('\n');
        }
        if (context != null && context.path("tables").size() > 0) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?i)\\brule\\s*#?(\\d+)").matcher(question);
            java.util.Set<Integer> named = new java.util.LinkedHashSet<>();
            while (m.find()) {
                named.add(Integer.valueOf(m.group(1)));
            }
            JsonNode table = tableMeant(question, context);
            for (int n : named) {
                JsonNode rule = table == null ? null : table.path("rules").path(n - 1);
                if (rule == null || rule.isMissingNode()) {
                    notes.append("- ").append(table == null ? "this table" : table.path("name").asText()).append(" has no rule ").append(n).append('\n');
                } else {
                    notes.append("- rule ").append(n).append(" of ").append(table.path("name").asText()).append(": ").append(ruleLine(table, rule)).append('\n');
                }
            }
        }
        return notes.length() == 0 ? question : question + "\n\n(From the app, not the person:\n" + notes + ")";
    }

    /** The table a question is about: one it names, else the open one. */
    private static JsonNode tableMeant(String question, JsonNode context) {
        String q = question.toLowerCase(java.util.Locale.ROOT);
        JsonNode open = null;
        JsonNode named = null;
        for (JsonNode t : context.path("tables")) {
            if (t.path("open").asBoolean(false)) {
                open = t;
            }
            String name = t.path("name").asText().toLowerCase(java.util.Locale.ROOT);
            if (!name.isEmpty() && q.contains(name) && (named == null || name.length() > named.path("name").asText().length())) {
                named = t;
            }
        }
        if (named != null) {
            return named;
        }
        return open != null ? open : context.path("tables").path(0);
    }

    // --- what the screen shows ---------------------------------------------------------------------

    String screen(String page, JsonNode context) {
        if (context == null || context.isMissingNode() || context.isNull() || context.isEmpty()) {
            return "(nothing was read from the screen)";
        }
        StringBuilder out = new StringBuilder();
        List<String> skip = new ArrayList<>();
        // The screen's situation in one sentence, first: a small model answers from what it reads first.
        if (context.path("now").isTextual() && !context.path("now").asText().isBlank()) {
            out.append("NOW: ").append(context.path("now").asText().trim()).append("\n\n");
            skip.add("now");
        }
        if (context.has("tables")) {
            skip.add("tables");
        }
        facts(context, skip, "", out);
        // The open table first: a question that names no table is about it.
        List<JsonNode> tables = new ArrayList<>();
        context.path("tables").forEach(tables::add);
        tables.sort((a, b) -> Boolean.compare(b.path("open").asBoolean(false), a.path("open").asBoolean(false)));
        for (JsonNode table : tables) {
            out.append('\n');
            table(table, out);
        }
        return out.toString();
    }

    /** Facts as an indented list: "name: value", lists of values joined, lists of things itemised. */
    private void facts(JsonNode node, List<String> skip, String indent, StringBuilder out) {
        for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            if (skip.contains(e.getKey())) {
                continue;
            }
            JsonNode v = e.getValue();
            if (v.isNull() || (v.isObject() && v.isEmpty()) || (v.isTextual() && v.asText().isBlank())) {
                continue;
            }
            if (v.isArray() && v.isEmpty()) {
                // An empty list is a fact: no tags, no problems, nothing deployed.
                out.append(indent).append("- ").append(e.getKey()).append(": none\n");
                continue;
            }
            out.append(indent).append("- ").append(e.getKey()).append(':');
            if (v.isObject()) {
                out.append('\n');
                facts(v, List.of(), indent + "  ", out);
            } else if (v.isArray() && allScalar(v)) {
                List<String> items = new ArrayList<>();
                v.forEach((x) -> items.add(clip(x.asText())));
                out.append(' ').append(String.join(", ", items)).append('\n');
            } else if (v.isArray()) {
                out.append('\n');
                int n = 0;
                for (JsonNode item : v) {
                    if (++n > MAX_CASES) {
                        out.append(indent).append("  - … ").append(v.size() - MAX_CASES).append(" more\n");
                        break;
                    }
                    if (item.isObject()) {
                        out.append(indent).append("  -\n");
                        facts(item, List.of(), indent + "    ", out);
                    } else {
                        out.append(indent).append("  - ").append(clip(item.asText())).append('\n');
                    }
                }
            } else {
                out.append(' ').append(clip(v.asText())).append('\n');
            }
        }
    }

    /** A table as a grid the model reads like the person does: numbered rules, conditions, then results. */
    private void table(JsonNode t, StringBuilder out) {
        out.append("## Table: ").append(t.path("name").asText());
        if (t.path("open").asBoolean(false)) {
            out.append(" — the tab open now: 'this table', or a question naming no table, means this one");
        } else {
            out.append(" — another table of this domain");
        }
        out.append('\n');
        if (!t.path("tabular").asBoolean(true)) {
            out.append("A single expression, not a table; not edited on this screen.\n");
            return;
        }
        String policy = t.path("hitPolicy").asText("UNIQUE");
        out.append("When several rules match: ").append(HIT_POLICIES.getOrDefault(policy, policy));
        String aggregation = t.path("aggregation").asText("");
        if ("COLLECT".equals(policy) && AGGREGATIONS.containsKey(aggregation)) {
            out.append(", ").append(AGGREGATIONS.get(aggregation));
        } else if ("COLLECT".equals(policy)) {
            out.append(", as a list");
        }
        out.append('\n');

        List<String> conditions = new ArrayList<>();
        for (JsonNode i : t.path("inputs")) {
            String c = "`" + i.path("key").asText() + "` (" + i.path("type").asText("any") + ")";
            if (!i.path("expression").asText().equals(i.path("key").asText())) {
                c += " checks `" + i.path("expression").asText() + "`";
            }
            if (!i.path("from").asText("").isEmpty()) {
                c += ", the result of " + i.path("from").asText();
            }
            conditions.add(c);
        }
        List<String> results = new ArrayList<>();
        for (JsonNode o : t.path("outputs")) {
            results.add("`" + o.path("name").asText() + "` (" + o.path("type").asText("any") + ")");
        }
        out.append("Conditions: ").append(conditions.isEmpty() ? "none — every rule matches" : String.join(", ", conditions)).append('\n');
        out.append("Results: ").append(String.join(", ", results)).append('\n');
        if (t.path("uses").size() > 0) {
            List<String> uses = new ArrayList<>();
            t.path("uses").forEach((u) -> uses.add(u.asText()));
            out.append("Uses the results of: ").append(String.join(", ", uses)).append('\n');
        }

        // One line per rule, its number first and last: a small model keeps numbers straight this way,
        // which it does not in a wide grid. A condition left out matches anything, as in a proposal.
        JsonNode rules = t.path("rules");
        out.append("Rules (").append(rules.size()).append(")").append("FIRST".equals(policy) ? ", tried from rule 1 down" : "").append(":\n");
        int n = 0;
        for (JsonNode r : rules) {
            if (++n > MAX_RULES) {
                out.append("… ").append(rules.size() - MAX_RULES).append(" more rules\n");
                break;
            }
            out.append("Rule ").append(n).append(" — ").append(ruleLine(t, r))
                    // The number again at the end: measured on qwen2.5:7b, it stops reading rule 4 as rule 5.
                    .append("   [this is rule ").append(n).append("]\n");
        }

        JsonNode supplied = t.path("supplied");
        if (supplied.size() > 0) {
            List<String> names = new ArrayList<>();
            supplied.forEach((x) -> names.add("`" + x.asText() + "`"));
            out.append("A test case of this table supplies: ").append(String.join(", ", names)).append('\n');
        }
        JsonNode tests = t.path("tests");
        JsonNode columns = tests.path("columns");
        JsonNode rows = tests.path("rows");
        if (columns.size() > 0 && rows.size() > 0) {
            out.append("Test cases (").append(rows.size()).append("):\n");
            int c = 0;
            for (JsonNode row : rows) {
                if (++c > MAX_CASES) {
                    out.append("… ").append(rows.size() - MAX_CASES).append(" more cases\n");
                    break;
                }
                String scenario = "";
                List<String> given = new ArrayList<>();
                List<String> expects = new ArrayList<>();
                for (int i = 0; i < columns.size(); i++) {
                    String column = columns.get(i).asText();
                    String value = clip(row.path(i).asText(""));
                    if ("scenario".equals(column)) {
                        scenario = value;
                    } else if (column.trim().startsWith("=>")) {
                        expects.add(column.trim().substring(2).trim() + " " + (value.isEmpty() ? "(not checked)" : value));
                    } else if (!value.isEmpty()) {
                        given.add(column + " " + value);
                    }
                }
                out.append("- ").append(scenario).append(": ").append(String.join(", ", given))
                        .append(" → expects ").append(String.join(", ", expects)).append('\n');
            }
        } else {
            out.append("No test cases yet.\n");
        }
        if (t.path("results").isTextual()) {
            out.append("Last test run: ").append(clip(t.path("results").asText())).append('\n');
        }
        for (JsonNode p : t.path("problems")) {
            out.append("Problem: ").append(clip(p.asText())).append('\n');
        }
    }

    /** "when country "GB", market "LSE" → gives feeCode "UK-STAMP" (description)": a rule in words. */
    private static String ruleLine(JsonNode t, JsonNode r) {
        JsonNode inputs = t.path("inputs");
        JsonNode outputs = t.path("outputs");
        List<String> when = new ArrayList<>();
        for (int i = 0; i < r.path("inputs").size(); i++) {
            String c = r.path("inputs").get(i).asText().trim();
            if (!c.isEmpty() && !"-".equals(c)) {
                when.add(inputs.path(i).path("key").asText("input " + (i + 1)) + " " + clip(c));
            }
        }
        List<String> gives = new ArrayList<>();
        for (int i = 0; i < r.path("outputs").size(); i++) {
            String c = r.path("outputs").get(i).asText().trim();
            gives.add(outputs.path(i).path("name").asText("result " + (i + 1)) + " " + (c.isEmpty() ? "(nothing)" : clip(c)));
        }
        String description = clip(r.path("description").asText(""));
        return "when " + (when.isEmpty() ? "anything (every condition is -)" : String.join(", ", when))
                + " → gives " + String.join(", ", gives) + (description.isEmpty() ? "" : "   (" + description + ")");
    }

    private static boolean allScalar(JsonNode array) {
        for (JsonNode x : array) {
            if (x.isContainerNode()) {
                return false;
            }
        }
        return true;
    }

    private static String clip(String text) {
        String t = text.replaceAll("\\s+", " ").trim();
        return t.length() <= MAX_TEXT ? t : t.substring(0, MAX_TEXT) + "…";
    }

    private String instructions(String name) {
        if (overrides != null) {
            java.nio.file.Path file = overrides.resolve(name).normalize();
            if (file.startsWith(overrides) && java.nio.file.Files.isRegularFile(file)) {
                try {
                    return java.nio.file.Files.readString(file);
                } catch (IOException e) {
                    throw new IllegalStateException("the assistant's instructions cannot be read: " + file, e);
                }
            }
        }
        return resource("assistant/" + name);
    }

    private static String resource(String path) {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("the assistant's instructions are missing: " + path, e);
        }
    }
}
