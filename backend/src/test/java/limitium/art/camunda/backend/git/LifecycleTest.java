package limitium.art.camunda.backend.git;

import limitium.art.camunda.backend.config.GitProperties;
import limitium.art.camunda.backend.lifecycle.TestGate;
import limitium.art.camunda.backend.service.TestExecutionService;
import limitium.art.camunda.evaluator.diff.Resolution;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Releases are made only on green tests; landing carries a change through every line, or nothing. */
class LifecycleTest {

    static String dmn(String discount) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<definitions id=\"Defs\" name=\"Defs\" namespace=\"http://camunda.org/schema/1.0/dmn\" xmlns=\"https://www.omg.org/spec/DMN/20191111/MODEL/\">\n"
                + "  <decision id=\"Pricing\" name=\"Pricing\">\n"
                + "    <decisionTable id=\"DT\" hitPolicy=\"UNIQUE\">\n"
                + "      <input id=\"in\"><inputExpression id=\"ie\" typeRef=\"number\"><text>amount</text></inputExpression></input>\n"
                + "      <output id=\"out\" name=\"discount\" typeRef=\"string\"/>\n"
                + "      <rule id=\"r1\"><inputEntry id=\"e1\"><text>-</text></inputEntry><outputEntry id=\"e2\"><text>\"" + discount + "\"</text></outputEntry></rule>\n"
                + "    </decisionTable>\n  </decision>\n</definitions>\n";
    }

    static String spec(String expected) {
        return "tests:\n  - decision: Pricing\n    columns: [scenario, amount, \"=> discount\"]\n    rows:\n      - [any, 10, \"" + expected + "\"]\n";
    }

    private GitRuleStore store;
    private Releases releases;
    private Landing landing;

    @BeforeEach
    void setUp() throws Exception {
        Path root = Path.of("build/tmp/lifecycle-test", String.valueOf(System.nanoTime()));
        GitProperties p = new GitProperties();
        p.setEnabled(true);
        store = new GitRuleStore(p, root.resolve("ws"));
        store.initialise();
        TestGate gate = new TestGate(store, new TestExecutionService());
        RemoteSync sync = new RemoteSync(store);
        releases = new Releases(store, sync, gate);
        landing = new Landing(store, sync, gate);
        commitOnMain(dmn("5%"), spec("5%"), "pricing");
    }

    private void commitOnMain(String dmn, String yaml, String message) throws Exception {
        Path main = store.mainCheckout();
        try (Git git = Git.open(main.toFile())) {
            git.checkout().setName("main").call();
            Files.writeString(main.resolve("dmn/pricing.dmn"), dmn);
            Files.writeString(main.resolve("yaml/pricing.yaml"), yaml);
            git.add().addFilepattern(".").call();
            git.commit().setMessage(message).call();
        }
    }

    private String at(String rev) throws Exception {
        return new String(store.readAt(rev, "dmn/pricing.dmn"), StandardCharsets.UTF_8);
    }

    private WorkspaceId spaceWith(String name, String baseline, String target, String discount) throws Exception {
        WorkspaceId id = WorkspaceId.of(baseline.startsWith("v") ? WorkspaceId.Kind.HOTFIX : WorkspaceId.Kind.FEATURE, "alice", name);
        store.createWorkspace(id, baseline, target);
        Files.writeString(store.checkoutOf(id).resolve("dmn/pricing.dmn"), dmn(discount));
        Files.writeString(store.checkoutOf(id).resolve("yaml/pricing.yaml"), spec(discount));
        store.commit(id, "discount " + discount, "alice");
        return id;
    }

    // --- releases ------------------------------------------------------------------------------

    @Test
    @DisplayName("main is never tagged; a release line is opened, then tagged, with the verdict in the tag")
    void firstRelease() throws Exception {
        assertEquals("NOT_A_RELEASE_LINE", assertThrows(Releases.Refused.class, () -> releases.release("main", "first", "alice", false)).code);
        assertEquals("release/1.0", releases.openLine(null, null, "alice").get("line"));
        Map<String, Object> made = releases.release("release/1.0", "first", "alice", false);
        assertEquals("v1.0.0", made.get("version"));
        Releases.Line line = releases.lines().stream().filter(l -> l.branch.equals("release/1.0")).findFirst().orElseThrow();
        assertEquals("v1.0.0", line.versions.get(0).name);
        assertTrue(line.versions.get(0).verified, "made here, every test green, recorded in the tag");
        assertEquals("first", line.versions.get(0).notes);
        assertEquals("v1.0.1", line.nextVersion);
        Releases.Refused again = assertThrows(Releases.Refused.class, () -> releases.release("release/1.0", null, "alice", false));
        assertEquals("NOTHING_NEW", again.code);
    }

    @Test
    @DisplayName("one failing test anywhere and no version is made")
    void failingTestsRefuseARelease() throws Exception {
        commitOnMain(dmn("5%"), spec("9%"), "a wrong expectation");
        releases.openLine(null, null, "alice");
        Releases.Refused refused = assertThrows(Releases.Refused.class, () -> releases.release("release/1.0", null, "alice", false));
        assertEquals("TESTS_FAILED", refused.code);
        assertNull(store.resolve("refs/tags/v1.0.0"));
    }

    @Test
    @DisplayName("a version tagged by hand is shown as unverified")
    void handMadeTagIsUnverified() throws Exception {
        try (Git git = Git.open(store.mainCheckout().toFile())) {
            git.tag().setName("v0.9.0").setAnnotated(true).setMessage("by hand").call();
        }
        Releases.Line main = releases.lines().get(0);
        assertFalse(main.versions.get(0).verified);
    }

    // --- landing -------------------------------------------------------------------------------

    @Test
    @DisplayName("a space lands into the newest work when nothing disagrees and every test is green")
    void landsIntoMain() throws Exception {
        WorkspaceId id = spaceWith("promo", "main", "main", "7%");
        Landing.Result check = landing.land(id, null, null, false, "alice");
        assertEquals("CLEAN", check.status);
        assertEquals(dmn("5%"), at("refs/heads/main"), "a check moves nothing");
        Landing.Result landed = landing.land(id, null, check.tips, true, "alice");
        assertEquals("LANDED", landed.status);
        assertTrue(at("refs/heads/main").contains("7%"));
    }

    @Test
    @DisplayName("a fix to an old version lands in its line, every newer line, and the newest work")
    void fixTravelsForward() throws Exception {
        cut("1.0");                                                    // release/1.0, v1.0.0
        commitOnMain(dmn("5%"), spec("5%") + "", "more work");        // something new on main
        Files.writeString(store.mainCheckout().resolve("yaml/notes.txt"), "x");
        try (Git git = Git.open(store.mainCheckout().toFile())) {
            git.add().addFilepattern(".").call();
            git.commit().setMessage("notes").call();
        }
        cut("1.1");                                                    // release/1.1, v1.1.0
        WorkspaceId fix = spaceWith("rounding", "v1.0.0", "release/1.0", "6%");
        Landing.Result result = landing.land(fix, null, null, true, "alice");
        assertEquals("LANDED", result.status, String.valueOf(result.message));
        assertEquals(List.of("release/1.0", "release/1.1", "main"), result.hops.stream().map(h -> h.into).toList());
        for (String line : List.of("release/1.0", "release/1.1", "main")) {
            assertTrue(at("refs/heads/" + line).contains("6%"), line + " has the fix");
        }
    }

    @Test
    @DisplayName("a conflict at any step lands nothing; answered, the whole chain lands")
    void conflictLandsNothingUntilAnswered() throws Exception {
        WorkspaceId id = spaceWith("promo", "main", "main", "7%");
        commitOnMain(dmn("8%"), spec("8%"), "someone else's discount");
        String before = store.resolve("refs/heads/main");
        Landing.Result result = landing.land(id, null, null, true, "alice");
        assertEquals("CONFLICTS", result.status);
        assertEquals(before, store.resolve("refs/heads/main"), "nothing moved");
        assertTrue(result.open >= 2, "the cell and the test both disagree");
        Map<String, Resolution> answers = new java.util.LinkedHashMap<>();
        for (Landing.Conflict c : result.hops.get(0).conflicts) {
            answers.put(c.id, Resolution.THEIRS);           // take the space's
            assertFalse(c.theirsChanges.isEmpty(), "each conflict names the commits behind the other side");
        }
        Landing.Result landed = landing.land(id, answers, result.tips, true, "alice");
        assertEquals("LANDED", landed.status, landed.message);
        assertTrue(at("refs/heads/main").contains("7%"));
    }

    @Test
    @DisplayName("a merge that is clean but breaks a test lands nothing")
    void failingTestsLandNothing() throws Exception {
        WorkspaceId id = WorkspaceId.of(WorkspaceId.Kind.FEATURE, "alice", "broken");
        store.createWorkspace(id, "main", "main");
        Files.writeString(store.checkoutOf(id).resolve("dmn/pricing.dmn"), dmn("7%")); // tests still expect 5%
        store.commit(id, "discount without its test", "alice");
        String before = store.resolve("refs/heads/main");
        Landing.Result result = landing.land(id, null, null, true, "alice");
        assertEquals("TESTS_FAILED", result.status);
        assertEquals(before, store.resolve("refs/heads/main"));
    }

    @Test
    @DisplayName("a line that moved since the person looked is not landed onto blindly")
    void movedLineIsRepresented() throws Exception {
        WorkspaceId id = spaceWith("promo", "main", "main", "7%");
        Landing.Result seen = landing.land(id, null, null, false, "alice");
        Files.writeString(store.mainCheckout().resolve("yaml/other.txt"), "y");
        try (Git git = Git.open(store.mainCheckout().toFile())) {
            git.add().addFilepattern(".").call();
            git.commit().setMessage("someone landed meanwhile").call();
        }
        Landing.Result result = landing.land(id, Collections.emptyMap(), seen.tips, true, "alice");
        assertEquals("MOVED", result.status);
        assertTrue(at("refs/heads/main").contains("5%"));
    }

    // --- naming, pulling ------------------------------------------------------------------------

    @Test
    @DisplayName("each side of a conflict is named by the commit that changed that cell, not the newest commit on the file")
    void conflictNamedByItsInitiatingCommit() throws Exception {
        WorkspaceId id = spaceWith("promo", "main", "main", "7%");
        commitOnMain(dmn("8%"), spec("8%"), "raise the discount to 8%");
        // A later commit on main touches the same file but not the contested cell.
        commitOnMain(dmn("8%").replace("name=\"Pricing\">", "name=\"Pricing\" label=\"x\">"), spec("8%"), "tidy the file");
        Landing.Result result = landing.land(id, null, null, false, "alice");
        Landing.Conflict cell = result.hops.get(0).conflicts.stream().filter(c -> c.kind.equals("CELL")).findFirst().orElseThrow();
        assertEquals(1, cell.oursChanges.size());
        assertEquals("raise the discount to 8%", cell.oursChanges.get(0).message, "the commit that changed it, not the newest");
        assertEquals("discount 7%", cell.theirsChanges.get(0).message);
    }

    @Test
    @DisplayName("a space brings in its line after the line moved; conflicts are answered as in landing")
    void pullTheLineIntoASpace() throws Exception {
        WorkspaceId id = spaceWith("promo", "main", "main", "7%");
        commitOnMain(dmn("8%"), spec("8%"), "someone else's discount");
        Landing.Result check = landing.pull(id, "main", null, null, false, "alice");
        assertEquals("CONFLICTS", check.status);
        String before = store.sharedHead(id);
        Map<String, Resolution> answers = new java.util.LinkedHashMap<>();
        for (Landing.Conflict c : check.hops.get(0).conflicts) {
            answers.put(c.id, Resolution.OURS); // keep the space's own
        }
        Landing.Result pulled = landing.pull(id, "main", answers, before, true, "alice");
        assertEquals("PULLED", pulled.status, pulled.message);
        String after = store.sharedHead(id);
        assertFalse(before.equals(after));
        assertTrue(Files.readString(store.checkoutOf(id).resolve("dmn/pricing.dmn")).contains("7%"), "the checkout moved with it");
        assertEquals("CLEAN", landing.land(id, null, null, false, "alice").status, "and now it lands without a question");
    }

    @Test
    @DisplayName("bringing changes in is refused while the space has unsaved edits")
    void pullRefusedWithUnsavedEdits() throws Exception {
        WorkspaceId id = spaceWith("promo", "main", "main", "7%");
        commitOnMain(dmn("5%"), spec("5%") + "", "touch");
        Files.writeString(store.checkoutOf(id).resolve("dmn/pricing.dmn"), dmn("6%"));
        Landing.Refused refused = assertThrows(Landing.Refused.class, () -> landing.pull(id, "main", null, null, true, "alice"));
        assertEquals("UNSAVED", refused.code);
    }

    // --- managing lines --------------------------------------------------------------------------

    @Test
    @DisplayName("a line opens at any commit of main; a version is made at any commit of a line newer than its last")
    void openLineAndVersionACommit() throws Exception {
        String first = store.resolve("refs/heads/main");
        commitOnMain(dmn("7%"), spec("7%"), "seven");
        Map<String, Object> opened = releases.openLine(first, null, "alice");
        assertEquals("release/1.0", opened.get("line"));
        assertEquals(first, store.resolve("refs/heads/release/1.0"));
        assertEquals("LINE_EXISTS", assertThrows(Releases.Refused.class, () -> releases.openLine(first, "1.0", "alice")).code);

        Map<String, Object> made = releases.release("release/1.0", first, "first", "alice", false);
        assertEquals("v1.0.0", made.get("version"));
        assertEquals(first, made.get("commit"));
        assertEquals("NOT_NEWER", assertThrows(Releases.Refused.class,
                () -> releases.release("release/1.0", first, null, "alice", false)).code);
        String seven = store.resolve("refs/heads/main");
        assertEquals("NOT_ON_LINE", assertThrows(Releases.Refused.class,
                () -> releases.release("release/1.0", seven, null, "alice", false)).code);
        // the next line is numbered after it, and must leave main after it did
        assertEquals("NOT_AFTER_PREVIOUS", assertThrows(Releases.Refused.class, () -> releases.openLine(first, null, "alice")).code);
        assertEquals("OUT_OF_ORDER", assertThrows(Releases.Refused.class, () -> releases.openLine(seven, "0.9", "alice")).code);
        assertEquals("release/1.1", releases.openLine(seven, null, "alice").get("line"));
    }

    @Test
    @DisplayName("a fix to the oldest line is carried through every newer line and main in one step")
    void wholeChain() throws Exception {
        cut("1.0");
        commitOnMain(dmn("5%"), spec("5%"), "more");
        Files.writeString(store.mainCheckout().resolve("yaml/a.txt"), "a");
        try (Git git = Git.open(store.mainCheckout().toFile())) {
            git.add().addFilepattern(".").call();
            git.commit().setMessage("a").call();
        }
        releases.openLine(null, "1.1", "alice");                       // stabilising, not tagged
        Files.writeString(store.mainCheckout().resolve("yaml/b.txt"), "b");
        try (Git git = Git.open(store.mainCheckout().toFile())) {
            git.add().addFilepattern(".").call();
            git.commit().setMessage("b").call();
        }
        releases.openLine(null, "1.2", "alice");
        assertNull(store.resolve("refs/tags/v1.1.0"), "opening a line makes no version");
        WorkspaceId fix = spaceWith("chain", "v1.0.0", "release/1.0", "6%");
        Landing.Result result = landing.land(fix, null, null, true, "alice");
        assertEquals("LANDED", result.status, String.valueOf(result.message));
        assertEquals(List.of("release/1.0", "release/1.1", "release/1.2", "main"), result.hops.stream().map(h -> h.into).toList());
        for (String line : List.of("release/1.0", "release/1.1", "release/1.2", "main")) {
            assertTrue(at("refs/heads/" + line).contains("6%"), line + " has the fix");
        }
    }

    @Test
    @DisplayName("a line whose fix stopped short is carried forward into newer lines and main, and then reports nothing missing")
    void carryAStalledLineForward() throws Exception {
        cut("1.0");
        // a fix on release/1.0 that never travelled on (as when carrying forward was off)
        Path main = store.mainCheckout();
        try (Git git = Git.open(main.toFile())) {
            git.checkout().setName("release/1.0").call();
            Files.writeString(main.resolve("dmn/pricing.dmn"), dmn("6%"));
            Files.writeString(main.resolve("yaml/pricing.yaml"), spec("6%"));
            git.add().addFilepattern(".").call();
            git.commit().setMessage("fix on the line").call();
            git.checkout().setName("main").call();
        }
        assertEquals(List.of("main"), releases.lines().stream().filter(l -> l.branch.equals("release/1.0")).findFirst().orElseThrow().missingFrom);
        Landing.Result result = landing.carryForward("release/1.0", null, null, true, "alice");
        assertEquals("LANDED", result.status, String.valueOf(result.message));
        assertTrue(at("refs/heads/main").contains("6%"), "main has the fix now");
        assertTrue(releases.lines().stream().filter(l -> l.branch.equals("release/1.0")).findFirst().orElseThrow().missingFrom.isEmpty());
        assertEquals("NOTHING_TO_LAND", landing.carryForward("release/1.0", null, null, false, "alice").status);
    }

    /** Opens release line {@code number} at main's newest commit and tags its first version. */
    private void cut(String number) throws Exception {
        releases.openLine(null, number, "alice");
        releases.release("release/" + number, number, "alice", false);
    }
}
