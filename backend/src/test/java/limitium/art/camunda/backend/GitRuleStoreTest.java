package limitium.art.camunda.backend;

import limitium.art.camunda.backend.config.GitProperties;
import limitium.art.camunda.backend.git.GitRuleStore;
import limitium.art.camunda.backend.git.Workspace;
import limitium.art.camunda.backend.git.WorkspaceId;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Repository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** No network: the repository is initialised locally under build/tmp. */
class GitRuleStoreTest {

    private Path workRoot;
    private GitRuleStore store;

    @BeforeEach
    void setUp() throws Exception {
        workRoot = Path.of("build/tmp/git-store-test", String.valueOf(System.nanoTime()));
        if (Files.exists(workRoot)) {
            try (var walk = Files.walk(workRoot)) {
                for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(p);
                }
            }
        }
        GitProperties properties = new GitProperties();
        properties.setEnabled(true);
        properties.setAuthorName("camunda-eval");
        properties.setAuthorEmail("camunda-eval@localhost");
        store = new GitRuleStore(properties, workRoot);
        store.initialise();
    }

    @Test
    void initialisesWithoutARemote() {
        assertTrue(Files.exists(store.mainCheckout().resolve(Constants.DOT_GIT)),
                "a laptop with no git server must still get a working repository");
        assertTrue(Files.exists(store.mainCheckout().resolve("dmn")));
        assertTrue(Files.exists(store.mainCheckout().resolve("yaml")));
    }

    @Test
    @DisplayName("runs are kept out of history, since a stray copy must never be committed")
    void runsAreIgnored() throws Exception {
        String ignore = Files.readString(store.mainCheckout().resolve(".gitignore"));
        assertTrue(ignore.contains("runs/"), ignore);
    }

    // --- the identity model -------------------------------------------------------------------

    @Test
    @DisplayName("the id is the branch name, so owner and kind need no separate store")
    void idIsTheBranchName() {
        WorkspaceId id = WorkspaceId.of(WorkspaceId.Kind.FEATURE, "alice", "Pricing Q4");
        assertEquals("feature/alice/pricing-q4", id.branch());
        assertEquals("alice", id.owner);

        WorkspaceId parsed = WorkspaceId.parse("hotfix/bob/rounding");
        assertEquals(WorkspaceId.Kind.HOTFIX, parsed.kind);
        assertEquals("bob", parsed.owner);
        assertEquals("rounding", parsed.name);
    }

    @Test
    @DisplayName("a name that would escape the work root is slugged, not honoured")
    void namesCannotTraverse() {
        WorkspaceId id = WorkspaceId.of(WorkspaceId.Kind.FEATURE, "alice", "../../etc/passwd");
        assertFalse(id.branch().contains(".."), id.branch());
        assertFalse(id.checkoutDir().contains("/"), id.checkoutDir());
    }

    @Test
    void anUnparseableBranchIsNotAWorkspace() {
        assertNotNull(WorkspaceId.parse("feature/alice/x"));
        assertEquals(null, WorkspaceId.parse("main"));
        assertEquals(null, WorkspaceId.parse("release/1.4"));
        assertEquals(null, WorkspaceId.parse("feature/alice"));
    }

    // --- working spaces ------------------------------------------------------------------------

    @Test
    @DisplayName("each working space gets its own checkout, so nothing ever switches branches")
    void createGivesItsOwnCheckout() throws Exception {
        WorkspaceId alice = WorkspaceId.of(WorkspaceId.Kind.FEATURE, "alice", "q4");
        WorkspaceId bob = WorkspaceId.of(WorkspaceId.Kind.FEATURE, "bob", "fees");
        store.createWorkspace(alice, "main", "main");
        store.createWorkspace(bob, "main", "main");

        assertTrue(Files.exists(store.checkoutOf(alice).resolve("dmn")));
        assertTrue(Files.exists(store.checkoutOf(bob).resolve("dmn")));
        assertFalse(store.checkoutOf(alice).equals(store.checkoutOf(bob)));

        try (Git git = Git.open(store.checkoutOf(alice).toFile())) {
            assertEquals("feature/alice/q4", git.getRepository().getBranch());
        }
        try (Git git = Git.open(store.mainCheckout().toFile())) {
            assertEquals("main", git.getRepository().getBranch(),
                    "creating a working space must not move the main checkout");
        }
    }

    @Test
    void listedWorkspacesCarryTheirBaseline() throws Exception {
        WorkspaceId id = WorkspaceId.of(WorkspaceId.Kind.FEATURE, "alice", "q4");
        store.createWorkspace(id, "main", "main");

        List<Workspace> all = store.workspaces();
        assertEquals(1, all.size());
        assertEquals("feature/alice/q4", all.get(0).id);
        assertEquals("alice", all.get(0).owner);
        assertEquals("main", all.get(0).baseline);
        assertEquals("FEATURE", all.get(0).kind);
    }

    @Test
    void deleteRemovesBranchAndCheckout() throws Exception {
        WorkspaceId id = WorkspaceId.of(WorkspaceId.Kind.FEATURE, "alice", "q4");
        store.createWorkspace(id, "main", "main");
        store.deleteWorkspace(id);

        assertFalse(Files.exists(store.checkoutOf(id)));
        assertEquals(0, store.workspaces().size());
        try (Git git = Git.open(store.mainCheckout().toFile())) {
            assertTrue(git.branchList().call().stream()
                    .noneMatch(r -> r.getName().endsWith("feature/alice/q4")));
        }
    }

    // --- commit --------------------------------------------------------------------------------

    @Test
    @DisplayName("a commit is pushed, because publishing names the sha and it must resolve for others")
    void commitPushesToTheSharedRepository() throws Exception {
        WorkspaceId id = WorkspaceId.of(WorkspaceId.Kind.FEATURE, "alice", "q4");
        store.createWorkspace(id, "main", "main");
        Files.writeString(store.checkoutOf(id).resolve("dmn/pricing.dmn"), "<definitions/>");

        String sha = store.commit(id, "add pricing", "alice");
        assertNotNull(sha);

        // Resolvable from the shared repository, not just from alice's own checkout.
        assertTrue(store.hasCommit(sha), "the sha must resolve for everyone, not only its author");
        try (Git git = Git.open(store.mainCheckout().toFile())) {
            Repository repository = git.getRepository();
            assertNotNull(repository.resolve(sha));
        }
    }

    @Test
    void committingNothingIsRefusedRatherThanMakingAnEmptyCommit() throws Exception {
        WorkspaceId id = WorkspaceId.of(WorkspaceId.Kind.FEATURE, "alice", "q4");
        store.createWorkspace(id, "main", "main");
        assertThrows(IllegalStateException.class, () -> store.commit(id, "nothing changed", "alice"));
    }

    @Test
    @DisplayName("uncommitted edits are visible, because publishing is refused while they exist")
    void dirtyStateIsReported() throws Exception {
        WorkspaceId id = WorkspaceId.of(WorkspaceId.Kind.FEATURE, "alice", "q4");
        store.createWorkspace(id, "main", "main");
        assertFalse(store.isDirty(id));

        Files.writeString(store.checkoutOf(id).resolve("dmn/pricing.dmn"), "<definitions/>");
        assertTrue(store.isDirty(id));

        store.commit(id, "add pricing", "alice");
        assertFalse(store.isDirty(id));
    }

    @Test
    @DisplayName("content is read at a revision without touching any working tree")
    void readAtRevision() throws Exception {
        WorkspaceId id = WorkspaceId.of(WorkspaceId.Kind.FEATURE, "alice", "q4");
        store.createWorkspace(id, "main", "main");
        Files.writeString(store.checkoutOf(id).resolve("dmn/pricing.dmn"), "<definitions id=\"v1\"/>");
        String sha = store.commit(id, "add pricing", "alice");

        byte[] content = store.readAt(sha, "dmn/pricing.dmn");
        assertEquals("<definitions id=\"v1\"/>", new String(content));
        assertEquals(null, store.readAt(sha, "dmn/absent.dmn"));

        try (Git git = Git.open(store.mainCheckout().toFile())) {
            assertEquals("main", git.getRepository().getBranch(),
                    "reading a revision must not move a checkout");
        }
    }

    // --- legacy import --------------------------------------------------------------------------

    @Test
    @DisplayName("existing files are copied in, never moved: a move looks exactly like a loss")
    void legacyFilesAreCopiedNotMoved() throws Exception {
        Path legacyDmn = workRoot.resolveSibling("legacy-dmn");
        Path legacyYaml = workRoot.resolveSibling("legacy-yaml");
        Files.createDirectories(legacyDmn);
        Files.createDirectories(legacyYaml);
        Files.writeString(legacyDmn.resolve("old.dmn"), "<definitions/>");
        Files.writeString(legacyYaml.resolve("old.yaml"), "tests: []\n");

        int copied = store.importLegacy(legacyDmn, legacyYaml);
        assertEquals(2, copied);

        assertTrue(Files.exists(legacyDmn.resolve("old.dmn")), "the original must still be there");
        assertTrue(Files.exists(store.mainCheckout().resolve("dmn/old.dmn")));
        assertEquals(0, store.importLegacy(legacyDmn, legacyYaml), "importing twice must be a no-op");
    }
}
