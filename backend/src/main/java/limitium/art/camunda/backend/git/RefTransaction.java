package limitium.art.camunda.backend.git;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteRefUpdate;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Moves several refs as one action, here and on the remote, or none of them.
 *
 * <p>Every step that can fail for a reason of substance (merging, tests) happened before this runs;
 * what remains is pointer updates. Locally each is a compare-and-swap against the value read when
 * the action was computed; on the remote they go in one atomic push, never forced. If anything is
 * refused — a line moved here, another instance moved it there, the remote is unreachable — the
 * local moves are swapped back and the caller is told which, so the person can look again.
 */
final class RefTransaction {

    static final class Move {
        final String ref;
        /** Null when the ref is created. */
        final ObjectId from;
        final ObjectId to;

        Move(String ref, ObjectId from, ObjectId to) {
            this.ref = ref;
            this.from = from;
            this.to = to;
        }
    }

    static final class Refused extends RuntimeException {
        final String code;

        Refused(String code, String message) {
            super(message);
            this.code = code;
        }
    }

    private RefTransaction() {
    }

    static void apply(GitRuleStore store, List<Move> moves) throws IOException {
        apply(store, moves, true);
    }

    /**
     * @param serialise take the server-wide ref lock. A save moves only its own space's branch while
     *                  holding that space's lock, which already excludes creating or removing it; taking
     *                  the ref lock there would invert the lock order.
     */
    static void apply(GitRuleStore store, List<Move> moves, boolean serialise) throws IOException {
        ReentrantLock refLock = serialise ? store.lock() : new ReentrantLock();
        refLock.lock();
        try (Git git = Git.open(store.mainCheckout().toFile())) {
            Repository repository = git.getRepository();
            List<Move> done = new ArrayList<>();
            for (Move move : moves) {
                if (!swap(repository, move.ref, move.from, move.to)) {
                    undo(repository, done);
                    throw new Refused("MOVED", move.ref.replaceFirst("^refs/(heads|tags)/", "")
                            + " changed while this was being prepared; nothing was changed — look again");
                }
                done.add(move);
            }
            if (!store.properties().hasRemote()) {
                return;
            }
            String refused = send(store, git, moves);
            if (refused != null) {
                undo(repository, done);
                throw new Refused(refused.startsWith("unreachable") ? "REMOTE_UNREACHABLE" : "REMOTE_REFUSED",
                        refused.startsWith("unreachable")
                                ? "the shared repository could not be reached, so nothing was changed here either: " + refused.substring(12)
                                : "another instance changed " + refused + " first; nothing was changed — look again");
            }
            for (Move move : moves) {
                store.clearNotSent(move.ref);
            }
        } finally {
            refLock.unlock();
        }
    }

    /** Null when every ref reached the remote; else which one did not, or "unreachable: why". */
    private static String send(GitRuleStore store, Git git, List<Move> moves) {
        ReentrantLock remote = store.remoteLock();
        remote.lock();
        try {
            store.ensureOrigin();
            List<RefSpec> specs = new ArrayList<>();
            for (Move move : moves) {
                specs.add(new RefSpec(move.ref + ":" + move.ref));
            }
            Iterable<PushResult> results;
            try {
                results = git.push().setRemote("origin").setCredentialsProvider(store.remoteCredentials())
                        .setTimeout(store.properties().getRemoteTimeoutSeconds())
                        .setRefSpecs(specs).setForce(false).setAtomic(true).call();
            } catch (org.eclipse.jgit.api.errors.TransportException e) {
                if (e.getMessage() != null && e.getMessage().toLowerCase().contains("atomic")) {
                    results = git.push().setRemote("origin").setCredentialsProvider(store.remoteCredentials())
                            .setTimeout(store.properties().getRemoteTimeoutSeconds())
                            .setRefSpecs(specs).setForce(false).call();
                } else {
                    throw e;
                }
            }
            for (PushResult result : results) {
                for (RemoteRefUpdate update : result.getRemoteUpdates()) {
                    if (update.getStatus() != RemoteRefUpdate.Status.OK && update.getStatus() != RemoteRefUpdate.Status.UP_TO_DATE) {
                        return update.getRemoteName().replaceFirst("^refs/(heads|tags)/", "");
                    }
                }
            }
            return null;
        } catch (Exception e) {
            return "unreachable: " + e.getMessage();
        } finally {
            remote.unlock();
        }
    }

    private static void undo(Repository repository, List<Move> done) throws IOException {
        for (int i = done.size() - 1; i >= 0; i--) {
            Move move = done.get(i);
            swap(repository, move.ref, move.to, move.from);
        }
    }

    /** Compare-and-swap; {@code from} or {@code to} null for "absent". */
    private static boolean swap(Repository repository, String ref, ObjectId from, ObjectId to) throws IOException {
        RefUpdate update = repository.updateRef(ref);
        update.setExpectedOldObjectId(from == null ? ObjectId.zeroId() : from);
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
}
