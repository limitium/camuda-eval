package limitium.art.camunda.evaluator.testspec;

import limitium.art.camunda.evaluator.diff.DecisionTableReader;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What a test author can choose from, derived from the DMN file.
 *
 * <p>Test columns are deliberately <em>not</em> the decision's declared inputs. Evaluating a decision
 * evaluates everything it requires, so what a test supplies is the variables at the edge of that
 * subgraph. For a chained decision the two differ: a decision declaring {@code genreRecommendation}
 * as an input is really asking for {@code preferredGenre}, because the decision producing
 * {@code genreRecommendation} will be evaluated on the way.
 *
 * <p>Computing that closure is what lets the editor offer the right names instead of the literal ones.
 */
public final class TestSpecHints {

    private TestSpecHints() {
    }

    public static final class DecisionHint {
        public final String decision;
        public final String name;
        /** Variables to supply when evaluating this decision, upstream inputs included. */
        public final List<String> suppliedVariables;
        /** Output variable names, the valid targets for an {@code =>} column. */
        public final List<String> outputs;
        /** Decisions evaluated on the way, nearest first. */
        public final List<String> requires;
        /**
         * Results of the decisions evaluated on the way, by output name, to the decision that gives
         * each. A test of a chain may expect these too, so every step of the chain is checked.
         */
        public final Map<String, String> chainOutputs;

        DecisionHint(String decision, String name, List<String> suppliedVariables,
                     List<String> outputs, List<String> requires) {
            this(decision, name, suppliedVariables, outputs, requires, new LinkedHashMap<String, String>());
        }

        DecisionHint(String decision, String name, List<String> suppliedVariables,
                     List<String> outputs, List<String> requires, Map<String, String> chainOutputs) {
            this.decision = decision;
            this.name = name;
            this.suppliedVariables = Collections.unmodifiableList(suppliedVariables);
            this.outputs = Collections.unmodifiableList(outputs);
            this.requires = Collections.unmodifiableList(requires);
            this.chainOutputs = Collections.unmodifiableMap(chainOutputs);
        }

        /** Whether {@code => name} is something a test of this decision can check. */
        public boolean canExpect(String name) {
            return outputs.contains(name) || chainOutputs.containsKey(name);
        }
    }

    public static List<DecisionHint> of(String dmnXml) {
        Map<String, DecisionTableReader.Table> tables = DecisionTableReader.read(dmnXml);
        List<DecisionHint> hints = new ArrayList<DecisionHint>();
        for (DecisionTableReader.Table table : tables.values()) {
            Set<String> supplied = new LinkedHashSet<String>();
            Set<String> required = new LinkedHashSet<String>();
            collect(table.decisionId, tables, supplied, required, new LinkedHashSet<String>());
            required.remove(table.decisionId);

            List<String> outputs = new ArrayList<String>();
            for (DecisionTableReader.Column column : table.outputs) {
                outputs.add(column.expression != null ? column.expression : column.id);
            }
            Map<String, String> chainOutputs = new LinkedHashMap<String, String>();
            for (String upstream : required) {
                DecisionTableReader.Table producer = tables.get(upstream);
                if (producer == null) {
                    continue;
                }
                for (DecisionTableReader.Column column : producer.outputs) {
                    String name = column.expression != null ? column.expression : column.id;
                    if (!outputs.contains(name) && !chainOutputs.containsKey(name)) {
                        chainOutputs.put(name, upstream);
                    }
                }
            }
            hints.add(new DecisionHint(table.decisionId, table.decisionName,
                    new ArrayList<String>(supplied), outputs, new ArrayList<String>(required), chainOutputs));
        }
        return hints;
    }

    /**
     * Walks the requirement graph, gathering every input and dropping any that a decision in the
     * subgraph produces for itself. Cycles are broken by the visited set rather than trusted not to
     * exist — a malformed file should not hang the editor.
     */
    private static void collect(String decisionId, Map<String, DecisionTableReader.Table> tables,
                                Set<String> supplied, Set<String> required, Set<String> visited) {
        if (!visited.add(decisionId)) {
            return;
        }
        DecisionTableReader.Table table = tables.get(decisionId);
        if (table == null) {
            return;
        }
        required.add(decisionId);
        for (DecisionTableReader.Column column : table.inputs) {
            if (column.expression != null) {
                supplied.add(column.expression);
            }
        }
        for (String upstream : table.requiredDecisions) {
            collect(upstream, tables, supplied, required, visited);
        }
        // Anything produced inside the subgraph is computed, not supplied.
        for (String id : required) {
            DecisionTableReader.Table produced = tables.get(id);
            if (produced == null) {
                continue;
            }
            for (DecisionTableReader.Column column : produced.outputs) {
                if (column.expression != null) {
                    supplied.remove(column.expression);
                }
            }
        }
    }

    /** A starter spec for a DMN file: a block per decision, correct columns, no rows. */
    public static TestSpec skeleton(String dmnXml) {
        List<DecisionTests> blocks = new ArrayList<DecisionTests>();
        for (DecisionHint hint : of(dmnXml)) {
            if (hint.outputs.isEmpty()) {
                continue;
            }
            blocks.add(new DecisionTests(hint.decision,
                    new ArrayList<String>(hint.suppliedVariables),
                    new ArrayList<String>(hint.outputs),
                    new ArrayList<TestCase>()));
        }
        return new TestSpec(blocks);
    }

    static Map<String, Object> unused() {
        return new LinkedHashMap<String, Object>();
    }
}
