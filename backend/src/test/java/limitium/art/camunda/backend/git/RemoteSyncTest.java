package limitium.art.camunda.backend.git;

import limitium.art.camunda.backend.config.GitProperties;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Constants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two instances, one remote: everything that moves between them moves through git, and nothing is
 * ever overwritten — work both sides did is kept and reported.
 */
class RemoteSyncTest {

    private Path root;
    private GitRuleStore a;
    private GitRuleStore b;
    private RemoteSync syncA;
    private RemoteSync syncB;

    @BeforeEach
    void setUp() throws Exception {
        root = Path.of("build/tmp/remote-sync-test", String.valueOf(System.nanoTime())).toAbsolutePath();
        Path remote = root.resolve("remote.git");
        Git.init().setBare(true).setDirectory(remote.toFile()).setInitialBranch("main").call().close();
        // The first instance makes the repository and sends it; the second starts from the remote.
        a = instance("a", remote);
        syncA = new RemoteSync(a);
        RemoteSync.Report first = syncA.run();
        assertTrue(first.ok, first.error);
        b = instance("b", remote);
        syncB = new RemoteSync(b);
        RemoteSync.Report second = syncB.run();
        assertTrue(second.ok, second.error);
    }

    private GitRuleStore instance(String name, Path remote) throws Exception {
        GitProperties p = new GitProperties();
        // each instance has its own name; a space belongs to the one that made it
        p.setEnabled(true);
        p.setRemoteUrl(remote.toUri().toString());
        GitRuleStore store = new GitRuleStore(p, root.resolve(name));
        store.setInstance(name);
        store.initialise();
        return store;
    }

    private static WorkspaceId space(String owner, String name) {
        return WorkspaceId.of(WorkspaceId.Kind.FEATURE, owner, name);
    }

    @Test
    @DisplayName("a space started on one instance is continued on another, and the first follows it")
    void spaceContinuesElsewhere() throws Exception {
        WorkspaceId id = space("alice", "promo");
        a.createWorkspace(id, "main", "main");
        Files.writeString(a.checkoutOf(id).resolve("dmn/pricing.dmn"), "v1");
        a.commit(id, "first", "alice");

        syncB.run();
        assertEquals(1, b.spacesElsewhere().size());
        Workspace opened = b.openWorkspace(id);
        assertEquals("main", opened.baseline, "the baseline travelled with the space");
        Files.writeString(b.checkoutOf(id).resolve("dmn/pricing.dmn"), "v2 on b");
        String second = b.commit(id, "second", "alice");
        assertEquals("b", b.savedState(id).get("savedOn"), "a save names the instance it was made on");

        syncA.run();
        assertEquals("SAVED", a.savedState(id).get("state"), "a's checkout had nothing unsaved, so it followed");
        assertEquals(second, a.savedState(id).get("here"));
        assertEquals("v2 on b", Files.readString(a.checkoutOf(id).resolve("dmn/pricing.dmn")));
    }

    @Test
    @DisplayName("a save behind the branch is refused and keeps the edits; refreshing merges them over the newer version")
    void saveBehindIsRefusedThenRefreshed() throws Exception {
        WorkspaceId id = space("alice", "promo");
        a.createWorkspace(id, "main", "main");
        Files.writeString(a.checkoutOf(id).resolve("dmn/pricing.dmn"), "pricing v1");
        Files.writeString(a.checkoutOf(id).resolve("dmn/fees.dmn"), "fees v1");
        a.commit(id, "first", "alice");
        syncB.run();
        b.openWorkspace(id);
        Files.writeString(b.checkoutOf(id).resolve("dmn/pricing.dmn"), "pricing v2 from b");
        String fromB = b.commit(id, "b changes pricing", "alice");

        Files.writeString(a.checkoutOf(id).resolve("dmn/fees.dmn"), "fees v2 from a");   // unsaved on a
        GitRuleStore.NotSaved refused = assertThrows(GitRuleStore.NotSaved.class, () -> a.commit(id, "a changes fees", "alice"));
        assertEquals("BEHIND", refused.code);
        assertEquals("fees v2 from a", Files.readString(a.checkoutOf(id).resolve("dmn/fees.dmn")), "the edit is kept");
        try (Git remote = Git.open(root.resolve("remote.git").toFile())) {
            assertEquals(fromB, remote.getRepository().resolve(Constants.R_HEADS + id.branch()).getName(), "the branch is b's; nothing forked");
        }

        Landing landing = new Landing(a, syncA, new limitium.art.camunda.backend.lifecycle.TestGate(a, new limitium.art.camunda.backend.service.TestExecutionService()));
        Landing.Result refreshed = landing.refresh(id, null, true, "alice");
        assertEquals("REFRESHED", refreshed.status, refreshed.message);
        assertEquals("pricing v2 from b", Files.readString(a.checkoutOf(id).resolve("dmn/pricing.dmn")), "b's saved change came in");
        assertEquals("fees v2 from a", Files.readString(a.checkoutOf(id).resolve("dmn/fees.dmn")), "a's edit is still there, unsaved");
        assertEquals("UNSAVED", a.savedState(id).get("state"));
        a.commit(id, "a changes fees", "alice");
        assertEquals("SAVED", a.savedState(id).get("state"));
    }

    @Test
    @DisplayName("with the remote unreachable nothing is saved, and the edits stay")
    void unreachableRemoteSavesNothing() throws Exception {
        WorkspaceId id = space("alice", "promo");
        a.createWorkspace(id, "main", "main");
        Files.writeString(a.checkoutOf(id).resolve("dmn/pricing.dmn"), "v1");
        String first = a.commit(id, "first", "alice");
        Path remote = root.resolve("remote.git");
        Path away = root.resolve("remote-away.git");
        Files.move(remote, away);
        try {
            Files.writeString(a.checkoutOf(id).resolve("dmn/pricing.dmn"), "v2");
            GitRuleStore.NotSaved refused = assertThrows(GitRuleStore.NotSaved.class, () -> a.commit(id, "second", "alice"));
            assertEquals("REMOTE_UNREACHABLE", refused.code);
            assertEquals(first, a.sharedHead(id), "the branch did not move here either");
            assertEquals("UNSAVED", a.savedState(id).get("state"));
        } finally {
            Files.move(away, remote);
        }
        a.commit(id, "second", "alice");
        assertEquals("SAVED", a.savedState(id).get("state"));
    }

    @Test
    @DisplayName("a newer version of a space reaches the other instance at the next exchange")
    void newerVersionsArrive() throws Exception {
        WorkspaceId id = space("alice", "promo");
        a.createWorkspace(id, "main", "main");
        Files.writeString(a.checkoutOf(id).resolve("dmn/pricing.dmn"), "v1");
        a.commit(id, "first", "alice");
        syncB.run();
        Files.writeString(a.checkoutOf(id).resolve("dmn/pricing.dmn"), "v2");
        String second = a.commit(id, "second", "alice");
        RemoteSync.Report report = syncB.run();
        assertTrue(report.takenIn.contains(id.branch()), String.valueOf(report.takenIn));
        assertEquals(second, b.resolve("refs/heads/" + id.branch()));
    }

    @Test
    @DisplayName("a space removed on its instance goes from the others too")
    void removalTravels() throws Exception {
        WorkspaceId done = space("alice", "done");
        a.createWorkspace(done, "main", "main");
        Files.writeString(a.checkoutOf(done).resolve("dmn/x.dmn"), "x");
        a.commit(done, "v", "alice");
        syncB.run();
        assertEquals(1, b.spacesElsewhere().size());
        a.deleteWorkspace(done);
        RemoteSync.Report report = syncB.run();
        assertTrue(report.removed.contains(done.branch()), String.valueOf(report.removed));
        assertTrue(b.spacesElsewhere().isEmpty());
    }

    @Test
    @DisplayName("each instance declares its servers through git; a server two instances declare is caught")
    void serversAreDeclaredAndOverlapsCaught() throws Exception {
        Instances ia = new Instances(a);
        Instances ib = new Instances(b);
        java.util.Map<String, Object> devTopic = java.util.Map.of("name", "dev", "type", "kafka", "topic", "dmn-rules", "where", "k1:9092,k2:9092");
        java.util.Map<String, Object> stgTopic = java.util.Map.of("name", "stg", "type", "kafka", "topic", "dmn-rules-stg", "where", "k1:9092");
        ia.declare("a", java.util.List.of(devTopic));
        syncA.afterFetch(() -> {
            try {
                ia.declare("a", java.util.List.of(devTopic));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        syncA.run();
        syncB.run();
        assertTrue(ib.overlaps("b", java.util.List.of(stgTopic)).isEmpty(), "different topics: their own servers");
        java.util.Map<String, Object> sameTopic = java.util.Map.of("name", "mine", "type", "kafka", "topic", "dmn-rules", "where", "k2:9092");
        java.util.List<Instances.Overlap> overlaps = ib.overlaps("b", java.util.List.of(sameTopic));
        assertEquals(1, overlaps.size());
        assertEquals("a", overlaps.get(0).otherInstance);
    }

    @Test
    @DisplayName("a removal made while the remote was unreachable is finished later, not undone")
    void offlineRemovalIsFinished() throws Exception {
        WorkspaceId id = space("alice", "gone");
        a.createWorkspace(id, "main", "main");
        Files.writeString(a.checkoutOf(id).resolve("dmn/x.dmn"), "x");
        a.commit(id, "v", "alice");
        Path remote = root.resolve("remote.git");
        Path away = root.resolve("remote-away.git");
        Files.move(remote, away);           // the remote is unreachable
        a.deleteWorkspace(id);
        Files.move(away, remote);           // and back
        RemoteSync.Report report = syncA.run();
        assertTrue(report.ok, report.error);
        assertNull(a.resolve(Constants.R_HEADS + id.branch()), "not brought back from the remote");
        try (Git r = Git.open(remote.toFile())) {
            assertNull(r.getRepository().resolve(Constants.R_HEADS + id.branch()), "and gone from the remote");
        }
    }

    @Test
    @DisplayName("a version made on one instance is deployable on the other; versions never move")
    void versionsTravelAndNeverMove() throws Exception {
        try (Git g = Git.open(a.mainCheckout().toFile())) {
            g.tag().setName("v1.0.0").setAnnotated(true).setMessage("first").call();
        }
        syncA.run();
        syncB.run();
        String there = b.resolve("refs/tags/v1.0.0");
        assertEquals(a.resolve("refs/tags/v1.0.0"), there);

        // The same version name made differently on b is refused, not overwritten either way.
        try (Git g = Git.open(b.mainCheckout().toFile())) {
            g.commit().setMessage("other").setAllowEmpty(true).call();
            g.tag().setName("v1.0.0").setAnnotated(true).setForceUpdate(true).setMessage("impostor").call();
        }
        RemoteSync.Report report = syncB.run();
        assertTrue(report.refused.keySet().stream().anyMatch(k -> k.contains("v1.0.0")), String.valueOf(report.refused));
        syncA.run();
        assertEquals(there, a.resolve("refs/tags/v1.0.0"));
    }
}
