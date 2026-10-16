package limitium.art.camunda.evaluator.testspec;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Checks a test spec against the DMN file it exercises.
 *
 * <p>A test spec is the decision table's <em>usage</em>, the way calling code is an API's usage. Rename
 * a variable in the table and the spec still parses perfectly while testing something that no longer
 * exists, so the mismatch has to be found by comparison rather than by the file being malformed.
 *
 * <p>Errors block a merge or a tag; warnings do not. The split matters: an input the tests do not
 * supply is legitimate when the rules wildcard it, and the coverage gate already fails if the new
 * column produced rules nothing exercises. Making that an error would be a second, louder complaint
 * about a problem that is already caught.
 *
 * <p>Nothing here rewrites the spec. Messages name the survivor instead. Where the previous version
 * of the DMN is available, a rename is identified exactly — the differ matches the column by id and
 * sees its expression change — which is the only way to connect {@code region} to {@code market}.
 * Without history the message falls back to the closest surviving name, which catches a typo but not
 * a genuine rename.
 */
public final class TestSpecValidator {

    private TestSpecValidator() {
    }

    public static class Problem {
        public enum Severity {
            /** Blocks a merge or a tag. */
            ERROR,
            /** Shown in the editor; blocks nothing. */
            WARNING
        }

        public final Severity severity;
        public final String decision;
        public final String column;
        public final String message;

        public Problem(Severity severity, String decision, String column, String message) {
            this.severity = severity;
            this.decision = decision;
            this.column = column;
            this.message = message;
        }
    }

    public static List<Problem> validate(String dmnXml, String yamlContent) {
        return validate(dmnXml, yamlContent, null);
    }

    /**
     * @param previousDmnXml the DMN as it was before the edit, when available. Used only to turn
     *                       "this column does not exist" into "this column was renamed to X".
     */
    public static List<Problem> validate(String dmnXml, String yamlContent, String previousDmnXml) {
        List<Problem> problems = new ArrayList<Problem>();

        TestSpec spec;
        try {
            spec = TestSpecReader.read(yamlContent);
        } catch (RuntimeException e) {
            problems.add(new Problem(Problem.Severity.ERROR, null, null, e.getMessage()));
            return problems;
        }

        List<TestSpecHints.DecisionHint> hints;
        try {
            hints = TestSpecHints.of(dmnXml);
        } catch (RuntimeException e) {
            problems.add(new Problem(Problem.Severity.ERROR, null, null,
                    "could not read the DMN file: " + e.getMessage()));
            return problems;
        }

        Renames renames = Renames.between(previousDmnXml, dmnXml);

        Set<String> tested = new LinkedHashSet<String>();
        for (DecisionTests block : spec.decisions) {
            tested.add(block.decision);
            TestSpecHints.DecisionHint hint = find(hints, block.decision);
            if (hint == null) {
                problems.add(new Problem(Problem.Severity.ERROR, block.decision, null,
                        "decision '" + block.decision + "' is not in the DMN file"
                                + suggestion(block.decision, decisionIds(hints))));
                continue;
            }
            ChainScope scope = ChainScope.of(dmnXml, hint.decision, block.inputColumns);
            checkBlock(block, hint, scope, renames, problems);
        }

        for (TestSpecHints.DecisionHint hint : hints) {
            if (!tested.contains(hint.decision) && !hint.outputs.isEmpty()) {
                problems.add(new Problem(Problem.Severity.WARNING, hint.decision, null,
                        "decision '" + hint.decision + "' has no tests"));
            }
        }
        return problems;
    }

    /**
     * Judges a block by the scope its columns give it: supplying what a required table gives cuts the
     * chain there, so a table can be tested alone, with some of its chain, or with all of it.
     */
    private static void checkBlock(DecisionTests block, TestSpecHints.DecisionHint hint, ChainScope scope,
                                   Renames renames, List<Problem> problems) {
        List<String> anyInput = new ArrayList<String>(scope.anyInput);
        String table = hint.name != null && !hint.name.isEmpty() ? hint.name : block.decision;
        // Values a condition checks that no column supplies yet: the likely new name of a renamed one.
        List<String> unsupplied = new ArrayList<String>();
        for (String variable : scope.needed) {
            if (!block.inputColumns.contains(variable)) {
                unsupplied.add(variable);
            }
        }
        for (String column : block.inputColumns) {
            if (!scope.anyInput.contains(column)) {
                String explained = renames.explain(column, anyInput);
                if (explained.isEmpty() && unsupplied.size() == 1) {
                    explained = " — did you mean '" + unsupplied.get(0) + "'?";
                }
                problems.add(new Problem(Problem.Severity.ERROR, block.decision, column,
                        "no condition of " + table + (hint.requires.isEmpty() ? "" : " or of the tables it uses")
                                + " checks '" + column + "'; they check " + listed(anyInput) + explained));
            }
        }
        for (String output : block.outputColumns) {
            if (scope.outputs.containsKey(output)) {
                continue;
            }
            String producer = hint.chainOutputs.get(output);
            if (producer != null && scope.cut.contains(producer)) {
                problems.add(new Problem(Problem.Severity.ERROR, block.decision, output,
                        "'" + TestSpecReader.EXPECT_PREFIX + " " + output + "' expects what '" + producer
                                + "' gives, but these cases supply that value, so '" + producer + "' does not run"));
            } else {
                problems.add(new Problem(Problem.Severity.ERROR, block.decision, output,
                        "'" + TestSpecReader.EXPECT_PREFIX + " " + output + "' expects a result " + table
                                + (hint.requires.isEmpty() ? "" : " and the tables it uses")
                                + " do not give; they give " + listed(new ArrayList<String>(scope.outputs.keySet()))
                                + renames.explain(output, hint.outputs)));
            }
        }
        for (String variable : scope.needed) {
            if (!block.inputColumns.contains(variable)) {
                problems.add(new Problem(Problem.Severity.WARNING, block.decision, variable,
                        "'" + variable + "' has no test column, so it evaluates as null"));
            }
        }
        if (block.cases.isEmpty()) {
            problems.add(new Problem(Problem.Severity.WARNING, block.decision, null,
                    "decision '" + block.decision + "' has no test rows"));
        }
    }

    public static boolean hasErrors(List<Problem> problems) {
        for (Problem problem : problems) {
            if (problem.severity == Problem.Severity.ERROR) {
                return true;
            }
        }
        return false;
    }

    // --- what actually happened to the name ----------------------------------------------------

    /**
     * Old name to new name, read from a diff of the two DMN versions. A column matched by id whose
     * expression changed is a rename, which no amount of string similarity would reveal when the new
     * name is an unrelated word.
     */
    static final class Renames {
        private final java.util.Map<String, String> byOldName;

        private Renames(java.util.Map<String, String> byOldName) {
            this.byOldName = byOldName;
        }

        static Renames between(String previousDmnXml, String dmnXml) {
            java.util.Map<String, String> map = new java.util.LinkedHashMap<String, String>();
            if (previousDmnXml == null) {
                return new Renames(map);
            }
            try {
                limitium.art.camunda.evaluator.diff.DmnDiff diff =
                        limitium.art.camunda.evaluator.diff.DmnDiffer.diff(previousDmnXml, dmnXml);
                for (limitium.art.camunda.evaluator.diff.DecisionDiff decision : diff.decisions) {
                    index(map, decision.inputs);
                    index(map, decision.outputs);
                }
            } catch (RuntimeException e) {
                return new Renames(new java.util.LinkedHashMap<String, String>());
            }
            return new Renames(map);
        }

        private static void index(java.util.Map<String, String> map,
                                  List<limitium.art.camunda.evaluator.diff.ColumnDiff> columns) {
            for (limitium.art.camunda.evaluator.diff.ColumnDiff column : columns) {
                if (column.leftExpression == null || column.rightExpression == null) {
                    continue;
                }
                if (!column.leftExpression.equals(column.rightExpression)) {
                    map.put(column.leftExpression, column.rightExpression);
                }
            }
        }

        String explain(String missing, List<String> candidates) {
            String renamed = byOldName.get(missing);
            if (renamed != null) {
                return " — it was renamed to '" + renamed + "'";
            }
            return suggestion(missing, candidates);
        }
    }

    private static String listed(List<String> names) {
        if (names.isEmpty()) {
            return "nothing";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < names.size(); i++) {
            sb.append(i == 0 ? "" : i == names.size() - 1 ? " and " : ", ").append('\'').append(names.get(i)).append('\'');
        }
        return sb.toString();
    }

    // --- suggesting the survivor -------------------------------------------------------------

    /**
     * Names the closest surviving candidate, which for a rename is the new name. Only offered when
     * the names are close enough that a guess is worth making — an unrelated suggestion is worse
     * than none, because it sends the reader looking in the wrong place.
     */
    static String suggestion(String missing, List<String> candidates) {
        String best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (String candidate : candidates) {
            int distance = editDistance(missing.toLowerCase(), candidate.toLowerCase());
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }
        if (best == null) {
            return "";
        }
        int threshold = Math.max(2, missing.length() / 2);
        return bestDistance <= threshold ? " — did you mean '" + best + "'?" : "";
    }

    private static int editDistance(String a, String b) {
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }

    private static TestSpecHints.DecisionHint find(List<TestSpecHints.DecisionHint> hints, String id) {
        for (TestSpecHints.DecisionHint hint : hints) {
            if (hint.decision.equals(id)) {
                return hint;
            }
        }
        return null;
    }

    private static List<String> decisionIds(List<TestSpecHints.DecisionHint> hints) {
        List<String> ids = new ArrayList<String>();
        for (TestSpecHints.DecisionHint hint : hints) {
            ids.add(hint.decision);
        }
        return Collections.unmodifiableList(ids);
    }
}
