package limitium.art.camunda.backend.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import limitium.art.camunda.evaluator.DmnEvaluator;
import limitium.art.camunda.evaluator.testspec.ChainScope;
import org.camunda.bpm.dmn.engine.DmnDecisionResult;
import org.camunda.bpm.engine.variable.VariableMap;
import org.camunda.bpm.engine.variable.Variables;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the model proposes is checked before anyone sees it: every cell of a new rule is evaluated by
 * the engine itself, alone, in a table with that cell's column — so a cell the engine would refuse is
 * refused here, with its reason, and the model is asked once to correct it. A test case is checked
 * against the table's columns and then run, so it arrives saying what the table gives today.
 *
 * The result is the proposal in the shape the screen applies: one rule's cells in column order, or one
 * test case's values by column. It never carries anything else, so the screen can only ever add one
 * rule or one case.
 */
public class ProposalCheck {

    public static final String ADD_RULE = "add_rule";
    public static final String ADD_TEST = "add_test";
    public static final String CHANGE_CELL = "change_cell";
    public static final String REMOVE_RULE = "remove_rule";

    private static final ObjectMapper JSON = new ObjectMapper();

    /** The normalised proposal, or why it was refused (in words the model can act on). */
    public static class Outcome {
        public final ObjectNode proposal;
        public final List<String> problems;

        public Outcome(ObjectNode proposal, List<String> problems) {
            this.proposal = proposal;
            this.problems = problems;
        }

        public boolean ok() {
            return problems.isEmpty();
        }
    }

    private final JsonNode context;
    private final String dmnXml;

    /**
     * @param context what the table screen sent: {@code tables[]} with their columns, rules and test columns
     * @param dmnXml  the file as it is on screen, to run test cases against; may be null
     */
    public ProposalCheck(JsonNode context, String dmnXml) {
        this.context = context;
        this.dmnXml = dmnXml;
    }

    public Outcome check(JsonNode proposal) {
        List<String> problems = new ArrayList<>();
        String type = proposal.path("type").asText("");
        JsonNode table = findTable(proposal.path("table").asText(""), problems);
        if (table == null) {
            return new Outcome(null, problems);
        }
        if (ADD_RULE.equals(type)) {
            return rule(proposal, table, problems);
        }
        if (ADD_TEST.equals(type)) {
            return test(proposal, table, problems);
        }
        if (CHANGE_CELL.equals(type)) {
            return cell(proposal, table, problems);
        }
        if (REMOVE_RULE.equals(type)) {
            return removal(proposal, table, problems);
        }
        problems.add("\"type\" must be add_rule, add_test, change_cell or remove_rule, not \"" + type + "\"");
        return new Outcome(null, problems);
    }

    // --- one cell, one rule ----------------------------------------------------------------------

    /** One cell of one rule, checked by the engine like a new rule's cell. */
    private Outcome cell(JsonNode proposal, JsonNode table, List<String> problems) {
        JsonNode rule = ruleAt(proposal, table, problems);
        if (rule == null) {
            return new Outcome(null, problems);
        }
        String column = proposal.path("column").asText("").trim();
        JsonNode inputs = table.path("inputs");
        JsonNode outputs = table.path("outputs");
        int in = column(inputs, column, "key", "expression", "label");
        int out = in >= 0 ? -1 : column(outputs, column, "name", "label");
        if (in < 0 && out < 0) {
            problems.add("\"column\" is `" + column + "`, which is not a column of " + table.path("name").asText()
                    + "; its conditions are " + names(inputs, "key") + " and its results " + names(outputs, "name"));
            return new Outcome(null, problems);
        }
        String value = cellText(proposal.path("value"));
        Map<String, Object> sample = sampleValues();
        String refused;
        if (in >= 0) {
            value = value.isEmpty() ? "-" : value;
            refused = "-".equals(value) ? null : unknownName(value, sample);
            if (refused == null && !"-".equals(value)) {
                refused = checkCondition(inputs.get(in), value, sample);
            }
        } else {
            refused = value.isEmpty() ? null : unknownName(value, sample);
            if (refused == null && !value.isEmpty()) {
                refused = checkResult(outputs.get(out), value, sample);
            }
        }
        if (refused != null) {
            problems.add("`" + value + "` is refused: " + refused);
            return new Outcome(null, problems);
        }
        int n = proposal.path("rule").asInt();
        String before = (in >= 0 ? rule.path("inputs").path(in) : rule.path("outputs").path(out)).asText("");
        ObjectNode result = JSON.createObjectNode();
        result.put("type", CHANGE_CELL);
        result.put("table", table.path("id").asText());
        result.put("tableName", table.path("name").asText());
        result.put("rule", n);
        result.put("side", in >= 0 ? "in" : "out");
        result.put("index", in >= 0 ? in : out);
        result.put("column", in >= 0 ? inputs.get(in).path("key").asText() : outputs.get(out).path("name").asText());
        result.put("before", before);
        result.put("value", value);
        if (before.trim().equals(value.trim())) {
            problems.add("rule " + n + "'s " + result.path("column").asText() + " is `" + before + "` already");
            return new Outcome(null, problems);
        }
        return new Outcome(result, problems);
    }

    /** One rule out; the card shows which, as it reads. */
    private Outcome removal(JsonNode proposal, JsonNode table, List<String> problems) {
        JsonNode rule = ruleAt(proposal, table, problems);
        if (rule == null) {
            return new Outcome(null, problems);
        }
        ObjectNode result = JSON.createObjectNode();
        result.put("type", REMOVE_RULE);
        result.put("table", table.path("id").asText());
        result.put("tableName", table.path("name").asText());
        result.put("rule", proposal.path("rule").asInt());
        result.set("inputs", rule.path("inputs"));
        result.set("outputs", rule.path("outputs"));
        result.put("description", rule.path("description").asText(""));
        ObjectNode columns = result.putObject("columns");
        ArrayNode conditionNames = columns.putArray("inputs");
        table.path("inputs").forEach((c) -> conditionNames.add(c.path("key").asText()));
        ArrayNode resultNames = columns.putArray("outputs");
        table.path("outputs").forEach((c) -> resultNames.add(c.path("name").asText()));
        return new Outcome(result, problems);
    }

    private static JsonNode ruleAt(JsonNode proposal, JsonNode table, List<String> problems) {
        int n = proposal.path("rule").asInt(0);
        int count = table.path("rules").size();
        if (n < 1 || n > count) {
            problems.add("\"rule\" must be a rule number of " + table.path("name").asText() + ", 1 to " + count + "; it is `" + proposal.path("rule").asText() + "`");
            return null;
        }
        return table.path("rules").get(n - 1);
    }

    // --- a rule ------------------------------------------------------------------------------------

    private Outcome rule(JsonNode proposal, JsonNode table, List<String> problems) {
        JsonNode inputs = table.path("inputs");
        JsonNode outputs = table.path("outputs");
        String[] in = new String[inputs.size()];
        String[] out = new String[outputs.size()];
        java.util.Arrays.fill(in, "-");

        JsonNode when = proposal.path("when");
        for (Iterator<Map.Entry<String, JsonNode>> it = when.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            int i = column(inputs, e.getKey(), "key", "expression", "label");
            if (i < 0) {
                problems.add("\"when\" names `" + e.getKey() + "`, which is not a condition of " + table.path("name").asText()
                        + "; its conditions are " + names(inputs, "key"));
                continue;
            }
            String cell = cellText(e.getValue());
            in[i] = cell.isEmpty() ? "-" : cell;
        }
        JsonNode then = proposal.path("then");
        for (Iterator<Map.Entry<String, JsonNode>> it = then.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            int i = column(outputs, e.getKey(), "name", "label");
            if (i < 0) {
                problems.add("\"then\" names `" + e.getKey() + "`, which is not a result of " + table.path("name").asText()
                        + "; its results are " + names(outputs, "name"));
                continue;
            }
            out[i] = cellText(e.getValue());
        }
        for (int i = 0; i < out.length; i++) {
            if (out[i] == null) {
                problems.add("\"then\" gives nothing for `" + outputs.get(i).path("name").asText() + "`: give its value, or `null` for no value");
            }
        }
        if (!problems.isEmpty()) {
            return new Outcome(null, problems);
        }

        Map<String, Object> sample = sampleValues();
        for (int i = 0; i < in.length; i++) {
            if (!"-".equals(in[i])) {
                String refused = unknownName(in[i], sample);
                if (refused == null) {
                    refused = checkCondition(inputs.get(i), in[i], sample);
                }
                if (refused != null) {
                    problems.add("condition `" + inputs.get(i).path("key").asText() + "` = `" + in[i] + "` is refused: " + refused);
                }
            }
        }
        for (int i = 0; i < out.length; i++) {
            if (!out[i].isEmpty()) {
                String refused = unknownName(out[i], sample);
                if (refused == null) {
                    refused = checkResult(outputs.get(i), out[i], sample);
                }
                if (refused != null) {
                    problems.add("result `" + outputs.get(i).path("name").asText() + "` = `" + out[i] + "` is refused: " + refused);
                }
            }
        }
        if (!problems.isEmpty()) {
            return new Outcome(null, problems);
        }

        int count = table.path("rules").size();
        int position = proposal.path("position").canConvertToInt() ? proposal.path("position").asInt() : count + 1;
        position = Math.max(1, Math.min(count + 1, position));
        String misplaced = placement(table, position, in, out);
        if (misplaced != null) {
            problems.add(misplaced);
            return new Outcome(null, problems);
        }

        ObjectNode result = JSON.createObjectNode();
        result.put("type", ADD_RULE);
        result.put("table", table.path("id").asText());
        result.put("tableName", table.path("name").asText());
        result.put("position", position);
        ArrayNode cells = result.putArray("inputs");
        for (String c : in) {
            cells.add(c);
        }
        ArrayNode results = result.putArray("outputs");
        for (String c : out) {
            results.add(c);
        }
        result.put("description", proposal.path("description").asText("").trim());
        // The column names the cells are under, to show the rule as a row of the table.
        ObjectNode columns = result.putObject("columns");
        ArrayNode conditionNames = columns.putArray("inputs");
        inputs.forEach((c) -> conditionNames.add(c.path("key").asText()));
        ArrayNode resultNames = columns.putArray("outputs");
        outputs.forEach((c) -> resultNames.add(c.path("name").asText()));
        return new Outcome(result, problems);
    }

    // --- where it goes ------------------------------------------------------------------------------

    private static final String PROPOSED = "AssistantProposedRule";
    private static final Object UNKNOWN = new Object();
    private static final java.util.regex.Pattern RULE_ID = java.util.regex.Pattern.compile("DmnEvaluatedDecisionRuleImpl\\{id='([^']+)'");
    private static final String NUM = "-?\\d+(?:\\.\\d+)?";

    /**
     * Where the rule goes, run: the table as on screen with the rule in place, given values the new
     * rule matches. Under "First matching rule wins" an earlier rule taking those values means the new
     * one never applies; under "Only one rule may match" another rule matching them too is an error,
     * and under "Any may match" so is one giving a different result. Values that cannot be read off a
     * cell (a function, a negation) skip the check rather than guess.
     */
    private String placement(JsonNode table, int position, String[] in, String[] out) {
        String policy = table.path("hitPolicy").asText("UNIQUE");
        if (dmnXml == null || dmnXml.isBlank() || !java.util.Set.of("FIRST", "UNIQUE", "ANY").contains(policy)) {
            return null;
        }
        JsonNode inputs = table.path("inputs");
        Map<String, Object> values = new LinkedHashMap<>();
        for (int i = 0; i < inputs.size(); i++) {
            String expression = inputs.get(i).path("expression").asText().trim();
            Object v = valueMatching(in[i], inputs.get(i).path("type").asText(""));
            if (!expression.matches("[A-Za-z_][A-Za-z0-9_]*") || v == UNKNOWN) {
                return null;
            }
            values.put(expression, v);
        }
        String decision = table.path("id").asText();
        List<String> order = new ArrayList<>();
        String placed;
        try {
            placed = withRule(decision, position, in, out, order);
        } catch (Exception e) {
            return null;
        }
        String given = describe(values);
        Reach reach = run(placed, decision, policy, PROPOSED, values);
        if (reach == null) {
            return null;
        }
        List<String> others = new ArrayList<>();
        reach.others.forEach((id) -> others.add(ruleName(table, order, position, id)));
        if ("FIRST".equals(policy)) {
            int at = order.indexOf(reach.others.get(0)) + 1;
            return "as rule " + position + " it would never apply: for " + given + ", " + others.get(0)
                    + " matches first, and under \"First matching rule wins\" the first match decides. Give it position " + at
                    + ", just above that rule";
        }
        if ("UNIQUE".equals(policy)) {
            return "placed anywhere, this rule overlaps " + String.join(" and ", others) + ": both match " + given
                    + ", and under \"Only one rule may match\" that is an error. Change its conditions so they do not overlap";
        }
        return "this rule and " + String.join(" and ", others) + " both match " + given
                + " but give different results, and under \"Any may match, all must agree\" that is an error";
    }

    /** Which other rules stand in a rule's way, for values it matches; null when none do. */
    private static final class Reach {
        final List<String> others;

        Reach(List<String> others) {
            this.others = others;
        }
    }

    /**
     * The table run with values the target rule matches. Under first-match, an earlier rule taking
     * them is in its way; under unique or any, another rule matching them too (the engine names it in
     * its refusal). Null when nothing is in the way.
     */
    private static Reach run(String xml, String decision, String policy, String target, Map<String, Object> values) {
        DmnEvaluator evaluator = new DmnEvaluator();
        evaluator.setCollectCoverage(true);
        try {
            VariableMap vars = Variables.createVariables();
            values.forEach(vars::putValue);
            String scoped = ChainScope.cutXml(xml, decision, values.keySet());
            evaluator.loadRuleFromString(scoped, decision).evaluate(vars);
        } catch (RuntimeException e) {
            java.util.regex.Matcher m = RULE_ID.matcher(rootMessage(e));
            List<String> others = new ArrayList<>();
            while (m.find()) {
                if (!target.equals(m.group(1)) && !others.contains(m.group(1))) {
                    others.add(m.group(1));
                }
            }
            return others.isEmpty() || "FIRST".equals(policy) ? null : new Reach(others);
        }
        if ("FIRST".equals(policy)) {
            for (DmnEvaluator.CoverageEvent event : evaluator.getCoverageEvents()) {
                if (decision.equals(event.decisionKey) && !target.equals(event.ruleId)) {
                    return new Reach(List.of(event.ruleId));
                }
            }
        }
        return null;
    }

    /**
     * Whether a rule already in a table can apply, as the review asks of every rule a change added or
     * changed: "rule 6 never applies: rule 5 (…) matches its cases first". Null when it applies, or
     * when the values it matches cannot be read off its cells.
     */
    public static String reach(String xml, String decision, String ruleId) {
        try {
            javax.xml.parsers.DocumentBuilderFactory factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            org.w3c.dom.Element root = factory.newDocumentBuilder().parse(new org.xml.sax.InputSource(new java.io.StringReader(xml))).getDocumentElement();
            org.w3c.dom.Element table = null;
            for (org.w3c.dom.Element d : children(root, "decision")) {
                if (decision.equals(d.getAttribute("id")) && !children(d, "decisionTable").isEmpty()) {
                    table = children(d, "decisionTable").get(0);
                }
            }
            if (table == null) {
                return null;
            }
            String policy = table.getAttribute("hitPolicy").isEmpty() ? "UNIQUE" : table.getAttribute("hitPolicy");
            if (!java.util.Set.of("FIRST", "UNIQUE", "ANY").contains(policy)) {
                return null;
            }
            List<org.w3c.dom.Element> rules = children(table, "rule");
            int index = -1;
            for (int i = 0; i < rules.size(); i++) {
                if (ruleId.equals(rules.get(i).getAttribute("id"))) {
                    index = i;
                }
            }
            if (index < 0) {
                return null;
            }
            List<org.w3c.dom.Element> inputs = children(table, "input");
            List<org.w3c.dom.Element> entries = children(rules.get(index), "inputEntry");
            Map<String, Object> values = new LinkedHashMap<>();
            for (int i = 0; i < inputs.size() && i < entries.size(); i++) {
                org.w3c.dom.Element expression = children(inputs.get(i), "inputExpression").isEmpty() ? null : children(inputs.get(i), "inputExpression").get(0);
                String name = expression == null ? "" : textOf(expression).trim();
                Object v = valueMatching(textOf(entries.get(i)), expression == null ? "" : expression.getAttribute("typeRef"));
                if (!name.matches("[A-Za-z_][A-Za-z0-9_]*") || v == UNKNOWN) {
                    return null;
                }
                values.put(name, v);
            }
            Reach reach = run(xml, decision, policy, ruleId, values);
            if (reach == null) {
                return null;
            }
            List<String> others = new ArrayList<>();
            for (String id : reach.others) {
                for (int i = 0; i < rules.size(); i++) {
                    if (id.equals(rules.get(i).getAttribute("id"))) {
                        String description = children(rules.get(i), "description").isEmpty() ? "" : children(rules.get(i), "description").get(0).getTextContent().trim();
                        others.add("rule " + (i + 1) + (description.isEmpty() ? "" : " (" + description + ")"));
                    }
                }
            }
            String given = describe(values);
            if ("FIRST".equals(policy)) {
                return "rule " + (index + 1) + " never applies: for " + given + ", " + String.join(", ", others) + " matches first";
            }
            return "rule " + (index + 1) + " overlaps " + String.join(" and ", others) + ": both match " + given
                    + ("UNIQUE".equals(policy) ? ", and only one rule may match" : ", with different results");
        } catch (Exception e) {
            return null;
        }
    }

    /** The ids of a table's rules in the order on screen; none when the domain cannot be read. */
    private List<String> ruleIds(String decision) {
        List<String> ids = new ArrayList<>();
        try {
            javax.xml.parsers.DocumentBuilderFactory factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            org.w3c.dom.Element root = factory.newDocumentBuilder().parse(new org.xml.sax.InputSource(new java.io.StringReader(dmnXml))).getDocumentElement();
            for (org.w3c.dom.Element d : children(root, "decision")) {
                if (decision.equals(d.getAttribute("id"))) {
                    for (org.w3c.dom.Element t : children(d, "decisionTable")) {
                        children(t, "rule").forEach((r) -> ids.add(r.getAttribute("id")));
                    }
                }
            }
        } catch (Exception e) {
            ids.clear();
        }
        return ids;
    }

    private static String textOf(org.w3c.dom.Element entry) {
        List<org.w3c.dom.Element> text = children(entry, "text");
        return text.isEmpty() ? entry.getTextContent() : text.get(0).getTextContent();
    }

    /** A value the cell matches, of the column's type; UNKNOWN when it cannot be read off the cell. */
    static Object valueMatching(String cell, String type) {
        String c = cell.trim();
        if (c.isEmpty() || "-".equals(c)) {
            return null;
        }
        if ("true".equals(c) || "false".equals(c)) {
            return Boolean.valueOf(c);
        }
        java.util.regex.Matcher text = java.util.regex.Pattern.compile("^\"([^\"]*)\"(?:\\s*,\\s*\"[^\"]*\")*$").matcher(c);
        if (text.matches()) {
            return "date".equals(type) ? UNKNOWN : text.group(1);
        }
        if (c.matches(NUM + "(?:\\s*,\\s*" + NUM + ")*")) {
            return Double.valueOf(c.split(",")[0].trim());
        }
        java.util.regex.Matcher compare = java.util.regex.Pattern.compile("^(>=|<=|>|<)\\s*(" + NUM + ")$").matcher(c);
        if (compare.matches()) {
            double n = Double.parseDouble(compare.group(2));
            switch (compare.group(1)) {
                case ">":
                    return n + 1;
                case "<":
                    return n - 1;
                default:
                    return n;
            }
        }
        java.util.regex.Matcher range = java.util.regex.Pattern.compile("^([\\[(\\]])\\s*(" + NUM + ")\\s*\\.\\.\\s*(" + NUM + ")\\s*([\\])\\[])$").matcher(c);
        if (range.matches()) {
            double a = Double.parseDouble(range.group(2));
            double b = Double.parseDouble(range.group(3));
            return "[".equals(range.group(1)) ? a : (a + b) / 2;
        }
        return UNKNOWN;
    }

    /** The domain as on screen with the new rule at its place; {@code order} receives the table's rule ids. */
    private String withRule(String decision, int position, String[] in, String[] out, List<String> order) throws Exception {
        javax.xml.parsers.DocumentBuilderFactory factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        org.w3c.dom.Document doc = factory.newDocumentBuilder().parse(new org.xml.sax.InputSource(new java.io.StringReader(dmnXml)));
        org.w3c.dom.Element root = doc.getDocumentElement();
        String ns = root.getNamespaceURI();
        String prefix = root.getPrefix() == null ? "" : root.getPrefix() + ":";
        org.w3c.dom.Element tableElement = null;
        for (org.w3c.dom.Element d : children(root, "decision")) {
            if (decision.equals(d.getAttribute("id"))) {
                List<org.w3c.dom.Element> t = children(d, "decisionTable");
                tableElement = t.isEmpty() ? null : t.get(0);
            }
        }
        if (tableElement == null) {
            throw new IllegalStateException("no such table");
        }
        List<org.w3c.dom.Element> rules = children(tableElement, "rule");
        org.w3c.dom.Element rule = doc.createElementNS(ns, prefix + "rule");
        rule.setAttribute("id", PROPOSED);
        for (int i = 0; i < in.length; i++) {
            rule.appendChild(entry(doc, ns, prefix, "inputEntry", PROPOSED + "_in" + i, in[i]));
        }
        for (int i = 0; i < out.length; i++) {
            rule.appendChild(entry(doc, ns, prefix, "outputEntry", PROPOSED + "_out" + i, out[i]));
        }
        if (position - 1 < rules.size()) {
            tableElement.insertBefore(rule, rules.get(position - 1));
        } else {
            tableElement.appendChild(rule);
        }
        for (org.w3c.dom.Element r : children(tableElement, "rule")) {
            order.add(r.getAttribute("id"));
        }
        java.io.StringWriter xml = new java.io.StringWriter();
        javax.xml.transform.TransformerFactory.newInstance().newTransformer()
                .transform(new javax.xml.transform.dom.DOMSource(doc), new javax.xml.transform.stream.StreamResult(xml));
        return xml.toString();
    }

    private static org.w3c.dom.Element entry(org.w3c.dom.Document doc, String ns, String prefix, String kind, String id, String cell) {
        org.w3c.dom.Element e = doc.createElementNS(ns, prefix + kind);
        e.setAttribute("id", id);
        org.w3c.dom.Element t = doc.createElementNS(ns, prefix + "text");
        t.setTextContent(cell);
        e.appendChild(t);
        return e;
    }

    private static List<org.w3c.dom.Element> children(org.w3c.dom.Element parent, String localName) {
        List<org.w3c.dom.Element> out = new ArrayList<>();
        for (org.w3c.dom.Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof org.w3c.dom.Element && localName.equals(n.getLocalName())) {
                out.add((org.w3c.dom.Element) n);
            }
        }
        return out;
    }

    /** "rule 5 (EU domestic markets pay flat basis points)", numbered as the table is now. */
    private static String ruleName(JsonNode table, List<String> order, int position, String id) {
        int at = order.indexOf(id);
        int now = at < position - 1 ? at : at - 1;
        String description = table.path("rules").path(now).path("description").asText("").trim();
        return "rule " + (now + 1) + (description.isEmpty() ? "" : " (" + description + ")");
    }

    private static String describe(Map<String, Object> values) {
        List<String> parts = new ArrayList<>();
        values.forEach((k, v) -> {
            if (v != null) {
                String shown = v instanceof Double && (Double) v == Math.rint((Double) v) ? String.valueOf(((Double) v).longValue()) : String.valueOf(v);
                parts.add(k + " " + (v instanceof String ? "\"" + shown + "\"" : shown));
            }
        });
        return parts.isEmpty() ? "any values" : String.join(", ", parts);
    }

    /** A condition alone in a table of its own column, evaluated: a refusal there is a refusal anywhere. */
    private String checkCondition(JsonNode input, String cell, Map<String, Object> sample) {
        String xml = table(
                "<input id=\"i\"><inputExpression id=\"ie\" typeRef=\"" + attr(input.path("type").asText("string")) + "\"><text>"
                        + text(input.path("expression").asText()) + "</text></inputExpression></input>"
                        + "<output id=\"o\" name=\"checked\" typeRef=\"string\"/>",
                "<inputEntry id=\"ce\"><text>" + text(cell) + "</text></inputEntry><outputEntry id=\"co\"><text>\"yes\"</text></outputEntry>");
        return evaluate(xml, sample);
    }

    /**
     * A result alone, under a condition that always matches, so the engine has to work it out. One
     * that comes out as no value was not written as `null` is refused too: the engine reads
     * `JP-LRG` as JP minus LRG, two names that do not exist, and quietly gives nothing.
     */
    private String checkResult(JsonNode output, String cell, Map<String, Object> sample) {
        String name = output.path("name").asText("result");
        String xml = table(
                "<input id=\"i\"><inputExpression id=\"ie\" typeRef=\"string\"><text>\"any\"</text></inputExpression></input>"
                        + "<output id=\"o\" name=\"" + attr(name) + "\" typeRef=\"" + attr(output.path("type").asText("string")) + "\"/>",
                "<inputEntry id=\"ce\"><text>-</text></inputEntry><outputEntry id=\"co\"><text>" + text(cell) + "</text></outputEntry>");
        try {
            VariableMap vars = Variables.createVariables();
            sample.forEach(vars::putValue);
            DmnDecisionResult r = new DmnEvaluator().loadRuleFromString(xml, "check").evaluate(vars);
            Object value = r.getResultList().isEmpty() ? null : r.getResultList().get(0).get(name);
            if (value == null && !"null".equals(cell.trim())) {
                return "it gives no value; text goes in double quotes, like `\"" + cell.replace("\"", "") + "\"`";
            }
            return null;
        } catch (RuntimeException e) {
            return plain(rootMessage(e));
        }
    }

    /** FEEL's own words and the functions of this engine: anything else bare in a cell must be a value's name. */
    private static final java.util.Set<String> FEEL_WORDS = new java.util.HashSet<>(java.util.Arrays.asList(
            "not", "null", "true", "false", "and", "or", "if", "then", "else", "for", "in", "return", "some", "every",
            "satisfies", "between", "instance", "of", "function", "date", "time", "and", "duration", "years", "months",
            "string", "number", "length", "substring", "before", "after", "upper", "lower", "case", "replace",
            "contains", "starts", "ends", "with", "matches", "split", "list", "count", "min", "max", "sum", "mean",
            "median", "stddev", "mode", "all", "any", "sublist", "append", "concatenate", "insert", "remove",
            "reverse", "index", "union", "distinct", "values", "flatten", "product", "sort", "decimal", "floor",
            "ceiling", "round", "up", "down", "half", "abs", "modulo", "sqrt", "log", "exp", "odd", "even", "now",
            "today", "day", "week", "month", "year", "is", "context", "get", "entries", "value", "put", "merge",
            "boolean", "invoke"));
    private static final java.util.regex.Pattern QUOTED = java.util.regex.Pattern.compile("\"(?:[^\"\\\\]|\\\\.)*\"");
    private static final java.util.regex.Pattern NAME = java.util.regex.Pattern.compile("(?<![\\w.])[A-Za-z_][A-Za-z0-9_]*");

    /** A bare word that is neither FEEL nor the name of a value: almost always text without its quotes. */
    private static String unknownName(String cell, Map<String, Object> known) {
        java.util.regex.Matcher m = NAME.matcher(QUOTED.matcher(cell).replaceAll("\"\""));
        while (m.find()) {
            String word = m.group();
            if (!FEEL_WORDS.contains(word) && !known.containsKey(word)) {
                // Text without its quotes, the usual mistake: say how it is written.
                String bare = cell.trim();
                return "`" + word + "` is read as the name of a value, and there is none; text goes in double quotes"
                        + (bare.matches("[A-Za-z][\\w-]*") ? ", like `\"" + bare + "\"`" : bare.matches("[A-Za-z][\\w-]*(\\s*,\\s*[A-Za-z][\\w-]*)+")
                        ? ", like `" + String.join(",", java.util.Arrays.stream(bare.split("\\s*,\\s*")).map((w) -> "\"" + w + "\"").toArray(String[]::new)) + "`" : "");
            }
        }
        return null;
    }

    private static String table(String columns, String entries) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<definitions xmlns=\"https://www.omg.org/spec/DMN/20191111/MODEL/\" id=\"checkDefinitions\" name=\"check\" namespace=\"http://camunda.org/schema/1.0/dmn\">"
                + "<decision id=\"check\" name=\"check\"><decisionTable id=\"t\" hitPolicy=\"FIRST\">"
                + columns + "<rule id=\"r\">" + entries + "</rule>"
                + "</decisionTable></decision></definitions>";
    }

    private static String evaluate(String xml, Map<String, Object> sample) {
        try {
            VariableMap vars = Variables.createVariables();
            sample.forEach(vars::putValue);
            new DmnEvaluator().loadRuleFromString(xml, "check").evaluate(vars);
            return null;
        } catch (RuntimeException e) {
            String message = rootMessage(e);
            return message.startsWith("No rules matched") ? null : plain(message);
        }
    }

    /**
     * A value for every name a cell may use — what any table of the domain checks, what its test cases
     * supply, and what its tables give — of the type of the column that checks it, so a cell is checked
     * the way it will run: `"100"` against a number column fails here as it would there.
     */
    private Map<String, Object> sampleValues() {
        Map<String, String> types = new LinkedHashMap<>();
        for (JsonNode t : context.path("tables")) {
            for (JsonNode v : t.path("supplied")) {
                types.putIfAbsent(v.asText(), "");
            }
            for (JsonNode o : t.path("outputs")) {
                types.put(o.path("name").asText(), o.path("type").asText(""));
            }
        }
        for (JsonNode t : context.path("tables")) {
            for (JsonNode input : t.path("inputs")) {
                String expression = input.path("expression").asText().trim();
                if (expression.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                    types.put(expression, input.path("type").asText(""));
                }
            }
        }
        Map<String, Object> values = new LinkedHashMap<>();
        // A name of no known type is most often a number worked with.
        types.forEach((name, type) -> values.put(name, sampleOf(type.isEmpty() ? "number" : type)));
        return values;
    }

    private static Object sampleOf(String type) {
        switch (type) {
            case "number":
            case "double":
                return 1.0d;
            case "integer":
                return 1;
            case "long":
                return 1L;
            case "boolean":
                return Boolean.TRUE;
            case "date":
                return "2025-01-01T00:00:00";
            default:
                return "text";
        }
    }

    // --- a test case -------------------------------------------------------------------------------

    private Outcome test(JsonNode proposal, JsonNode table, List<String> problems) {
        List<String> supplied = new ArrayList<>();
        for (JsonNode v : table.path("supplied")) {
            supplied.add(v.asText());
        }
        if (supplied.isEmpty()) {
            for (JsonNode input : table.path("inputs")) {
                supplied.add(input.path("expression").asText());
            }
        }
        ObjectNode given = JSON.createObjectNode();
        for (Iterator<Map.Entry<String, JsonNode>> it = proposal.path("given").fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            String name = match(supplied, e.getKey(), table.path("inputs"));
            if (name == null) {
                problems.add("\"given\" names `" + e.getKey() + "`, which " + table.path("name").asText()
                        + " is not supplied; a test case supplies " + String.join(", ", backticked(supplied)));
            } else {
                given.set(name, value(e.getValue()));
            }
        }
        JsonNode outputs = table.path("outputs");
        ObjectNode expect = JSON.createObjectNode();
        for (Iterator<Map.Entry<String, JsonNode>> it = proposal.path("expect").fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            int i = column(outputs, e.getKey(), "name", "label");
            if (i < 0) {
                problems.add("\"expect\" names `" + e.getKey() + "`, which is not a result of " + table.path("name").asText()
                        + "; its results are " + names(outputs, "name"));
            } else {
                expect.set(outputs.get(i).path("name").asText(), value(e.getValue()));
            }
        }
        if (given.isEmpty() && problems.isEmpty()) {
            problems.add("\"given\" is empty: say which values the case supplies");
        }
        if (expect.isEmpty() && problems.isEmpty()) {
            problems.add("\"expect\" is empty: say what the table should give");
        }
        if (!problems.isEmpty()) {
            return new Outcome(null, problems);
        }

        ObjectNode result = JSON.createObjectNode();
        result.put("type", ADD_TEST);
        result.put("table", table.path("id").asText());
        result.put("tableName", table.path("name").asText());
        String scenario = proposal.path("scenario").asText("").trim();
        result.put("scenario", scenario.isEmpty() ? "new case" : scenario);
        result.set("given", given);
        result.set("expect", expect);
        if (dmnXml != null && !dmnXml.isBlank()) {
            result.set("today", runToday(table.path("id").asText(), given, expect));
        }
        return new Outcome(result, problems);
    }

    /** What the table gives for the case now, and whether that is what the case expects. */
    private ObjectNode runToday(String decision, ObjectNode given, ObjectNode expect) {
        ObjectNode today = JSON.createObjectNode();
        Map<String, Object> values = JSON.convertValue(given, LinkedHashMap.class);
        // What the case does not supply is none, as when the tests run.
        for (JsonNode v : context.path("tables")) {
            if (v.path("id").asText().equals(decision)) {
                v.path("supplied").forEach((s) -> values.putIfAbsent(s.asText(), null));
            }
        }
        try {
            VariableMap vars = Variables.createVariables();
            values.forEach(vars::putValue);
            String scoped = ChainScope.cutXml(dmnXml, decision, given.properties().stream().map(Map.Entry::getKey).collect(java.util.stream.Collectors.toSet()));
            DmnEvaluator evaluator = new DmnEvaluator();
            evaluator.setCollectCoverage(true);
            DmnDecisionResult r = evaluator.loadRuleFromString(scoped, decision).evaluate(vars);
            // Which of the table's rules the case reaches, by their numbers on screen.
            List<String> order = ruleIds(decision);
            ArrayNode reached = today.putArray("rules");
            for (DmnEvaluator.CoverageEvent event : evaluator.getCoverageEvents()) {
                if (decision.equals(event.decisionKey) && order.contains(event.ruleId)) {
                    reached.add(order.indexOf(event.ruleId) + 1);
                }
            }
            ArrayNode rows = today.putArray("results");
            boolean agrees = !r.getResultList().isEmpty();
            for (Map<String, Object> entry : r.getResultList()) {
                rows.add(JSON.valueToTree(new LinkedHashMap<>(entry)));
            }
            for (Iterator<Map.Entry<String, JsonNode>> it = expect.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> e = it.next();
                boolean found = false;
                for (Map<String, Object> entry : r.getResultList()) {
                    found |= same(entry.get(e.getKey()), e.getValue());
                }
                agrees &= found;
            }
            today.put("agrees", agrees);
        } catch (RuntimeException e) {
            String message = rootMessage(e);
            today.putArray("results");
            today.put("agrees", false);
            if (message.startsWith("No rules matched")) {
                today.put("noMatch", true);
            } else {
                today.put("error", plain(message));
            }
        }
        return today;
    }

    private static boolean same(Object actual, JsonNode expected) {
        if (actual == null) {
            return expected == null || expected.isNull();
        }
        if (expected.isNumber() || actual instanceof Number) {
            try {
                return new BigDecimal(String.valueOf(actual)).compareTo(new BigDecimal(expected.asText())) == 0;
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return String.valueOf(actual).equals(expected.asText());
    }

    // --- helpers -----------------------------------------------------------------------------------

    private JsonNode findTable(String wanted, List<String> problems) {
        JsonNode tables = context.path("tables");
        String w = wanted.trim();
        if (w.isEmpty()) {
            w = context.path("openTable").asText("");
        }
        for (JsonNode t : tables) {
            if (t.path("id").asText().equalsIgnoreCase(w) || t.path("name").asText().equalsIgnoreCase(w)) {
                return t;
            }
        }
        List<String> names = new ArrayList<>();
        tables.forEach((t) -> names.add("\"" + t.path("name").asText() + "\""));
        problems.add("\"table\" is \"" + wanted + "\", but the tables here are " + String.join(", ", names));
        return null;
    }

    /** The index of the column a name means, by any of its names, ignoring case; -1 if none. */
    private static int column(JsonNode columns, String name, String... fields) {
        String n = name.trim();
        for (int i = 0; i < columns.size(); i++) {
            for (String f : fields) {
                if (columns.get(i).path(f).asText().trim().equalsIgnoreCase(n)) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static String match(List<String> supplied, String name, JsonNode inputs) {
        for (String s : supplied) {
            if (s.equalsIgnoreCase(name.trim())) {
                return s;
            }
        }
        // A condition's label for the value it checks.
        int i = column(inputs, name, "label", "key");
        if (i >= 0) {
            String expression = inputs.get(i).path("expression").asText();
            for (String s : supplied) {
                if (s.equals(expression)) {
                    return s;
                }
            }
        }
        return null;
    }

    private static String names(JsonNode columns, String field) {
        List<String> out = new ArrayList<>();
        columns.forEach((c) -> out.add(c.path(field).asText()));
        return String.join(", ", backticked(out));
    }

    private static List<String> backticked(List<String> names) {
        List<String> out = new ArrayList<>();
        names.forEach((n) -> out.add("`" + n + "`"));
        return out;
    }

    /** A cell as the model wrote it: text as is, a number or true/false as its literal. */
    private static String cellText(JsonNode v) {
        if (v == null || v.isNull()) {
            return "null";
        }
        return v.isTextual() ? v.asText().trim() : v.toString();
    }

    /** A test value is a value: a number written as text stays text only if it is not a number. */
    private static JsonNode value(JsonNode v) {
        return v == null ? JSON.nullNode() : v;
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        String message = e.getMessage() != null && e.getMessage().startsWith("No rules matched") ? e.getMessage() : t.getMessage();
        return message == null ? e.getClass().getSimpleName() : message;
    }

    /** The engine's words, with the hint that fixes the usual mistake. */
    private static String plain(String message) {
        String m = message.replaceAll("\\s+", " ").trim();
        java.util.regex.Matcher bare = java.util.regex.Pattern.compile("no variable found for name '([^']+)'").matcher(m);
        if (bare.find()) {
            return "`" + bare.group(1) + "` is read as the name of a value, and there is none; text goes in double quotes, like `\"" + bare.group(1) + "\"`";
        }
        if (m.length() > 300) {
            m = m.substring(0, 300) + "…";
        }
        return m;
    }

    private static String attr(String s) {
        return text(s).replace("\"", "&quot;");
    }

    private static String text(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
