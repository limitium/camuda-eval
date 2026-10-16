package limitium.art.camunda.evaluator.diff;

import org.camunda.bpm.model.dmn.Dmn;
import org.camunda.bpm.model.dmn.DmnModelInstance;
import org.camunda.bpm.model.dmn.instance.Decision;
import org.camunda.bpm.model.dmn.instance.DecisionTable;
import org.camunda.bpm.model.dmn.instance.Input;
import org.camunda.bpm.model.dmn.instance.InformationRequirement;
import org.camunda.bpm.model.dmn.instance.InputEntry;
import org.camunda.bpm.model.dmn.instance.InputExpression;
import org.camunda.bpm.model.dmn.instance.Output;
import org.camunda.bpm.model.dmn.instance.OutputEntry;

import java.io.ByteArrayInputStream;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads decision tables out of DMN XML in a form a diff or a merge can work with.
 *
 * <p>Deliberately tolerant where {@code DmnEvaluator.loadAllRules} is not: a decision with no
 * decision table (a literal expression) and a decision table with no rules are both normal inputs
 * here, reported as {@code tabular == false} or an empty rule list rather than thrown as errors.
 * A diff of two documents must not fail because one of them contains an empty table.
 */
public final class DecisionTableReader {

    private DecisionTableReader() {
    }

    public static final class Column {
        public final String id;
        public final String label;
        /** Input expression text for an input; the output variable name for an output. */
        public final String expression;
        public final String typeRef;

        Column(String id, String label, String expression, String typeRef) {
            this.id = id;
            this.label = label;
            this.expression = expression;
            this.typeRef = typeRef;
        }
    }

    public static final class Rule {
        public final String id;
        public final String description;
        public final List<String> inputEntries;
        public final List<String> outputEntries;

        Rule(String id, String description, List<String> inputEntries, List<String> outputEntries) {
            this.id = id;
            this.description = description;
            this.inputEntries = Collections.unmodifiableList(inputEntries);
            this.outputEntries = Collections.unmodifiableList(outputEntries);
        }
    }

    public static final class Table {
        public final String decisionId;
        public final String decisionName;
        /** False when the decision holds no decision table, e.g. a literal expression. */
        public final boolean tabular;
        /** Hit policy including aggregation, e.g. "UNIQUE" or "COLLECT SUM". Null when not tabular. */
        public final String hitPolicy;
        public final List<Column> inputs;
        public final List<Column> outputs;
        public final List<Rule> rules;
        /** Ids of decisions this one consumes, from its information requirements. */
        public final List<String> requiredDecisions;

        Table(String decisionId, String decisionName, boolean tabular, String hitPolicy,
              List<Column> inputs, List<Column> outputs, List<Rule> rules,
              List<String> requiredDecisions) {
            this.decisionId = decisionId;
            this.decisionName = decisionName;
            this.tabular = tabular;
            this.hitPolicy = hitPolicy;
            this.inputs = Collections.unmodifiableList(inputs);
            this.outputs = Collections.unmodifiableList(outputs);
            this.rules = Collections.unmodifiableList(rules);
            this.requiredDecisions = Collections.unmodifiableList(requiredDecisions);
        }
    }

    /** Reads every decision in the document, keyed by decision id, preserving document order. */
    public static Map<String, Table> read(String dmnXml) {
        DmnModelInstance model = parse(dmnXml);
        Map<String, Table> tables = new LinkedHashMap<String, Table>();
        for (Decision decision : model.getModelElementsByType(Decision.class)) {
            Table table = readDecision(decision);
            tables.put(table.decisionId, table);
        }
        return tables;
    }

    private static DmnModelInstance parse(String dmnXml) {
        try {
            return Dmn.readModelFromStream(new ByteArrayInputStream(dmnXml.getBytes("UTF-8")));
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException("UTF-8 unavailable", e);
        }
    }

    private static Table readDecision(Decision decision) {
        String id = decision.getId();
        String name = decision.getName();

        List<String> requiredDecisions = new ArrayList<String>();
        for (InformationRequirement requirement : decision.getInformationRequirements()) {
            Decision required = requirement.getRequiredDecision();
            if (required != null && required.getId() != null) {
                requiredDecisions.add(required.getId());
            }
        }

        Collection<DecisionTable> decisionTables = decision.getChildElementsByType(DecisionTable.class);
        if (decisionTables.isEmpty()) {
            return new Table(id, name, false, null,
                    new ArrayList<Column>(), new ArrayList<Column>(), new ArrayList<Rule>(),
                    requiredDecisions);
        }

        DecisionTable table = decisionTables.iterator().next();

        List<Column> inputs = new ArrayList<Column>();
        for (Input input : table.getInputs()) {
            InputExpression expression = input.getInputExpression();
            String text = expression == null ? null : trimToNull(expression.getTextContent());
            String typeRef = expression == null ? null : expression.getTypeRef();
            inputs.add(new Column(input.getId(), input.getLabel(), text, typeRef));
        }

        List<Column> outputs = new ArrayList<Column>();
        for (Output output : table.getOutputs()) {
            // For an output the variable name is the stable identity, far more so than its generated id.
            outputs.add(new Column(output.getId(), output.getLabel(), output.getName(), output.getTypeRef()));
        }

        List<Rule> rules = new ArrayList<Rule>();
        for (org.camunda.bpm.model.dmn.instance.Rule rule : table.getRules()) {
            List<String> inputEntries = new ArrayList<String>();
            for (InputEntry entry : rule.getInputEntries()) {
                inputEntries.add(trimToNull(entry.getTextContent()));
            }
            List<String> outputEntries = new ArrayList<String>();
            for (OutputEntry entry : rule.getOutputEntries()) {
                outputEntries.add(trimToNull(entry.getTextContent()));
            }
            String description = rule.getDescription() == null
                    ? null
                    : trimToNull(rule.getDescription().getTextContent());
            rules.add(new Rule(rule.getId(), description, inputEntries, outputEntries));
        }

        return new Table(id, name, true, hitPolicyOf(table), inputs, outputs, rules, requiredDecisions);
    }

    private static String hitPolicyOf(DecisionTable table) {
        String policy = table.getHitPolicy() == null ? "UNIQUE" : table.getHitPolicy().toString();
        if (table.getAggregation() != null) {
            policy = policy + " " + table.getAggregation().toString();
        }
        return policy;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
