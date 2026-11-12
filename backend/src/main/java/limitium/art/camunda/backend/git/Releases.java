package limitium.art.camunda.backend.git;

import com.fasterxml.jackson.databind.ObjectMapper;
import limitium.art.camunda.backend.lifecycle.TestGate;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.TagBuilder;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevObject;
import org.eclipse.jgit.revwalk.RevTag;
import org.eclipse.jgit.revwalk.RevWalk;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Release lines and the versions made on them.
 *
 * <p>A version is a tag, and it is made only through here: every domain's tests run at the commit
 * first, and one failure refuses it. The verdict is written into the tag, and the tag is created by
 * compare-and-swap here and on the remote together — a version name means one commit, forever, on
 * every instance. A tag made some other way (by hand with git) is shown as unverified; deploying it
 * still runs every test first, so nothing failing ever runs.
 *
 * <p>Numbering: releasing the newest work opens the next line ({@code release/1.6}) and makes its
 * first version ({@code v1.6.0}); releasing a line makes its next patch ({@code v1.5.3}).
 */
public class Releases {

    static final String VERDICT = "camunda-eval-verified: ";
    private static final ObjectMapper JSON = new ObjectMapper();

    public static class Refused extends RuntimeException {
        public final String code;
        public final Object details;

        Refused(String code, String message, Object details) {
            super(message);
            this.code = code;
            this.details = details;
        }
    }

    public static class Version {
        public String name;
        public String commit;
        public String when;
        public String by;
        public String notes;
        /** The release branch it was made on. */
        public String branch;
        /** Made here, with every test green at its commit, recorded in the tag. */
        public boolean verified;
        public Map<String, Object> verdict;
    }

    public static class Line {
        public String branch;
        public boolean main;
        public String tip;
        public String tipMessage;
        public String tipAuthor;
        public String tipWhen;
        /** Newest first. */
        public List<Version> versions = new ArrayList<>();
        /** Commits on the line since its newest version (all of them when there is none). */
        public int unreleased;
        /** The tag suggested for it next: after its newest tag, or its own number when it has none. */
        public String nextVersion;
        /** The release branch suggested when one is created from it. */
        public String nextBranch;
        /** The next newer release branch (open or deleted): this branch's tags are numbered below it. */
        public String newerBranch;
        /** For the newest work: the line releasing it opens. */
        public String opensLine;
        /** main only: the newest line, open or closed, and where it left main; the next line opens from a newer commit of main. */
        public String opensAfterLine;
        public String opensAfter;
        /** Newer lines and main that do not have this line's newest commit yet: its fixes stopped short. */
        public List<String> missingFrom = new ArrayList<>();
    }

    private final GitRuleStore store;
    private final RemoteSync sync;
    private final TestGate gate;

    public Releases(GitRuleStore store, RemoteSync sync, TestGate gate) {
        this.store = store;
        this.sync = sync;
        this.gate = gate;
    }

    public List<Line> lines() throws IOException {
        List<Line> lines = new ArrayList<>();
        try (Git git = Git.open(store.mainCheckout().toFile()); RevWalk walk = new RevWalk(git.getRepository())) {
            Repository repository = git.getRepository();
            List<Version> all = versions(repository, walk);
            String mainBranch = store.properties().getMainBranch();
            List<String> branches = new ArrayList<>();
            branches.add(mainBranch);
            List<String> releaseLines = new ArrayList<>();
            for (Ref ref : repository.getRefDatabase().getRefsByPrefix(Constants.R_HEADS + ReleaseNames.PREFIX)) {
                String branch = ref.getName().substring(Constants.R_HEADS.length());
                if (ReleaseNames.isBranch(branch)) {
                    releaseLines.add(branch);
                }
            }
            releaseLines.sort(Comparator.comparing((String b) -> ReleaseNames.branchKey(b)).reversed());
            branches.addAll(releaseLines);
            java.util.TreeMap<String, java.util.Map.Entry<String, ObjectId>> starts = lineStarts(repository, all);
            for (String branch : branches) {
                ObjectId tipId = repository.resolve(Constants.R_HEADS + branch);
                if (tipId == null) {
                    continue;
                }
                RevCommit tip = walk.parseCommit(tipId);
                Line line = new Line();
                line.branch = branch;
                line.main = branch.equals(mainBranch);
                line.tip = tip.getName();
                line.tipMessage = tip.getShortMessage();
                line.tipAuthor = tip.getAuthorIdent().getName();
                line.tipWhen = Instant.ofEpochSecond(tip.getCommitTime()).toString();
                if (line.main) {
                    // main is never tagged: the next release is stabilised on its own line first.
                    line.opensLine = nextBranch(starts, null);
                    line.nextBranch = line.opensLine;
                    if (!starts.isEmpty()) {
                        ObjectId start = startOf(repository, starts, starts.lastKey(), tip);
                        line.opensAfterLine = starts.lastEntry().getValue().getKey();
                        line.opensAfter = start == null ? null : start.getName();
                    }
                    line.nextVersion = null;
                    for (Version v : all) {
                        if (isAncestor(repository, v.commit, tip)) {
                            line.versions.add(v);
                        }
                    }
                } else {
                    for (Version v : all) {
                        if (branch.equals(v.branch)) {
                            line.versions.add(v);
                        }
                    }
                    int[] own = ReleaseNames.ofBranch(branch);
                    if (line.versions.isEmpty()) {
                        line.nextVersion = "v" + ReleaseNames.show(own);
                    } else {
                        int[] last = ReleaseNames.ofTag(line.versions.get(0).name);
                        line.nextVersion = "v" + last[0] + "." + last[1] + "." + (last[2] + 1);
                    }
                    java.util.Map.Entry<String, java.util.Map.Entry<String, ObjectId>> newer = starts.higherEntry(ReleaseNames.branchKey(branch));
                    line.newerBranch = newer == null ? null : newer.getValue().getKey();
                    line.nextBranch = nextBranch(starts, branch);
                }
                if (!line.main) {
                    List<String> after = new ArrayList<>();
                    for (String other : releaseLines) {
                        if (ReleaseNames.branchKey(other).compareTo(ReleaseNames.branchKey(branch)) > 0) {
                            after.add(other);
                        }
                    }
                    after.add(mainBranch);
                    for (String other : after) {
                        ObjectId otherTip = repository.resolve(Constants.R_HEADS + other);
                        if (otherTip != null && !GitRuleStore.isAncestor(repository, tip, walk.parseCommit(otherTip))) {
                            line.missingFrom.add(other);
                        }
                    }
                }
                line.unreleased = line.versions.isEmpty()
                        ? count(repository, tip, null)
                        : count(repository, tip, line.versions.get(0).commit);
                lines.add(line);
            }
        }
        return lines;
    }

    /**
     * Makes the next version of a line — only when every domain's tests pass at its newest commit.
     * With {@code check}, runs the tests and says what would be made, changing nothing.
     */
    public Map<String, Object> release(String branch, String notes, String by, boolean check) throws IOException {
        return release(branch, null, notes, by, check);
    }

    /**
     * Makes the next version of a line at one of its commits ({@code at}; its newest when null): a
     * commit on the line, newer than its last version — versions only ever move forward.
     */
    public Map<String, Object> release(String branch, String at, String notes, String by, boolean check) throws IOException {
        return release(branch, at, null, notes, by, check);
    }

    /**
     * Tags a commit of a release branch as {@code name} (vX.Y.Z; the suggested next tag when blank).
     * A tag follows version order: after the branch's newest tag, from the branch's own number, and
     * below the next newer release branch — so v1.0.2 goes on release/1.0, never on release/1.1.
     */
    public Map<String, Object> release(String branch, String at, String name, String notes, String by, boolean check) throws IOException {
        if (sync.enabled()) {
            sync.run(); // a version made on another instance meanwhile must be seen before numbering
        }
        ReentrantLock lifecycle = store.lifecycleLock();
        lifecycle.lock();
        try {
            Line line = null;
            for (Line l : lines()) {
                if (l.branch.equals(branch)) {
                    line = l;
                }
            }
            if (line == null) {
                throw new Refused("NO_SUCH_LINE", "there is no branch " + branch, null);
            }
            if (line.main) {
                throw new Refused("NOT_A_RELEASE_LINE", "tags are made on release branches; create a release branch from "
                        + branch + " to stabilise it, then tag it there", null);
            }
            String commit = line.tip;
            if (at != null && !at.isBlank()) {
                try (Git git = Git.open(store.mainCheckout().toFile()); RevWalk walk = new RevWalk(git.getRepository())) {
                    ObjectId id = git.getRepository().resolve(at + "^{commit}");
                    if (id == null) {
                        throw new Refused("NO_SUCH_COMMIT", "there is no commit " + at, null);
                    }
                    if (!GitRuleStore.isAncestor(git.getRepository(), id, walk.parseCommit(ObjectId.fromString(line.tip)))) {
                        throw new Refused("NOT_ON_LINE", "that commit is not on " + branch, null);
                    }
                    if (!line.versions.isEmpty()) {
                        String last = line.versions.get(0).commit;
                        if (last.equals(id.getName()) || !GitRuleStore.isAncestor(git.getRepository(), ObjectId.fromString(last), walk.parseCommit(id))) {
                            throw new Refused("NOT_NEWER", "that commit is not newer than " + line.versions.get(0).name
                                    + "; a tag only comes after the one before it", null);
                        }
                    }
                    commit = id.getName();
                }
            } else if (line.unreleased == 0 && !line.versions.isEmpty()) {
                throw new Refused("NOTHING_NEW", "the newest commit on " + branch + " is already tagged " + line.versions.get(0).name, null);
            }
            String tagName = name == null || name.isBlank() ? line.nextVersion : ReleaseNames.tagFrom(name);
            checkTagName(line, name, tagName);
            TestGate.Report report = gate.run(commit);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("line", branch);
            result.put("version", tagName);
            result.put("commit", commit);
            result.put("tests", report);
            if (!report.passed) {
                throw new Refused("TESTS_FAILED", report.failed + (report.failed == 1 ? " test fails" : " tests fail")
                        + " at " + (commit.equals(line.tip) ? "the newest commit of " + branch : commit.substring(0, 7)) + "; no tag was made", report);
            }
            if (check) {
                return result;
            }
            ObjectId tip = ObjectId.fromString(commit);
            ObjectId tag;
            try (Git git = Git.open(store.mainCheckout().toFile()); ObjectInserter inserter = git.getRepository().newObjectInserter()) {
                Map<String, Object> verdict = new LinkedHashMap<>(report.summary());
                verdict.put("at", Instant.now().toString());
                verdict.put("by", by);
                TagBuilder builder = new TagBuilder();
                builder.setTag(tagName);
                builder.setObjectId(tip, Constants.OBJ_COMMIT);
                builder.setTagger(new PersonIdent(by == null ? store.properties().getAuthorName() : by, store.properties().getAuthorEmail()));
                builder.setMessage(ReleaseNames.annotation(notes == null || notes.isBlank() ? tagName : notes.trim(), branch)
                        + VERDICT + JSON.writeValueAsString(verdict) + "\n");
                tag = inserter.insert(builder);
                inserter.flush();
            }
            List<RefTransaction.Move> moves = new ArrayList<>();
            moves.add(new RefTransaction.Move(Constants.R_TAGS + tagName, null, tag));
            try {
                RefTransaction.apply(store, moves);
            } catch (RefTransaction.Refused e) {
                throw new Refused("MOVED".equals(e.code) || "REMOTE_REFUSED".equals(e.code) ? "VERSION_TAKEN" : e.code,
                        "MOVED".equals(e.code) || "REMOTE_REFUSED".equals(e.code)
                                ? tagName + " was just made elsewhere; nothing was changed — look again" : e.getMessage(), null);
            }
            return result;
        } finally {
            lifecycle.unlock();
        }
    }

    /** A tag name follows version order on its branch; said plainly when it does not. */
    private void checkTagName(Line line, String typed, String tagName) throws IOException {
        if (tagName == null) {
            throw new Refused("BAD_VERSION", "“" + typed.trim() + "” is not a version: a tag is numbered like v1.2.3", null);
        }
        if (store.resolve(Constants.R_TAGS + tagName) != null) {
            throw new Refused("VERSION_TAKEN", tagName + " exists already", null);
        }
        int[] n = ReleaseNames.ofTag(tagName);
        if (!line.versions.isEmpty() && ReleaseNames.compare(n, ReleaseNames.ofTag(line.versions.get(0).name)) <= 0) {
            throw new Refused("NOT_AFTER_LAST_TAG", tagName + " is not after " + line.versions.get(0).name + ", the newest tag on " + line.branch, null);
        }
        int[] own = ReleaseNames.ofBranch(line.branch);
        if (ReleaseNames.compare(n, own) < 0) {
            throw new Refused("BELOW_BRANCH", "tags on " + line.branch + " start at v" + ReleaseNames.show(own), null);
        }
        // Below the next newer release branch, and within the branch's own minor: release/1.0 holds 1.0.x.
        int[] nextMinor = {own[0], own[1] + 1, 0};
        boolean newerFirst = line.newerBranch != null && ReleaseNames.compare(ReleaseNames.ofBranch(line.newerBranch), nextMinor) < 0;
        int[] limit = newerFirst ? ReleaseNames.ofBranch(line.newerBranch) : nextMinor;
        if (ReleaseNames.compare(n, limit) >= 0) {
            throw new Refused("ABOVE_BRANCH", "tags on " + line.branch + " are numbered below v" + ReleaseNames.show(limit)
                    + (newerFirst ? ", where " + line.newerBranch + " starts" : "; a newer minor needs its own release branch"), null);
        }
    }

    public Map<String, Object> openLine(String at, String number, String by) throws IOException {
        return openLine(at, number, null, by);
    }

    /**
     * Creates a release branch at a commit of main or of a release branch ({@code from}, the branch the
     * commit was picked on): where that release is stabilised and later fixed. {@code number} is "X.Y" or
     * "X.Y.Z"; the suggested one when blank. It follows version order: above the release branch it is
     * created from — from main, above every release branch — and it starts after the one below it.
     */
    public Map<String, Object> openLine(String at, String number, String from, String by) throws IOException {
        if (sync.enabled()) {
            sync.run();
        }
        ReentrantLock lifecycle = store.lifecycleLock();
        lifecycle.lock();
        try (Git git = Git.open(store.mainCheckout().toFile()); RevWalk walk = new RevWalk(git.getRepository())) {
            Repository repository = git.getRepository();
            String mainBranch = store.properties().getMainBranch();
            ObjectId id = repository.resolve((at == null || at.isBlank() ? Constants.R_HEADS + mainBranch : at) + "^{commit}");
            if (id == null) {
                throw new Refused("NO_SUCH_COMMIT", "there is no commit " + at, null);
            }
            ObjectId mainTip = repository.resolve(Constants.R_HEADS + mainBranch);
            java.util.TreeMap<String, java.util.Map.Entry<String, ObjectId>> starts = lineStarts(repository, versions(repository, walk));
            boolean fromRelease = ReleaseNames.isBranch(from);
            ObjectId sourceTip = repository.resolve(Constants.R_HEADS + (fromRelease ? from : mainBranch));
            if (sourceTip == null || !GitRuleStore.isAncestor(repository, id, walk.parseCommit(sourceTip))) {
                throw new Refused("NOT_ON_MAIN", "that commit is not on " + (fromRelease ? from : mainBranch), null);
            }
            String typed = number == null || number.isBlank() ? null : number.trim().replaceFirst("^" + ReleaseNames.PREFIX, "").replaceFirst("^v", "");
            String branch = typed == null ? nextBranch(starts, fromRelease ? from : null) : ReleaseNames.PREFIX + typed;
            int[] n = ReleaseNames.ofBranch(branch);
            if (n == null) {
                throw new Refused("BAD_NUMBER", "“" + number.trim() + "” is not a version: a release branch is numbered like 1.2 or 1.2.3", null);
            }
            String key = ReleaseNames.branchKey(branch);
            if (starts.containsKey(key)) {
                String same = starts.get(key).getKey();
                throw repository.resolve(Constants.R_HEADS + same) != null
                        ? new Refused("LINE_EXISTS", same + " exists already", null)
                        : new Refused("RELEASED_BEFORE", same + " was tagged before and deleted; start a fix from one of its tags to create it again", null);
            }
            // Version order: above what it is created from — from main, above every release branch — so a
            // fix merged forward always moves into newer work.
            if (fromRelease && ReleaseNames.compare(n, ReleaseNames.ofBranch(from)) <= 0) {
                throw new Refused("NOT_AFTER_SOURCE", "a release branch created from " + from + " is numbered after "
                        + ReleaseNames.ofBranch(from)[0] + "." + ReleaseNames.ofBranch(from)[1]
                        + (ReleaseNames.ofBranch(from)[2] == 0 ? "" : "." + ReleaseNames.ofBranch(from)[2]), null);
            }
            if (!fromRelease && !starts.isEmpty() && starts.lastKey().compareTo(key) > 0) {
                throw new Refused("OUT_OF_ORDER", starts.lastEntry().getValue().getKey()
                        + " exists already; a release branch created from " + mainBranch + " is numbered after it", null);
            }
            java.util.Map.Entry<String, java.util.Map.Entry<String, ObjectId>> before = starts.lowerEntry(key);
            if (before != null) {
                ObjectId fork = startOf(repository, starts, before.getKey(), mainTip);
                if (fork != null && (fork.equals(id) || !GitRuleStore.isAncestor(repository, fork, walk.parseCommit(id)))) {
                    throw new Refused("NOT_AFTER_PREVIOUS", branch + " must start after "
                            + before.getValue().getKey() + " did (at " + fork.getName().substring(0, 7) + "); pick a newer commit of "
                            + before.getValue().getKey() + " or of main", null);
                }
            }
            try {
                RefTransaction.apply(store, java.util.Collections.singletonList(new RefTransaction.Move(Constants.R_HEADS + branch, null, id)));
            } catch (RefTransaction.Refused e) {
                throw new Refused("LINE_EXISTS", branch + " was just created elsewhere; look again", null);
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("line", branch);
            result.put("commit", id.getName());
            result.put("nextVersion", "v" + ReleaseNames.show(n));
            return result;
        } finally {
            lifecycle.unlock();
        }
    }

    /** Every release line by its number, open or closed (a closed one is still where its versions are): its branch and a commit on it. */
    private static java.util.TreeMap<String, java.util.Map.Entry<String, ObjectId>> lineStarts(Repository repository, List<Version> versions) throws IOException {
        java.util.TreeMap<String, java.util.Map.Entry<String, ObjectId>> starts = new java.util.TreeMap<>();
        for (Ref ref : repository.getRefDatabase().getRefsByPrefix(Constants.R_HEADS + ReleaseNames.PREFIX)) {
            String branch = ref.getName().substring(Constants.R_HEADS.length());
            if (ReleaseNames.isBranch(branch)) {
                starts.put(ReleaseNames.branchKey(branch), new java.util.AbstractMap.SimpleImmutableEntry<>(branch, ref.getObjectId()));
            }
        }
        for (Version v : versions) {
            if (v.branch != null) {
                starts.putIfAbsent(ReleaseNames.branchKey(v.branch), new java.util.AbstractMap.SimpleImmutableEntry<>(v.branch, ObjectId.fromString(v.commit)));
            }
        }
        return starts;
    }

    /**
     * The release branch suggested next: from main, the next minor after the newest release branch
     * (1.0 when there is none); from a release branch, its next minor when free and nothing sits
     * between, else its next patch — release/1.0 suggests 1.1, or 1.0.1 once 1.1 exists.
     */
    private static String nextBranch(java.util.TreeMap<String, java.util.Map.Entry<String, ObjectId>> starts, String from) {
        if (from == null) {
            if (starts.isEmpty()) {
                return ReleaseNames.PREFIX + "1.0";
            }
            int[] newest = ReleaseNames.ofBranch(starts.lastEntry().getValue().getKey());
            return ReleaseNames.PREFIX + newest[0] + "." + (newest[1] + 1);
        }
        int[] n = ReleaseNames.ofBranch(from);
        int[] minor = {n[0], n[1] + 1, 0};
        java.util.Map.Entry<String, java.util.Map.Entry<String, ObjectId>> newer = starts.higherEntry(ReleaseNames.branchKey(from));
        if (newer == null || ReleaseNames.compare(ReleaseNames.ofBranch(newer.getValue().getKey()), minor) > 0) {
            return ReleaseNames.PREFIX + minor[0] + "." + minor[1];
        }
        int[] patch = {n[0], n[1], n[2] + 1};
        while (starts.containsKey(ReleaseNames.key(patch))) {
            patch[2]++;
        }
        return ReleaseNames.PREFIX + ReleaseNames.show(patch);
    }

    /** main's own history: its commits following first parents only, not what was merged into it. */
    private static java.util.Set<ObjectId> firstParents(Repository repository, ObjectId tip) throws IOException {
        java.util.Set<ObjectId> chain = new java.util.HashSet<>();
        try (RevWalk walk = new RevWalk(repository)) {
            RevCommit c = walk.parseCommit(tip);
            while (c != null) {
                chain.add(c.copy());
                c = c.getParentCount() == 0 ? null : walk.parseCommit(c.getParent(0));
            }
        }
        return chain;
    }

    /**
     * Where a line started: following its own first parents back to the history of main or of an older
     * line, whichever it was opened from. (A merge-base would not do: once a line's fixes are carried
     * forward, the newer lines and main contain its tip.)
     */
    private static ObjectId startOf(Repository repository, java.util.TreeMap<String, java.util.Map.Entry<String, ObjectId>> starts,
                                    String key, ObjectId mainTip) throws IOException {
        java.util.Set<ObjectId> older = mainTip == null ? new java.util.HashSet<>() : firstParents(repository, mainTip);
        for (java.util.Map.Entry<String, ObjectId> line : starts.headMap(key, false).values()) {
            older.addAll(firstParents(repository, line.getValue()));
        }
        return forkPoint(repository, starts.get(key).getValue(), older);
    }

    /** The first commit of a line's own first parents that is also in the given history. */
    private static ObjectId forkPoint(Repository repository, ObjectId onLine, java.util.Set<ObjectId> history) throws IOException {
        try (RevWalk walk = new RevWalk(repository)) {
            RevCommit c = walk.parseCommit(onLine);
            while (c != null) {
                if (history.contains(c)) {
                    return c.copy();
                }
                c = c.getParentCount() == 0 ? null : walk.parseCommit(c.getParent(0));
            }
        }
        return null;
    }

    // --- reading versions --------------------------------------------------------------------

    private List<Version> versions(Repository repository, RevWalk walk) throws IOException {
        List<Version> versions = new ArrayList<>();
        for (Ref ref : repository.getRefDatabase().getRefsByPrefix(Constants.R_TAGS)) {
            String name = ref.getName().substring(Constants.R_TAGS.length());
            if (!ReleaseNames.isTag(name)) {
                continue;
            }
            RevObject object = walk.parseAny(ref.getObjectId());
            Version v = new Version();
            v.name = name;
            v.branch = ReleaseNames.branchOfTag(name, null);
            if (object instanceof RevTag) {
                RevTag tag = (RevTag) object;
                walk.parseBody(tag);
                RevObject target = walk.peel(tag);
                v.commit = target.getName();
                v.when = tag.getTaggerIdent() == null ? null : tag.getTaggerIdent().getWhenAsInstant().toString();
                v.by = tag.getTaggerIdent() == null ? null : tag.getTaggerIdent().getName();
                String message = tag.getFullMessage();
                v.branch = ReleaseNames.branchOfTag(name, message);
                int at = message.indexOf(VERDICT);
                int trailer = message.indexOf(ReleaseNames.BRANCH_TRAILER);
                int notesEnd = Math.min(at < 0 ? message.length() : at, trailer < 0 ? message.length() : trailer);
                v.notes = message.substring(0, notesEnd).trim();
                if (at >= 0) {
                    try {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> verdict = JSON.readValue(message.substring(at + VERDICT.length()).trim(), Map.class);
                        v.verdict = verdict;
                        v.verified = Boolean.TRUE.equals(verdict.get("passed")) && v.commit.equals(verdict.get("commit"));
                    } catch (Exception e) {
                        v.verified = false;
                    }
                }
            } else {
                v.commit = object.getName();
                RevCommit commit = walk.parseCommit(object);
                v.when = Instant.ofEpochSecond(commit.getCommitTime()).toString();
            }
            versions.add(v);
        }
        versions.sort(Comparator.comparing((Version v) -> ReleaseNames.tagKey(v.name)).reversed());
        return versions;
    }

    private static boolean isAncestor(Repository repository, String commit, RevCommit tip) throws IOException {
        return GitRuleStore.isAncestor(repository, ObjectId.fromString(commit), tip);
    }

    private static int count(Repository repository, RevCommit tip, String since) throws IOException {
        try (RevWalk walk = new RevWalk(repository)) {
            walk.markStart(walk.parseCommit(tip));
            if (since != null) {
                walk.markUninteresting(walk.parseCommit(ObjectId.fromString(since)));
            }
            int n = 0;
            for (RevCommit ignored : walk) {
                n++;
            }
            return n;
        }
    }
}
