package limitium.art.camunda.backend.git;

import limitium.art.camunda.backend.config.GitProperties;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tidying may remove only what exists elsewhere, archives the rest, and is all or nothing. */
class BranchCleanupTest {

    private static final Instant LATER = Instant.now().plus(Duration.ofDays(60));

    private GitProperties properties;
    private GitRuleStore store;
    private BranchCleanup cleanup;

    @BeforeEach
    void setUp() throws Exception {
        Path workRoot = Path.of("build/tmp/branch-cleanup-test", String.valueOf(System.nanoTime()));
        properties = new GitProperties();
        properties.setEnabled(true);
        properties.setStaleAfterDays(30);
        properties.setKeepReleaseLines(2);
        store = new GitRuleStore(properties, workRoot);
        store.initialise();
        cleanup = new BranchCleanup(store, properties);
    }

    // --- spaces ------------------------------------------------------------------------------------

    @Test
    @DisplayName("a space holding nothing its line lacks is removed outright once idle")
    void emptySpaceIsRemoved() throws Exception {
        WorkspaceId id = space("alice", "idle");
        assertTrue(cleanup.plan().items.isEmpty(), "a space just opened is never offered");
        BranchCleanup.Item item = only(cleanup.plan(LATER), id.branch());
        assertEquals(BranchCleanup.Action.REMOVE, item.action);
        assertEquals(BranchCleanup.Reason.NOTHING_UNIQUE, item.reason);

        cleanup.apply(Collections.singletonMap(item.id, item.head), "bob", LATER);
        assertNull(resolve(Constants.R_HEADS + id.branch()));
        assertFalse(Files.exists(store.checkoutOf(id)));
    }

    @Test
    @DisplayName("a recent space with its own work is left alone")
    void activeSpaceIsKept() throws Exception {
        WorkspaceId id = space("alice", "busy");
        saveVersion(id, "a.dmn", "one");
        BranchCleanup.Plan plan = cleanup.plan();
        assertTrue(plan.items.isEmpty(), "nothing to tidy: " + plan.items.size());
        assertEquals(1, plan.spacesInUse);
    }

    @Test
    @DisplayName("a stale space with unlanded work is archived, and comes back exactly as it was")
    void staleWorkIsArchivedAndRestorable() throws Exception {
        WorkspaceId id = space("carol", "promo");
        String head = saveVersion(id, "a.dmn", "promo");
        BranchCleanup.Item item = only(cleanup.plan(LATER), id.branch());
        assertEquals(BranchCleanup.Action.ARCHIVE, item.action);

        cleanup.apply(Collections.singletonMap(item.id, item.head), "bob", LATER);
        assertNull(resolve(Constants.R_HEADS + id.branch()), "the branch is gone");
        List<BranchCleanup.Archived> archived = cleanup.archived();
        assertEquals(1, archived.size());
        assertEquals(head, archived.get(0).sha, "but its work is kept");
        assertEquals("bob", archived.get(0).archivedBy);
        assertEquals("main", archived.get(0).targetLine);

        Workspace restored = cleanup.restore(archived.get(0).ref);
        assertEquals(head, restored.head);
        assertEquals("main", restored.targetLine);
        assertTrue(Files.exists(store.checkoutOf(id).resolve("dmn/a.dmn")));
        assertTrue(cleanup.archived().isEmpty(), "restored, so no longer in the archive");
    }

    @Test
    @DisplayName("unsaved edits block their space, and choosing it stops the whole run")
    void unsavedEditsBlockEverything() throws Exception {
        WorkspaceId dirty = space("dave", "draft");
        saveVersion(dirty, "a.dmn", "saved");
        Files.writeString(store.checkoutOf(dirty).resolve("dmn/a.dmn"), "unsaved");
        WorkspaceId idle = space("erin", "idle");

        Instant muchLater = Instant.now().plus(Duration.ofDays(90));
        BranchCleanup.Plan plan = cleanup.plan(muchLater);
        BranchCleanup.Item blocked = only(plan, dirty.branch());
        assertEquals(BranchCleanup.Action.BLOCKED, blocked.action);
        assertEquals(BranchCleanup.Reason.UNSAVED_EDITS, blocked.reason);

        Map<String, String> chosen = new LinkedHashMap<>();
        chosen.put(only(plan, idle.branch()).id, only(plan, idle.branch()).head);
        chosen.put(blocked.id, blocked.head);
        assertThrows(BranchCleanup.PlanChanged.class, () -> cleanup.apply(chosen, "bob", muchLater));
        assertNotNull(resolve(Constants.R_HEADS + idle.branch()), "nothing was done, not even the safe part");
        assertEquals("unsaved", Files.readString(store.checkoutOf(dirty).resolve("dmn/a.dmn")));
    }

    @Test
    @DisplayName("a space that moved since the list was shown is not touched")
    void movedSpaceIsRefused() throws Exception {
        WorkspaceId id = space("alice", "moving");
        String shown = saveVersion(id, "a.dmn", "one");
        BranchCleanup.Item item = only(cleanup.plan(LATER), id.branch());
        String now = saveVersion(id, "a.dmn", "two");

        assertThrows(BranchCleanup.PlanChanged.class,
                () -> cleanup.apply(Collections.singletonMap(item.id, shown), "bob", LATER));
        assertEquals(ObjectId.fromString(now), resolve(Constants.R_HEADS + id.branch()));
        assertTrue(cleanup.archived().isEmpty());
    }

    // --- release lines -------------------------------------------------------------------------------

    @Test
    @DisplayName("old lines close only when fully released; newest lines and main are never offered")
    void oldReleasedLinesClose() throws Exception {
        line("release/1.1", "v1.1.0", false);
        line("release/1.2", "v1.2.0", true);   // a commit after its last version
        line("release/1.3", "v1.3.0", false);
        line("release/1.4", "v1.4.0", false);

        BranchCleanup.Plan plan = cleanup.plan();
        assertEquals(List.of("release/1.4", "release/1.3"), plan.linesKept);
        assertEquals(BranchCleanup.Action.CLOSE, only(plan, "release/1.1").action);
        assertEquals(BranchCleanup.Reason.UNRELEASED_CHANGES, only(plan, "release/1.2").reason);
        assertTrue(plan.items.stream().noneMatch(i -> i.id.equals("main")));

        BranchCleanup.Item close = only(plan, "release/1.1");
        cleanup.apply(Collections.singletonMap(close.id, close.head), "bob");
        assertNull(resolve(Constants.R_HEADS + "release/1.1"));
        assertNotNull(resolve(Constants.R_TAGS + "v1.1.0"), "its versions stay, so a fix can recreate the line");
    }

    @Test
    @DisplayName("an old line an open space will land in stays open")
    void lineWithOpenSpaceStays() throws Exception {
        line("release/1.1", "v1.1.0", false);
        line("release/1.2", "v1.2.0", false);
        line("release/1.3", "v1.3.0", false);
        WorkspaceId fix = WorkspaceId.of(WorkspaceId.Kind.HOTFIX, "dave", "rounding");
        store.createWorkspace(fix, "v1.1.0", "release/1.1");

        BranchCleanup.Item item = only(cleanup.plan(), "release/1.1");
        assertEquals(BranchCleanup.Reason.OPEN_SPACE_LANDS_HERE, item.reason);
        assertEquals(List.of(fix.branch()), item.detail);
    }

    // --- helpers ---------------------------------------------------------------------------------------

    private WorkspaceId space(String owner, String name) throws Exception {
        WorkspaceId id = WorkspaceId.of(WorkspaceId.Kind.FEATURE, owner, name);
        store.createWorkspace(id, "main", "main");
        return id;
    }

    private String saveVersion(WorkspaceId id, String file, String content) throws Exception {
        Files.writeString(store.checkoutOf(id).resolve("dmn").resolve(file), content);
        return store.commit(id, "save " + content, id.owner);
    }

    private void line(String name, String tag, boolean unreleasedAfter) throws Exception {
        try (Git git = Git.open(store.mainCheckout().toFile())) {
            git.commit().setAllowEmpty(true).setMessage("work before " + tag).call();
            RevCommit tip = git.commit().setAllowEmpty(true).setMessage(tag).call();
            git.branchCreate().setName(name).setStartPoint(tip).call();
            git.tag().setName(tag).setObjectId(tip).setAnnotated(true).setMessage(tag).call();
            if (unreleasedAfter) {
                git.checkout().setName(name).call();
                git.commit().setAllowEmpty(true).setMessage("unreleased on " + name).call();
                git.checkout().setName("main").call();
            }
        }
    }

    private ObjectId resolve(String ref) throws Exception {
        try (Repository repository = Git.open(store.mainCheckout().toFile()).getRepository()) {
            return repository.resolve(ref);
        }
    }

    private static BranchCleanup.Item only(BranchCleanup.Plan plan, String id) {
        return plan.items.stream().filter(i -> i.id.equals(id)).findFirst()
                .orElseThrow(() -> new AssertionError("no plan item for " + id));
    }
}
