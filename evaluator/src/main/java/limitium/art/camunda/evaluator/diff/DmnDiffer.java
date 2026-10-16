package limitium.art.camunda.evaluator.diff;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Semantic diff of two DMN documents at decision-table rule level.
 *
 * <p>Rules are paired in four passes of decreasing confidence, because dmn-js regenerates rule ids
 * on edit and matching on id alone would render an unchanged table as wholesale add/remove. The pass
 * that produced each pairing is reported as {@link RuleDiff#matchedBy} so a heuristic match stays
 * visibly a heuristic.
 *
 * <p>Cells on both sides are aligned to a single merged column list, which is what lets a consumer
 * render the result as a plain grid instead of re-deriving the alignment itself.
 */
public final class DmnDiffer {

    /** A cell that matches anything. Empty, "-" and quoted "-" all mean this. */
    public static final String WILDCARD = "-";

    /**
     * Below this fraction of equal cells, two rules are treated as unrelated rather than changed.
     *
     * <p>Half, not more: a table with one input and one output is the common case, and editing its
     * input leaves exactly half the cells equal. A stricter bar would make "this rule was edited"
     * undetectable in precisely the tables people edit most, turning every threshold tweak into a
     * remove plus an add.
     */
    static final double SIMILARITY_THRESHOLD = 0.5;

    private static final String KEY_SEPARATOR = "\u0000";

    private DmnDiffer() {
    }

    public static DmnDiff diff(String leftXml, String rightXml) {
        return diff(leftXml, rightXml, "left", "right");
    }

    public static DmnDiff diff(String leftXml, String rightXml, String leftLabel, String rightLabel) {
        Map<String, DecisionTableReader.Table> left = DecisionTableReader.read(leftXml);
        Map<String, DecisionTableReader.Table> right = DecisionTableReader.read(rightXml);

        Map<String, String> rightIdByName = new LinkedHashMap<String, String>();
        for (DecisionTableReader.Table table : right.values()) {
            if (table.decisionName != null) {
                rightIdByName.put(table.decisionName, table.decisionId);
            }
        }

        List<DecisionDiff> decisions = new ArrayList<DecisionDiff>();
        Set<String> consumedRight = new LinkedHashSet<String>();
        Summary summary = new Summary();

        for (DecisionTableReader.Table leftTable : left.values()) {
            DecisionTableReader.Table rightTable = right.get(leftTable.decisionId);
            if (rightTable == null && leftTable.decisionName != null) {
                String byName = rightIdByName.get(leftTable.decisionName);
                if (byName != null && !consumedRight.contains(byName)) {
                    rightTable = right.get(byName);
                }
            }
            if (rightTable == null) {
                decisions.add(removedDecision(leftTable));
                summary.decisionsRemoved++;
            } else {
                consumedRight.add(rightTable.decisionId);
                DecisionDiff diff = diffDecision(leftTable, rightTable, summary);
                decisions.add(diff);
                if (diff.change == ChangeType.CHANGED) {
                    summary.decisionsChanged++;
                }
            }
        }

        for (DecisionTableReader.Table rightTable : right.values()) {
            if (!consumedRight.contains(rightTable.decisionId)) {
                decisions.add(addedDecision(rightTable));
                summary.decisionsAdded++;
            }
        }

        boolean identical = summary.isEmpty();
        return new DmnDiff(leftLabel, rightLabel, identical, summary.toDiffSummary(), decisions);
    }

    // --- decisions --------------------------------------------------------------------------

    private static DecisionDiff diffDecision(DecisionTableReader.Table left,
                                             DecisionTableReader.Table right,
                                             Summary summary) {
        boolean nonTabular = !left.tabular || !right.tabular;
        boolean hitPolicyChanged = !equalsNormalized(left.hitPolicy, right.hitPolicy);
        if (hitPolicyChanged) {
            summary.hitPolicyChanged = true;
        }

        Alignment inputs = alignColumns(left.inputs, right.inputs, false);
        Alignment outputs = alignColumns(left.outputs, right.outputs, true);
        countColumns(inputs.columns, summary);
        countColumns(outputs.columns, summary);

        List<RuleDiff> rules = diffRules(left.rules, right.rules, inputs, outputs, summary);

        boolean changed = hitPolicyChanged
                || !equalsNormalized(left.decisionName, right.decisionName)
                || hasChange(inputs.columns)
                || hasChange(outputs.columns);
        if (!changed) {
            for (RuleDiff rule : rules) {
                if (rule.change != ChangeType.UNCHANGED) {
                    changed = true;
                    break;
                }
            }
        }

        return new DecisionDiff(left.decisionId, left.decisionName, right.decisionName,
                changed ? ChangeType.CHANGED : ChangeType.UNCHANGED, nonTabular,
                left.hitPolicy, right.hitPolicy, inputs.columns, outputs.columns, rules);
    }

    private static DecisionDiff removedDecision(DecisionTableReader.Table table) {
        Alignment inputs = alignColumns(table.inputs, emptyColumns(), false);
        Alignment outputs = alignColumns(table.outputs, emptyColumns(), true);
        List<RuleDiff> rules = new ArrayList<RuleDiff>();
        for (int i = 0; i < table.rules.size(); i++) {
            rules.add(buildRuleDiff(table.rules.get(i), null, i, -1, inputs, outputs, ChangeType.REMOVED, null));
        }
        return new DecisionDiff(table.decisionId, table.decisionName, null, ChangeType.REMOVED,
                !table.tabular, table.hitPolicy, null, inputs.columns, outputs.columns, rules);
    }

    private static DecisionDiff addedDecision(DecisionTableReader.Table table) {
        Alignment inputs = alignColumns(emptyColumns(), table.inputs, false);
        Alignment outputs = alignColumns(emptyColumns(), table.outputs, true);
        List<RuleDiff> rules = new ArrayList<RuleDiff>();
        for (int i = 0; i < table.rules.size(); i++) {
            rules.add(buildRuleDiff(null, table.rules.get(i), -1, i, inputs, outputs, ChangeType.ADDED, null));
        }
        return new DecisionDiff(table.decisionId, null, table.decisionName, ChangeType.ADDED,
                !table.tabular, null, table.hitPolicy, inputs.columns, outputs.columns, rules);
    }

    private static List<DecisionTableReader.Column> emptyColumns() {
        return Collections.emptyList();
    }

    // --- columns ----------------------------------------------------------------------------

    /** The merged column list plus, for each side, where that side's column landed in it. */
    static final class Alignment {
        final List<ColumnDiff> columns;
        final int[] leftToMerged;
        final int[] rightToMerged;

        Alignment(List<ColumnDiff> columns, int[] leftToMerged, int[] rightToMerged) {
            this.columns = columns;
            this.leftToMerged = leftToMerged;
            this.rightToMerged = rightToMerged;
        }
    }

    private static Alignment alignColumns(List<DecisionTableReader.Column> left,
                                          List<DecisionTableReader.Column> right,
                                          boolean output) {
        int[] leftMatch = matchColumns(left, right, output);
        int[] rightMatch = invert(leftMatch, right.size());
        List<int[]> order = mergeOrder(leftMatch, rightMatch, left.size(), right.size());

        List<ColumnDiff> columns = new ArrayList<ColumnDiff>();
        int[] leftToMerged = filled(left.size());
        int[] rightToMerged = filled(right.size());

        for (int position = 0; position < order.size(); position++) {
            int li = order.get(position)[0];
            int ri = order.get(position)[1];
            DecisionTableReader.Column l = li >= 0 ? left.get(li) : null;
            DecisionTableReader.Column r = ri >= 0 ? right.get(ri) : null;
            if (li >= 0) {
                leftToMerged[li] = position;
            }
            if (ri >= 0) {
                rightToMerged[ri] = position;
            }

            ChangeType change;
            if (l == null) {
                change = ChangeType.ADDED;
            } else if (r == null) {
                change = ChangeType.REMOVED;
            } else if (!equalsNormalized(l.label, r.label)
                    || !equalsNormalized(l.expression, r.expression)
                    || !equalsNormalized(l.typeRef, r.typeRef)) {
                change = ChangeType.CHANGED;
            } else {
                change = ChangeType.UNCHANGED;
            }

            String id = l != null && l.id != null ? l.id : (r != null ? r.id : null);
            columns.add(new ColumnDiff(id, change, li, ri,
                    l == null ? null : l.label, r == null ? null : r.label,
                    l == null ? null : l.expression, r == null ? null : r.expression,
                    l == null ? null : l.typeRef, r == null ? null : r.typeRef));
        }

        return new Alignment(columns, leftToMerged, rightToMerged);
    }

    /**
     * Matches columns by id, then by the semantic key, then by label. For an output the semantic key
     * is the variable name, which survives edits far better than the generated id.
     */
    private static int[] matchColumns(List<DecisionTableReader.Column> left,
                                      List<DecisionTableReader.Column> right,
                                      boolean output) {
        int[] leftMatch = filled(left.size());
        boolean[] rightTaken = new boolean[right.size()];

        for (int pass = 0; pass < 3; pass++) {
            Map<String, List<Integer>> rightByKey = new LinkedHashMap<String, List<Integer>>();
            for (int j = 0; j < right.size(); j++) {
                if (rightTaken[j]) {
                    continue;
                }
                String key = columnKey(right.get(j), pass, output);
                if (key == null) {
                    continue;
                }
                List<Integer> bucket = rightByKey.get(key);
                if (bucket == null) {
                    bucket = new ArrayList<Integer>();
                    rightByKey.put(key, bucket);
                }
                bucket.add(Integer.valueOf(j));
            }
            for (int i = 0; i < left.size(); i++) {
                if (leftMatch[i] >= 0) {
                    continue;
                }
                String key = columnKey(left.get(i), pass, output);
                if (key == null) {
                    continue;
                }
                List<Integer> bucket = rightByKey.get(key);
                if (bucket == null || bucket.isEmpty()) {
                    continue;
                }
                int j = bucket.remove(0).intValue();
                leftMatch[i] = j;
                rightTaken[j] = true;
            }
        }
        return leftMatch;
    }

    private static String columnKey(DecisionTableReader.Column column, int pass, boolean output) {
        if (pass == 0) {
            return column.id;
        }
        if (pass == 1) {
            if (column.expression == null) {
                return null;
            }
            return output
                    ? normalize(column.expression)
                    : normalize(column.expression) + KEY_SEPARATOR + String.valueOf(column.typeRef);
        }
        return column.label;
    }

    private static void countColumns(List<ColumnDiff> columns, Summary summary) {
        for (ColumnDiff column : columns) {
            if (column.change == ChangeType.ADDED) {
                summary.columnsAdded++;
            } else if (column.change == ChangeType.REMOVED) {
                summary.columnsRemoved++;
            } else if (column.change == ChangeType.CHANGED) {
                summary.columnsChanged++;
            }
        }
    }

    private static boolean hasChange(List<ColumnDiff> columns) {
        for (ColumnDiff column : columns) {
            if (column.change != ChangeType.UNCHANGED) {
                return true;
            }
        }
        return false;
    }

    // --- rules ------------------------------------------------------------------------------

    private static List<RuleDiff> diffRules(List<DecisionTableReader.Rule> left,
                                            List<DecisionTableReader.Rule> right,
                                            Alignment inputs, Alignment outputs,
                                            Summary summary) {
        int[] leftMatch = filled(left.size());
        String[] matchedBy = new String[left.size()];
        boolean[] rightTaken = new boolean[right.size()];

        matchRulesById(left, right, leftMatch, matchedBy, rightTaken);
        matchRulesByInputKey(left, right, inputs, leftMatch, matchedBy, rightTaken);
        matchRulesByLcs(left, right, inputs, outputs, leftMatch, matchedBy, rightTaken);

        int[] rightMatch = invert(leftMatch, right.size());
        List<int[]> order = mergeOrder(leftMatch, rightMatch, left.size(), right.size());

        List<RuleDiff> rules = new ArrayList<RuleDiff>();
        for (int position = 0; position < order.size(); position++) {
            int li = order.get(position)[0];
            int ri = order.get(position)[1];
            DecisionTableReader.Rule l = li >= 0 ? left.get(li) : null;
            DecisionTableReader.Rule r = ri >= 0 ? right.get(ri) : null;

            ChangeType change;
            if (l == null) {
                change = ChangeType.ADDED;
                summary.rulesAdded++;
            } else if (r == null) {
                change = ChangeType.REMOVED;
                summary.rulesRemoved++;
            } else {
                change = classifyPair(l, r, li, ri, inputs, outputs);
                if (change == ChangeType.CHANGED) {
                    summary.rulesChanged++;
                } else if (change == ChangeType.MOVED) {
                    summary.rulesMoved++;
                }
            }

            rules.add(buildRuleDiff(l, r, li, ri, inputs, outputs, change,
                    li >= 0 ? matchedBy[li] : null));
        }
        return rules;
    }

    private static void matchRulesById(List<DecisionTableReader.Rule> left,
                                       List<DecisionTableReader.Rule> right,
                                       int[] leftMatch, String[] matchedBy, boolean[] rightTaken) {
        Map<String, Integer> rightById = new LinkedHashMap<String, Integer>();
        for (int j = 0; j < right.size(); j++) {
            String id = right.get(j).id;
            if (id != null && !rightById.containsKey(id)) {
                rightById.put(id, Integer.valueOf(j));
            }
        }
        for (int i = 0; i < left.size(); i++) {
            String id = left.get(i).id;
            if (id == null) {
                continue;
            }
            Integer j = rightById.get(id);
            if (j != null && !rightTaken[j.intValue()]) {
                leftMatch[i] = j.intValue();
                matchedBy[i] = "id";
                rightTaken[j.intValue()] = true;
            }
        }
    }

    /**
     * In a decision table the input tuple is the rule's identity and the outputs are its payload,
     * so this is the right fallback when ids have been regenerated.
     */
    private static void matchRulesByInputKey(List<DecisionTableReader.Rule> left,
                                             List<DecisionTableReader.Rule> right,
                                             Alignment inputs,
                                             int[] leftMatch, String[] matchedBy, boolean[] rightTaken) {
        Map<String, List<Integer>> rightByKey = new LinkedHashMap<String, List<Integer>>();
        for (int j = 0; j < right.size(); j++) {
            if (rightTaken[j]) {
                continue;
            }
            String key = inputKey(right.get(j), inputs, false);
            List<Integer> bucket = rightByKey.get(key);
            if (bucket == null) {
                bucket = new ArrayList<Integer>();
                rightByKey.put(key, bucket);
            }
            bucket.add(Integer.valueOf(j));
        }
        for (int i = 0; i < left.size(); i++) {
            if (leftMatch[i] >= 0) {
                continue;
            }
            List<Integer> bucket = rightByKey.get(inputKey(left.get(i), inputs, true));
            if (bucket == null || bucket.isEmpty()) {
                continue;
            }
            int j = bucket.remove(0).intValue();
            leftMatch[i] = j;
            matchedBy[i] = "inputs";
            rightTaken[j] = true;
        }
    }

    /**
     * Longest common subsequence over whatever is still unpaired, so a rule whose threshold was
     * edited reads as one changed rule rather than a remove plus an add.
     */
    private static void matchRulesByLcs(List<DecisionTableReader.Rule> left,
                                        List<DecisionTableReader.Rule> right,
                                        Alignment inputs, Alignment outputs,
                                        int[] leftMatch, String[] matchedBy, boolean[] rightTaken) {
        List<Integer> leftOpen = new ArrayList<Integer>();
        for (int i = 0; i < left.size(); i++) {
            if (leftMatch[i] < 0) {
                leftOpen.add(Integer.valueOf(i));
            }
        }
        List<Integer> rightOpen = new ArrayList<Integer>();
        for (int j = 0; j < right.size(); j++) {
            if (!rightTaken[j]) {
                rightOpen.add(Integer.valueOf(j));
            }
        }
        if (leftOpen.isEmpty() || rightOpen.isEmpty()) {
            return;
        }

        int n = leftOpen.size();
        int m = rightOpen.size();
        int[][] dp = new int[n + 1][m + 1];
        boolean[][] pairable = new boolean[n][m];
        for (int a = n - 1; a >= 0; a--) {
            for (int b = m - 1; b >= 0; b--) {
                double sim = similarity(left.get(leftOpen.get(a).intValue()),
                        right.get(rightOpen.get(b).intValue()), inputs, outputs);
                pairable[a][b] = sim >= SIMILARITY_THRESHOLD;
                if (pairable[a][b]) {
                    dp[a][b] = dp[a + 1][b + 1] + 1;
                } else {
                    dp[a][b] = Math.max(dp[a + 1][b], dp[a][b + 1]);
                }
            }
        }

        int a = 0;
        int b = 0;
        while (a < n && b < m) {
            if (pairable[a][b] && dp[a][b] == dp[a + 1][b + 1] + 1) {
                int i = leftOpen.get(a).intValue();
                int j = rightOpen.get(b).intValue();
                leftMatch[i] = j;
                matchedBy[i] = "position";
                rightTaken[j] = true;
                a++;
                b++;
            } else if (dp[a + 1][b] >= dp[a][b + 1]) {
                a++;
            } else {
                b++;
            }
        }
    }

    private static double similarity(DecisionTableReader.Rule left, DecisionTableReader.Rule right,
                                     Alignment inputs, Alignment outputs) {
        int total = inputs.columns.size() + outputs.columns.size();
        if (total == 0) {
            return 1.0;
        }
        int equal = 0;
        for (int m = 0; m < inputs.columns.size(); m++) {
            if (equalsNormalized(cell(left, inputs, m, true, true), cell(right, inputs, m, false, true))) {
                equal++;
            }
        }
        for (int m = 0; m < outputs.columns.size(); m++) {
            if (equalsNormalized(cell(left, outputs, m, true, false), cell(right, outputs, m, false, false))) {
                equal++;
            }
        }
        return (double) equal / (double) total;
    }

    private static ChangeType classifyPair(DecisionTableReader.Rule left, DecisionTableReader.Rule right,
                                           int leftIndex, int rightIndex,
                                           Alignment inputs, Alignment outputs) {
        for (int m = 0; m < inputs.columns.size(); m++) {
            if (!equalsNormalized(cell(left, inputs, m, true, true), cell(right, inputs, m, false, true))) {
                return ChangeType.CHANGED;
            }
        }
        for (int m = 0; m < outputs.columns.size(); m++) {
            if (!equalsNormalized(cell(left, outputs, m, true, false), cell(right, outputs, m, false, false))) {
                return ChangeType.CHANGED;
            }
        }
        if (!equalsNormalized(left.description, right.description)) {
            return ChangeType.CHANGED;
        }
        // Order is semantic under FIRST and PRIORITY, so a pure move is still worth reporting.
        return leftIndex == rightIndex ? ChangeType.UNCHANGED : ChangeType.MOVED;
    }

    private static RuleDiff buildRuleDiff(DecisionTableReader.Rule left, DecisionTableReader.Rule right,
                                          int leftIndex, int rightIndex,
                                          Alignment inputs, Alignment outputs,
                                          ChangeType change, String matchedBy) {
        List<CellDiff> inputCells = buildCells(left, right, inputs, true);
        List<CellDiff> outputCells = buildCells(left, right, outputs, false);
        return new RuleDiff(left == null ? null : left.id, right == null ? null : right.id,
                change, leftIndex, rightIndex,
                left == null ? null : left.description, right == null ? null : right.description,
                inputCells, outputCells, matchedBy);
    }

    private static List<CellDiff> buildCells(DecisionTableReader.Rule left, DecisionTableReader.Rule right,
                                             Alignment alignment, boolean input) {
        List<CellDiff> cells = new ArrayList<CellDiff>();
        for (int m = 0; m < alignment.columns.size(); m++) {
            ColumnDiff column = alignment.columns.get(m);
            String l = left == null ? null : cell(left, alignment, m, true, input);
            String r = right == null ? null : cell(right, alignment, m, false, input);
            ChangeType change;
            if (left == null) {
                change = ChangeType.ADDED;
            } else if (right == null) {
                change = ChangeType.REMOVED;
            } else if (equalsNormalized(l, r)) {
                change = ChangeType.UNCHANGED;
            } else {
                change = ChangeType.CHANGED;
            }
            cells.add(new CellDiff(column.id, change, l, r));
        }
        return cells;
    }

    /** Reads the entry a rule holds for merged column {@code mergedIndex}, or null if that side has no such column. */
    private static String cell(DecisionTableReader.Rule rule, Alignment alignment,
                               int mergedIndex, boolean leftSide, boolean input) {
        ColumnDiff column = alignment.columns.get(mergedIndex);
        int index = leftSide ? column.leftIndex : column.rightIndex;
        if (index < 0) {
            return null;
        }
        List<String> entries = input ? rule.inputEntries : rule.outputEntries;
        return index < entries.size() ? entries.get(index) : null;
    }

    private static String inputKey(DecisionTableReader.Rule rule, Alignment inputs, boolean leftSide) {
        StringBuilder key = new StringBuilder();
        for (int m = 0; m < inputs.columns.size(); m++) {
            if (m > 0) {
                key.append(KEY_SEPARATOR);
            }
            key.append(normalize(cell(rule, inputs, m, leftSide, true)));
        }
        return key.toString();
    }

    // --- shared helpers ---------------------------------------------------------------------

    /**
     * Folds a cell to its meaning: empty, "-" and quoted "-" are all the wildcard, whitespace runs
     * collapse, and a trailing semicolon is noise. Without this, cosmetic edits read as changes.
     */
    static String normalize(String entry) {
        if (entry == null) {
            return WILDCARD;
        }
        String value = entry.trim().replaceAll("\\s+", " ");
        if (value.endsWith(";")) {
            value = value.substring(0, value.length() - 1).trim();
        }
        if (value.isEmpty() || WILDCARD.equals(value) || "\"-\"".equals(value)) {
            return WILDCARD;
        }
        return value;
    }

    private static boolean equalsNormalized(String left, String right) {
        return normalize(left).equals(normalize(right));
    }

    private static int[] filled(int size) {
        int[] array = new int[size];
        Arrays.fill(array, -1);
        return array;
    }

    private static int[] invert(int[] leftMatch, int rightSize) {
        int[] rightMatch = filled(rightSize);
        for (int i = 0; i < leftMatch.length; i++) {
            if (leftMatch[i] >= 0) {
                rightMatch[leftMatch[i]] = i;
            }
        }
        return rightMatch;
    }

    /**
     * Produces the display order: left order preserved, with right-only entries inserted at the
     * position they occupy on the right rather than dumped at the end.
     */
    private static List<int[]> mergeOrder(int[] leftMatch, int[] rightMatch, int leftSize, int rightSize) {
        List<int[]> order = new ArrayList<int[]>();
        boolean[] rightEmitted = new boolean[rightSize];
        int nextRight = 0;

        for (int i = 0; i < leftSize; i++) {
            int j = leftMatch[i];
            if (j >= 0) {
                while (nextRight < j) {
                    if (rightMatch[nextRight] < 0 && !rightEmitted[nextRight]) {
                        order.add(new int[]{-1, nextRight});
                        rightEmitted[nextRight] = true;
                    }
                    nextRight++;
                }
                order.add(new int[]{i, j});
                rightEmitted[j] = true;
                if (j + 1 > nextRight) {
                    nextRight = j + 1;
                }
            } else {
                order.add(new int[]{i, -1});
            }
        }

        for (int j = 0; j < rightSize; j++) {
            if (!rightEmitted[j] && rightMatch[j] < 0) {
                order.add(new int[]{-1, j});
                rightEmitted[j] = true;
            }
        }
        return order;
    }

    /** Mutable accumulator; converted to the immutable {@link DiffSummary} at the end. */
    private static final class Summary {
        int decisionsAdded;
        int decisionsRemoved;
        int decisionsChanged;
        int rulesAdded;
        int rulesRemoved;
        int rulesChanged;
        int rulesMoved;
        int columnsAdded;
        int columnsRemoved;
        int columnsChanged;
        boolean hitPolicyChanged;

        boolean isEmpty() {
            return decisionsAdded == 0 && decisionsRemoved == 0 && decisionsChanged == 0
                    && rulesAdded == 0 && rulesRemoved == 0 && rulesChanged == 0 && rulesMoved == 0
                    && columnsAdded == 0 && columnsRemoved == 0 && columnsChanged == 0
                    && !hitPolicyChanged;
        }

        DiffSummary toDiffSummary() {
            return new DiffSummary(decisionsAdded, decisionsRemoved, decisionsChanged,
                    rulesAdded, rulesRemoved, rulesChanged, rulesMoved,
                    columnsAdded, columnsRemoved, columnsChanged, hitPolicyChanged);
        }
    }
}
