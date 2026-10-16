package limitium.art.camunda.backend.git;

import limitium.art.camunda.backend.lifecycle.TestGate;
import limitium.art.camunda.evaluator.diff.Attribution;
import limitium.art.camunda.evaluator.diff.ChangeAttribution;
import limitium.art.camunda.evaluator.diff.Choice;
import limitium.art.camunda.evaluator.diff.DmnMerger;
import limitium.art.camunda.evaluator.diff.MergeConflict;
import limitium.art.camunda.evaluator.diff.MergeResult;
import limitium.art.camunda.evaluator.diff.Resolution;
import limitium.art.camunda.evaluator.diff.TestSpecMerger;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheBuilder;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.revwalk.filter.RevFilter;
import org.eclipse.jgit.treewalk.TreeWalk;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Landing a space: into the line it targets, then carried forward through every newer line to the
 * newest work — all of it, or none of it.
 *
 * <p>Every step is merged in memory (nothing is checked out, no ref moves) and tested. The person
 * sees every step's conflicts and test results together; answers are keyed by conflict id, so they
 * survive recomputing when a line moves meanwhile. Only when every step is clean and green do the
 * lines move — here and on the remote, as one {@link RefTransaction}.
 */
public class Landing {

    private static final Pattern LINE = Pattern.compile("release/(\\d+)\\.(\\d+)");
    private static final Pattern VERSION = Pattern.compile("v(\\d+)\\.(\\d+)\\.\\d+");

    public static class Refused extends RuntimeException {
        public final String code;

        Refused(String code, String message) {
            super(message);
            this.code = code;
        }
    }

    /** One step of the chain: what goes into which line. */
    public static class Hop {
        /** The line receiving it. */
        public String into;
        /** The space (first step) or the line before (carrying forward). */
        public String from;
        public String base;
        public String ours;
        public String theirs;
        /** The merged commit, computed in memory; never on a line until the whole chain lands. */
        public String result;
        /** The line already holds all of it; nothing to do at this step. */
        public boolean already;
        /** Computed with earlier steps' open conflicts answered "keep the line's" for now. */
        public boolean provisional;
        /** The line does not exist yet (a fix to an old version): it is created from that version. */
        public boolean createsLine;
        public List<Conflict> conflicts = new ArrayList<>();
        public List<DomainChange> domains = new ArrayList<>();
        public TestGate.Report tests;
    }

    public static class DomainChange {
        public String name;
        /** added, removed, changed (taken from one side), merged (both changed, combined), unchanged. */
        public String change;
        public int conflicts;
        /** The merged content with open conflicts showing the line's value: what the person looks at. */
        public String previewDmn;
        public String previewYaml;
    }

    /** A conflict at one step of the chain, in one domain. */
    public static class Conflict {
        public String id;
        public String hop;
        public String domain;
        public String kind;
        public String tier;
        public String decisionId;
        public String ruleId;
        public String columnId;
        public String label;
        public String base;
        public String ours;
        public String theirs;
        public List<Choice> choices;
        public String detail;
        public List<Attribution> oursChanges;
        public List<Attribution> theirsChanges;
    }

    public static class Result {
        /** CLEAN (would land), CONFLICTS, TESTS_FAILED, LANDED, MOVED. */
        public String status;
        public String message;
        public String space;
        public String target;
        /** Each line's newest commit as read for this computation; echoed back to land. */
        public Map<String, String> tips = new LinkedHashMap<>();
        public List<Hop> hops = new ArrayList<>();
        public int open;
    }

    private final GitRuleStore store;
    private final RemoteSync sync;
    private final TestGate gate;

    public Landing(GitRuleStore store, RemoteSync sync, TestGate gate) {
        this.store = store;
        this.sync = sync;
        this.gate = gate;
    }

    /**
     * Computes the whole chain and, when {@code apply} and everything is clean and green and the
     * lines are where the person last saw them ({@code expectedTips}), lands it.
     */
    public Result land(WorkspaceId id, Map<String, Resolution> answers, Map<String, String> expectedTips,
                       boolean apply, String by) throws IOException, org.eclipse.jgit.api.errors.GitAPIException {
        Workspace space = null;
        for (Workspace w : store.workspaces()) {
            if (w.id.equals(id.branch())) {
                space = w;
            }
        }
        if (space == null) {
            throw new GitRuleStore.NoSuchSpace(id.branch());
        }
        if (space.dirty) {
            throw new Refused("UNSAVED", "there are changes that are not in a saved version; only saved versions land");
        }
        String shared = store.sharedHead(id);
        if (shared == null || !shared.equals(space.head)) {
            throw new Refused("NOT_SHARED", "the last saved version has not been shared yet — save the version again to share it");
        }
        if (sync.enabled()) {
            sync.run(); // check and land against what every instance sees, never a stale copy
        }
        Map<String, Resolution> chosen = answers == null ? Collections.<String, Resolution>emptyMap() : answers;
        ReentrantLock lifecycle = store.lifecycleLock();
        lifecycle.lock();
        try (Git git = Git.open(store.mainCheckout().toFile());
             ObjectInserter inserter = git.getRepository().newObjectInserter()) {
            Repository repository = git.getRepository();
            String[] baseline = store.baselineOf(id);
            List<String> chain = chain(repository, space.targetLine);
            Result result = new Result();
            result.space = id.branch();
            result.target = space.targetLine;

            return landChain(repository, inserter, result, chain, baseline, ObjectId.fromString(space.head), id.branch(),
                    "Land " + id.branch() + " into ", false, chosen, expectedTips, apply, by);
        } finally {
            lifecycle.unlock();
        }
    }

    /**
     * The chain itself, from what comes in ({@code source}) through every line of {@code chain}:
     * merged in memory, tested at each step, and — with {@code apply}, clean, green and unmoved —
     * landed in one transaction. Carrying a line forward starts at that line, which already has it.
     */
    private Result landChain(Repository repository, ObjectInserter inserter, Result result, List<String> chain, String[] baseline,
                             ObjectId source, String sourceName, String firstMessage, boolean carrying,
                             Map<String, Resolution> chosen, Map<String, String> expectedTips, boolean apply, String by)
            throws IOException {
            ObjectId incoming = source;
            String incomingName = sourceName;
            boolean earlierOpen = false;
            for (int i = 0; i < chain.size(); i++) {
                String line = chain.get(i);
                Hop hop = new Hop();
                hop.into = line;
                hop.from = incomingName;
                hop.provisional = earlierOpen;
                ObjectId tip = repository.resolve(Constants.R_HEADS + line);
                if (tip == null && i == 0 && baseline[0] != null && VERSION.matcher(baseline[0]).matches()) {
                    tip = repository.resolve(Constants.R_TAGS + baseline[0] + "^{commit}");
                    hop.createsLine = true;
                }
                if (tip == null) {
                    throw new Refused("NO_LINE", line + " does not exist, and nothing says what to make it from");
                }
                result.tips.put(line, hop.createsLine ? "" : tip.getName());
                hop.ours = tip.getName();
                hop.theirs = incoming.getName();
                if (GitRuleStore.isAncestor(repository, incoming, tip)) {
                    hop.already = true;
                    if (i == 0 && !carrying) {
                        result.hops.add(hop);
                        result.status = "NOTHING_TO_LAND";
                        result.message = lineName(line) + " already has everything this space saved";
                        return result;
                    }
                    incoming = tip;
                    incomingName = line;
                    result.hops.add(hop);
                    continue;
                }
                ObjectId base = mergeBase(repository, tip, incoming);
                hop.base = base == null ? null : base.getName();
                String hopKey = line;
                Map<String, byte[]> merged = mergeDomains(repository, hop, hopKey, base, tip, incoming, chosen);
                attribute(repository, hop, hopKey, base, tip, incoming);
                ObjectId commit = commit(repository, inserter, tip, incoming, merged,
                        (i == 0 && !carrying ? firstMessage + line : "Carry " + incomingName + " forward into " + line), by);
                hop.result = commit.getName();
                if (hop.conflicts.isEmpty() && !earlierOpen) {
                    hop.tests = gate.run(commit.getName());
                }
                earlierOpen = earlierOpen || !hop.conflicts.isEmpty();
                result.open += hop.conflicts.size();
                result.hops.add(hop);
                incoming = commit;
                incomingName = line;
            }

            boolean failing = false;
            for (Hop hop : result.hops) {
                if (hop.tests != null && !hop.tests.passed) {
                    failing = true;
                }
            }
            result.status = result.open > 0 ? "CONFLICTS" : failing ? "TESTS_FAILED" : "CLEAN";
            if (!apply || !"CLEAN".equals(result.status)) {
                return result;
            }
            if (expectedTips != null && !expectedTips.isEmpty() && !expectedTips.equals(result.tips)) {
                result.status = "MOVED";
                result.message = "a line moved while you were deciding; your answers are kept — look at what changed";
                return result;
            }
            List<RefTransaction.Move> moves = new ArrayList<>();
            for (Hop hop : result.hops) {
                if (hop.already) {
                    continue;
                }
                moves.add(new RefTransaction.Move(Constants.R_HEADS + hop.into,
                        hop.createsLine ? null : ObjectId.fromString(hop.ours), ObjectId.fromString(hop.result)));
            }
            try {
                RefTransaction.apply(store, moves);
            } catch (RefTransaction.Refused e) {
                result.status = "MOVED";
                result.message = e.getMessage();
                return result;
            }
            result.status = "LANDED";
            return result;
    }

    /**
     * Carries a release line forward: what is on it, into every newer line and main, the same way a
     * landing does — for a line whose last change stopped short (a landing made while carrying
     * forward was off, or a line moved outside the app).
     */
    public Result carryForward(String line, Map<String, Resolution> answers, Map<String, String> expectedTips,
                               boolean apply, String by) throws IOException, org.eclipse.jgit.api.errors.GitAPIException {
        if (sync.enabled()) {
            sync.run();
        }
        Map<String, Resolution> chosen = answers == null ? Collections.<String, Resolution>emptyMap() : answers;
        ReentrantLock lifecycle = store.lifecycleLock();
        lifecycle.lock();
        try (Git git = Git.open(store.mainCheckout().toFile());
             ObjectInserter inserter = git.getRepository().newObjectInserter()) {
            Repository repository = git.getRepository();
            ObjectId tip = repository.resolve(Constants.R_HEADS + line);
            if (tip == null || !LINE.matcher(line).matches()) {
                throw new Refused("NO_LINE", "there is no release line " + line);
            }
            Result result = new Result();
            result.space = line;
            result.target = line;
            Result done = landChain(repository, inserter, result, chain(repository, line), new String[]{null, line},
                    tip, line, "", true, chosen, expectedTips, apply, by);
            if ("CLEAN".equals(done.status) || "LANDED".equals(done.status)) {
                boolean anything = false;
                for (Hop hop : done.hops) {
                    anything = anything || !hop.already;
                }
                if (!anything) {
                    done.status = "NOTHING_TO_LAND";
                    done.message = lineName(line) + " is already in every newer line and main";
                }
            }
            return done;
        } finally {
            lifecycle.unlock();
        }
    }

    /**
     * Brings another branch — the space's own line after it moved, a release line, or a colleague's
     * space from any instance — into a space: merged in memory, conflicts answered the same way as
     * landing, and only then the space moves (on its own instance, with nothing unsaved in it).
     * Tests are run and shown; they do not stop a pull, any more than they stop a save.
     */
    public Result pull(WorkspaceId id, String from, Map<String, Resolution> answers, String expectedHead,
                       boolean apply, String by) throws IOException, org.eclipse.jgit.api.errors.GitAPIException {
        Workspace space = null;
        for (Workspace w : store.workspaces()) {
            if (w.id.equals(id.branch())) {
                space = w;
            }
        }
        if (space == null) {
            throw new GitRuleStore.NoSuchSpace(id.branch());
        }
        if (space.dirty) {
            throw new Refused("UNSAVED", "there are unsaved changes in this space; save or undo them first, then bring the changes in");
        }
        if (sync.enabled()) {
            sync.run();
        }
        Map<String, Resolution> chosen = answers == null ? Collections.<String, Resolution>emptyMap() : answers;
        ReentrantLock lifecycle = store.lifecycleLock();
        lifecycle.lock();
        try (Git git = Git.open(store.mainCheckout().toFile());
             ObjectInserter inserter = git.getRepository().newObjectInserter()) {
            Repository repository = git.getRepository();
            ObjectId ours = ObjectId.fromString(space.head);
            ObjectId theirs = repository.resolve(Constants.R_HEADS + from);
            if (theirs == null) {
                theirs = repository.resolve(from + "^{commit}");
            }
            if (theirs == null) {
                throw new Refused("NO_SUCH_SOURCE", "there is no " + from + " to bring in");
            }
            Result result = new Result();
            result.space = id.branch();
            result.target = id.branch();
            result.tips.put(id.branch(), space.head);
            Hop hop = new Hop();
            hop.into = id.branch();
            hop.from = from;
            hop.ours = space.head;
            hop.theirs = theirs.getName();
            result.hops.add(hop);
            if (GitRuleStore.isAncestor(repository, theirs, ours)) {
                hop.already = true;
                result.status = "NOTHING_TO_PULL";
                result.message = "this space already has everything in " + from;
                return result;
            }
            ObjectId base = mergeBase(repository, ours, theirs);
            hop.base = base == null ? null : base.getName();
            Map<String, byte[]> merged = mergeDomains(repository, hop, id.branch(), base, ours, theirs, chosen);
            attribute(repository, hop, id.branch(), base, ours, theirs);
            ObjectId commit = commit(repository, inserter, ours, theirs, merged, "Bring " + from + " into " + id.branch(), by);
            hop.result = commit.getName();
            if (hop.conflicts.isEmpty()) {
                hop.tests = gate.run(commit.getName());
            }
            result.open = hop.conflicts.size();
            result.status = result.open > 0 ? "CONFLICTS" : "CLEAN";
            if (!apply || result.open > 0) {
                return result;
            }
            if (expectedHead != null && !expectedHead.isEmpty() && !expectedHead.equals(space.head)) {
                result.status = "MOVED";
                result.message = "this space was saved again meanwhile; your answers are kept — look again";
                return result;
            }
            try {
                store.advanceSpace(id, space.head, commit.getName());
            } catch (IllegalStateException e) {
                result.status = "MOVED";
                result.message = e.getMessage();
                return result;
            }
            result.status = "PULLED";
            return result;
        } finally {
            lifecycle.unlock();
        }
    }

    /**
     * Brings a checkout up to its space's saved version (the branch — the one place a space is
     * written) after it was saved elsewhere: on another instance, or by a colleague here. With no
     * unsaved changes the checkout just follows. With unsaved changes, they are merged over the saved
     * version — anything both changed is asked — and stay unsaved afterwards.
     */
    public Result refresh(WorkspaceId id, Map<String, Resolution> answers, boolean apply, String by)
            throws IOException, org.eclipse.jgit.api.errors.GitAPIException {
        Workspace space = null;
        for (Workspace w : store.workspaces()) {
            if (w.id.equals(id.branch())) {
                space = w;
            }
        }
        if (space == null) {
            throw new GitRuleStore.NoSuchSpace(id.branch());
        }
        if (sync.enabled()) {
            sync.run();
        }
        Map<String, Resolution> chosen = answers == null ? Collections.<String, Resolution>emptyMap() : answers;
        Result result = new Result();
        result.space = id.branch();
        result.target = id.branch();
        String saved = store.sharedHead(id);
        String here = space.head;
        result.tips.put(id.branch(), saved == null ? "" : saved);
        if (saved == null || saved.equals(here)) {
            result.status = "UP_TO_DATE";
            result.message = "this space is already at its newest saved version";
            return result;
        }
        boolean follows;
        try (Git git = Git.open(store.mainCheckout().toFile())) {
            follows = git.getRepository().resolve(here + "^{commit}") != null
                    && GitRuleStore.isAncestor(git.getRepository(), ObjectId.fromString(here), ObjectId.fromString(saved));
        }
        if (!space.dirty && follows) {
            result.status = store.refreshCheckout(id) ? "REFRESHED" : "MOVED";
            result.message = "REFRESHED".equals(result.status) ? null : "files in this space changed meanwhile; look again";
            return result;
        }
        ReentrantLock lifecycle = store.lifecycleLock();
        lifecycle.lock();
        try (Git git = Git.open(store.mainCheckout().toFile());
             ObjectInserter inserter = git.getRepository().newObjectInserter()) {
            Repository repository = git.getRepository();
            ObjectId head = ObjectId.fromString(here);
            Map<String, byte[]> working = store.workingFiles(id);
            // The unsaved changes, as a commit nobody will ever see on a branch: so they can be merged
            // like any other side, and named in a conflict as what they are.
            Map<String, byte[]> asChanges = new LinkedHashMap<>();
            for (String path : files(repository, head).keySet()) {
                if (!working.containsKey(path)) {
                    asChanges.put(path, null);
                }
            }
            asChanges.putAll(working);
            String instance = store.instance().isEmpty() ? "this instance" : store.instance();
            ObjectId ours = commitOn(repository, inserter, head, asChanges, "Unsaved changes on " + instance, by, head);
            ObjectId theirs = ObjectId.fromString(saved);

            Hop hop = new Hop();
            hop.into = id.branch();
            hop.from = "saved";
            hop.base = here;
            hop.ours = ours.getName();
            hop.theirs = saved;
            result.hops.add(hop);
            // Usually the checkout's version is where the saved one came from; when it is not (a local
            // version the remote never took), they are joined from where they split.
            ObjectId base = mergeBase(repository, head, theirs);
            hop.base = base == null ? here : base.getName();
            Map<String, byte[]> changes = mergeDomains(repository, hop, id.branch(), base, ours, theirs, chosen);
            attribute(repository, hop, id.branch(), base, ours, theirs);
            result.open = hop.conflicts.size();
            result.status = result.open > 0 ? "CONFLICTS" : "CLEAN";
            if (!apply || result.open > 0) {
                return result;
            }
            Map<String, byte[]> after = new TreeMap<>(working);
            for (Map.Entry<String, byte[]> e : changes.entrySet()) {
                if (e.getValue() == null) {
                    after.remove(e.getKey());
                } else {
                    after.put(e.getKey(), e.getValue());
                }
            }
            try {
                store.refreshWith(id, here, working, saved, after);
            } catch (IllegalStateException e) {
                result.status = "MOVED";
                result.message = e.getMessage();
                return result;
            }
            result.status = "REFRESHED";
            return result;
        } finally {
            lifecycle.unlock();
        }
    }

    // --- which commit made each side -------------------------------------------------------------

    /**
     * Names each side of every conflict by the commit that initiated it: walking that side's commits
     * since the two split, newest first, the first one whose change altered the contested element —
     * that cell, rule, column, case — not merely the newest commit touching the file.
     */
    private void attribute(Repository repository, Hop hop, String hopKey, ObjectId base, ObjectId ours, ObjectId theirs) throws IOException {
        for (Conflict c : hop.conflicts) {
            String prefix = hopKey + "|" + c.domain + "|";
            String rawId = c.id.startsWith(prefix) ? c.id.substring(prefix.length()) : c.id;
            List<Attribution> o = initiator(repository, base, ours, c, rawId);
            List<Attribution> t = initiator(repository, base, theirs, c, rawId);
            c.oursChanges = o.isEmpty() ? first(c.oursChanges) : o;
            c.theirsChanges = t.isEmpty() ? first(c.theirsChanges) : t;
        }
    }

    private static List<Attribution> first(List<Attribution> list) {
        return list == null || list.isEmpty() ? Collections.<Attribution>emptyList() : Collections.singletonList(list.get(0));
    }

    private List<Attribution> initiator(Repository repository, ObjectId base, ObjectId tip, Conflict c, String rawId) throws IOException {
        List<String> paths = Arrays.asList("dmn/" + c.domain + ".dmn", "yaml/" + c.domain + ".yaml");
        try (RevWalk walk = new RevWalk(repository)) {
            walk.markStart(walk.parseCommit(tip));
            if (base != null) {
                walk.markUninteresting(walk.parseCommit(base));
            }
            walk.setTreeFilter(org.eclipse.jgit.treewalk.filter.AndTreeFilter.create(
                    org.eclipse.jgit.treewalk.filter.PathFilterGroup.createFromStrings(paths),
                    org.eclipse.jgit.treewalk.filter.TreeFilter.ANY_DIFF));
            for (RevCommit commit : walk) {
                if (commit.getParentCount() != 1) {
                    continue; // landings and carries only bring a change; the commit that made it is further back
                }
                String now = fingerprint(repository, commit, c, rawId);
                String before = fingerprint(repository, commit.getParent(0), c, rawId);
                if (!now.equals(before)) {
                    return Collections.singletonList(new Attribution(commit.getName(), commit.getName().substring(0, 7),
                            commit.getShortMessage(), commit.getAuthorIdent().getName(),
                            Instant.ofEpochSecond(commit.getCommitTime()).toString()));
                }
            }
        }
        return Collections.emptyList();
    }

    /** The contested element as it stands at a commit. */
    private static String fingerprint(Repository repository, ObjectId commit, Conflict c, String rawId) throws IOException {
        Map<String, byte[]> files = files(repository, commit);
        String dmn = text(files.get("dmn/" + c.domain + ".dmn"));
        String yaml = text(files.get("yaml/" + c.domain + ".yaml"));
        if (c.kind.startsWith("TEST_") || rawId.startsWith("tests|")) {
            return limitium.art.camunda.evaluator.diff.TestSpecMerger.fingerprint(yaml, rawId);
        }
        if (c.kind.startsWith("DOMAIN_")) {
            return dmn + "\u0000" + yaml;
        }
        if (dmn.isEmpty() || "CONNECTION_LOST".equals(c.kind) || "RESULT_CLASH".equals(c.kind)) {
            return dmn;
        }
        Map<String, limitium.art.camunda.evaluator.diff.DecisionTableReader.Table> tables;
        try {
            tables = limitium.art.camunda.evaluator.diff.DecisionTableReader.read(dmn);
        } catch (RuntimeException e) {
            return dmn;
        }
        limitium.art.camunda.evaluator.diff.DecisionTableReader.Table t = tables.get(c.decisionId);
        if (t == null) {
            return "no table";
        }
        if (c.ruleId != null && !c.ruleId.isEmpty()) {
            for (limitium.art.camunda.evaluator.diff.DecisionTableReader.Rule rule : t.rules) {
                if (!rule.id.equals(c.ruleId)) {
                    continue;
                }
                if (c.columnId != null && !c.columnId.isEmpty()) {
                    for (int i = 0; i < t.inputs.size(); i++) {
                        if (c.columnId.equals(t.inputs.get(i).id)) {
                            return "in:" + (i < rule.inputEntries.size() ? rule.inputEntries.get(i) : "");
                        }
                    }
                    for (int i = 0; i < t.outputs.size(); i++) {
                        if (c.columnId.equals(t.outputs.get(i).id)) {
                            return "out:" + (i < rule.outputEntries.size() ? rule.outputEntries.get(i) : "");
                        }
                    }
                    return "no column";
                }
                return rule.inputEntries + "->" + rule.outputEntries + "|" + rule.description;
            }
            return "no rule";
        }
        if (c.columnId != null && !c.columnId.isEmpty()) {
            for (limitium.art.camunda.evaluator.diff.DecisionTableReader.Column col : t.inputs) {
                if (c.columnId.equals(col.id)) {
                    return col.expression + "|" + col.typeRef + "|" + col.label;
                }
            }
            for (limitium.art.camunda.evaluator.diff.DecisionTableReader.Column col : t.outputs) {
                if (c.columnId.equals(col.id)) {
                    return col.expression + "|" + col.typeRef + "|" + col.label;
                }
            }
            return "no column";
        }
        if ("HIT_POLICY".equals(c.kind)) {
            return String.valueOf(t.hitPolicy);
        }
        if ("TEXT".equals(c.kind)) {
            return String.valueOf(t.decisionName);
        }
        StringBuilder order = new StringBuilder(t.decisionName + "|" + t.hitPolicy + "|");
        for (limitium.art.camunda.evaluator.diff.DecisionTableReader.Rule rule : t.rules) {
            order.append(rule.id).append(rule.inputEntries).append(rule.outputEntries).append(';');
        }
        return order.toString();
    }

    /** The target line, every newer release line, then the newest work. */
    List<String> chain(Repository repository, String target) throws IOException {
        String main = store.properties().getMainBranch();
        List<String> chain = new ArrayList<>();
        chain.add(target);
        if (target.equals(main)) {
            return chain;
        }
        Matcher t = LINE.matcher(target);
        if (!t.matches()) {
            chain.add(main);
            return chain;
        }
        TreeMap<String, String> newer = new TreeMap<>();
        for (org.eclipse.jgit.lib.Ref ref : repository.getRefDatabase().getRefsByPrefix(Constants.R_HEADS + "release/")) {
            String branch = ref.getName().substring(Constants.R_HEADS.length());
            Matcher m = LINE.matcher(branch);
            if (!m.matches()) {
                continue;
            }
            int cmp = Integer.compare(Integer.parseInt(m.group(1)), Integer.parseInt(t.group(1)));
            if (cmp == 0) {
                cmp = Integer.compare(Integer.parseInt(m.group(2)), Integer.parseInt(t.group(2)));
            }
            if (cmp > 0) {
                newer.put(String.format("%06d.%06d", Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))), branch);
            }
        }
        chain.addAll(newer.values());
        chain.add(main);
        return chain;
    }

    // --- one step: every domain ----------------------------------------------------------------

    private Map<String, byte[]> mergeDomains(Repository repository, Hop hop, String hopKey, ObjectId base,
                                             ObjectId ours, ObjectId theirs, Map<String, Resolution> chosen) throws IOException {
        Map<String, byte[]> b = files(repository, base);
        Map<String, byte[]> o = files(repository, ours);
        Map<String, byte[]> t = files(repository, theirs);
        Set<String> names = new LinkedHashSet<>();
        for (Map<String, byte[]> side : Arrays.asList(o, t, b)) {
            for (String path : side.keySet()) {
                if (path.startsWith("dmn/") && path.endsWith(".dmn")) {
                    names.add(path.substring(4, path.length() - 4));
                }
            }
        }
        Map<String, byte[]> changes = new LinkedHashMap<>();
        for (String domain : names) {
            String dmn = "dmn/" + domain + ".dmn";
            String yaml = "yaml/" + domain + ".yaml";
            DomainChange change = new DomainChange();
            change.name = domain;
            String prefix = hopKey + "|" + domain + "|";
            Map<String, Resolution> mine = new LinkedHashMap<>();
            for (Map.Entry<String, Resolution> e : chosen.entrySet()) {
                if (e.getKey().startsWith(prefix)) {
                    mine.put(e.getKey().substring(prefix.length()), e.getValue());
                }
            }
            boolean inB = b.containsKey(dmn);
            boolean inO = o.containsKey(dmn);
            boolean inT = t.containsKey(dmn);
            ChangeAttribution attribution = attribution(repository, base, ours, theirs, Arrays.asList(dmn, yaml));

            if (!inO && !inT) {
                continue;
            }
            if (inO != inT) {
                boolean oursHas = inO;
                Map<String, byte[]> has = oursHas ? o : t;
                boolean untouched = inB && same(has.get(dmn), b.get(dmn)) && same(has.get(yaml), b.get(yaml));
                if (!inB) {
                    change.change = oursHas ? "unchanged" : "added";
                    if (!oursHas) {
                        put(changes, dmn, t.get(dmn));
                        put(changes, yaml, t.get(yaml));
                    }
                } else if (untouched) {
                    change.change = oursHas ? "removed" : "unchanged";
                    if (oursHas) {
                        changes.put(dmn, null);
                        changes.put(yaml, null);
                    }
                } else {
                    // Removed on one side, changed on the other.
                    String cid = "domain";
                    Resolution r = mine.get(cid);
                    boolean keep = oursHas ? r != Resolution.THEIRS : r == Resolution.THEIRS;
                    if (r == null) {
                        keep = oursHas;
                        hop.conflicts.add(conflict(hop, domain, prefix + cid, MergeConflict.Kind.DOMAIN_DELETE_MODIFY.name(),
                                "the domain " + domain + " was removed on one side and changed on the other",
                                oursHas ? Arrays.asList(choice(Resolution.OURS, "keep " + domain + ", with the changes"), choice(Resolution.THEIRS, "remove " + domain))
                                        : Arrays.asList(choice(Resolution.OURS, "keep " + domain + " removed"), choice(Resolution.THEIRS, "keep " + domain + ", with the changes")),
                                "removing a domain stops it running everywhere this version is deployed", attribution));
                        change.conflicts++;
                    }
                    if (keep && !oursHas) {
                        put(changes, dmn, t.get(dmn));
                        put(changes, yaml, t.get(yaml));
                    } else if (!keep && oursHas) {
                        changes.put(dmn, null);
                        changes.put(yaml, null);
                    }
                    change.change = keep ? "changed" : "removed";
                }
                fillPreview(change, changes, o, dmn, yaml);
                hop.domains.add(change);
                continue;
            }
            if (!inB) {
                if (same(o.get(dmn), t.get(dmn)) && same(o.get(yaml), t.get(yaml))) {
                    change.change = "unchanged";
                } else {
                    String cid = "domain";
                    Resolution r = mine.get(cid);
                    if (r == Resolution.THEIRS) {
                        put(changes, dmn, t.get(dmn));
                        put(changes, yaml, t.get(yaml));
                    } else if (r == null) {
                        hop.conflicts.add(conflict(hop, domain, prefix + cid, MergeConflict.Kind.DOMAIN_ADD_ADD.name(),
                                "a domain called " + domain + " was created on both sides, differently",
                                Arrays.asList(choice(Resolution.OURS, "keep " + lineName(hop.into) + "’s"), choice(Resolution.THEIRS, "take " + hop.from + "’s")),
                                "its name is the key the running app reads it by, so only one can exist", attribution));
                        change.conflicts++;
                    }
                    change.change = "merged";
                }
                fillPreview(change, changes, o, dmn, yaml);
                hop.domains.add(change);
                continue;
            }
            // In all three: the table and the tests merge on their own.
            boolean dmnChanged = false;
            String dmnB = text(b.get(dmn));
            String dmnO = text(o.get(dmn));
            String dmnT = text(t.get(dmn));
            if (same(o.get(dmn), t.get(dmn)) || same(t.get(dmn), b.get(dmn))) {
                change.previewDmn = dmnO;
            } else if (same(o.get(dmn), b.get(dmn))) {
                put(changes, dmn, t.get(dmn));
                change.previewDmn = dmnT;
                dmnChanged = true;
            } else {
                MergeResult r = DmnMerger.merge(dmnB, dmnO, dmnT, mine, attribution);
                for (MergeConflict c : r.conflicts) {
                    hop.conflicts.add(conflict(hop, domain, prefix, c));
                    change.conflicts++;
                }
                changes.put(dmn, r.previewXml.getBytes(StandardCharsets.UTF_8));
                change.previewDmn = r.previewXml;
                dmnChanged = true;
                change.change = "merged";
            }
            String yB = text(b.get(yaml));
            String yO = text(o.get(yaml));
            String yT = text(t.get(yaml));
            boolean yamlChanged = false;
            if (yO.equals(yT) || yT.equals(yB)) {
                change.previewYaml = yO;
            } else if (yO.equals(yB)) {
                put(changes, yaml, t.get(yaml));
                change.previewYaml = yT;
                yamlChanged = true;
            } else {
                MergeResult r = TestSpecMerger.merge(yB, yO, yT, mine);
                for (MergeConflict c : r.conflicts) {
                    Conflict added = conflict(hop, domain, prefix, c);
                    added.oursChanges = attribution.forConflict(added.id, Resolution.OURS);
                    added.theirsChanges = attribution.forConflict(added.id, Resolution.THEIRS);
                    hop.conflicts.add(added);
                    change.conflicts++;
                }
                changes.put(yaml, r.previewXml.getBytes(StandardCharsets.UTF_8));
                change.previewYaml = r.previewXml;
                yamlChanged = true;
                change.change = "merged";
            }
            if (change.change == null) {
                change.change = dmnChanged || yamlChanged ? "changed" : "unchanged";
            }
            hop.domains.add(change);
        }
        return changes;
    }

    private static void fillPreview(DomainChange change, Map<String, byte[]> changes, Map<String, byte[]> ours, String dmn, String yaml) {
        byte[] d = changes.containsKey(dmn) ? changes.get(dmn) : ours.get(dmn);
        byte[] y = changes.containsKey(yaml) ? changes.get(yaml) : ours.get(yaml);
        change.previewDmn = d == null ? null : new String(d, StandardCharsets.UTF_8);
        change.previewYaml = y == null ? null : new String(y, StandardCharsets.UTF_8);
    }

    private static void put(Map<String, byte[]> changes, String path, byte[] content) {
        changes.put(path, content);
    }

    private static boolean same(byte[] a, byte[] b) {
        return Arrays.equals(a, b);
    }

    private static String text(byte[] bytes) {
        return bytes == null ? "" : new String(bytes, StandardCharsets.UTF_8);
    }

    private static Choice choice(Resolution key, String label) {
        return new Choice(key, label, null);
    }

    private Conflict conflict(Hop hop, String domain, String id, String kind, String label, List<Choice> choices,
                              String detail, ChangeAttribution attribution) {
        Conflict c = new Conflict();
        c.id = id;
        c.hop = hop.into;
        c.domain = domain;
        c.kind = kind;
        c.tier = MergeConflict.Tier.SEMANTIC.name();
        c.label = label;
        c.choices = choices;
        c.detail = detail;
        c.oursChanges = attribution.forConflict(id, Resolution.OURS);
        c.theirsChanges = attribution.forConflict(id, Resolution.THEIRS);
        return c;
    }

    private static Conflict conflict(Hop hop, String domain, String prefix, MergeConflict m) {
        Conflict c = new Conflict();
        c.id = prefix + m.id;
        c.hop = hop.into;
        c.domain = domain;
        c.kind = m.kind.name();
        c.tier = m.tier.name();
        c.decisionId = m.decisionId;
        c.ruleId = m.ruleId;
        c.columnId = m.columnId;
        c.label = m.label;
        c.base = m.base;
        c.ours = m.ours;
        c.theirs = m.theirs;
        c.choices = m.choices;
        c.detail = m.detail;
        c.oursChanges = m.oursChanges;
        c.theirsChanges = m.theirsChanges;
        return c;
    }

    /**
     * The commits on each side, since they split, that touched this domain — so a conflict says whose
     * change it collides with and why. Per domain, most recent first.
     */
    private ChangeAttribution attribution(Repository repository, ObjectId base, ObjectId ours, ObjectId theirs, List<String> paths) throws IOException {
        final List<Attribution> o = touched(repository, base, ours, paths);
        final List<Attribution> t = touched(repository, base, theirs, paths);
        return new ChangeAttribution() {
            public List<Attribution> forConflict(String conflictId, Resolution side) {
                return side == Resolution.THEIRS ? t : o;
            }
        };
    }

    private static List<Attribution> touched(Repository repository, ObjectId base, ObjectId tip, List<String> paths) throws IOException {
        List<Attribution> result = new ArrayList<>();
        try (RevWalk walk = new RevWalk(repository)) {
            walk.markStart(walk.parseCommit(tip));
            if (base != null) {
                walk.markUninteresting(walk.parseCommit(base));
            }
            walk.setTreeFilter(org.eclipse.jgit.treewalk.filter.AndTreeFilter.create(
                    org.eclipse.jgit.treewalk.filter.PathFilterGroup.createFromStrings(paths),
                    org.eclipse.jgit.treewalk.filter.TreeFilter.ANY_DIFF));
            for (RevCommit c : walk) {
                if (c.getParentCount() > 1) {
                    continue; // landings and carries: the change itself is in the commits they bring
                }
                result.add(new Attribution(c.getName(), c.getName().substring(0, 7), c.getShortMessage(),
                        c.getAuthorIdent().getName(), Instant.ofEpochSecond(c.getCommitTime()).toString()));
                if (result.size() >= 5) {
                    break;
                }
            }
        }
        return result;
    }

    // --- git plumbing --------------------------------------------------------------------------

    private static ObjectId mergeBase(Repository repository, ObjectId a, ObjectId b) throws IOException {
        try (RevWalk walk = new RevWalk(repository)) {
            walk.setRevFilter(RevFilter.MERGE_BASE);
            walk.markStart(walk.parseCommit(a));
            walk.markStart(walk.parseCommit(b));
            RevCommit base = walk.next();
            return base == null ? null : base.getId();
        }
    }

    /** Every file under dmn/ and yaml/ at a commit; empty for none. */
    private static Map<String, byte[]> files(Repository repository, ObjectId commitId) throws IOException {
        Map<String, byte[]> files = new TreeMap<>();
        if (commitId == null) {
            return files;
        }
        try (RevWalk walk = new RevWalk(repository); TreeWalk tree = new TreeWalk(repository)) {
            tree.addTree(walk.parseCommit(commitId).getTree());
            tree.setRecursive(true);
            while (tree.next()) {
                String path = tree.getPathString();
                if ((path.startsWith("dmn/") || path.startsWith("yaml/")) && !path.endsWith(".gitkeep")) {
                    files.put(path, repository.open(tree.getObjectId(0)).getBytes());
                }
            }
        }
        return files;
    }

    /** The line's tree with this step's changes, as a merge commit of the line and what came in. */
    private static ObjectId commit(Repository repository, ObjectInserter inserter, ObjectId ours, ObjectId theirs,
                                   Map<String, byte[]> changes, String message, String by) throws IOException {
        return commitOn(repository, inserter, ours, changes, message, by, ours, theirs);
    }

    /** A commit whose tree is {@code tree}'s with {@code changes} applied (null removes), with the given parents. */
    private static ObjectId commitOn(Repository repository, ObjectInserter inserter, ObjectId ours,
                                     Map<String, byte[]> changes, String message, String by, ObjectId... parents) throws IOException {
        DirCache index = DirCache.newInCore();
        DirCacheBuilder builder = index.builder();
        try (RevWalk walk = new RevWalk(repository); TreeWalk tree = new TreeWalk(repository)) {
            tree.addTree(walk.parseCommit(ours).getTree());
            tree.setRecursive(true);
            while (tree.next()) {
                String path = tree.getPathString();
                if (changes.containsKey(path)) {
                    continue;
                }
                DirCacheEntry entry = new DirCacheEntry(path);
                entry.setFileMode(tree.getFileMode(0));
                entry.setObjectId(tree.getObjectId(0));
                builder.add(entry);
            }
        }
        for (Map.Entry<String, byte[]> change : changes.entrySet()) {
            if (change.getValue() == null) {
                continue; // removed
            }
            DirCacheEntry entry = new DirCacheEntry(change.getKey());
            entry.setFileMode(FileMode.REGULAR_FILE);
            entry.setObjectId(inserter.insert(Constants.OBJ_BLOB, change.getValue()));
            builder.add(entry);
        }
        builder.finish();
        ObjectId tree = index.writeTree(inserter);
        PersonIdent who = new PersonIdent(by == null ? "camunda-eval" : by, "camunda-eval@localhost");
        CommitBuilder commit = new CommitBuilder();
        commit.setTreeId(tree);
        commit.setParentIds(parents);
        commit.setAuthor(who);
        commit.setCommitter(who);
        commit.setMessage(message);
        ObjectId id = inserter.insert(commit);
        inserter.flush();
        return id;
    }

    private String lineName(String branch) {
        return branch.equals(store.properties().getMainBranch()) ? branch : branch.replace("release/", "release ");
    }
}
