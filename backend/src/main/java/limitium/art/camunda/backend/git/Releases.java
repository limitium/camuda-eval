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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    private static final Pattern VERSION = Pattern.compile("v(\\d+)\\.(\\d+)\\.(\\d+)");
    private static final Pattern LINE = Pattern.compile("release/(\\d+)\\.(\\d+)");
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
        /** What releasing it now would make. */
        public String nextVersion;
        /** For the newest work: the line releasing it opens. */
        public String opensLine;
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
            for (Ref ref : repository.getRefDatabase().getRefsByPrefix(Constants.R_HEADS + "release/")) {
                String branch = ref.getName().substring(Constants.R_HEADS.length());
                if (LINE.matcher(branch).matches()) {
                    releaseLines.add(branch);
                }
            }
            releaseLines.sort(Comparator.comparing((String b) -> lineKey(b)).reversed());
            branches.addAll(releaseLines);
            int[] next = nextLine(all, releaseLines);
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
                    line.opensLine = "release/" + next[0] + "." + next[1];
                    line.nextVersion = null;
                    for (Version v : all) {
                        if (isAncestor(repository, v.commit, tip)) {
                            line.versions.add(v);
                        }
                    }
                } else {
                    Matcher m = LINE.matcher(branch);
                    m.matches();
                    String prefix = "v" + m.group(1) + "." + m.group(2) + ".";
                    int patch = -1;
                    for (Version v : all) {
                        if (v.name.startsWith(prefix)) {
                            line.versions.add(v);
                            patch = Math.max(patch, Integer.parseInt(v.name.substring(prefix.length())));
                        }
                    }
                    line.nextVersion = prefix + (patch + 1);
                }
                if (!line.main) {
                    List<String> after = new ArrayList<>();
                    for (String other : releaseLines) {
                        if (lineKey(other).compareTo(lineKey(branch)) > 0) {
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
                throw new Refused("NO_SUCH_LINE", "there is no line " + branch, null);
            }
            if (line.main) {
                throw new Refused("NOT_A_RELEASE_LINE", "versions are tagged on release lines; open a release line from "
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
                                    + "; a version only comes after the one before it", null);
                        }
                    }
                    commit = id.getName();
                }
            } else if (line.unreleased == 0 && !line.versions.isEmpty()) {
                throw new Refused("NOTHING_NEW", "the newest commit on " + branch + " is already " + line.versions.get(0).name, null);
            }
            TestGate.Report report = gate.run(commit);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("line", branch);
            result.put("version", line.nextVersion);
            result.put("commit", commit);
            result.put("tests", report);
            if (!report.passed) {
                throw new Refused("TESTS_FAILED", report.failed + (report.failed == 1 ? " test fails" : " tests fail")
                        + " at " + (commit.equals(line.tip) ? "the newest commit of " + branch : commit.substring(0, 7)) + "; no version was made", report);
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
                builder.setTag(line.nextVersion);
                builder.setObjectId(tip, Constants.OBJ_COMMIT);
                builder.setTagger(new PersonIdent(by == null ? store.properties().getAuthorName() : by, store.properties().getAuthorEmail()));
                builder.setMessage((notes == null || notes.isBlank() ? line.nextVersion : notes.trim())
                        + "\n\n" + VERDICT + JSON.writeValueAsString(verdict) + "\n");
                tag = inserter.insert(builder);
                inserter.flush();
            }
            List<RefTransaction.Move> moves = new ArrayList<>();
            moves.add(new RefTransaction.Move(Constants.R_TAGS + line.nextVersion, null, tag));
            try {
                RefTransaction.apply(store, moves);
            } catch (RefTransaction.Refused e) {
                throw new Refused("MOVED".equals(e.code) || "REMOTE_REFUSED".equals(e.code) ? "VERSION_TAKEN" : e.code,
                        "MOVED".equals(e.code) || "REMOTE_REFUSED".equals(e.code)
                                ? line.nextVersion + " was just made elsewhere; nothing was changed — look again" : e.getMessage(), null);
            }
            return result;
        } finally {
            lifecycle.unlock();
        }
    }

    /**
     * Opens a release line at a commit of main: where that release is stabilised and later fixed.
     * {@code number} is "M.m"; the next free one when blank.
     */
    public Map<String, Object> openLine(String at, String number, String by) throws IOException {
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
            if (mainTip == null || !GitRuleStore.isAncestor(repository, id, walk.parseCommit(mainTip))) {
                throw new Refused("NOT_ON_MAIN", "a release line opens from a commit of " + mainBranch, null);
            }
            String branch;
            if (number == null || number.isBlank()) {
                List<String> releaseLines = new ArrayList<>();
                for (Ref ref : repository.getRefDatabase().getRefsByPrefix(Constants.R_HEADS + "release/")) {
                    releaseLines.add(ref.getName().substring(Constants.R_HEADS.length()));
                }
                releaseLines.removeIf(b -> !LINE.matcher(b).matches());
                int[] next = nextLine(versions(repository, walk), releaseLines);
                branch = "release/" + next[0] + "." + next[1];
            } else {
                branch = "release/" + number.trim().replaceFirst("^release/", "").replaceFirst("^v", "");
            }
            Matcher m = LINE.matcher(branch);
            if (!m.matches()) {
                throw new Refused("BAD_NUMBER", "a release line is numbered like 1.2", null);
            }
            if (repository.resolve(Constants.R_HEADS + branch) != null) {
                throw new Refused("LINE_EXISTS", branch.replace("release/", "release ") + " is open already", null);
            }
            String prefix = "v" + m.group(1) + "." + m.group(2) + ".";
            for (Version v : versions(repository, walk)) {
                if (v.name.startsWith(prefix)) {
                    throw new Refused("RELEASED_BEFORE", branch.replace("release/", "release ") + " was released before (" + v.name
                            + "); start a fix from that version to open it again", null);
                }
            }
            // Lines are in order: a newer number is never older than what it follows, and it forks
            // from main after the line before it, so carrying a fix forward always moves into newer work.
            java.util.Set<ObjectId> mainChain = firstParents(repository, mainTip);
            java.util.TreeMap<String, ObjectId> starts = new java.util.TreeMap<>(); // line key -> a commit on it
            java.util.Map<String, String> names = new java.util.HashMap<>();
            for (Ref ref : repository.getRefDatabase().getRefsByPrefix(Constants.R_HEADS + "release/")) {
                String other = ref.getName().substring(Constants.R_HEADS.length());
                if (LINE.matcher(other).matches()) {
                    starts.put(lineKey(other), ref.getObjectId());
                    names.put(lineKey(other), other);
                }
            }
            for (Version v : versions(repository, walk)) { // a closed line is still where its versions are
                Matcher vm = VERSION.matcher(v.name);
                vm.matches();
                String vLine = "release/" + vm.group(1) + "." + vm.group(2);
                starts.putIfAbsent(lineKey(vLine), ObjectId.fromString(v.commit));
                names.putIfAbsent(lineKey(vLine), vLine);
            }
            if (!starts.isEmpty() && starts.lastKey().compareTo(lineKey(branch)) > 0) {
                throw new Refused("OUT_OF_ORDER", "release " + names.get(starts.lastKey()).substring(8)
                        + " exists already; a new line is numbered after it", null);
            }
            java.util.Map.Entry<String, ObjectId> before = starts.lowerEntry(lineKey(branch));
            if (before != null) {
                ObjectId fork = forkPoint(repository, before.getValue(), mainChain);
                if (fork != null && (fork.equals(id) || !GitRuleStore.isAncestor(repository, fork, walk.parseCommit(id)))) {
                    throw new Refused("NOT_AFTER_PREVIOUS", "release " + branch.substring(8) + " must branch from main after release "
                            + names.get(before.getKey()).substring(8) + " did (at " + fork.getName().substring(0, 7) + "); pick a newer commit of main", null);
                }
            }
            try {
                RefTransaction.apply(store, java.util.Collections.singletonList(new RefTransaction.Move(Constants.R_HEADS + branch, null, id)));
            } catch (RefTransaction.Refused e) {
                throw new Refused("LINE_EXISTS", branch + " was just opened elsewhere; look again", null);
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("line", branch);
            result.put("commit", id.getName());
            result.put("nextVersion", prefix + "0");
            return result;
        } finally {
            lifecycle.unlock();
        }
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
     * Where a line left main: following the line's own first parents back to main's history. (A
     * merge-base would not do: once a line's fixes are carried into main, main contains its tip.)
     */
    private static ObjectId forkPoint(Repository repository, ObjectId onLine, java.util.Set<ObjectId> mainChain) throws IOException {
        try (RevWalk walk = new RevWalk(repository)) {
            RevCommit c = walk.parseCommit(onLine);
            while (c != null) {
                if (mainChain.contains(c)) {
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
            if (!VERSION.matcher(name).matches()) {
                continue;
            }
            RevObject object = walk.parseAny(ref.getObjectId());
            Version v = new Version();
            v.name = name;
            if (object instanceof RevTag) {
                RevTag tag = (RevTag) object;
                walk.parseBody(tag);
                RevObject target = walk.peel(tag);
                v.commit = target.getName();
                v.when = tag.getTaggerIdent() == null ? null : tag.getTaggerIdent().getWhenAsInstant().toString();
                v.by = tag.getTaggerIdent() == null ? null : tag.getTaggerIdent().getName();
                String message = tag.getFullMessage();
                int at = message.indexOf(VERDICT);
                v.notes = (at < 0 ? message : message.substring(0, at)).trim();
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
        versions.sort(Comparator.comparing((Version v) -> versionKey(v.name)).reversed());
        return versions;
    }

    /** The next line the newest work opens: same major, next minor; v1.0 when nothing exists yet. */
    private static int[] nextLine(List<Version> versions, List<String> lines) {
        int major = -1;
        int minor = -1;
        for (Version v : versions) {
            Matcher m = VERSION.matcher(v.name);
            m.matches();
            int ma = Integer.parseInt(m.group(1));
            int mi = Integer.parseInt(m.group(2));
            if (ma > major || (ma == major && mi > minor)) {
                major = ma;
                minor = mi;
            }
        }
        for (String line : lines) {
            Matcher m = LINE.matcher(line);
            m.matches();
            int ma = Integer.parseInt(m.group(1));
            int mi = Integer.parseInt(m.group(2));
            if (ma > major || (ma == major && mi > minor)) {
                major = ma;
                minor = mi;
            }
        }
        return major < 0 ? new int[]{1, 0} : new int[]{major, minor + 1};
    }

    private static String versionKey(String name) {
        Matcher m = VERSION.matcher(name);
        if (!m.matches()) {
            return name;
        }
        return String.format("%06d.%06d.%06d", Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
    }

    private static String lineKey(String branch) {
        Matcher m = LINE.matcher(branch);
        return m.matches() ? String.format("%06d.%06d", Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))) : branch;
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
