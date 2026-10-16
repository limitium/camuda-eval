package limitium.art.camunda.backend.git;

import limitium.art.camunda.backend.config.GitProperties;
import limitium.art.camunda.backend.service.VersionedFiles;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real threads, started together, against the guards: whatever the interleaving, nothing is lost,
 * nothing is overwritten, and every action either happens whole or is refused.
 */
class ConcurrencyTest {

    private static final int THREADS = 16;

    private Path root;
    private GitRuleStore store;
    private VersionedFiles files;

    @BeforeEach
    void setUp() throws Exception {
        root = Path.of("build/tmp/concurrency-test", String.valueOf(System.nanoTime()));
        GitProperties properties = new GitProperties();
        properties.setEnabled(true);
        properties.setIdleGraceDays(0);
        store = new GitRuleStore(properties, root.resolve("ws"));
        store.initialise();
        files = new VersionedFiles();
    }

    /** Runs the tasks at the same instant and returns what each did. */
    private static <T> List<Future<T>> together(List<Callable<T>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch go = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        for (Callable<T> task : tasks) {
            futures.add(pool.submit(() -> {
                go.await();
                return task.call();
            }));
        }
        go.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS), "every racer finishes");
        return futures;
    }

    // --- files ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("of many saves built on one version, exactly one wins and the rest are refused")
    void oneSaveWinsTheRest412() throws Exception {
        Path file = root.resolve("pricing.dmn");
        VersionedFiles.writeAtomically(file, "v0".getBytes(StandardCharsets.UTF_8));
        String loaded = VersionedFiles.etag(Files.readAllBytes(file));

        List<Callable<VersionedFiles.WriteResult>> saves = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            String content = "edit by " + i;
            saves.add(() -> files.write(file, content, loaded, true));
        }
        int ok = 0;
        String winner = null;
        for (Future<VersionedFiles.WriteResult> f : together(saves)) {
            VersionedFiles.WriteResult r = f.get();
            if (r.ok()) {
                ok++;
                winner = r.etag;
            } else {
                assertEquals(412, r.status.value());
            }
        }
        assertEquals(1, ok, "one save wins");
        assertEquals(winner, VersionedFiles.etag(Files.readAllBytes(file)), "and its content is what is on disk");
    }

    @Test
    @DisplayName("a reader never sees half a file, however often it is rewritten")
    void readersNeverSeeHalfAFile() throws Exception {
        Path file = root.resolve("big.dmn");
        String a = "A".repeat(400_000);
        String b = "B".repeat(400_000);
        VersionedFiles.writeAtomically(file, a.getBytes(StandardCharsets.UTF_8));
        AtomicBoolean stop = new AtomicBoolean();
        AtomicInteger torn = new AtomicInteger();
        List<Callable<Integer>> tasks = new ArrayList<>();
        tasks.add(() -> {
            for (int i = 0; i < 200; i++) {
                VersionedFiles.writeAtomically(file, (i % 2 == 0 ? b : a).getBytes(StandardCharsets.UTF_8));
            }
            stop.set(true);
            return 0;
        });
        for (int r = 0; r < 4; r++) {
            tasks.add(() -> {
                int reads = 0;
                while (!stop.get()) {
                    String seen = Files.readString(file);
                    if (!seen.equals(a) && !seen.equals(b)) {
                        torn.incrementAndGet();
                    }
                    reads++;
                }
                return reads;
            });
        }
        together(tasks);
        assertEquals(0, torn.get(), "no read saw a mix or a truncated file");
    }

    @Test
    @DisplayName("of many creators of one new file, exactly one creates it")
    void oneCreatorWins() throws Exception {
        Path file = root.resolve("new.dmn");
        List<Callable<Boolean>> creates = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            byte[] content = ("by " + i).getBytes(StandardCharsets.UTF_8);
            creates.add(() -> files.create(file, content));
        }
        int created = 0;
        for (Future<Boolean> f : together(creates)) {
            if (f.get()) {
                created++;
            }
        }
        assertEquals(1, created);
    }

    @Test
    @DisplayName("a save built on a version since removed does not bring the file back")
    void noResurrection() throws Exception {
        Path file = root.resolve("gone.dmn");
        VersionedFiles.writeAtomically(file, "v0".getBytes(StandardCharsets.UTF_8));
        String loaded = VersionedFiles.etag(Files.readAllBytes(file));
        assertTrue(files.delete(file, loaded, true).ok());
        VersionedFiles.WriteResult save = files.write(file, "late edit", loaded, true);
        assertEquals(412, save.status.value());
        assertEquals("REMOVED", save.body.get("code"));
        assertTrue(Files.notExists(file));
    }

    @Test
    @DisplayName("removing a file a colleague changed since is refused")
    void staleRemoveRefused() throws Exception {
        Path file = root.resolve("pricing.dmn");
        VersionedFiles.writeAtomically(file, "v0".getBytes(StandardCharsets.UTF_8));
        String seen = VersionedFiles.etag(Files.readAllBytes(file));
        assertTrue(files.write(file, "colleague's edit", seen, true).ok());
        assertEquals(412, files.delete(file, seen, true).status.value());
        assertEquals("colleague's edit", Files.readString(file));
    }

    // --- spaces --------------------------------------------------------------------------------------

    @Test
    @DisplayName("of many creators of one space, exactly one creates it")
    void oneSpaceCreatorWins() throws Exception {
        WorkspaceId id = WorkspaceId.of(WorkspaceId.Kind.FEATURE, "alice", "race");
        List<Callable<Boolean>> creates = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            creates.add(() -> {
                try {
                    store.createWorkspace(id, "main", "main");
                    return true;
                } catch (IllegalStateException | org.eclipse.jgit.api.errors.RefAlreadyExistsException e) {
                    return false;
                }
            });
        }
        int created = 0;
        for (Future<Boolean> f : together(creates)) {
            if (f.get()) {
                created++;
            }
        }
        assertEquals(1, created);
        assertEquals(1, store.workspaces().size());
    }

    @Test
    @DisplayName("many spaces saving versions at once: every version is saved and shared")
    void parallelCommitsAllShared() throws Exception {
        List<WorkspaceId> spaces = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            WorkspaceId id = WorkspaceId.of(WorkspaceId.Kind.FEATURE, "user" + i, "work");
            store.createWorkspace(id, "main", "main");
            spaces.add(id);
        }
        List<Callable<List<String>>> work = new ArrayList<>();
        for (WorkspaceId id : spaces) {
            work.add(() -> {
                List<String> shas = new ArrayList<>();
                for (int n = 0; n < 5; n++) {
                    Files.writeString(store.checkoutOf(id).resolve("dmn/a.dmn"), id.owner + " " + n);
                    shas.add(store.commit(id, "save " + n, id.owner));
                }
                return shas;
            });
        }
        List<Future<List<String>>> results = together(work);
        for (int i = 0; i < spaces.size(); i++) {
            List<String> shas = results.get(i).get();
            assertEquals(5, shas.size());
            assertEquals(shas.get(4), store.sharedHead(spaces.get(i)), "the last version is what everyone sees");
        }
    }

    @Test
    @DisplayName("a save in progress holds off deleting its space; the space is then gone, cleanly")
    void deleteWaitsForASave() throws Exception {
        WorkspaceId id = WorkspaceId.of(WorkspaceId.Kind.FEATURE, "alice", "busy");
        store.createWorkspace(id, "main", "main");
        CountDownLatch saving = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<?> save = pool.submit(() -> store.editing(id, () -> {
            saving.countDown();
            awaitQuietly(release);
            Files.writeString(store.checkoutOf(id).resolve("dmn/a.dmn"), "written during the delete attempt");
            return null;
        }));
        saving.await();
        Future<?> delete = pool.submit(() -> {
            store.deleteWorkspace(id);
            return null;
        });
        Thread.sleep(300);
        assertTrue(!delete.isDone(), "deletion waits while a save is in progress");
        release.countDown();
        save.get(10, TimeUnit.SECONDS);
        delete.get(10, TimeUnit.SECONDS);
        pool.shutdown();
        assertTrue(Files.notExists(store.checkoutOf(id)));
        assertThrows(GitRuleStore.NoSuchSpace.class, () -> store.editing(id, () -> null),
                "a save arriving after the deletion is told the space is gone, not given a fresh one");
    }

    @Test
    @DisplayName("tidy-up cannot remove a space a save is writing into: it waits, sees the edit, refuses")
    void tidyWaitsAndSeesTheSave() throws Exception {
        BranchCleanup cleanup = new BranchCleanup(store, propertiesOf());
        WorkspaceId id = WorkspaceId.of(WorkspaceId.Kind.FEATURE, "bob", "idle");
        store.createWorkspace(id, "main", "main");
        Instant later = Instant.now().plus(Duration.ofDays(60));
        BranchCleanup.Item shown = cleanup.plan(later).items.stream().filter(i -> i.id.equals(id.branch())).findFirst().orElseThrow();
        assertEquals(BranchCleanup.Action.REMOVE, shown.action, "idle and empty: offered for removal");

        CountDownLatch saving = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<?> save = pool.submit(() -> store.editing(id, () -> {
            saving.countDown();
            awaitQuietly(release);
            Files.writeString(store.checkoutOf(id).resolve("dmn/a.dmn"), "bob came back");
            return null;
        }));
        saving.await();
        Future<?> tidy = pool.submit(() -> cleanup.apply(Collections.singletonMap(shown.id, shown.head), "alice", later));
        Thread.sleep(300);
        assertTrue(!tidy.isDone(), "tidy-up waits for the save");
        release.countDown();
        save.get(10, TimeUnit.SECONDS);
        Exception refused = assertThrows(Exception.class, () -> tidy.get(10, TimeUnit.SECONDS));
        assertTrue(refused.getCause() instanceof BranchCleanup.PlanChanged, String.valueOf(refused.getCause()));
        pool.shutdown();
        assertEquals("bob came back", Files.readString(store.checkoutOf(id).resolve("dmn/a.dmn")), "the edit survives");
    }

    // --- the environment record ---------------------------------------------------------------------

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private GitProperties propertiesOf() {
        GitProperties p = new GitProperties();
        p.setEnabled(true);
        p.setIdleGraceDays(0);
        p.setStaleAfterDays(30);
        return p;
    }
}
