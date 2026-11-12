package limitium.art.camunda.backend.git;

import limitium.art.camunda.evaluator.diff.ChangeType;
import limitium.art.camunda.evaluator.diff.DecisionDiff;
import limitium.art.camunda.evaluator.diff.DecisionTableReader;
import limitium.art.camunda.evaluator.diff.DmnDiff;
import limitium.art.camunda.evaluator.diff.DmnDiffer;
import limitium.art.camunda.evaluator.diff.RuleDiff;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.AndTreeFilter;
import org.eclipse.jgit.treewalk.filter.PathFilterGroup;
import org.eclipse.jgit.treewalk.filter.TreeFilter;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The history of one thing in a domain, as fine as asked: the whole domain (its tables and tests), one
 * table, one rule, one column, or one cell. Newest first, each commit with what it changed in that
 * thing and nothing else — a cell's history is the commits that changed that cell, not every commit
 * that touched its file.
 *
 * <p>Identity is the DMN id. The editor keeps table, rule and column ids when it edits, so a rule stays
 * the same rule however its cells, its place or its neighbours change; a renamed column is the same
 * column.
 *
 * <p>A merge that only brought a change in — a landing or a pull — is not listed: the commit that made
 * the change is, under its own message and author. A merge is listed only when it made something
 * neither side had (a rule combining both sides' cells, a conflict answered with an edit).
 */
@Component
public class ElementHistory {

    /** Commits that touched the domain looked at, at most; older ones are left out and said so. */
    static final int MAX_COMMITS = 300;
    /** Changes returned, at most. */
    static final int MAX_ENTRIES = 50;

    public enum Level { DOMAIN, TABLE, RULE, COLUMN, CELL }

    /** What to follow: a domain, and inside it optionally a table, a rule and/or a column, by id. */
    public static final class Subject {
        public final String domain;
        public final String decision;
        public final String rule;
        public final String column;

        public Subject(String domain, String decision, String rule, String column) {
            this.domain = domain;
            this.decision = blankToNull(decision);
            this.rule = blankToNull(rule);
            this.column = blankToNull(column);
        }

        public Level level() {
            if (decision == null) {
                return Level.DOMAIN;
            }
            if (rule != null && column != null) {
                return Level.CELL;
            }
            if (rule != null) {
                return Level.RULE;
            }
            return column != null ? Level.COLUMN : Level.TABLE;
        }

        String dmnPath() {
            return "dmn/" + domain + ".dmn";
        }

        String yamlPath() {
            return "yaml/" + domain + ".yaml";
        }
    }

    /** One change of the subject. */
    public static final class Entry {
        /** Null for what a work space has saved but not committed. */
        public String sha;
        public String shortSha;
        public String message;
        public String author;
        /** ISO-8601; null for what is not committed. */
        public String when;
        public boolean uncommitted;
        /** A merge (landing, pull) that made this change itself rather than bringing it in. */
        public boolean merge;
        /** ADDED, REMOVED, CHANGED or MOVED: what happened to the subject. */
        public String change;
        /** A rule or a cell: the rule's number before and after (1 = first); null where it did not exist. */
        public Integer ruleBefore;
        public Integer ruleAfter;
        /** A cell: its text before and after; null where it did not exist. */
        public String before;
        public String after;
        /** A column: its heading before and after, when that changed. */
        public String headingBefore;
        public String headingAfter;
        /** A column: each rule whose cell in it changed. */
        public List<CellChange> cells;
        /** A rule, a table, the domain: the rule-level diff, narrowed to the subject. */
        public DmnDiff diff;
        /** The domain: whether its tests changed, and how many cases before and after. */
        public boolean testsChanged;
        public Integer casesBefore;
        public Integer casesAfter;
    }

    /** One rule's cell in a column's history. */
    public static final class CellChange {
        public String change;
        public Integer ruleBefore;
        public Integer ruleAfter;
        public String before;
        public String after;
    }

    public static final class Result {
        public String level;
        public List<Entry> entries = new ArrayList<>();
        /** Commits that touched the domain, looked at. */
        public int searched;
        /** True when older commits were not looked at, or older changes not returned. */
        public boolean more;
    }

    private final GitRuleStore store;
    /**
     * Parsed tables and diffs, kept across requests: both are keyed by blob ids, which name content,
     * so an entry can never go stale. Clicking from cell to cell in one table then parses nothing.
     */
    private final Cache cache = new Cache();

    public ElementHistory(GitRuleStore store) {
        this.store = store;
    }

    /** A work space's history: what it saved but did not commit, then its commits. */
    public Result ofSpace(WorkspaceId id, Subject subject) throws IOException {
        try (Git git = Git.open(store.checkoutOf(id).toFile())) {
            Repository repository = git.getRepository();
            ObjectId head = repository.resolve(Constants.HEAD + "^{commit}");
            Map<String, byte[]> working = new HashMap<>();
            Map<String, byte[]> files = store.workingFiles(id);
            for (String path : new String[]{subject.dmnPath(), subject.yamlPath()}) {
                if (files.containsKey(path)) {
                    working.put(path, files.get(path));
                }
            }
            return walk(repository, head, working, subject, cache);
        }
    }

    /** The history up to any commit: a sha, a version, a branch. Null when there is no such commit. */
    public Result at(String revision, Subject subject) throws IOException {
        try (Git git = Git.open(store.mainCheckout().toFile())) {
            Repository repository = git.getRepository();
            ObjectId tip = repository.resolve(revision + "^{commit}");
            return tip == null ? null : walk(repository, tip, null, subject, cache);
        }
    }

    /**
     * Walks back from {@code tip}, newest first. {@code working}, when given, is a checkout's files —
     * present or not — compared with the tip first.
     */
    static Result walk(Repository repository, ObjectId tip, Map<String, byte[]> working, Subject subject, Cache cache) throws IOException {
        Reader reader = new Reader(repository, subject, cache);
        Result result = new Result();
        result.level = subject.level().name();

        if (working != null && tip != null) {
            Version now = reader.working(working);
            Version committed = reader.at(tip);
            if (!reader.atoms(now).equals(reader.atoms(committed)) || reader.moved(committed, now)) {
                Entry e = reader.describe(committed, now);
                e.uncommitted = true;
                result.entries.add(e);
            }
        }
        if (tip == null) {
            return result;
        }

        List<String> paths = subject.level() == Level.DOMAIN
                ? Arrays.asList(subject.dmnPath(), subject.yamlPath())
                : Arrays.asList(subject.dmnPath());
        List<Step> steps = new ArrayList<>();
        try (RevWalk walk = new RevWalk(repository)) {
            walk.markStart(walk.parseCommit(tip));
            walk.setTreeFilter(AndTreeFilter.create(PathFilterGroup.createFromStrings(paths), TreeFilter.ANY_DIFF));
            for (RevCommit c : walk) {
                if (steps.size() >= MAX_COMMITS) {
                    result.more = true;
                    break;
                }
                steps.add(new Step(c));
            }
        }
        result.searched = steps.size();

        for (Step step : steps) {
            if (result.entries.size() >= MAX_ENTRIES) {
                result.more = true;
                break;
            }
            Version after = reader.at(step.id);
            List<Version> parents = new ArrayList<>();
            for (ObjectId p : step.parents) {
                parents.add(reader.at(p));
            }
            Version before = parents.isEmpty() ? Version.NONE : parents.get(0);
            boolean changed;
            if (parents.size() > 1) {
                changed = reader.madeByMerge(after, parents);
            } else {
                changed = !reader.atoms(after).equals(reader.atoms(before)) || reader.moved(before, after);
            }
            if (!changed) {
                continue;
            }
            Entry e = reader.describe(before, after);
            e.sha = step.id.getName();
            e.shortSha = e.sha.substring(0, 7);
            e.message = step.message;
            e.author = step.author;
            e.when = step.when;
            e.merge = parents.size() > 1;
            result.entries.add(e);
        }
        return result;
    }

    /** A commit, read while walking; parents as the walk rewrote them (the nearest ones touching the domain). */
    private static final class Step {
        final ObjectId id;
        final List<ObjectId> parents = new ArrayList<>();
        final String message;
        final String author;
        final String when;

        Step(RevCommit c) {
            id = c.copy();
            for (RevCommit p : c.getParents()) {
                parents.add(p.copy());
            }
            message = c.getShortMessage();
            author = c.getAuthorIdent().getName();
            when = Instant.ofEpochSecond(c.getCommitTime()).toString();
        }
    }

    /** The domain's two files at one point: blob ids, null where a file is absent. */
    private static final class Version {
        static final Version NONE = new Version(null, null);
        final ObjectId dmn;
        final ObjectId yaml;

        Version(ObjectId dmn, ObjectId yaml) {
            this.dmn = dmn;
            this.yaml = yaml;
        }
    }

    /** Bounded, least recently used first out; safe to share between requests. */
    static final class Cache {
        private static final int SIZE = 2000;
        private final Map<ObjectId, Map<String, DecisionTableReader.Table>> tables = lru();
        private final Map<String, DmnDiff> diffs = lru();

        private static <K, V> Map<K, V> lru() {
            return java.util.Collections.synchronizedMap(new LinkedHashMap<K, V>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                    return size() > SIZE;
                }
            });
        }
    }

    /** Reads versions and what the subject is in each, parsing every blob once. */
    private static final class Reader {
        private final Repository repository;
        private final Subject subject;
        private final Map<ObjectId, byte[]> blobs = new HashMap<>();
        private final Map<ObjectId, Map<String, DecisionTableReader.Table>> tables;
        private final Map<String, DmnDiff> diffs;

        Reader(Repository repository, Subject subject, Cache cache) {
            this.repository = repository;
            this.subject = subject;
            this.tables = cache.tables;
            this.diffs = cache.diffs;
        }

        Version at(ObjectId commit) throws IOException {
            try (RevWalk walk = new RevWalk(repository)) {
                RevCommit c = walk.parseCommit(commit);
                return new Version(blob(c, subject.dmnPath()), blob(c, subject.yamlPath()));
            }
        }

        private ObjectId blob(RevCommit c, String path) throws IOException {
            try (TreeWalk tree = TreeWalk.forPath(repository, path, c.getTree())) {
                return tree == null ? null : tree.getObjectId(0);
            }
        }

        /** A checkout's files, under the ids git would give them, so they compare with commits by id. */
        Version working(Map<String, byte[]> files) {
            return new Version(put(files.get(subject.dmnPath())), put(files.get(subject.yamlPath())));
        }

        private ObjectId put(byte[] content) {
            if (content == null) {
                return null;
            }
            ObjectId id;
            try (ObjectInserter.Formatter f = new ObjectInserter.Formatter()) {
                id = f.idFor(Constants.OBJ_BLOB, content);
            }
            blobs.put(id, content);
            return id;
        }

        private byte[] bytes(ObjectId id) {
            if (id == null) {
                return null;
            }
            return blobs.computeIfAbsent(id, k -> {
                try {
                    return repository.open(k).getBytes();
                } catch (IOException e) {
                    return null;
                }
            });
        }

        private String text(ObjectId id) {
            byte[] b = bytes(id);
            return b == null ? null : new String(b, StandardCharsets.UTF_8);
        }

        /** The tables of a version's DMN; empty when it has none; null when it does not read. */
        private Map<String, DecisionTableReader.Table> tables(Version v) {
            if (v.dmn == null) {
                return new LinkedHashMap<>();
            }
            Map<String, DecisionTableReader.Table> read = tables.get(v.dmn);
            if (read == null) {
                try {
                    read = DecisionTableReader.read(text(v.dmn));
                } catch (RuntimeException e) {
                    read = UNREADABLE;
                }
                tables.put(v.dmn, read);
            }
            return read == UNREADABLE ? null : read;
        }

        /** Kept for a file that does not read, so it is not parsed again; told apart by identity. */
        private static final Map<String, DecisionTableReader.Table> UNREADABLE = new LinkedHashMap<>();

        private DecisionTableReader.Table table(Version v) {
            Map<String, DecisionTableReader.Table> all = tables(v);
            return all == null ? null : all.get(subject.decision);
        }

        // --- what the subject is made of ---------------------------------------------------------

        /**
         * The subject as small named parts (a cell's value, a column's heading, a rule's note), so two
         * versions compare part by part and a merge can be checked part by part. A wildcard cell is no
         * part of a rule or a column: adding a column of "any" does not change every rule.
         */
        Map<String, String> atoms(Version v) {
            Map<String, String> atoms = new LinkedHashMap<>();
            Map<String, DecisionTableReader.Table> all = tables(v);
            if (all == null) {
                atoms.put("unreadable", String.valueOf(v.dmn));
                return atoms;
            }
            switch (subject.level()) {
                case DOMAIN:
                    for (DecisionTableReader.Table t : all.values()) {
                        tableAtoms(t, t.decisionId + "|", atoms);
                    }
                    String yaml = text(v.yaml);
                    if (yaml != null) {
                        for (String line : yaml.split("\n")) {
                            if (!line.isBlank()) {
                                atoms.merge("tests|" + line.trim(), "1", (a, b) -> String.valueOf(Integer.parseInt(a) + 1));
                            }
                        }
                    }
                    break;
                case TABLE:
                    DecisionTableReader.Table t = all.get(subject.decision);
                    if (t != null) {
                        tableAtoms(t, "", atoms);
                    }
                    break;
                case RULE:
                    ruleAtoms(all.get(subject.decision), atoms);
                    break;
                case COLUMN:
                    columnAtoms(all.get(subject.decision), atoms);
                    break;
                case CELL:
                    String value = cell(all.get(subject.decision), subject.rule, subject.column, true);
                    if (value != null) {
                        atoms.put("cell", value);
                    }
                    break;
                default:
                    break;
            }
            return atoms;
        }

        private static void tableAtoms(DecisionTableReader.Table t, String prefix, Map<String, String> atoms) {
            atoms.put(prefix + "table", t.decisionName + "|" + t.hitPolicy);
            for (DecisionTableReader.Column c : t.inputs) {
                atoms.put(prefix + "col|" + c.id, heading("in", c));
            }
            for (DecisionTableReader.Column c : t.outputs) {
                atoms.put(prefix + "col|" + c.id, heading("out", c));
            }
            for (DecisionTableReader.Rule r : t.rules) {
                atoms.put(prefix + "rule|" + r.id, "|" + Objects.toString(r.description, ""));
                cells(t, r, prefix + "cell|" + r.id + "|", atoms);
            }
        }

        private void ruleAtoms(DecisionTableReader.Table t, Map<String, String> atoms) {
            DecisionTableReader.Rule r = rule(t, subject.rule);
            if (r != null) {
                atoms.put("rule", "|" + Objects.toString(r.description, ""));
                cells(t, r, "cell|", atoms);
            }
        }

        private void columnAtoms(DecisionTableReader.Table t, Map<String, String> atoms) {
            DecisionTableReader.Column c = column(t, subject.column);
            if (c == null) {
                return;
            }
            atoms.put("col", heading(isInput(t, c) ? "in" : "out", c));
            for (DecisionTableReader.Rule r : t.rules) {
                String value = cell(t, r.id, c.id, false);
                if (value != null) {
                    atoms.put("cell|" + r.id, value);
                }
            }
        }

        private static void cells(DecisionTableReader.Table t, DecisionTableReader.Rule r, String prefix, Map<String, String> atoms) {
            for (int i = 0; i < t.inputs.size(); i++) {
                String v = i < r.inputEntries.size() ? r.inputEntries.get(i) : null;
                if (!isWildcard(v)) {
                    atoms.put(prefix + t.inputs.get(i).id, v);
                }
            }
            for (int i = 0; i < t.outputs.size(); i++) {
                String v = i < r.outputEntries.size() ? r.outputEntries.get(i) : null;
                if (!isWildcard(v)) {
                    atoms.put(prefix + t.outputs.get(i).id, v);
                }
            }
        }

        /**
         * A merge made a change when some part of the subject in it matches no parent: the part was
         * neither kept from the line nor brought in, so this merge is where it came from.
         */
        boolean madeByMerge(Version merge, List<Version> parents) {
            Map<String, String> mine = atoms(merge);
            List<Map<String, String>> theirs = new ArrayList<>();
            Set<String> keys = new LinkedHashSet<>(mine.keySet());
            for (Version p : parents) {
                Map<String, String> a = atoms(p);
                theirs.add(a);
                keys.addAll(a.keySet());
            }
            for (String key : keys) {
                boolean explained = false;
                for (Map<String, String> a : theirs) {
                    if (Objects.equals(a.get(key), mine.get(key))) {
                        explained = true;
                        break;
                    }
                }
                if (!explained) {
                    return true;
                }
            }
            return false;
        }

        /**
         * A rule moved when its place among the rules both versions have changed — not when a rule
         * above it came or went. For a table: when any of them did. A cell, a column and the domain
         * have no order of their own.
         */
        boolean moved(Version before, Version after) {
            Level level = subject.level();
            if (level != Level.RULE && level != Level.TABLE) {
                return false;
            }
            DecisionTableReader.Table a = table(before);
            DecisionTableReader.Table b = table(after);
            if (a == null || b == null) {
                return false;
            }
            List<String> orderA = commonOrder(a, b);
            List<String> orderB = commonOrder(b, a);
            if (level == Level.TABLE) {
                return !orderA.equals(orderB);
            }
            int i = orderA.indexOf(subject.rule);
            return i >= 0 && i != orderB.indexOf(subject.rule);
        }

        private static List<String> commonOrder(DecisionTableReader.Table of, DecisionTableReader.Table other) {
            Set<String> there = new LinkedHashSet<>();
            for (DecisionTableReader.Rule r : other.rules) {
                there.add(r.id);
            }
            List<String> order = new ArrayList<>();
            for (DecisionTableReader.Rule r : of.rules) {
                if (there.contains(r.id)) {
                    order.add(r.id);
                }
            }
            return order;
        }

        // --- what changed, in words and cells -----------------------------------------------------

        Entry describe(Version before, Version after) {
            Entry e = new Entry();
            Map<String, String> a = atoms(before);
            Map<String, String> b = atoms(after);
            e.change = a.isEmpty() && !b.isEmpty() ? "ADDED" : !a.isEmpty() && b.isEmpty() ? "REMOVED"
                    : a.equals(b) ? "MOVED" : "CHANGED";
            DecisionTableReader.Table ta = table(before);
            DecisionTableReader.Table tb = table(after);
            switch (subject.level()) {
                case CELL:
                    e.ruleBefore = number(ta, subject.rule);
                    e.ruleAfter = number(tb, subject.rule);
                    e.before = a.get("cell");
                    e.after = b.get("cell");
                    break;
                case RULE:
                    e.ruleBefore = number(ta, subject.rule);
                    e.ruleAfter = number(tb, subject.rule);
                    e.diff = narrowed(before, after, ta, tb);
                    break;
                case COLUMN:
                    describeColumn(e, ta, tb);
                    break;
                case TABLE:
                case DOMAIN:
                    e.diff = narrowed(before, after, ta, tb);
                    if (subject.level() == Level.DOMAIN) {
                        e.testsChanged = !Objects.equals(before.yaml, after.yaml);
                        if (e.testsChanged) {
                            e.casesBefore = cases(text(before.yaml));
                            e.casesAfter = cases(text(after.yaml));
                        }
                    }
                    break;
                default:
                    break;
            }
            return e;
        }

        private void describeColumn(Entry e, DecisionTableReader.Table ta, DecisionTableReader.Table tb) {
            DecisionTableReader.Column ca = column(ta, subject.column);
            DecisionTableReader.Column cb = column(tb, subject.column);
            String ha = ca == null ? null : label(ca);
            String hb = cb == null ? null : label(cb);
            if (!Objects.equals(ca == null ? null : heading("", ca), cb == null ? null : heading("", cb))) {
                e.headingBefore = ha;
                e.headingAfter = hb;
            }
            e.cells = new ArrayList<>();
            Set<String> ids = new LinkedHashSet<>();
            if (tb != null) {
                tb.rules.forEach(r -> ids.add(r.id));
            }
            if (ta != null) {
                ta.rules.forEach(r -> ids.add(r.id));
            }
            for (String id : ids) {
                String va = ca == null ? null : cell(ta, id, ca.id, false);
                String vb = cb == null ? null : cell(tb, id, cb.id, false);
                if (Objects.equals(va, vb)) {
                    continue;
                }
                CellChange c = new CellChange();
                c.ruleBefore = number(ta, id);
                c.ruleAfter = number(tb, id);
                c.before = va == null && c.ruleBefore != null && ca != null ? DmnDiffer.WILDCARD : va;
                c.after = vb == null && c.ruleAfter != null && cb != null ? DmnDiffer.WILDCARD : vb;
                c.change = c.ruleBefore == null ? "ADDED" : c.ruleAfter == null ? "REMOVED" : "CHANGED";
                e.cells.add(c);
            }
        }

        /**
         * The semantic diff of the two versions, keeping only what concerns the subject: for a rule
         * that rule, for a table that table, for the domain every table that changed. Rules that only
         * shifted because another came or went are dropped; a rule that really moved stays.
         */
        private DmnDiff narrowed(Version before, Version after, DecisionTableReader.Table ta, DecisionTableReader.Table tb) {
            String key = before.dmn + ":" + after.dmn;
            DmnDiff full = diffs.get(key);
            if (full == null) {
                try {
                    full = DmnDiffer.diff(before.dmn == null ? EMPTY_DMN : text(before.dmn), after.dmn == null ? EMPTY_DMN : text(after.dmn));
                } catch (RuntimeException e) {
                    return null;
                }
                diffs.put(key, full);
            }
            List<DecisionDiff> decisions = new ArrayList<>();
            for (DecisionDiff d : full.decisions) {
                if (subject.decision != null && !subject.decision.equals(d.id)) {
                    continue;
                }
                if (subject.level() == Level.DOMAIN && d.change == ChangeType.UNCHANGED) {
                    continue;
                }
                DecisionTableReader.Table da = tables(before) == null ? null : tables(before).get(d.id);
                DecisionTableReader.Table db = tables(after) == null ? null : tables(after).get(d.id);
                List<RuleDiff> rules = new ArrayList<>();
                for (RuleDiff r : d.rules) {
                    if (subject.level() == Level.RULE) {
                        if (subject.rule.equals(r.rightRuleId) || subject.rule.equals(r.leftRuleId)) {
                            rules.add(r);
                        }
                    } else if (r.change == ChangeType.MOVED) {
                        if (reallyMoved(da, db, r)) {
                            rules.add(r);
                        }
                    } else if (r.change != ChangeType.UNCHANGED) {
                        rules.add(r);
                    }
                }
                decisions.add(new DecisionDiff(d.id, d.leftName, d.rightName, d.change, d.nonTabular,
                        d.leftHitPolicy, d.rightHitPolicy, d.inputs, d.outputs, rules));
            }
            return new DmnDiff(full.leftLabel, full.rightLabel, full.identical, full.summary, decisions);
        }

        private static boolean reallyMoved(DecisionTableReader.Table a, DecisionTableReader.Table b, RuleDiff r) {
            if (a == null || b == null || r.leftRuleId == null || r.rightRuleId == null) {
                return true;
            }
            int i = commonOrder(a, b).indexOf(r.leftRuleId);
            return i < 0 || i != commonOrder(b, a).indexOf(r.rightRuleId);
        }

        // --- reading one thing out of a table -----------------------------------------------------

        private static DecisionTableReader.Rule rule(DecisionTableReader.Table t, String id) {
            if (t == null || id == null) {
                return null;
            }
            for (DecisionTableReader.Rule r : t.rules) {
                if (id.equals(r.id)) {
                    return r;
                }
            }
            return null;
        }

        private static Integer number(DecisionTableReader.Table t, String ruleId) {
            if (t == null) {
                return null;
            }
            for (int i = 0; i < t.rules.size(); i++) {
                if (t.rules.get(i).id.equals(ruleId)) {
                    return i + 1;
                }
            }
            return null;
        }

        private static DecisionTableReader.Column column(DecisionTableReader.Table t, String id) {
            if (t == null || id == null) {
                return null;
            }
            for (DecisionTableReader.Column c : t.inputs) {
                if (id.equals(c.id)) {
                    return c;
                }
            }
            for (DecisionTableReader.Column c : t.outputs) {
                if (id.equals(c.id)) {
                    return c;
                }
            }
            return null;
        }

        private static boolean isInput(DecisionTableReader.Table t, DecisionTableReader.Column c) {
            return t.inputs.contains(c);
        }

        /**
         * A rule's cell in a column, or null when the rule or the column is not there. A wildcard
         * reads as "-" with {@code keepWildcard}, else as null, so it is no part of a rule or column.
         */
        private static String cell(DecisionTableReader.Table t, String ruleId, String columnId, boolean keepWildcard) {
            DecisionTableReader.Rule r = rule(t, ruleId);
            if (r == null) {
                return null;
            }
            for (int i = 0; i < t.inputs.size(); i++) {
                if (t.inputs.get(i).id.equals(columnId)) {
                    return value(i < r.inputEntries.size() ? r.inputEntries.get(i) : null, keepWildcard);
                }
            }
            for (int i = 0; i < t.outputs.size(); i++) {
                if (t.outputs.get(i).id.equals(columnId)) {
                    return value(i < r.outputEntries.size() ? r.outputEntries.get(i) : null, keepWildcard);
                }
            }
            return null;
        }

        private static String value(String v, boolean keepWildcard) {
            if (isWildcard(v)) {
                return keepWildcard ? DmnDiffer.WILDCARD : null;
            }
            return v;
        }
    }

    private static boolean isWildcard(String v) {
        return v == null || v.isBlank() || DmnDiffer.WILDCARD.equals(v.trim());
    }

    private static String heading(String side, DecisionTableReader.Column c) {
        return side + "|" + Objects.toString(c.label, "") + "|" + Objects.toString(c.expression, "") + "|" + Objects.toString(c.typeRef, "");
    }

    /** A column as a person reads its heading: its label, else its expression or name; and its type. */
    private static String label(DecisionTableReader.Column c) {
        String name = c.label != null && !c.label.isBlank() ? c.label : Objects.toString(c.expression, "unnamed");
        if (c.label != null && !c.label.isBlank() && c.expression != null && !c.expression.equals(c.label)) {
            name = name + " (" + c.expression + ")";
        }
        return c.typeRef == null ? name : name + ", " + c.typeRef;
    }

    private static int cases(String yaml) {
        if (yaml == null || yaml.isBlank()) {
            return 0;
        }
        try {
            return limitium.art.camunda.evaluator.testspec.TestSpecReader.read(yaml).caseCount();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static final String EMPTY_DMN = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><definitions xmlns=\"https://www.omg.org/spec/DMN/20191111/MODEL/\" id=\"empty\" name=\"empty\" namespace=\"empty\"/>";
}
