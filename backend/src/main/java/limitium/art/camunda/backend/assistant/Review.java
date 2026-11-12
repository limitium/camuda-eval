package limitium.art.camunda.backend.assistant;

import limitium.art.camunda.backend.config.GitProperties;
import limitium.art.camunda.backend.git.GitRuleStore;
import limitium.art.camunda.backend.git.Workspace;
import limitium.art.camunda.backend.git.WorkspaceId;
import limitium.art.camunda.backend.model.DecisionCoverage;
import limitium.art.camunda.backend.model.TestResult;
import limitium.art.camunda.backend.model.TestStatistics;
import limitium.art.camunda.backend.service.TestExecutionService;
import limitium.art.camunda.evaluator.diff.CellDiff;
import limitium.art.camunda.evaluator.diff.ChangeType;
import limitium.art.camunda.evaluator.diff.ColumnDiff;
import limitium.art.camunda.evaluator.diff.DecisionDiff;
import limitium.art.camunda.evaluator.diff.DmnDiff;
import limitium.art.camunda.evaluator.diff.DmnDiffer;
import limitium.art.camunda.evaluator.diff.RuleDiff;
import limitium.art.camunda.evaluator.testspec.DecisionTests;
import limitium.art.camunda.evaluator.testspec.TestCase;
import limitium.art.camunda.evaluator.testspec.TestSpec;
import limitium.art.camunda.evaluator.testspec.TestSpecReader;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * A work space's changes, reviewed by the app: what changed rule by rule, and what the engine says
 * about it — test cases that fail, changed rules no test case reaches, changed rules that never apply
 * or overlap another. Uncommitted changes when there are some; otherwise everything its commits would
 * merge. These are facts the model then sums up; it does not find them itself.
 */
@Component
public class Review {

    private static final String EMPTY = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<definitions xmlns=\"https://www.omg.org/spec/DMN/20191111/MODEL/\" id=\"empty\" name=\"empty\" namespace=\"http://camunda.org/schema/1.0/dmn\"/>";

    /** Something the review found: "problem" stops a merge or is wrong; "warning" should be looked at. */
    public static class Finding {
        public final String severity;
        public final String domain;
        public final String text;

        Finding(String severity, String domain, String text) {
            this.severity = severity;
            this.domain = domain;
            this.text = text;
        }
    }

    public static class Result {
        /** "uncommitted" or "commits" (what Merge would take). */
        public final String scope;
        public final String scopeText;
        public final List<String> changes = new ArrayList<>();
        public final List<Finding> findings = new ArrayList<>();
        public int testsPassed;
        public int testsTotal;

        Result(String scope, String scopeText) {
            this.scope = scope;
            this.scopeText = scopeText;
        }
    }

    public static class Unavailable extends RuntimeException {
        public Unavailable(String message) {
            super(message);
        }
    }

    private final GitRuleStore store;
    private final GitProperties git;
    private final TestExecutionService tests;

    public Review(GitRuleStore store, GitProperties git, TestExecutionService tests) {
        this.store = store;
        this.git = git;
        this.tests = tests;
    }

    /** The review of a work space by its branch name; with {@code uncommittedOnly}, only what is not committed. */
    public Result of(String branch, boolean uncommittedOnly) throws Exception {
        WorkspaceId id = WorkspaceId.parse(branch == null ? "" : branch);
        if (!git.isEnabled() || id == null) {
            throw new Unavailable("there is no work space to review here");
        }
        Workspace space = null;
        for (Workspace w : store.workspaces()) {
            if (w.id.equals(id.toString())) {
                space = w;
            }
        }
        if (space == null) {
            throw new Unavailable("there is no work space " + branch);
        }

        Map<String, String> uncommitted = store.changes(id);
        Map<String, byte[]> after = new LinkedHashMap<>();
        Map<String, byte[]> before = new LinkedHashMap<>();
        Set<String> paths = new TreeSet<>();
        Result result;
        if (!uncommitted.isEmpty()) {
            result = new Result("uncommitted", "the changes not committed yet");
            Map<String, byte[]> working = store.workingFiles(id);
            for (String path : uncommitted.keySet()) {
                paths.add(path);
                if (working.containsKey(path)) {
                    after.put(path, working.get(path));
                }
                byte[] committed = store.readAt(id.branch(), path);
                if (committed != null) {
                    before.put(path, committed);
                }
            }
            // A changed table is run with its test cases, changed or not, and the other way round.
            for (String path : new ArrayList<>(paths)) {
                String other = path.startsWith("dmn/") ? "yaml/" + base(path) + ".yaml" : "dmn/" + base(path) + ".dmn";
                if (!after.containsKey(other) && working.containsKey(other)) {
                    after.put(other, working.get(other));
                    byte[] committed = store.readAt(id.branch(), other);
                    if (committed != null) {
                        before.put(other, committed);
                    }
                }
            }
        } else if (uncommittedOnly) {
            return new Result("uncommitted", "the changes not committed yet");
        } else {
            String head = space.head;
            String target = store.resolve(space.targetLine);
            String from = target == null || head == null ? null : store.mergeBase(head, target);
            if (head == null || from == null || from.equals(head)) {
                return new Result("commits", "its commits not merged yet");
            }
            result = new Result("commits", "its commits not merged into " + space.targetLine + " yet");
            for (String dir : new String[]{"dmn", "yaml"}) {
                Set<String> names = new LinkedHashSet<>(store.filesAt(head, dir));
                names.addAll(store.filesAt(from, dir));
                for (String name : names) {
                    String path = dir + "/" + name;
                    byte[] now = store.readAt(head, path);
                    byte[] then = store.readAt(from, path);
                    if (now != null) {
                        after.put(path, now);
                    }
                    if (then != null) {
                        before.put(path, then);
                    }
                    if (!java.util.Arrays.equals(now, then)) {
                        paths.add(path);
                    }
                }
            }
        }

        Set<String> domains = new TreeSet<>();
        for (String path : paths) {
            if (!path.endsWith(".gitkeep")) {
                domains.add(base(path));
            }
        }
        for (String domain : domains) {
            domain(domain, before, after, result);
        }
        return result;
    }

    private void domain(String domain, Map<String, byte[]> before, Map<String, byte[]> after, Result result) {
        String xmlBefore = text(before.get("dmn/" + domain + ".dmn"));
        String xmlAfter = text(after.get("dmn/" + domain + ".dmn"));
        String yamlBefore = text(before.get("yaml/" + domain + ".yaml"));
        String yamlAfter = text(after.get("yaml/" + domain + ".yaml"));
        if (xmlAfter == null) {
            result.changes.add(domain + ": removed");
            return;
        }

        // What changed, rule by rule; and which rules are new or changed, to look at closer.
        Map<String, Set<String>> touched = new LinkedHashMap<>();
        DmnDiff diff = DmnDiffer.diff(xmlBefore == null ? EMPTY : xmlBefore, xmlAfter);
        for (DecisionDiff d : diff.decisions) {
            String name = d.rightName != null ? d.rightName : d.leftName;
            if (d.change == ChangeType.REMOVED) {
                result.changes.add(domain + " / " + name + ": table removed");
                continue;
            }
            if (d.change == ChangeType.ADDED) {
                result.changes.add(domain + " / " + name + ": table added, " + d.rules.size() + " rules");
            }
            if (d.leftHitPolicy != null && d.rightHitPolicy != null && !d.leftHitPolicy.equals(d.rightHitPolicy)) {
                result.changes.add(domain + " / " + name + ": when several rules match, " + d.leftHitPolicy + " → " + d.rightHitPolicy);
            }
            for (ColumnDiff c : d.inputs) {
                column(domain, name, "condition", c, result);
            }
            for (ColumnDiff c : d.outputs) {
                column(domain, name, "result", c, result);
            }
            for (RuleDiff r : d.rules) {
                switch (r.change) {
                    case ADDED:
                        result.changes.add(domain + " / " + name + ": rule " + (r.rightIndex + 1) + " added — " + rule(d, r, false));
                        touched.computeIfAbsent(d.id, (k) -> new LinkedHashSet<>()).add(r.rightRuleId);
                        break;
                    case REMOVED:
                        result.changes.add(domain + " / " + name + ": rule " + (r.leftIndex + 1) + " removed (it was " + rule(d, r, true) + ")");
                        break;
                    case CHANGED:
                        result.changes.add(domain + " / " + name + ": rule " + (r.rightIndex + 1) + " changed — " + cells(d, r));
                        touched.computeIfAbsent(d.id, (k) -> new LinkedHashSet<>()).add(r.rightRuleId);
                        break;
                    case MOVED:
                        result.changes.add(domain + " / " + name + ": rule " + (r.leftIndex + 1) + " moved to " + (r.rightIndex + 1));
                        touched.computeIfAbsent(d.id, (k) -> new LinkedHashSet<>()).add(r.rightRuleId);
                        break;
                    default:
                        break;
                }
            }
        }
        String caseChanges = cases(yamlBefore, yamlAfter);
        if (caseChanges != null) {
            result.changes.add(domain + ": test cases " + caseChanges);
        }

        // Every new or changed rule must be able to apply.
        for (Map.Entry<String, Set<String>> e : touched.entrySet()) {
            for (String ruleId : e.getValue()) {
                String blocked = ProposalCheck.reach(xmlAfter, e.getKey(), ruleId);
                if (blocked != null) {
                    result.findings.add(new Finding("problem", domain, nameOf(diff, e.getKey()) + ": " + blocked));
                }
            }
        }

        // The domain's test cases, run on it as it is now.
        if (yamlAfter == null || yamlAfter.isBlank()) {
            result.findings.add(new Finding("warning", domain, domain + " has no test cases, so nothing checks these changes"));
            return;
        }
        try {
            TestStatistics stats = tests.runYamlTests(xmlAfter, yamlAfter, null);
            result.testsTotal += stats.totalTests;
            result.testsPassed += stats.passedTests;
            for (TestResult t : stats.results) {
                if (!t.passed) {
                    String why = t.errorMessage == null ? "" : t.errorMessage.split("\n")[0];
                    result.findings.add(new Finding("problem", domain, "test case “" + t.testName + "” fails: " + why));
                }
            }
            if (stats.coverageStats != null) {
                for (Map.Entry<String, Set<String>> e : touched.entrySet()) {
                    DecisionCoverage coverage = stats.coverageStats.decisions.get(e.getKey());
                    if (coverage == null) {
                        continue;
                    }
                    for (String ruleId : e.getValue()) {
                        if (coverage.uncoveredRules.contains(ruleId)) {
                            result.findings.add(new Finding("warning", domain, nameOf(diff, e.getKey()) + ": rule "
                                    + numberOf(diff, e.getKey(), ruleId) + " is new or changed, and no test case reaches it"));
                        }
                    }
                }
            }
        } catch (RuntimeException e) {
            result.findings.add(new Finding("problem", domain, "the test cases cannot run: " + String.valueOf(e.getMessage()).split("\n")[0]));
        }
    }

    private static void column(String domain, String table, String kind, ColumnDiff c, Result result) {
        String name = c.rightExpression != null ? c.rightExpression : c.leftExpression;
        if (c.change == ChangeType.ADDED) {
            result.changes.add(domain + " / " + table + ": " + kind + " `" + name + "` added");
        } else if (c.change == ChangeType.REMOVED) {
            result.changes.add(domain + " / " + table + ": " + kind + " `" + name + "` removed");
        } else if (c.change == ChangeType.CHANGED) {
            result.changes.add(domain + " / " + table + ": " + kind + " `" + c.leftExpression + "` changed"
                    + (c.leftExpression != null && !c.leftExpression.equals(c.rightExpression) ? " to `" + c.rightExpression + "`" : "")
                    + (c.leftTypeRef != null && !c.leftTypeRef.equals(c.rightTypeRef) ? ", type " + c.leftTypeRef + " → " + c.rightTypeRef : ""));
        }
    }

    /** "when country "DE", market "XETRA" → feeCode "DE-XETRA", …" for one side of a rule. */
    private static String rule(DecisionDiff d, RuleDiff r, boolean left) {
        List<String> when = new ArrayList<>();
        for (CellDiff c : r.inputs) {
            String v = left ? c.left : c.right;
            if (v != null && !v.trim().isEmpty() && !"-".equals(v.trim())) {
                when.add(columnName(d.inputs, c.columnId) + " " + v.trim());
            }
        }
        List<String> gives = new ArrayList<>();
        for (CellDiff c : r.outputs) {
            String v = left ? c.left : c.right;
            gives.add(columnName(d.outputs, c.columnId) + " " + (v == null || v.trim().isEmpty() ? "(nothing)" : v.trim()));
        }
        String description = left ? r.leftDescription : r.rightDescription;
        return "when " + (when.isEmpty() ? "anything" : String.join(", ", when)) + " → " + String.join(", ", gives)
                + (description == null || description.isBlank() ? "" : " (" + description.trim() + ")");
    }

    /** Only the cells that changed: "feeCode "US-STD" → "US-FLAT"". */
    private static String cells(DecisionDiff d, RuleDiff r) {
        List<String> parts = new ArrayList<>();
        for (CellDiff c : r.inputs) {
            if (c.change != ChangeType.UNCHANGED) {
                parts.add(columnName(d.inputs, c.columnId) + " " + show(c.left) + " → " + show(c.right));
            }
        }
        for (CellDiff c : r.outputs) {
            if (c.change != ChangeType.UNCHANGED) {
                parts.add(columnName(d.outputs, c.columnId) + " " + show(c.left) + " → " + show(c.right));
            }
        }
        return parts.isEmpty() ? "its description" : String.join(", ", parts);
    }

    private static String show(String cell) {
        return cell == null || cell.trim().isEmpty() ? "-" : cell.trim();
    }

    private static String columnName(List<ColumnDiff> columns, String id) {
        for (ColumnDiff c : columns) {
            if (c.id.equals(id)) {
                return c.rightExpression != null ? c.rightExpression : c.leftExpression;
            }
        }
        return id;
    }

    private static String nameOf(DmnDiff diff, String decisionId) {
        for (DecisionDiff d : diff.decisions) {
            if (d.id.equals(decisionId)) {
                return d.rightName != null ? d.rightName : d.leftName;
            }
        }
        return decisionId;
    }

    private static int numberOf(DmnDiff diff, String decisionId, String ruleId) {
        for (DecisionDiff d : diff.decisions) {
            if (d.id.equals(decisionId)) {
                for (RuleDiff r : d.rules) {
                    if (ruleId.equals(r.rightRuleId)) {
                        return r.rightIndex + 1;
                    }
                }
            }
        }
        return 0;
    }

    /** "1 added (“German trade on XETRA”), 1 changed" — cases by table and scenario; null when unchanged. */
    private static String cases(String before, String after) {
        Map<String, String> was = caseMap(before);
        Map<String, String> now = caseMap(after);
        if (was == null || now == null) {
            return now == null && after != null ? "cannot be read" : null;
        }
        List<String> added = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        int changed = 0;
        for (Map.Entry<String, String> e : now.entrySet()) {
            if (!was.containsKey(e.getKey())) {
                added.add("“" + e.getKey().substring(e.getKey().indexOf('|') + 1) + "”");
            } else if (!was.get(e.getKey()).equals(e.getValue())) {
                changed++;
            }
        }
        for (String k : was.keySet()) {
            if (!now.containsKey(k)) {
                removed.add("“" + k.substring(k.indexOf('|') + 1) + "”");
            }
        }
        List<String> parts = new ArrayList<>();
        if (!added.isEmpty()) {
            parts.add(added.size() + " added (" + String.join(", ", added) + ")");
        }
        if (changed > 0) {
            parts.add(changed + " changed");
        }
        if (!removed.isEmpty()) {
            parts.add(removed.size() + " removed (" + String.join(", ", removed) + ")");
        }
        return parts.isEmpty() ? null : String.join(", ", parts);
    }

    private static Map<String, String> caseMap(String yaml) {
        Map<String, String> map = new LinkedHashMap<>();
        if (yaml == null || yaml.isBlank()) {
            return map;
        }
        try {
            TestSpec spec = TestSpecReader.read(yaml);
            for (DecisionTests d : spec.decisions) {
                for (TestCase c : d.cases) {
                    map.put(d.decision + "|" + c.scenario, String.valueOf(c.inputs) + "=>" + c.expected);
                }
            }
            return map;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String base(String path) {
        String name = path.substring(path.indexOf('/') + 1);
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }

    private static String text(byte[] bytes) {
        return bytes == null ? null : new String(bytes, StandardCharsets.UTF_8);
    }
}
