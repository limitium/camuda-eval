package limitium.art.camunda.backend.git;

import limitium.art.camunda.backend.config.GitProperties;
import org.eclipse.jgit.api.CreateBranchCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevObject;
import org.eclipse.jgit.revwalk.RevTag;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The rule repository: one clone per working space, plus one for {@code main}.
 *
 * <p>A checkout per working space is what lets several people edit at once without a branch switch
 * ever moving files under an open editor. Nothing here switches branches on an existing checkout —
 * a new working space gets its own worktree and keeps it until it is deleted.
 *
 * <p>Reads at a revision go through {@link TreeWalk}, never through a checkout, for the same reason.
 */
public class GitRuleStore {

    private static final Logger log = LoggerFactory.getLogger(GitRuleStore.class);

    private final GitProperties properties;
    private final Path workRoot;
    /*
     * Locking, always taken in this order and never the other way round:
     *
     *   refLock      server-wide: creating, deleting, restoring spaces; tidying; moving shared refs
     *   space lock   per space, read/write: saves and reads take it shared, so many can edit a space
     *                at once; committing, deleting, tidying and restoring take it exclusively, so none
     *                of those can interleave with a save or with each other
     *   file lock    per file (VersionedFiles): the version check and the write of one file
     *
     *   remoteLock   talking to the remote (fetch, push); a leaf — nothing else is taken while it is held
     *
     * A thread holding a space lock never takes refLock; that ordering is what keeps this deadlock-free.
     * Between instances nothing is locked: they meet only in the remote repository, where every push
     * is a compare-and-swap (never forced) and a refused one is reported, not retried blindly.
     */
    private final ConcurrentHashMap<String, java.util.concurrent.locks.ReentrantReadWriteLock> spaceLocks = new ConcurrentHashMap<>();
    /** Guards ref creation and deletion, which touch the shared repository rather than one worktree. */
    private final ReentrantLock refLock = new ReentrantLock();
    /**
     * One lifecycle action at a time on this instance — a landing or a release — taken before
     * refLock. Instances do not share it: between them, the remote's compare-and-swap decides.
     */
    private final ReentrantLock lifecycleLock = new ReentrantLock();
    /** Fetching from and pushing to the remote, one conversation at a time. */
    private final ReentrantLock remoteLock = new ReentrantLock();
    /** Per branch: why its newest version has not reached the remote yet; absent when it has. */
    private final ConcurrentHashMap<String, String> notSent = new ConcurrentHashMap<>();

    /** This instance's name (its environment). A space belongs to the instance that created it. */
    private volatile String instance = "";

    /** Where a space's baseline travels between instances: a blob under this ref, per branch. */
    static final String SPACE_META = "refs/space-meta/";

    /**
     * A save that did not happen. A space has one git branch, on the remote: the single place it is
     * written. A save moves that branch by compare-and-swap; if it moved first (saved on another
     * instance, or by a colleague here) or cannot be reached, the commit is undone and the changes
     * stay in the checkout, unsaved.
     */
    public static final class NotSaved extends RuntimeException {
        /** BEHIND or REMOTE_UNREACHABLE. */
        public final String code;

        public NotSaved(String code, String message) {
            super(message);
            this.code = code;
        }
    }

    /** A space that does not exist (any more): reported as such, never silently recreated. */
    public static final class NoSuchSpace extends RuntimeException {
        public NoSuchSpace(String branch) {
            super("no such working set: " + branch);
        }
    }

    /** A version committed in the space but not yet in the shared repository, so nobody else can see it. */
    public static final class NotShared extends RuntimeException {
        public final String commit;

        public NotShared(String commit, String reason) {
            super("the version was saved in this space but could not be shared: " + reason
                    + ". It is kept, and saving again shares it.");
            this.commit = commit;
        }
    }

    public interface SpaceAction<T> {
        T run() throws IOException, GitAPIException;
    }

    java.util.concurrent.locks.ReentrantReadWriteLock spaceLock(WorkspaceId id) {
        return spaceLocks.computeIfAbsent(id.checkoutDir(), k -> new java.util.concurrent.locks.ReentrantReadWriteLock());
    }

    /** Runs an edit (a save, a read) in a space, shared with other edits, excluded from commits and deletion. */
    public <T> T editing(WorkspaceId id, SpaceAction<T> action) throws IOException, GitAPIException {
        java.util.concurrent.locks.Lock lock = spaceLock(id).readLock();
        lock.lock();
        try {
            requireSpace(id);
            return action.run();
        } finally {
            lock.unlock();
        }
    }

    private void requireSpace(WorkspaceId id) {
        if (!Files.exists(checkoutOf(id).resolve(Constants.DOT_GIT))) {
            throw new NoSuchSpace(id.branch());
        }
    }

    public GitRuleStore(GitProperties properties, Path workRoot) {
        this.properties = properties;
        this.workRoot = workRoot;
    }

    public Path workRoot() {
        return workRoot;
    }

    /** The checkout holding the integration line; also the source every working space is cut from. */
    public Path mainCheckout() {
        return workRoot.resolve("main");
    }

    public Path checkoutOf(WorkspaceId id) {
        return workRoot.resolve(id.checkoutDir());
    }

    // --- lifecycle ---------------------------------------------------------------------------

    /**
     * Brings the main checkout into existence: clone it, or initialise an empty repository when no
     * remote is configured. Never fails startup on a network problem — a repository that cannot be
     * reached is a degraded editor, not a dead one.
     */
    public void initialise() throws IOException, GitAPIException {
        Files.createDirectories(workRoot);
        Path main = mainCheckout();
        if (Files.exists(main.resolve(Constants.DOT_GIT))) {
            return;
        }
        Files.createDirectories(main);

        if (properties.hasRemote()) {
            try {
                try (Git cloned = Git.cloneRepository()
                        .setURI(properties.getRemoteUrl())
                        .setDirectory(main.toFile())
                        .setBranch(properties.getMainBranch())
                        .setCredentialsProvider(credentials())
                        .call()) {
                    // An empty remote clones to a repository with no commit at all: the first
                    // instance to meet it makes the first commit, and the sync sends it.
                    if (cloned.getRepository().resolve(Constants.HEAD) == null) {
                        seed(cloned, main);
                    }
                }
                return;
            } catch (GitAPIException e) {
                log.warn("could not clone {}: {}. Continuing with a local repository.",
                        properties.getRemoteUrl(), e.getMessage());
            }
        }

        try (Git git = Git.init()
                .setDirectory(main.toFile())
                .setInitialBranch(properties.getMainBranch())
                .call()) {
            seed(git, main);
        }
    }

    private void seed(Git git, Path main) throws IOException, GitAPIException {
        // An empty clone points HEAD at whatever the client defaults to; the first commit must be main.
        git.getRepository().updateRef(Constants.HEAD).link(Constants.R_HEADS + properties.getMainBranch());
        // Git does not track empty directories, so without a placeholder these would be absent
        // from the tree and every clone would arrive without them.
        Files.createDirectories(main.resolve("dmn"));
        Files.createDirectories(main.resolve("yaml"));
        Files.writeString(main.resolve("dmn/.gitkeep"), "");
        Files.writeString(main.resolve("yaml/.gitkeep"), "");
        // Runs live outside the repository, but a stray copy must never enter history.
        Files.writeString(main.resolve(".gitignore"), "runs/\n");
        git.add().addFilepattern(".").call();
        commitAll(git, "initialise rule repository", properties.getAuthorName());
    }

    /**
     * Copies pre-existing flat files into the repository on first start.
     *
     * <p>Copy, never move: an upgrade that silently relocates someone's files looks exactly like an
     * upgrade that lost them.
     */
    public int importLegacy(Path legacyDmnDir, Path legacyYamlDir) throws IOException, GitAPIException {
        Path main = mainCheckout();
        Path dmnDir = main.resolve("dmn");
        if (holdsRuleFiles(dmnDir)) {
            return 0;
        }
        int copied = copyInto(legacyDmnDir, dmnDir) + copyInto(legacyYamlDir, main.resolve("yaml"));
        if (copied == 0) {
            return 0;
        }
        try (Git git = Git.open(main.toFile())) {
            git.add().addFilepattern(".").call();
            commitAll(git, "import existing rule files", properties.getAuthorName());
        }
        log.info("imported {} existing rule files into the repository", copied);
        return copied;
    }

    /** Placeholders like .gitkeep are not content; counting them would skip the import. */
    private boolean holdsRuleFiles(Path dir) throws IOException {
        if (Files.notExists(dir)) {
            return false;
        }
        try (var stream = Files.list(dir)) {
            return stream.anyMatch(p -> Files.isRegularFile(p)
                    && !p.getFileName().toString().startsWith("."));
        }
    }

    private int copyInto(Path from, Path to) throws IOException {
        if (from == null || Files.notExists(from)) {
            return 0;
        }
        Files.createDirectories(to);
        int copied = 0;
        try (var stream = Files.list(from)) {
            for (Path source : stream.toList()) {
                if (Files.isRegularFile(source)) {
                    Files.copy(source, to.resolve(source.getFileName()));
                    copied++;
                }
            }
        }
        return copied;
    }

    // --- working spaces ----------------------------------------------------------------------

    public List<Workspace> workspaces() throws IOException, GitAPIException {
        List<Workspace> result = new ArrayList<>();
        try (Git git = Git.open(mainCheckout().toFile())) {
            for (Ref ref : git.branchList().call()) {
                WorkspaceId id = WorkspaceId.parse(shortName(ref.getName()));
                if (id == null) {
                    continue;
                }
                Path checkout = checkoutOf(id);
                if (Files.exists(checkout.resolve(Constants.DOT_GIT))) {
                    result.add(describe(id));
                }
            }
        }
        result.sort(Comparator.comparing((Workspace w) -> w.lastModifiedAt == null ? "" : w.lastModifiedAt).reversed());
        return result;
    }

    /**
     * Creates the branch and its own checkout. The baseline decides the kind and the line this will
     * land on, so it is recorded on the branch's first commit rather than inferred later.
     */
    public Workspace createWorkspace(WorkspaceId id, String baseline, String targetLine)
            throws IOException, GitAPIException {
        return createWorkspace(id, baseline, baseline, targetLine);
    }

    /**
     * @param startPoint what the branch starts from (a line, version or another space, at its newest commit)
     * @param baseline   what it is recorded as built on (decides, for a fix, which line it recreates)
     */
    public Workspace createWorkspace(WorkspaceId id, String startPoint, String baseline, String targetLine)
            throws IOException, GitAPIException {
        refLock.lock();
        // Held until the checkout is complete: no save can reach a half-cloned space.
        java.util.concurrent.locks.Lock space = spaceLock(id).writeLock();
        space.lock();
        try {
            Path checkout = checkoutOf(id);
            if (Files.exists(checkout.resolve(Constants.DOT_GIT))) {
                throw new IllegalStateException("working space already exists: " + id.branch());
            }
            try (Git main = Git.open(mainCheckout().toFile())) {
                main.branchCreate()
                        .setName(id.branch())
                        .setStartPoint(startPoint)
                        .setUpstreamMode(CreateBranchCommand.SetupUpstreamMode.NOTRACK)
                        .call();
            }
            Files.createDirectories(checkout);
            Git.cloneRepository()
                    .setURI(mainCheckout().toUri().toString())
                    .setDirectory(checkout.toFile())
                    .setBranch(id.branch())
                    .call()
                    .close();
            // Inside .git, not the working tree: a marker file in the tree would make every fresh
            // working space dirty, and publishing is refused while a workspace is dirty.
            Files.writeString(baselineMarker(checkout), baseline + "\n" + targetLine + "\n");
            writeSpaceMeta(id, baseline, targetLine);
            sendQuietly(Constants.R_HEADS + id.branch(), SPACE_META + id.branch());
            return describe(id);
        } finally {
            space.unlock();
            refLock.unlock();
        }
    }

    public void deleteWorkspace(WorkspaceId id) throws IOException, GitAPIException {
        refLock.lock();
        java.util.concurrent.locks.Lock space = spaceLock(id).writeLock();
        space.lock();
        try {
            Path checkout = checkoutOf(id);
            if (Files.exists(checkout)) {
                deleteRecursively(checkout);
            }
            try (Git main = Git.open(mainCheckout().toFile())) {
                main.branchDelete().setBranchNames(id.branch()).setForce(true).call();
                RefUpdate meta = main.getRepository().updateRef(SPACE_META + id.branch());
                meta.setForceUpdate(true);
                meta.delete();
            }
            // Gone here; it must go from the remote too, or the next sync would bring it back.
            removeFromRemote(Constants.R_HEADS + id.branch(), SPACE_META + id.branch());
        } finally {
            space.unlock();
            refLock.unlock();
        }
    }

    /**
     * Commits everything in the working space and pushes it.
     *
     * <p>Pushing is not an optimisation here: publishing names this commit in a Kafka envelope, and a
     * sha nobody else can resolve is not provenance.
     */
    /**
     * Saves a version: commits in the space and shares it to the shared repository, exclusively — no
     * save, other commit, deletion or tidy-up of this space can interleave. A version that was
     * committed but could not be shared is reported as such ({@link NotShared}), never as saved; a
     * later call shares it, even with nothing new to commit.
     */
    public String commit(WorkspaceId id, String message, String author)
            throws IOException, GitAPIException {
        java.util.concurrent.locks.Lock lock = spaceLock(id).writeLock();
        lock.lock();
        try (Git git = Git.open(checkoutOf(id).toFile())) {
            requireSpace(id);
            git.add().addFilepattern("dmn").addFilepattern("yaml").call();
            git.add().addFilepattern("dmn").addFilepattern("yaml").setUpdate(true).call();
            ObjectId before = git.getRepository().resolve(Constants.HEAD);
            String saved = sharedHead(id);
            if (before != null && saved != null && !before.getName().equals(saved)) {
                try (Git main = Git.open(mainCheckout().toFile())) {
                    if (main.getRepository().resolve(before.getName() + "^{commit}") != null
                            && isAncestor(main.getRepository(), before, ObjectId.fromString(saved))) {
                        throw behind(id);
                    }
                }
            }
            String sha;
            boolean made = false;
            if (git.status().call().isClean()) {
                if (before == null || before.getName().equals(saved)) {
                    throw new IllegalStateException("nothing to commit");
                }
                sha = before.getName(); // committed before but never saved to the branch: save it now
            } else {
                sha = commitAll(git, message, author).getName();
                made = true;
            }
            String holding = "refs/incoming/" + id.branch();
            try {
                // The commit's objects go to the shared repository under a holding ref; the branch
                // itself moves only below, by compare-and-swap, here and on the remote together.
                git.push().setRemote("origin").setRefSpecs(new org.eclipse.jgit.transport.RefSpec("+" + sha + ":" + holding)).call();
                RefTransaction.apply(this, java.util.Collections.singletonList(new RefTransaction.Move(
                        Constants.R_HEADS + id.branch(), saved == null ? null : ObjectId.fromString(saved), ObjectId.fromString(sha))), false);
            } catch (RefTransaction.Refused e) {
                if (made && before != null) {
                    git.reset().setMode(org.eclipse.jgit.api.ResetCommand.ResetType.SOFT).setRef(before.getName()).call();
                }
                if ("REMOTE_UNREACHABLE".equals(e.code)) {
                    throw new NotSaved("REMOTE_UNREACHABLE", "the shared repository could not be reached, so nothing was saved; your changes are kept here, unsaved — save again when it can be reached");
                }
                throw behind(id);
            } catch (GitAPIException e) {
                if (made && before != null) {
                    git.reset().setMode(org.eclipse.jgit.api.ResetCommand.ResetType.SOFT).setRef(before.getName()).call();
                }
                throw new NotSaved("REMOTE_UNREACHABLE", "nothing was saved: " + e.getMessage());
            } finally {
                try (Git main = Git.open(mainCheckout().toFile())) {
                    RefUpdate drop = main.getRepository().updateRef(holding);
                    drop.setForceUpdate(true);
                    drop.delete();
                }
            }
            notSent.remove(Constants.R_HEADS + id.branch());
            return sha;
        } finally {
            lock.unlock();
        }
    }

    private NotSaved behind(WorkspaceId id) throws IOException {
        String where = "";
        String saved = sharedHead(id);
        if (saved != null) {
            try (Git main = Git.open(mainCheckout().toFile()); RevWalk walk = new RevWalk(main.getRepository())) {
                RevCommit c = walk.parseCommit(ObjectId.fromString(saved));
                String on = savedOn(c);
                where = " (" + c.getAuthorIdent().getName() + (on == null ? "" : " on " + on) + ", “" + c.getShortMessage() + "”)";
            } catch (Exception ignored) {
                // the message is enough without it
            }
        }
        return new NotSaved("BEHIND", "a newer version of this space was saved" + where
                + "; nothing was saved — refresh to continue from it. Your changes are kept, unsaved");
    }

    /** The instance a version was saved on, read from its committer; null when unknown. */
    public static String savedOn(RevCommit commit) {
        PersonIdent committer = commit.getCommitterIdent();
        return committer != null && committer.getEmailAddress() != null && committer.getEmailAddress().endsWith("@instance")
                ? committer.getName() : null;
    }

    /** Every branch whose newest version has not reached the remote, with why. */
    public Map<String, String> notSentAll() {
        Map<String, String> all = new TreeMap<>();
        for (Map.Entry<String, String> e : notSent.entrySet()) {
            all.put(shortName(e.getKey()), e.getValue());
        }
        return all;
    }

    /** Why a space's newest version has not reached other instances yet, or null when it has (or there is no remote). */
    public String notSentReason(WorkspaceId id) {
        return notSent.get(Constants.R_HEADS + id.branch());
    }

    /** Pushes the space's branch to the shared repository, retrying briefly; the ref is compared, not forced. */
    private void share(Git git, WorkspaceId id, String sha) {
        String reason = "unknown";
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                Iterable<org.eclipse.jgit.transport.PushResult> results =
                        git.push().setRemote("origin").setCredentialsProvider(credentials()).call();
                for (org.eclipse.jgit.transport.PushResult result : results) {
                    for (org.eclipse.jgit.transport.RemoteRefUpdate update : result.getRemoteUpdates()) {
                        org.eclipse.jgit.transport.RemoteRefUpdate.Status status = update.getStatus();
                        if (status != org.eclipse.jgit.transport.RemoteRefUpdate.Status.OK
                                && status != org.eclipse.jgit.transport.RemoteRefUpdate.Status.UP_TO_DATE) {
                            reason = status + (update.getMessage() == null ? "" : " " + update.getMessage());
                        }
                    }
                }
                if (sha.equals(sharedHead(id))) {
                    return;
                }
            } catch (GitAPIException | IOException e) {
                reason = e.getMessage();
            }
            try {
                Thread.sleep(50L * (attempt + 1));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        log.warn("committed {} in {} but could not share it: {}", sha, id.branch(), reason);
        throw new NotShared(sha, reason);
    }

    /** The space's branch as everyone else sees it: its tip in the shared repository. */
    public String sharedHead(WorkspaceId id) throws IOException {
        return resolve(Constants.R_HEADS + id.branch());
    }

    public boolean isDirty(WorkspaceId id) throws IOException, GitAPIException {
        try (Git git = Git.open(checkoutOf(id).toFile())) {
            Status status = git.status().call();
            return !status.isClean();
        }
    }

    // --- what a new working space can be built on -------------------------------------------

    /** A starting point for new work: the integration line, a release line, or a shipped version. */
    public static final class Baseline {
        public final String ref;
        public final String kind;      // MAIN | LINE | VERSION
        public final String line;      // the line a version belongs to, else null
        public final String when;
        public final String message;
        public final String sha;
        /**
         * Where this was cut from the newest work, read along first parents: a carried-forward fix
         * merges a line back into main, so the plain merge base would be the line's own tip.
         * Null for main itself.
         */
        public final String forkSha;
        public final String forkWhen;
        /** For a line: every time its work was carried forward into another line or main. */
        public final List<Carry> carried;

        Baseline(String ref, String kind, String line, String when, String message, String sha,
                 RevCommit fork, List<Carry> carried) {
            this.ref = ref;
            this.kind = kind;
            this.line = line;
            this.when = when;
            this.message = message;
            this.sha = sha;
            this.forkSha = fork == null ? null : fork.getName();
            this.forkWhen = fork == null ? null : time(fork);
            this.carried = carried;
        }
    }

    public static final class Carry {
        public final String into;
        public final String sha;
        public final String when;
        /** The commit of this line that was carried. */
        public final String fromSha;

        Carry(String into, String sha, String when, String fromSha) {
            this.into = into;
            this.sha = sha;
            this.when = when;
            this.fromSha = fromSha;
        }
    }

    private static final int HISTORY_LIMIT = 5000;

    /** Main, every release line, and every version tag, each placed in the history it belongs to. */
    public List<Baseline> baselines() throws IOException, GitAPIException {
        List<Baseline> result = new ArrayList<>();
        try (Git git = Git.open(mainCheckout().toFile())) {
            Repository repository = git.getRepository();
            String mainName = properties.getMainBranch();
            try (RevWalk walk = new RevWalk(repository)) {
                Map<String, List<RevCommit>> chains = new java.util.LinkedHashMap<>();
                for (Ref ref : git.branchList().call()) {
                    String name = shortName(ref.getName());
                    if (name.equals(mainName) || name.startsWith("release/")) {
                        chains.put(name, firstParents(walk, walk.parseCommit(ref.getObjectId())));
                    }
                }
                List<RevCommit> mainChain = chains.getOrDefault(mainName, Collections.<RevCommit>emptyList());
                java.util.Set<RevCommit> onMain = new java.util.HashSet<>(mainChain);

                for (Map.Entry<String, List<RevCommit>> entry : chains.entrySet()) {
                    String name = entry.getKey();
                    List<RevCommit> chain = entry.getValue();
                    RevCommit tip = chain.get(0);
                    boolean main = name.equals(mainName);
                    RevCommit fork = main ? null : forkOf(chain, onMain);
                    List<Carry> carried = main ? Collections.<Carry>emptyList()
                            : carries(name, chain, fork, chains, mainName, onMain);
                    result.add(new Baseline(name, main ? "MAIN" : "LINE", null, time(tip),
                            tip.getShortMessage(), tip.getName(), fork, carried));
                }

                for (Ref ref : git.tagList().call()) {
                    String tag = ref.getName().substring(Constants.R_TAGS.length());
                    if (!tag.matches("v\\d+\\.\\d+\\.\\d+")) {
                        continue;
                    }
                    String[] parts = tag.substring(1).split("\\.");
                    ObjectId peeled = repository.getRefDatabase().peel(ref).getPeeledObjectId();
                    RevCommit commit = walk.parseCommit(peeled != null ? peeled : ref.getObjectId());
                    // A release's own notes and date say what it is; the commit under it may be older.
                    RevObject tagged = walk.parseAny(ref.getObjectId());
                    String notes = commit.getShortMessage();
                    String when = time(commit);
                    if (tagged instanceof RevTag) {
                        RevTag annotated = (RevTag) tagged;
                        notes = annotated.getShortMessage();
                        if (annotated.getTaggerIdent() != null) {
                            when = annotated.getTaggerIdent().getWhenAsInstant().toString();
                        }
                    }
                    RevCommit fork = forkOf(firstParents(walk, commit), onMain);
                    result.add(new Baseline(tag, "VERSION", "release/" + parts[0] + "." + parts[1],
                            when, notes, commit.getName(), fork, Collections.<Carry>emptyList()));
                }
            }
        }
        return result;
    }

    /** One commit of the shared history, placed on the lane of the line whose own work it is. */
    public static final class HistoryCommit {
        public final String sha;
        public final String shortSha;
        public final List<String> parents;
        public final String message;
        public final String author;
        public final String when;
        /** main, or the release line whose own history this is. */
        public final String lane;
        /** Lines whose newest commit this is. */
        public final List<String> tips;
        /** Version tags on this commit, with their release notes. */
        public final List<Map<String, String>> versions;

        HistoryCommit(RevCommit commit, String lane, List<String> tips, List<Map<String, String>> versions) {
            this.sha = commit.getName();
            this.shortSha = commit.getName().substring(0, 7);
            List<String> parentIds = new ArrayList<>();
            for (RevCommit parent : commit.getParents()) {
                parentIds.add(parent.getName());
            }
            this.parents = parentIds;
            this.message = commit.getShortMessage();
            this.author = commit.getAuthorIdent().getName();
            this.when = time(commit);
            this.lane = lane;
            this.tips = tips;
            this.versions = versions;
        }
    }

    /** A lane of the history graph: the newest work, a release line, a space, or landed work. */
    public static final class HistoryLane {
        public final String id;
        public final String kind;      // MAIN | LINE | SPACE | LANDED
        /** For a space or landed work: whose it was and what it was called, read off its branch name. */
        public final String owner;
        public final String name;
        /** feature | hotfix, for a space or landed work whose branch name says so. */
        public final String work;
        /** For a space: the line it will land in, when known. */
        public final String target;

        HistoryLane(String id, String kind, String owner, String name, String work) {
            this(id, kind, owner, name, work, null);
        }

        HistoryLane(String id, String kind, String owner, String name, String work, String target) {
            this.id = id;
            this.kind = kind;
            this.owner = owner;
            this.name = name;
            this.work = work;
            this.target = target;
        }
    }

    /** Every release line in brief, window or not: what a finder needs to reach an old version. */
    public static final class LineSummary {
        public final String id;
        /** False once the line is closed and only its versions remain. */
        public final boolean open;
        public final String tipWhen;
        public final List<Map<String, String>> versions;
        public final int openSpaces;

        LineSummary(String id, boolean open, String tipWhen, List<Map<String, String>> versions, int openSpaces) {
            this.id = id;
            this.open = open;
            this.tipWhen = tipWhen;
            this.versions = versions;
            this.openSpaces = openSpaces;
        }
    }

    public static final class History {
        public final List<HistoryLane> lanes;
        public final List<HistoryCommit> commits;
        public final List<LineSummary> lines;
        /** The window shown: commits from this time on, and whether anything older exists. */
        public final String from;
        public final boolean earlier;
        public final int keepReleaseLines;

        History(List<HistoryLane> lanes, List<HistoryCommit> commits, List<LineSummary> lines,
                String from, boolean earlier, int keepReleaseLines) {
            this.lanes = lanes;
            this.commits = commits;
            this.lines = lines;
            this.from = from;
            this.earlier = earlier;
            this.keepReleaseLines = keepReleaseLines;
        }
    }

    private static final java.util.regex.Pattern BRANCH_IN_MESSAGE =
            java.util.regex.Pattern.compile("\\b(feature|hotfix)/([\\w.-]+)/([\\w.-]+)");

    /**
     * The history of main, every release line and every open space, newest first. Each commit sits on
     * the lane of the work it belongs to: main's own history is main's; what a line or a space did
     * after it was cut is its own; and the commits of work that has since landed (its branch gone)
     * get a lane of their own, named from the landing commit, instead of being mistaken for main's.
     */
    public History history(int limit) throws IOException, GitAPIException {
        return history(limit, 0);
    }

    /**
     * As {@link #history(int)}, limited to commits of the last {@code days} days (0: no limit). The
     * newest commit of main and of every open space is always included.
     */
    public History history(int limit, int days) throws IOException, GitAPIException {
        Instant cutoff = days > 0 ? Instant.now().minus(java.time.Duration.ofDays(days)) : Instant.EPOCH;
        boolean earlier = false;
        List<LineSummary> summaries = new ArrayList<>();
        List<HistoryCommit> result = new ArrayList<>();
        List<HistoryLane> laneList = new ArrayList<>();
        try (Git git = Git.open(mainCheckout().toFile())) {
            Repository repository = git.getRepository();
            String mainName = properties.getMainBranch();
            try (RevWalk walk = new RevWalk(repository)) {
                Map<String, RevCommit> tips = new java.util.LinkedHashMap<>();
                Map<String, RevCommit> spaces = new java.util.LinkedHashMap<>();
                for (Ref ref : git.branchList().call()) {
                    String name = shortName(ref.getName());
                    if (name.equals(mainName) || name.startsWith("release/")) {
                        tips.put(name, walk.parseCommit(ref.getObjectId()));
                    } else if (WorkspaceId.parse(name) != null) {
                        spaces.put(name, walk.parseCommit(ref.getObjectId()));
                    }
                }
                Map<RevCommit, String> lanes = new java.util.HashMap<>();
                if (tips.containsKey(mainName)) {
                    laneList.add(new HistoryLane(mainName, "MAIN", null, null, null));
                    for (RevCommit c : firstParents(walk, tips.get(mainName))) {
                        lanes.put(c, mainName);
                    }
                }
                for (Map.Entry<String, RevCommit> tip : tips.entrySet()) {
                    if (!tip.getKey().equals(mainName)) {
                        laneList.add(new HistoryLane(tip.getKey(), "LINE", null, null, null));
                        claim(walk, tip.getValue(), tip.getKey(), lanes);
                    }
                }
                for (Map.Entry<String, RevCommit> space : spaces.entrySet()) {
                    WorkspaceId id = WorkspaceId.parse(space.getKey());
                    String target = Files.exists(baselineMarker(checkoutOf(id))) ? baselineOf(id)[1] : null;
                    laneList.add(new HistoryLane(space.getKey(), "SPACE", id.owner, id.name,
                            id.kind.name().toLowerCase(), target));
                    claim(walk, space.getValue(), space.getKey(), lanes);
                }

                Map<RevCommit, List<Map<String, String>>> versions = new java.util.HashMap<>();
                java.util.Set<String> goneLines = new java.util.LinkedHashSet<>();
                for (Ref ref : git.tagList().call()) {
                    String tag = ref.getName().substring(Constants.R_TAGS.length());
                    if (!tag.matches("v\\d+\\.\\d+\\.\\d+")) {
                        continue;
                    }
                    ObjectId peeled = repository.getRefDatabase().peel(ref).getPeeledObjectId();
                    RevCommit commit = walk.parseCommit(peeled != null ? peeled : ref.getObjectId());
                    RevObject tagged = walk.parseAny(ref.getObjectId());
                    Map<String, String> version = new java.util.LinkedHashMap<>();
                    version.put("ref", tag);
                    version.put("notes", tagged instanceof RevTag
                            ? ((RevTag) tagged).getShortMessage() : commit.getShortMessage());
                    String[] parts = tag.substring(1).split("\\.");
                    version.put("line", "release/" + parts[0] + "." + parts[1]);
                    versions.computeIfAbsent(commit, k -> new ArrayList<>()).add(version);
                    if (!lanes.containsKey(commit)) {
                        lanes.put(commit, version.get("line"));
                        if (!tips.containsKey(version.get("line"))) {
                            goneLines.add(version.get("line"));
                        }
                    }
                }
                for (String line : goneLines) {
                    laneList.add(new HistoryLane(line, "LINE", null, null, null));
                }

                // Everything reachable, newest first, in an order a graph can be drawn in.
                walk.reset();
                walk.sort(org.eclipse.jgit.revwalk.RevSort.TOPO, true);
                walk.sort(org.eclipse.jgit.revwalk.RevSort.COMMIT_TIME_DESC, true);
                for (RevCommit tip : tips.values()) {
                    walk.markStart(walk.parseCommit(tip));
                }
                for (RevCommit tip : spaces.values()) {
                    walk.markStart(walk.parseCommit(tip));
                }
                // Main's and every open space's newest commit always show; a release line's need not —
                // an old line is reached through the line summaries, not by stretching the window.
                java.util.Set<RevCommit> tipCommits = new java.util.HashSet<>(spaces.values());
                if (tips.containsKey(mainName)) {
                    tipCommits.add(tips.get(mainName));
                }
                List<RevCommit> ordered = new ArrayList<>();
                for (RevCommit commit : walk) {
                    if (ordered.size() >= limit) {
                        earlier = true;
                        break;
                    }
                    if (Instant.ofEpochSecond(commit.getCommitTime()).isBefore(cutoff) && !tipCommits.contains(commit)) {
                        earlier = true;
                        continue;
                    }
                    ordered.add(commit);
                }

                // Every line in brief, including closed ones known only by their versions.
                Map<String, List<Map<String, String>>> versionsByLine = new TreeMap<>();
                for (Map.Entry<RevCommit, List<Map<String, String>>> entry : versions.entrySet()) {
                    for (Map<String, String> v : entry.getValue()) {
                        Map<String, String> brief = new java.util.LinkedHashMap<>(v);
                        brief.put("when", time(entry.getKey()));
                        brief.put("sha", entry.getKey().getName());
                        versionsByLine.computeIfAbsent(v.get("line"), k -> new ArrayList<>()).add(brief);
                    }
                }
                java.util.Set<String> lineIds = new java.util.TreeSet<>(versionsByLine.keySet());
                for (String tip : tips.keySet()) {
                    if (!tip.equals(mainName)) {
                        lineIds.add(tip);
                    }
                }
                for (String line : lineIds) {
                    int landing = 0;
                    for (HistoryLane lane : laneList) {
                        if ("SPACE".equals(lane.kind) && line.equals(lane.target)) {
                            landing++;
                        }
                    }
                    List<Map<String, String>> vs = versionsByLine.getOrDefault(line, new ArrayList<Map<String, String>>());
                    vs.sort((a, b) -> b.get("when").compareTo(a.get("when")));
                    RevCommit tip = tips.get(line);
                    summaries.add(new LineSummary(line, tip != null,
                            tip != null ? time(tip) : (vs.isEmpty() ? null : vs.get(0).get("when")), vs, landing));
                }

                // Landed work: the incoming side of a merge that no lane has claimed yet.
                for (RevCommit commit : ordered) {
                    if (commit.getParentCount() < 2 || lanes.containsKey(commit.getParent(1))) {
                        continue;
                    }
                    String laneId = "landed:" + commit.getName().substring(0, 7);
                    java.util.regex.Matcher m = BRANCH_IN_MESSAGE.matcher(commit.getFullMessage());
                    laneList.add(m.find()
                            ? new HistoryLane(laneId, "LANDED", m.group(2), m.group(3), m.group(1))
                            : new HistoryLane(laneId, "LANDED", commit.getAuthorIdent().getName(), null, null));
                    claim(walk, commit.getParent(1), laneId, lanes);
                }

                Map<RevCommit, List<String>> tipNames = new java.util.HashMap<>();
                for (Map.Entry<String, RevCommit> tip : tips.entrySet()) {
                    tipNames.computeIfAbsent(tip.getValue(), k -> new ArrayList<>()).add(tip.getKey());
                }
                for (Map.Entry<String, RevCommit> space : spaces.entrySet()) {
                    tipNames.computeIfAbsent(space.getValue(), k -> new ArrayList<>()).add(space.getKey());
                }
                for (RevCommit commit : ordered) {
                    String lane = lanes.get(commit);
                    result.add(new HistoryCommit(commit, lane == null ? mainName : lane,
                            tipNames.getOrDefault(commit, Collections.<String>emptyList()),
                            versions.getOrDefault(commit, Collections.<Map<String, String>>emptyList())));
                }
            }
        }
        return new History(laneList, result, summaries, days > 0 ? cutoff.toString() : null, earlier,
                properties.getKeepReleaseLines());
    }

    /** Gives a lane every commit of its own first-parent history not already someone else's. */
    private static void claim(RevWalk walk, RevCommit tip, String lane, Map<RevCommit, String> lanes)
            throws IOException {
        for (RevCommit c : firstParents(walk, tip)) {
            if (lanes.containsKey(c)) {
                break;
            }
            lanes.put(c, lane);
        }
    }

    private static List<RevCommit> firstParents(RevWalk walk, RevCommit tip) throws IOException {
        List<RevCommit> chain = new ArrayList<>();
        RevCommit current = tip;
        while (current != null && chain.size() < HISTORY_LIMIT) {
            walk.parseHeaders(current);
            chain.add(current);
            current = current.getParentCount() > 0 ? current.getParent(0) : null;
        }
        return chain;
    }

    /** The first commit on a line's own history that is also on the newest work's own history. */
    private static RevCommit forkOf(List<RevCommit> chain, java.util.Set<RevCommit> onMain) {
        for (RevCommit commit : chain) {
            if (onMain.contains(commit)) {
                return commit;
            }
        }
        return null;
    }

    /** Merges elsewhere whose incoming side is this line's own work: its fixes carried forward. */
    private static List<Carry> carries(String name, List<RevCommit> chain, RevCommit fork,
                                       Map<String, List<RevCommit>> chains, String mainName,
                                       java.util.Set<RevCommit> onMain) {
        java.util.Set<RevCommit> own = new java.util.HashSet<>();
        for (RevCommit commit : chain) {
            if (commit.equals(fork)) {
                break;
            }
            own.add(commit);
        }
        List<Carry> carried = new ArrayList<>();
        for (Map.Entry<String, List<RevCommit>> other : chains.entrySet()) {
            if (other.getKey().equals(name)) {
                continue;
            }
            boolean otherIsMain = other.getKey().equals(mainName);
            for (RevCommit commit : other.getValue()) {
                // Another line's history before its cut is main's; count only what it did itself.
                if (!otherIsMain && onMain.contains(commit)) {
                    break;
                }
                if (commit.getParentCount() > 1 && own.contains(commit.getParent(1))) {
                    carried.add(new Carry(other.getKey(), commit.getName(), time(commit),
                            commit.getParent(1).getName()));
                }
            }
        }
        return carried;
    }

    private static String time(RevCommit commit) {
        return Instant.ofEpochSecond(commit.getCommitTime()).toString();
    }

    /**
     * Files in a working space that differ from its last saved version, as paths relative to the
     * checkout, with what happened to each.
     */
    public Map<String, String> changes(WorkspaceId id) throws IOException, GitAPIException {
        Map<String, String> changes = new TreeMap<>();
        try (Git git = Git.open(checkoutOf(id).toFile())) {
            Status status = git.status().call();
            status.getModified().forEach(p -> changes.put(p, "CHANGED"));
            status.getChanged().forEach(p -> changes.put(p, "CHANGED"));
            status.getAdded().forEach(p -> changes.put(p, "ADDED"));
            status.getUntracked().forEach(p -> changes.put(p, "ADDED"));
            status.getMissing().forEach(p -> changes.put(p, "REMOVED"));
            status.getRemoved().forEach(p -> changes.put(p, "REMOVED"));
        }
        changes.keySet().removeIf(p -> !(p.startsWith("dmn/") || p.startsWith("yaml/")) || p.endsWith(".gitkeep"));
        return changes;
    }

    // --- reading at a revision ---------------------------------------------------------------

    /** Reads a path at any revision without touching a working tree. */
    public byte[] readAt(String revision, String path) throws IOException {
        try (Git git = Git.open(mainCheckout().toFile())) {
            Repository repository = git.getRepository();
            ObjectId resolved = repository.resolve(revision);
            if (resolved == null) {
                return null;
            }
            try (RevWalk walk = new RevWalk(repository)) {
                RevCommit commit = walk.parseCommit(resolved);
                try (TreeWalk treeWalk = TreeWalk.forPath(repository, path, commit.getTree())) {
                    if (treeWalk == null) {
                        return null;
                    }
                    return repository.open(treeWalk.getObjectId(0)).getBytes();
                }
            }
        }
    }

    /** The commit a revision names, or null. */
    public String resolve(String revision) throws IOException {
        try (Git git = Git.open(mainCheckout().toFile())) {
            ObjectId id = git.getRepository().resolve(revision + "^{commit}");
            return id == null ? null : id.getName();
        }
    }

    /** File names directly under a directory at a revision, e.g. the rule files of a release. */
    public List<String> filesAt(String revision, String dir) throws IOException {
        List<String> names = new ArrayList<>();
        try (Git git = Git.open(mainCheckout().toFile())) {
            Repository repository = git.getRepository();
            ObjectId resolved = repository.resolve(revision + "^{commit}");
            if (resolved == null) {
                return names;
            }
            try (RevWalk walk = new RevWalk(repository)) {
                RevCommit commit = walk.parseCommit(resolved);
                try (TreeWalk tree = TreeWalk.forPath(repository, dir, commit.getTree())) {
                    if (tree == null || !tree.isSubtree()) {
                        return names;
                    }
                    tree.enterSubtree();
                    while (tree.next()) {
                        names.add(tree.getNameString());
                    }
                }
            }
        }
        return names;
    }

    /** A commit as anyone can open it: what it is, who made it, what names it, and its domains. */
    public static final class CommitInfo {
        public String sha;
        public String shortSha;
        public String message;
        public String author;
        public String when;
        public List<String> parents = new ArrayList<>();
        /** Versions tagged on it. */
        public List<String> versions = new ArrayList<>();
        /** Branches (lines, spaces) whose newest commit it is. */
        public List<String> tips = new ArrayList<>();
        public List<String> domains = new ArrayList<>();
        /** The instance it was saved on, when it was a save. */
        public String savedOn;
        /** What it changed against its first parent (for a landing: everything that landed), per domain. */
        public List<DomainChange> changes = new ArrayList<>();
    }

    /** One domain's change in a commit, in words a person reads: rules, columns, test cases. */
    public static final class DomainChange {
        public String domain;
        /** ADDED, REMOVED or CHANGED. */
        public String kind;
        public int rulesAdded;
        public int rulesRemoved;
        public int rulesChanged;
        public int columnsChanged;
        public int tablesAdded;
        public int tablesRemoved;
        public boolean hitPolicyChanged;
        public boolean tableChanged;
        public int casesBefore;
        public int casesAfter;
        public boolean testsChanged;
    }

    private static List<DomainChange> changesOf(Repository repository, RevCommit commit) throws IOException {
        Map<String, DomainChange> byDomain = new java.util.TreeMap<>();
        RevCommit parent = commit.getParentCount() > 0 ? commit.getParent(0) : null;
        try (RevWalk walk = new RevWalk(repository); TreeWalk tree = new TreeWalk(repository)) {
            if (parent != null) {
                tree.addTree(walk.parseCommit(parent).getTree());
            } else {
                tree.addTree(new org.eclipse.jgit.treewalk.EmptyTreeIterator());
            }
            tree.addTree(walk.parseCommit(commit).getTree());
            tree.setRecursive(true);
            tree.setFilter(org.eclipse.jgit.treewalk.filter.TreeFilter.ANY_DIFF);
            while (tree.next()) {
                String path = tree.getPathString();
                boolean dmn = path.startsWith("dmn/") && path.endsWith(".dmn");
                boolean yaml = path.startsWith("yaml/") && path.endsWith(".yaml");
                if (!dmn && !yaml) {
                    continue;
                }
                String domain = path.substring(dmn ? 4 : 5, path.length() - (dmn ? 4 : 5));
                DomainChange c = byDomain.computeIfAbsent(domain, d -> {
                    DomainChange x = new DomainChange();
                    x.domain = d;
                    x.kind = "CHANGED";
                    return x;
                });
                ObjectId before = tree.getObjectId(0);
                ObjectId after = tree.getObjectId(1);
                String oldText = before.equals(ObjectId.zeroId()) ? null : new String(repository.open(before).getBytes(), StandardCharsets.UTF_8);
                String newText = after.equals(ObjectId.zeroId()) ? null : new String(repository.open(after).getBytes(), StandardCharsets.UTF_8);
                if (dmn) {
                    if (oldText == null) {
                        c.kind = "ADDED";
                    } else if (newText == null) {
                        c.kind = "REMOVED";
                    }
                    c.tableChanged = true;
                    try {
                        limitium.art.camunda.evaluator.diff.DiffSummary sum = limitium.art.camunda.evaluator.diff.DmnDiffer.diff(
                                oldText == null ? EMPTY_DMN : oldText, newText == null ? EMPTY_DMN : newText).summary;
                        c.rulesAdded = sum.rulesAdded;
                        c.rulesRemoved = sum.rulesRemoved;
                        c.rulesChanged = sum.rulesChanged + sum.rulesMoved;
                        c.columnsChanged = sum.columnsAdded + sum.columnsRemoved + sum.columnsChanged;
                        c.tablesAdded = sum.decisionsAdded;
                        c.tablesRemoved = sum.decisionsRemoved;
                        c.hitPolicyChanged = sum.hitPolicyChanged;
                    } catch (RuntimeException e) {
                        // a table that does not read is still a changed file; the counts are just unknown
                    }
                } else {
                    c.testsChanged = true;
                    c.casesBefore = cases(oldText);
                    c.casesAfter = cases(newText);
                }
            }
        }
        return new ArrayList<>(byDomain.values());
    }

    private static final String EMPTY_DMN = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><definitions xmlns=\"https://www.omg.org/spec/DMN/20191111/MODEL/\" id=\"empty\" name=\"empty\" namespace=\"empty\"/>";

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

    /** Describes any revision — a sha, a version, a branch — or null when there is no such commit. */
    public CommitInfo commitInfo(String revision) throws IOException {
        try (Git git = Git.open(mainCheckout().toFile()); RevWalk walk = new RevWalk(git.getRepository())) {
            Repository repository = git.getRepository();
            ObjectId resolved = repository.resolve(revision + "^{commit}");
            if (resolved == null) {
                return null;
            }
            RevCommit commit = walk.parseCommit(resolved);
            CommitInfo info = new CommitInfo();
            info.sha = commit.getName();
            info.shortSha = info.sha.substring(0, 7);
            info.message = commit.getFullMessage().trim();
            info.author = commit.getAuthorIdent().getName();
            info.when = time(commit);
            for (RevCommit parent : commit.getParents()) {
                info.parents.add(parent.getName());
            }
            for (Ref ref : repository.getRefDatabase().getRefs()) {
                ObjectId target = repository.getRefDatabase().peel(ref).getPeeledObjectId();
                if (target == null) {
                    target = ref.getObjectId();
                }
                if (!resolved.equals(target)) {
                    continue;
                }
                if (ref.getName().startsWith(Constants.R_TAGS)) {
                    info.versions.add(ref.getName().substring(Constants.R_TAGS.length()));
                } else if (ref.getName().startsWith(Constants.R_HEADS)) {
                    info.tips.add(ref.getName().substring(Constants.R_HEADS.length()));
                }
            }
            for (String file : filesAt(info.sha, "dmn")) {
                if (file.endsWith(".dmn")) {
                    info.domains.add(file.substring(0, file.length() - 4));
                }
            }
            info.savedOn = savedOn(commit);
            info.changes = changesOf(repository, commit);
            return info;
        }
    }

    /** The baseline and target line of a space, readable by other packages. */
    public String[] baselineOfSpace(WorkspaceId id) throws IOException {
        return baselineOf(id);
    }

    public boolean hasCommit(String sha) throws IOException {
        try (Git git = Git.open(mainCheckout().toFile())) {
            return git.getRepository().resolve(sha) != null;
        }
    }

    // --- between instances --------------------------------------------------------------------

    public void setInstance(String instance) {
        this.instance = instance == null ? "" : instance;
    }

    public String instance() {
        return instance;
    }

    /** Records a space's baseline and home instance in the repository itself, so every instance knows them. */
    void writeSpaceMeta(WorkspaceId id, String baseline, String targetLine) throws IOException {
        try (Git git = Git.open(mainCheckout().toFile());
             org.eclipse.jgit.lib.ObjectInserter inserter = git.getRepository().newObjectInserter()) {
            ObjectId blob = inserter.insert(Constants.OBJ_BLOB,
                    (baseline + "\n" + targetLine + "\n").getBytes(StandardCharsets.UTF_8));
            inserter.flush();
            RefUpdate update = git.getRepository().updateRef(SPACE_META + id.branch());
            update.setNewObjectId(blob);
            update.setForceUpdate(true);
            update.update();
        }
    }

    String[] readSpaceMeta(WorkspaceId id) throws IOException {
        try (Git git = Git.open(mainCheckout().toFile())) {
            ObjectId blob = git.getRepository().resolve(SPACE_META + id.branch());
            if (blob == null) {
                return null;
            }
            String[] lines = new String(git.getRepository().open(blob).getBytes(), StandardCharsets.UTF_8).split("\n");
            return new String[]{lines.length > 0 ? lines[0] : "", lines.length > 1 ? lines[1] : properties.getMainBranch(),
                    lines.length > 2 ? lines[2].trim() : ""};
        }
    }

    /** Spaces with no checkout on this instance (made or last worked on elsewhere): each can be continued here. */
    public List<Map<String, String>> spacesElsewhere() throws IOException, GitAPIException {
        List<Map<String, String>> result = new ArrayList<>();
        try (Git git = Git.open(mainCheckout().toFile()); RevWalk walk = new RevWalk(git.getRepository())) {
            for (Ref ref : git.branchList().call()) {
                WorkspaceId id = WorkspaceId.parse(shortName(ref.getName()));
                if (id == null || Files.exists(checkoutOf(id).resolve(Constants.DOT_GIT))) {
                    continue;
                }
                RevCommit head = walk.parseCommit(ref.getObjectId());
                String[] meta = readSpaceMeta(id);
                Map<String, String> space = new java.util.LinkedHashMap<>();
                space.put("id", id.branch());
                space.put("kind", id.kind.name());
                space.put("owner", id.owner);
                space.put("name", id.name);
                space.put("head", head.getName());
                space.put("lastMessage", head.getShortMessage());
                space.put("lastModifiedBy", head.getAuthorIdent().getName());
                space.put("lastModifiedAt", time(head));
                space.put("baseline", meta == null ? "" : meta[0]);
                space.put("targetLine", meta == null ? properties.getMainBranch() : meta[1]);
                space.put("savedOn", savedOn(head));
                result.add(space);
            }
        }
        return result;
    }

    /**
     * Gives a space an own checkout on this instance, from its branch — to continue there what was
     * started elsewhere. The branch stays the one place it is written.
     */
    public Workspace openWorkspace(WorkspaceId id) throws IOException, GitAPIException {
        refLock.lock();
        java.util.concurrent.locks.Lock space = spaceLock(id).writeLock();
        space.lock();
        try {
            Path checkout = checkoutOf(id);
            if (Files.exists(checkout.resolve(Constants.DOT_GIT))) {
                return describe(id);
            }
            if (resolve(Constants.R_HEADS + id.branch()) == null) {
                throw new NoSuchSpace(id.branch());
            }
            String[] meta = readSpaceMeta(id);
            Files.createDirectories(checkout);
            Git.cloneRepository().setURI(mainCheckout().toUri().toString())
                    .setDirectory(checkout.toFile()).setBranch(id.branch()).call().close();
            Files.writeString(baselineMarker(checkout),
                    (meta == null ? "" : meta[0]) + "\n" + (meta == null ? properties.getMainBranch() : meta[1]) + "\n");
            return describe(id);
        } finally {
            space.unlock();
            refLock.unlock();
        }
    }

    /** Starting points for a new space: the newest work, release lines (newest first), and every space. */
    public List<Map<String, Object>> bases() throws IOException, GitAPIException {
        List<Map<String, Object>> result = new ArrayList<>();
        try (Git git = Git.open(mainCheckout().toFile()); RevWalk walk = new RevWalk(git.getRepository())) {
            List<Ref> lines = new ArrayList<>();
            List<Ref> spaces = new ArrayList<>();
            Ref main = null;
            for (Ref ref : git.branchList().call()) {
                String name = shortName(ref.getName());
                if (name.equals(properties.getMainBranch())) {
                    main = ref;
                } else if (name.matches("release/\\d+\\.\\d+")) {
                    lines.add(ref);
                } else if (WorkspaceId.parse(name) != null) {
                    spaces.add(ref);
                }
            }
            lines.sort(Comparator.comparing((Ref r) -> {
                String[] p = shortName(r.getName()).substring(8).split("\\.");
                return String.format("%06d.%06d", Integer.parseInt(p[0]), Integer.parseInt(p[1]));
            }).reversed());
            List<Ref> ordered = new ArrayList<>();
            if (main != null) {
                ordered.add(main);
            }
            ordered.addAll(lines);
            ordered.addAll(spaces);
            for (Ref ref : ordered) {
                String name = shortName(ref.getName());
                RevCommit tip = walk.parseCommit(ref.getObjectId());
                Map<String, Object> b = new java.util.LinkedHashMap<>();
                WorkspaceId space = WorkspaceId.parse(name);
                b.put("ref", name);
                b.put("kind", space != null ? "SPACE" : name.equals(properties.getMainBranch()) ? "MAIN" : "LINE");
                b.put("tip", tip.getName());
                b.put("message", tip.getShortMessage());
                b.put("by", tip.getAuthorIdent().getName());
                b.put("when", time(tip));
                b.put("savedOn", savedOn(tip));
                if (space != null) {
                    b.put("owner", space.owner);
                    b.put("name", space.name);
                    String[] meta = readSpaceMeta(space);
                    b.put("targetLine", meta == null ? properties.getMainBranch() : meta[1]);
                }
                result.add(b);
            }
        }
        return result;
    }

    /**
     * The landing of this space's newest saved version, if it has landed: the commit on its line that
     * says "Land <branch> into <line>" and holds the space's head. Read from git, so a landing made on
     * any instance counts. Null when not landed (or landed, then saved again).
     */
    public Map<String, String> landing(WorkspaceId id, String targetLine) throws IOException {
        String head = sharedHead(id);
        if (head == null) {
            return null;
        }
        try (Git git = Git.open(mainCheckout().toFile()); RevWalk walk = new RevWalk(git.getRepository())) {
            Repository repository = git.getRepository();
            ObjectId line = repository.resolve(Constants.R_HEADS + (targetLine == null ? properties.getMainBranch() : targetLine));
            if (line == null) {
                return null;
            }
            ObjectId headId = ObjectId.fromString(head);
            if (!isAncestor(repository, headId, line)) {
                return null;
            }
            walk.markStart(walk.parseCommit(line));
            walk.setRevFilter(org.eclipse.jgit.revwalk.filter.RevFilter.ONLY_MERGES);
            String prefix = "Land " + id.branch() + " into ";
            int seen = 0;
            for (RevCommit c : walk) {
                if (++seen > 500) {
                    break;
                }
                if (c.getShortMessage().startsWith(prefix) && c.getParentCount() > 1 && c.getParent(1).getName().equals(head)) {
                    Map<String, String> landed = new java.util.LinkedHashMap<>();
                    landed.put("sha", c.getName());
                    landed.put("line", c.getShortMessage().substring(prefix.length()));
                    landed.put("by", c.getAuthorIdent().getName());
                    landed.put("at", time(c));
                    return landed;
                }
            }
        }
        return null;
    }

    /** The saved version (the branch) and whether this checkout matches it, has unsaved changes, or is behind. */
    public Map<String, Object> savedState(WorkspaceId id) throws IOException, GitAPIException {
        Map<String, Object> state = new java.util.LinkedHashMap<>();
        String saved = sharedHead(id);
        String here = null;
        boolean dirty = false;
        if (Files.exists(checkoutOf(id).resolve(Constants.DOT_GIT))) {
            try (Git git = Git.open(checkoutOf(id).toFile())) {
                ObjectId head = git.getRepository().resolve(Constants.HEAD);
                here = head == null ? null : head.getName();
                dirty = !git.status().call().isClean();
            }
        }
        boolean behind = saved != null && here != null && !saved.equals(here);
        state.put("state", behind ? (dirty ? "BEHIND_UNSAVED" : "BEHIND") : (dirty ? "UNSAVED" : "SAVED"));
        String[] meta = baselineOf(id);
        state.put("landed", landing(id, meta[1]));
        state.put("saved", saved);
        state.put("here", here);
        if (saved != null) {
            try (Git main = Git.open(mainCheckout().toFile()); RevWalk walk = new RevWalk(main.getRepository())) {
                RevCommit c = walk.parseCommit(ObjectId.fromString(saved));
                state.put("savedBy", c.getAuthorIdent().getName());
                state.put("savedOn", savedOn(c));
                state.put("savedAt", time(c));
                state.put("savedMessage", c.getShortMessage());
            }
        }
        return state;
    }

    /** The checkout's files under dmn/ and yaml/, as they are on disk now (saved or not). */
    public Map<String, byte[]> workingFiles(WorkspaceId id) throws IOException {
        Map<String, byte[]> files = new TreeMap<>();
        for (String dir : new String[]{"dmn", "yaml"}) {
            Path d = checkoutOf(id).resolve(dir);
            if (!Files.isDirectory(d)) {
                continue;
            }
            try (var stream = Files.list(d)) {
                for (Path f : stream.toList()) {
                    String name = f.getFileName().toString();
                    if (Files.isRegularFile(f) && !name.startsWith(".")) {
                        files.put(dir + "/" + name, Files.readAllBytes(f));
                    }
                }
            }
        }
        return files;
    }

    /**
     * Refreshes a checkout that has unsaved changes onto the newer saved version: the checkout moves
     * to it and the merged files are written over it, still unsaved. Refused if anything changed since
     * the merge was computed.
     */
    public void refreshWith(WorkspaceId id, String expectedHead, Map<String, byte[]> expectedFiles, String to,
                            Map<String, byte[]> result) throws IOException, GitAPIException {
        java.util.concurrent.locks.Lock lock = spaceLock(id).writeLock();
        lock.lock();
        try (Git git = Git.open(checkoutOf(id).toFile())) {
            requireSpace(id);
            ObjectId head = git.getRepository().resolve(Constants.HEAD);
            if (head == null || !head.getName().equals(expectedHead) || !sameFiles(workingFiles(id), expectedFiles)) {
                throw new IllegalStateException("files in this space changed while you were deciding; look again");
            }
            git.fetch().setRemote("origin").call();
            git.reset().setMode(org.eclipse.jgit.api.ResetCommand.ResetType.HARD).setRef(to).call();
            Map<String, byte[]> now = workingFiles(id);
            for (String path : now.keySet()) {
                if (!result.containsKey(path)) {
                    Files.deleteIfExists(checkoutOf(id).resolve(path));
                }
            }
            for (Map.Entry<String, byte[]> e : result.entrySet()) {
                limitium.art.camunda.backend.service.VersionedFiles.writeAtomically(checkoutOf(id).resolve(e.getKey()), e.getValue());
            }
        } finally {
            lock.unlock();
        }
    }

    private static boolean sameFiles(Map<String, byte[]> a, Map<String, byte[]> b) {
        if (!a.keySet().equals(b.keySet())) {
            return false;
        }
        for (Map.Entry<String, byte[]> e : a.entrySet()) {
            if (!java.util.Arrays.equals(e.getValue(), b.get(e.getKey()))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Moves a space to a version made for it (a pull: what was brought in, merged with it), then its
     * checkout with it — only when the checkout holds nothing unsaved and is where the merge was
     * computed from, so nothing is lost and nothing stale is moved onto.
     */
    public void advanceSpace(WorkspaceId id, String expectedHead, String to) throws IOException, GitAPIException {
        java.util.concurrent.locks.Lock lock = spaceLock(id).writeLock();
        lock.lock();
        try (Git git = Git.open(checkoutOf(id).toFile())) {
            requireSpace(id);
            if (!git.status().call().isClean()) {
                throw new IllegalStateException("there are unsaved changes in this space; save or undo them first, then bring the changes in");
            }
            ObjectId head = git.getRepository().resolve(Constants.HEAD);
            if (head == null || !head.getName().equals(expectedHead) || !expectedHead.equals(sharedHead(id))) {
                throw new IllegalStateException("this space was saved again meanwhile; look at the changes again");
            }
            try (Git main = Git.open(mainCheckout().toFile())) {
                RefUpdate update = main.getRepository().updateRef(Constants.R_HEADS + id.branch());
                update.setExpectedOldObjectId(ObjectId.fromString(expectedHead));
                update.setNewObjectId(ObjectId.fromString(to));
                RefUpdate.Result result = update.update();
                if (result != RefUpdate.Result.FAST_FORWARD && result != RefUpdate.Result.NEW) {
                    throw new IllegalStateException("this space moved meanwhile (" + result + "); look at the changes again");
                }
            }
            git.fetch().setRemote("origin").call();
            git.reset().setMode(org.eclipse.jgit.api.ResetCommand.ResetType.HARD).setRef(to).call();
            sendQuietly(Constants.R_HEADS + id.branch());
        } finally {
            lock.unlock();
        }
    }

    /**
     * Brings a space's checkout up to its branch after another instance moved it: only when the
     * checkout holds nothing of its own (no unsaved edits, no unshared version), so nothing is lost.
     * Returns false when it had to be left behind.
     */
    boolean refreshCheckout(WorkspaceId id) throws IOException, GitAPIException {
        java.util.concurrent.locks.Lock lock = spaceLock(id).writeLock();
        lock.lock();
        try (Git git = Git.open(checkoutOf(id).toFile())) {
            git.fetch().setRemote("origin").call();
            Repository repository = git.getRepository();
            ObjectId head = repository.resolve(Constants.HEAD);
            ObjectId tip = repository.resolve(Constants.R_REMOTES + "origin/" + id.branch());
            if (tip == null || tip.equals(head)) {
                return true;
            }
            if (!git.status().call().isClean() || (head != null && !isAncestor(repository, head, tip))) {
                return false;
            }
            git.reset().setMode(org.eclipse.jgit.api.ResetCommand.ResetType.HARD).setRef(tip.getName()).call();
            return true;
        } finally {
            lock.unlock();
        }
    }

    static boolean isAncestor(Repository repository, ObjectId ancestor, ObjectId of) throws IOException {
        try (RevWalk walk = new RevWalk(repository)) {
            return walk.isMergedInto(walk.parseCommit(ancestor), walk.parseCommit(of));
        }
    }

    /** Refs deleted here that the remote still has to lose; kept on disk so a restart does not forget them. */
    PendingRemovals pendingRemovals() {
        return new PendingRemovals(mainCheckout().resolve(Constants.DOT_GIT).resolve("camunda-eval-pending-removals"));
    }

    static final class PendingRemovals {
        private static final Object LOCK = new Object();
        private final Path file;

        PendingRemovals(Path file) {
            this.file = file;
        }

        List<String> all() throws IOException {
            synchronized (LOCK) {
                return Files.exists(file) ? new ArrayList<>(new java.util.LinkedHashSet<>(Files.readAllLines(file))) : new ArrayList<>();
            }
        }

        void add(String... refs) throws IOException {
            synchronized (LOCK) {
                java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<>(all());
                set.addAll(java.util.Arrays.asList(refs));
                limitium.art.camunda.backend.service.VersionedFiles.writeAtomically(file, String.join("\n", set).getBytes(StandardCharsets.UTF_8));
            }
        }

        void remove(String... refs) throws IOException {
            synchronized (LOCK) {
                java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<>(all());
                set.removeAll(java.util.Arrays.asList(refs));
                limitium.art.camunda.backend.service.VersionedFiles.writeAtomically(file, String.join("\n", set).getBytes(StandardCharsets.UTF_8));
            }
        }

        boolean contains(String ref) throws IOException {
            return all().contains(ref);
        }
    }

    /** Records refs for removal from the remote and tries now; the sync finishes it if this fails. */
    void removeFromRemote(String... refs) throws IOException {
        if (!properties.hasRemote()) {
            return;
        }
        pendingRemovals().add(refs);
        remoteLock.lock();
        try {
            ensureOrigin();
            List<org.eclipse.jgit.transport.RefSpec> specs = new ArrayList<>();
            for (String ref : refs) {
                specs.add(new org.eclipse.jgit.transport.RefSpec(":" + ref));
            }
            Map<String, String> refused = push(specs);
            List<String> done = new ArrayList<>();
            for (String ref : refs) {
                if (!refused.containsKey(ref)) {
                    done.add(ref);
                }
            }
            pendingRemovals().remove(done.toArray(new String[0]));
        } catch (Exception e) {
            log.warn("could not remove {} from the remote yet: {}", String.join(", ", refs), e.getMessage());
        } finally {
            remoteLock.unlock();
        }
    }

    /** Sends refs to the remote, never forced; remembers why one did not go. */
    void sendQuietly(String... refs) {
        if (!properties.hasRemote()) {
            return;
        }
        remoteLock.lock();
        try {
            ensureOrigin();
            List<org.eclipse.jgit.transport.RefSpec> specs = new ArrayList<>();
            for (String ref : refs) {
                specs.add(new org.eclipse.jgit.transport.RefSpec(ref + ":" + ref));
            }
            Map<String, String> refused = push(specs);
            for (String ref : refs) {
                if (refused.containsKey(ref)) {
                    notSent.put(ref, refused.get(ref));
                } else {
                    notSent.remove(ref);
                }
            }
        } catch (Exception e) {
            for (String ref : refs) {
                notSent.put(ref, "the remote could not be reached: " + e.getMessage());
            }
        } finally {
            remoteLock.unlock();
        }
    }

    /** Pushes the given specs (which may be forced, "+…") under the remote lock; returns what was refused. */
    Map<String, String> pushForced(List<org.eclipse.jgit.transport.RefSpec> specs) throws GitAPIException, IOException {
        remoteLock.lock();
        try {
            ensureOrigin();
            return push(specs);
        } finally {
            remoteLock.unlock();
        }
    }

    /**
     * Pushes, never forced. Returns the refs the remote refused, with a reason a person can act on.
     * Callers hold {@link #remoteLock}.
     */
    Map<String, String> push(List<org.eclipse.jgit.transport.RefSpec> specs) throws GitAPIException, IOException {
        Map<String, String> refused = new java.util.LinkedHashMap<>();
        if (specs.isEmpty()) {
            return refused;
        }
        // Removing a ref the remote never had is done already; JGit would call the push empty.
        List<org.eclipse.jgit.transport.RefSpec> live = new ArrayList<>();
        try (Git git = Git.open(mainCheckout().toFile())) {
            for (org.eclipse.jgit.transport.RefSpec spec : specs) {
                if (spec.getSource() == null || spec.isWildcard()
                        || git.getRepository().resolve(spec.getSource()) != null) {
                    live.add(spec);
                }
            }
        }
        specs = live;
        if (specs.isEmpty()) {
            return refused;
        }
        try (Git git = Git.open(mainCheckout().toFile())) {
            Iterable<org.eclipse.jgit.transport.PushResult> results = git.push().setRemote("origin")
                    .setCredentialsProvider(credentials()).setRefSpecs(specs).setForce(false)
                    .setTimeout(properties.getRemoteTimeoutSeconds()).call();
            for (org.eclipse.jgit.transport.PushResult result : results) {
                for (org.eclipse.jgit.transport.RemoteRefUpdate update : result.getRemoteUpdates()) {
                    switch (update.getStatus()) {
                        case OK:
                        case UP_TO_DATE:
                        case NON_EXISTING:
                            break;
                        case REJECTED_NONFASTFORWARD:
                        case REJECTED_REMOTE_CHANGED:
                            refused.put(update.getRemoteName(), "another instance saved a newer version of it; both versions are kept, and they need joining");
                            break;
                        default:
                            refused.put(update.getRemoteName(), update.getStatus()
                                    + (update.getMessage() == null ? "" : ": " + update.getMessage()));
                    }
                }
            }
        }
        return refused;
    }

    /** The main checkout talks to the remote as origin; a repository first made offline gains it here. */
    void ensureOrigin() throws IOException, GitAPIException {
        try (Git git = Git.open(mainCheckout().toFile())) {
            if (git.getRepository().getConfig().getString("remote", "origin", "url") == null) {
                git.remoteAdd().setName("origin").setUri(new org.eclipse.jgit.transport.URIish(properties.getRemoteUrl())).call();
            }
        } catch (java.net.URISyntaxException e) {
            throw new IOException("dmn.git.remote-url is not a repository address: " + e.getMessage(), e);
        }
    }

    ReentrantLock remoteLock() {
        return remoteLock;
    }

    ReentrantLock lifecycleLock() {
        return lifecycleLock;
    }

    CredentialsProvider remoteCredentials() {
        return credentials();
    }

    GitProperties properties() {
        return properties;
    }

    void clearNotSent(String ref) {
        notSent.remove(ref);
    }

    void markNotSent(String ref, String reason) {
        notSent.put(ref, reason);
    }

    // --- cleanup support -----------------------------------------------------------------------

    /** The lock every ref-moving operation holds; cleanup takes it for its whole transaction. */
    ReentrantLock lock() {
        return refLock;
    }

    /** Last time anything in a space was touched: its newest saved version or an unsaved edit. */
    Instant lastActivity(WorkspaceId id, String headWhen) throws IOException {
        Instant latest = headWhen == null ? Instant.EPOCH : Instant.parse(headWhen);
        Path checkout = checkoutOf(id);
        for (String dir : new String[]{"dmn", "yaml"}) {
            Path d = checkout.resolve(dir);
            if (!Files.isDirectory(d)) {
                continue;
            }
            try (java.util.stream.Stream<Path> files = Files.list(d)) {
                for (Path f : (Iterable<Path>) files::iterator) {
                    Instant modified = Files.getLastModifiedTime(f).toInstant();
                    if (modified.isAfter(latest)) {
                        latest = modified;
                    }
                }
            }
        }
        return latest;
    }

    /** Recreates a space from an archived commit: its branch, its own checkout and its baseline record. */
    public Workspace restoreWorkspace(WorkspaceId id, String sha, String baseline, String targetLine)
            throws IOException, GitAPIException {
        refLock.lock();
        java.util.concurrent.locks.Lock space = spaceLock(id).writeLock();
        space.lock();
        try {
            Path checkout = checkoutOf(id);
            try (Git main = Git.open(mainCheckout().toFile())) {
                if (main.getRepository().resolve(Constants.R_HEADS + id.branch()) != null
                        || Files.exists(checkout.resolve(Constants.DOT_GIT))) {
                    throw new IllegalStateException("a space called " + id.branch() + " exists again; restore it under another name");
                }
                main.branchCreate().setName(id.branch()).setStartPoint(sha)
                        .setUpstreamMode(CreateBranchCommand.SetupUpstreamMode.NOTRACK).call();
            }
            Files.createDirectories(checkout);
            Git.cloneRepository().setURI(mainCheckout().toUri().toString())
                    .setDirectory(checkout.toFile()).setBranch(id.branch()).call().close();
            Files.writeString(baselineMarker(checkout), baseline + "\n" + targetLine + "\n");
            writeSpaceMeta(id, baseline, targetLine);
            pendingRemovals().remove(Constants.R_HEADS + id.branch(), SPACE_META + id.branch());
            sendQuietly(Constants.R_HEADS + id.branch(), SPACE_META + id.branch());
            return describe(id);
        } finally {
            space.unlock();
            refLock.unlock();
        }
    }

    /** The baseline and target line a space was created with, as recorded at creation. */
    String[] baselineOf(WorkspaceId id) throws IOException {
        Path marker = baselineMarker(checkoutOf(id));
        if (!Files.exists(marker)) {
            String[] meta = readSpaceMeta(id);
            return meta != null ? meta : new String[]{"", properties.getMainBranch()};
        }
        List<String> lines = Files.readAllLines(marker);
        return new String[]{lines.isEmpty() ? "" : lines.get(0),
                lines.size() > 1 ? lines.get(1) : properties.getMainBranch()};
    }

    // --- internals ---------------------------------------------------------------------------

    private Workspace describe(WorkspaceId id) throws IOException, GitAPIException {
        Path checkout = checkoutOf(id);
        String baseline = "";
        String targetLine = properties.getMainBranch();
        Path marker = baselineMarker(checkout);
        if (Files.exists(marker)) {
            List<String> lines = Files.readAllLines(marker);
            baseline = lines.isEmpty() ? "" : lines.get(0);
            targetLine = lines.size() > 1 ? lines.get(1) : targetLine;
        }

        try (Git git = Git.open(checkout.toFile())) {
            Repository repository = git.getRepository();
            ObjectId head = repository.resolve(Constants.HEAD);
            String sha = head == null ? null : head.getName();
            String message = null;
            String by = null;
            String at = null;
            if (head != null) {
                try (RevWalk walk = new RevWalk(repository)) {
                    RevCommit commit = walk.parseCommit(head);
                    message = commit.getShortMessage();
                    by = commit.getAuthorIdent().getName();
                    at = Instant.ofEpochSecond(commit.getCommitTime()).toString();
                }
            }
            boolean dirty = !git.status().call().isClean();
            int[] aheadBehind = aheadBehind(id.branch(), targetLine, baseline);
            return new Workspace(id.branch(), id.kind.name(), id.owner, id.name, baseline, targetLine,
                    sha, message, by, at, dirty, aheadBehind[0], aheadBehind[1]);
        }
    }

    /**
     * Saved versions this branch has that its line does not, and the reverse. Read from the shared
     * repository, where both refs live; a line that does not exist yet (a hotfix line still to be
     * recreated from its tag) counts from the baseline instead.
     */
    private int[] aheadBehind(String branch, String line, String baseline) throws IOException {
        try (Git git = Git.open(mainCheckout().toFile())) {
            Repository repository = git.getRepository();
            ObjectId ours = repository.resolve(Constants.R_HEADS + branch);
            ObjectId theirs = repository.resolve(Constants.R_HEADS + line);
            if (theirs == null && baseline != null && !baseline.isEmpty()) {
                theirs = repository.resolve(baseline + "^{commit}");
            }
            if (ours == null || theirs == null) {
                return new int[]{0, 0};
            }
            return new int[]{count(repository, ours, theirs), count(repository, theirs, ours)};
        }
    }

    private static int count(Repository repository, ObjectId from, ObjectId excluding) throws IOException {
        try (RevWalk walk = new RevWalk(repository)) {
            walk.markStart(walk.parseCommit(from));
            walk.markUninteresting(walk.parseCommit(excluding));
            int n = 0;
            for (RevCommit ignored : walk) {
                n++;
            }
            return n;
        }
    }

    /** Local metadata, deliberately inside .git so it never shows up as an uncommitted change. */
    private static Path baselineMarker(Path checkout) {
        return checkout.resolve(Constants.DOT_GIT).resolve("camunda-eval-baseline");
    }

    private RevCommit commitAll(Git git, String message, String author) throws GitAPIException {
        PersonIdent ident = new PersonIdent(
                author == null || author.isBlank() ? properties.getAuthorName() : author,
                properties.getAuthorEmail());
        // The committer names the instance the version was saved on, so "saved on stg" can be shown.
        PersonIdent committer = instance.isEmpty() ? ident : new PersonIdent(instance, instance + "@instance");
        return git.commit().setMessage(message).setAuthor(ident).setCommitter(committer).call();
    }

    private CredentialsProvider credentials() {
        if (properties.getUsername() == null || properties.getUsername().isBlank()) {
            return null;
        }
        return new UsernamePasswordCredentialsProvider(properties.getUsername(), properties.getToken());
    }

    private static String shortName(String refName) {
        return refName.startsWith(Constants.R_HEADS) ? refName.substring(Constants.R_HEADS.length()) : refName;
    }

    private static void deleteRecursively(Path path) throws IOException {
        try (var stream = Files.walk(path)) {
            for (Path entry : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(entry);
            }
        }
    }

    static String utf8(byte[] bytes) {
        return bytes == null ? null : new String(bytes, StandardCharsets.UTF_8);
    }
}
