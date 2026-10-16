package limitium.art.camunda.backend.git;

import org.eclipse.jgit.api.FetchCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.FetchResult;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.TrackingRefUpdate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.locks.ReentrantLock;

/**
 * How instances meet: only through the remote repository. Each round fetches what other instances
 * sent, takes in what moved forward, sends what moved here, and reports what cannot be joined
 * without a person — never forcing anything either way.
 *
 * <p>Per branch, comparing this instance (here) with the remote (there):
 * <ul>
 *   <li>only there — new elsewhere: taken in;</li>
 *   <li>only here, and there before — removed elsewhere: removed here too, unless this instance
 *       holds work on it the remote never had, which is then sent back rather than lost;</li>
 *   <li>only here, never there — new here: sent;</li>
 *   <li>there ahead — taken in (fast-forward), and a space's checkout follows when it holds nothing
 *       of its own;</li>
 *   <li>here ahead — sent;</li>
 *   <li>both moved — diverged: both versions kept, reported, nothing overwritten.</li>
 * </ul>
 * Versions (tags) are never moved: a tag that differs between the two is reported, not replaced.
 */
public class RemoteSync {

    private static final Logger log = LoggerFactory.getLogger(RemoteSync.class);
    private static final String THERE = Constants.R_REMOTES + "origin/";

    /** What one round did. */
    public static class Report {
        public String at;
        public boolean ok = true;
        public String error;
        public List<String> takenIn = new ArrayList<>();
        public List<String> sent = new ArrayList<>();
        public List<String> removed = new ArrayList<>();
        public Map<String, String> diverged = new TreeMap<>();
        public Map<String, String> refused = new TreeMap<>();
        /** Spaces whose checkout could not follow (unsaved edits here): saving will say so. */
        public List<String> leftBehind = new ArrayList<>();
    }

    private final GitRuleStore store;
    /** One round at a time; taken before any other lock and never while holding one. */
    private final ReentrantLock round = new ReentrantLock();
    private volatile Report last;
    /** Runs after every fetch, before sending: re-declares this instance's servers over whatever came in. */
    private volatile Runnable afterFetch = () -> { };

    public void afterFetch(Runnable hook) {
        this.afterFetch = hook == null ? () -> { } : hook;
    }

    public RemoteSync(GitRuleStore store) {
        this.store = store;
    }

    // A deploy-only instance (production) only reads the remote: it sends nothing but the declaration
    // of its own servers, which keeps its brokers from being claimed by another instance.
    private volatile boolean readOnly;

    public void readOnly(boolean readOnly) {
        this.readOnly = readOnly;
    }

    public boolean enabled() {
        return store.properties().isEnabled() && store.properties().hasRemote();
    }

    public Report last() {
        return last;
    }

    /** One round; never throws — a remote that cannot be reached is reported, the editor keeps working. */
    public Report run() {
        Report report = new Report();
        report.at = Instant.now().toString();
        if (!enabled()) {
            report.ok = false;
            report.error = "no remote repository is configured (dmn.git.remote-url)";
            return report;
        }
        round.lock();
        try {
            Map<String, ObjectId> thereBefore = refs(THERE);
            fetch(report);
            forgetAgain();
            afterFetch.run();
            Map<String, ObjectId> thereNow = refs(THERE);
            List<String> toSend = new ArrayList<>();
            List<WorkspaceId> toRefresh = new ArrayList<>();
            List<WorkspaceId> toRemove = new ArrayList<>();
            reconcile(thereBefore, thereNow, toSend, toRefresh, toRemove, report);
            for (WorkspaceId id : toRefresh) {
                try {
                    if (!store.refreshCheckout(id)) {
                        report.leftBehind.add(id.branch());
                    }
                } catch (Exception e) {
                    report.leftBehind.add(id.branch());
                }
            }
            for (WorkspaceId id : toRemove) {
                removeHere(id, report);
            }
            send(toSend, report);
        } catch (Exception e) {
            report.ok = false;
            report.error = e.getMessage();
            log.warn("sync with {} failed: {}", store.properties().getRemoteUrl(), e.getMessage());
        } finally {
            round.unlock();
        }
        if (!report.diverged.isEmpty()) {
            log.warn("diverged from the remote: {}", report.diverged.keySet());
        }
        last = report;
        return report;
    }

    private void fetch(Report report) throws IOException, GitAPIException {
        ReentrantLock remote = store.remoteLock();
        remote.lock();
        try (Git git = Git.open(store.mainCheckout().toFile())) {
            store.ensureOrigin();
            // Branches mirror into refs/remotes/origin, pruned, so "removed elsewhere" can be seen.
            git.fetch().setRemote("origin").setCredentialsProvider(store.remoteCredentials())
                    .setTimeout(store.properties().getRemoteTimeoutSeconds())
                    .setRefSpecs(new RefSpec("+refs/heads/*:" + THERE + "*"))
                    .setRemoveDeletedRefs(true)
                    .call();
            // Versions, space records and archives come in as they are; never pruned (an unsent one
            // here would vanish) and versions never forced (a version means one thing forever).
            FetchCommand rest = git.fetch().setRemote("origin").setCredentialsProvider(store.remoteCredentials())
                    .setTimeout(store.properties().getRemoteTimeoutSeconds())
                    .setTagOpt(org.eclipse.jgit.transport.TagOpt.NO_TAGS)
                    .setRefSpecs(new RefSpec("refs/tags/*:refs/tags/*"),
                            new RefSpec("+" + GitRuleStore.SPACE_META + "*:" + GitRuleStore.SPACE_META + "*"),
                            new RefSpec("refs/archive/*:refs/archive/*"),
                            new RefSpec("+" + Instances.PREFIX + "*:" + Instances.PREFIX + "*"))
                    .setRemoveDeletedRefs(false);
            FetchResult result = rest.call();
            for (TrackingRefUpdate update : result.getTrackingRefUpdates()) {
                if (update.getResult() == RefUpdate.Result.REJECTED || update.getResult() == RefUpdate.Result.LOCK_FAILURE) {
                    report.refused.put(update.getLocalName(), "differs between this instance and the remote; left as it is here");
                }
            }
        } finally {
            remote.unlock();
        }
    }

    /**
     * A ref removed here but still on the remote comes back with every fetch until the removal
     * reaches the remote; remove it again so it is neither shown nor sent back.
     */
    private void forgetAgain() throws IOException {
        List<String> pending = store.pendingRemovals().all();
        if (pending.isEmpty()) {
            return;
        }
        ReentrantLock refLock = store.lock();
        refLock.lock();
        try (Git git = Git.open(store.mainCheckout().toFile())) {
            for (String ref : pending) {
                if (ref.startsWith(Constants.R_HEADS)) {
                    continue; // branches are fetched into refs/remotes, never over refs/heads
                }
                ObjectId current = git.getRepository().resolve(ref);
                if (current != null) {
                    moveRef(git.getRepository(), ref, current, null);
                }
            }
        } finally {
            refLock.unlock();
        }
    }

    private void reconcile(Map<String, ObjectId> thereBefore, Map<String, ObjectId> thereNow,
                           List<String> toSend, List<WorkspaceId> toRefresh, List<WorkspaceId> toRemove,
                           Report report) throws IOException {
        ReentrantLock refLock = store.lock();
        refLock.lock();
        try (Git git = Git.open(store.mainCheckout().toFile())) {
            Repository repository = git.getRepository();
            Map<String, ObjectId> here = refs(Constants.R_HEADS);
            List<String> pending = store.pendingRemovals().all();
            TreeSet<String> names = new TreeSet<>(here.keySet());
            names.addAll(thereNow.keySet());
            for (String name : names) {
                String ref = Constants.R_HEADS + name;
                if (pending.contains(ref)) {
                    continue; // removed here, still to be removed there: sent below
                }
                ObjectId mine = here.get(name);
                ObjectId theirs = thereNow.get(name);
                ObjectId seen = thereBefore.get(name);
                WorkspaceId space = WorkspaceId.parse(name);
                if (theirs == null) {
                    if (seen != null && seen.equals(mine) && !holdsWork(space)) {
                        if (space != null) {
                            toRemove.add(space);
                        } else if (moveRef(repository, ref, mine, null)) {
                            report.removed.add(name);
                        }
                    } else {
                        toSend.add(ref); // new here, or work here the removal would lose
                    }
                } else if (mine == null) {
                    if (moveRef(repository, ref, null, theirs)) {
                        report.takenIn.add(name);
                    }
                } else if (!mine.equals(theirs)) {
                    if (GitRuleStore.isAncestor(repository, mine, theirs)) {
                        if (moveRef(repository, ref, mine, theirs)) {
                            report.takenIn.add(name);
                            if (space != null && Files.exists(store.checkoutOf(space).resolve(Constants.DOT_GIT))) {
                                toRefresh.add(space);
                            }
                        }
                    } else if (GitRuleStore.isAncestor(repository, theirs, mine)) {
                        toSend.add(ref);
                    } else if (space != null) {
                        // A space has one branch, on the remote: the single place it is written. A local
                        // version the remote never took (made before saves were all-or-nothing) is not
                        // lost: it stays in this checkout, which then reads as behind — refreshing merges
                        // it over the saved version as unsaved changes.
                        if (moveRef(repository, ref, mine, theirs)) {
                            report.takenIn.add(name);
                            store.clearNotSent(ref);
                        }
                    } else {
                        String why = "this instance and another both moved this line; both are kept, and they need joining";
                        report.diverged.put(name, why);
                        store.markNotSent(ref, why);
                    }
                } else {
                    store.clearNotSent(ref);
                }
            }
        } finally {
            refLock.unlock();
        }
    }

    /** A space checkout here holding something the remote never had: unsaved edits. */
    private boolean holdsWork(WorkspaceId space) {
        if (space == null || !Files.exists(store.checkoutOf(space).resolve(Constants.DOT_GIT))) {
            return false;
        }
        try {
            return store.isDirty(space);
        } catch (Exception e) {
            return true; // unsure: keep it
        }
    }

    private void removeHere(WorkspaceId id, Report report) {
        try {
            // Removed on another instance (a tidy-up there); removing it here must not count as a
            // removal still to send, and must not pass through the remote removal again.
            store.lock().lock();
            java.util.concurrent.locks.Lock space = store.spaceLock(id).writeLock();
            space.lock();
            try {
                if (holdsWork(id)) {
                    return;
                }
                java.nio.file.Path checkout = store.checkoutOf(id);
                if (Files.exists(checkout)) {
                    try (var walk = Files.walk(checkout)) {
                        for (java.nio.file.Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                            Files.deleteIfExists(p);
                        }
                    }
                }
                try (Git git = Git.open(store.mainCheckout().toFile())) {
                    ObjectId current = git.getRepository().resolve(Constants.R_HEADS + id.branch());
                    moveRef(git.getRepository(), Constants.R_HEADS + id.branch(), current, null);
                }
                report.removed.add(id.branch());
            } finally {
                space.unlock();
                store.lock().unlock();
            }
        } catch (Exception e) {
            report.refused.put(id.branch(), "could not remove it here: " + e.getMessage());
        }
    }

    private void send(List<String> toSend, Report report) throws IOException, GitAPIException {
        ReentrantLock remote = store.remoteLock();
        remote.lock();
        try {
            List<RefSpec> specs = new ArrayList<>();
            List<String> removals = readOnly ? new ArrayList<>() : store.pendingRemovals().all();
            if (readOnly) {
                toSend.clear();
            }
            for (String ref : toSend) {
                specs.add(new RefSpec(ref + ":" + ref));
            }
            for (String ref : removals) {
                specs.add(new RefSpec(":" + ref));
            }
            // Versions, space records and archives made here; never forced, so one that differs is refused.
            for (String prefix : new String[]{Constants.R_TAGS, GitRuleStore.SPACE_META, "refs/archive/"}) {
                if (!readOnly && !refs(prefix).isEmpty()) { // a pattern matching nothing makes the whole push fail
                    specs.add(new RefSpec(prefix + "*:" + prefix + "*"));
                }
            }
            // This instance's declaration of its servers: only it writes that ref, so it is sent as it is.
            String mine = Instances.PREFIX + store.instance();
            if (!store.instance().isEmpty() && refs(Instances.PREFIX).containsKey(store.instance())) {
                specs.add(new RefSpec("+" + mine + ":" + mine));
            }
            Map<String, String> refused = store.push(specs);
            for (String ref : toSend) {
                if (refused.containsKey(ref)) {
                    report.refused.put(ref, refused.get(ref));
                    store.markNotSent(ref, refused.get(ref));
                } else {
                    report.sent.add(ref.substring(Constants.R_HEADS.length()));
                    store.clearNotSent(ref);
                }
            }
            List<String> gone = new ArrayList<>();
            for (String ref : removals) {
                if (refused.containsKey(ref)) {
                    report.refused.put(ref, refused.get(ref));
                } else {
                    gone.add(ref);
                }
            }
            store.pendingRemovals().remove(gone.toArray(new String[0]));
            for (Map.Entry<String, String> e : refused.entrySet()) {
                if (!toSend.contains(e.getKey()) && !removals.contains(e.getKey())) {
                    report.refused.put(e.getKey(), e.getValue());
                }
            }
        } finally {
            remote.unlock();
        }
    }

    /** Compare-and-swap on one ref; null for "absent". */
    private static boolean moveRef(Repository repository, String ref, ObjectId expected, ObjectId to) throws IOException {
        RefUpdate update = repository.updateRef(ref);
        update.setExpectedOldObjectId(expected == null ? ObjectId.zeroId() : expected);
        update.setForceUpdate(true);
        RefUpdate.Result result;
        if (to == null) {
            result = update.delete();
        } else {
            update.setNewObjectId(to);
            result = update.update();
        }
        return Arrays.asList(RefUpdate.Result.NEW, RefUpdate.Result.FAST_FORWARD, RefUpdate.Result.FORCED,
                RefUpdate.Result.NO_CHANGE).contains(result);
    }

    private Map<String, ObjectId> refs(String prefix) throws IOException {
        Map<String, ObjectId> refs = new LinkedHashMap<>();
        try (Git git = Git.open(store.mainCheckout().toFile())) {
            for (Ref ref : git.getRepository().getRefDatabase().getRefsByPrefix(prefix)) {
                String name = ref.getName().substring(prefix.length());
                if (!name.equals("HEAD")) {
                    refs.put(name, ref.getObjectId());
                }
            }
        }
        return refs;
    }
}
