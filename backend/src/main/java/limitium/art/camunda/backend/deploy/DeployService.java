package limitium.art.camunda.backend.deploy;

import limitium.art.camunda.backend.git.GitRuleStore;
import limitium.art.camunda.backend.git.Workspace;
import limitium.art.camunda.backend.git.WorkspaceId;
import limitium.art.camunda.backend.lifecycle.TestGate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Deploying to the applications this instance controls.
 *
 * <p>An application reads some rule files from its topic. Deploying a version to it publishes those files
 * at that version, after every test at that version passes, and stops any file it no longer reads.
 * What an application runs, and its history, are read back from its topic every time — never remembered.
 * Deploys are idempotent: redeploying repeats the last one.
 */
public class DeployService {

    private static final Logger log = LoggerFactory.getLogger(DeployService.class);

    public static class Refused extends RuntimeException {
        public final String code;
        public final Object details;

        Refused(String code, String message) {
            this(code, message, null);
        }

        Refused(String code, String message, Object details) {
            super(message);
            this.code = code;
            this.details = details;
        }
    }

    /** One application this instance deploys to. */
    public static class Application {
        public final String name;
        public final List<String> files;
        public final RuleTarget target;

        public Application(String name, List<String> files, RuleTarget target) {
            this.name = name;
            this.files = files == null || files.isEmpty() ? Arrays.asList(name) : files;
            this.target = target;
        }
    }

    private final GitRuleStore store;
    private final List<Application> applications;
    private final TestGate gate;
    private final DeployProperties properties;
    private final ConcurrentHashMap<String, ReentrantLock> deploying = new ConcurrentHashMap<>();
    private volatile limitium.art.camunda.backend.git.Instances instances;

    public DeployService(GitRuleStore store, List<Application> applications, TestGate gate, DeployProperties properties) {
        this.store = store;
        this.applications = applications;
        this.gate = gate;
        this.properties = properties;
    }

    public void instances(limitium.art.camunda.backend.git.Instances instances) {
        this.instances = instances;
    }

    /** What this instance declares to the others: each application's topic, never its credentials. */
    public List<Map<String, Object>> serversDeclared() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Application d : applications) {
            Map<String, Object> m = new LinkedHashMap<>(d.target.describe());
            m.put("name", d.name);
            list.add(m);
        }
        return list;
    }

    private List<limitium.art.camunda.backend.git.Instances.Overlap> overlaps() {
        if (instances == null) {
            return new ArrayList<>();
        }
        try {
            return instances.overlaps(properties.getEnv(), serversDeclared());
        } catch (IOException e) {
            return new ArrayList<>();
        }
    }

    public Application find(String name) {
        for (Application d : applications) {
            if (d.name.equals(name)) {
                return d;
            }
        }
        throw new Refused("NO_SUCH_APPLICATION", "this instance controls no application called " + name);
    }

    // --- what runs ---------------------------------------------------------------------------

    /** Every application, with what it runs now, read back from its topic. */
    public List<Map<String, Object>> overview() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Application d : applications) {
            list.add(state(d));
        }
        return list;
    }

    /** One application: what it runs, and its deployment history. */
    public Map<String, Object> detail(String name) {
        Application d = find(name);
        Map<String, Object> body = state(d);
        try {
            body.put("history", deployments(d.target.history()));
        } catch (Exception e) {
            body.put("history", new ArrayList<>());
            body.put("historyError", plain(e));
        }
        return body;
    }

    private Map<String, Object> state(Application d) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("name", d.name);
        s.put("files", d.files);
        s.put("server", d.target.describe());
        List<String> problems = new ArrayList<>();
        try {
            Map<String, Envelope> held = d.target.readBack();
            List<Envelope> running = new ArrayList<>();
            for (Envelope e : held.values()) {
                running.add(e.summary());
                if (e.env != null && !e.env.equals(properties.getEnv())) {
                    problems.add("it holds rules the " + e.env + " instance published; another instance writes to this topic");
                }
            }
            s.put("ok", true);
            s.put("running", running);
            Set<String> commits = new TreeSet<>();
            for (Envelope e : running) {
                commits.add(String.valueOf(e.commit));
            }
            Set<String> missing = new TreeSet<>(d.files);
            missing.removeAll(held.keySet());
            s.put("inStep", commits.size() <= 1 && (running.isEmpty() || missing.isEmpty()));
            s.put("version", running.isEmpty() ? null : running.get(0));
        } catch (Exception e) {
            s.put("ok", false);
            s.put("error", plain(e));
        }
        try {
            problems.addAll(d.target.check());
        } catch (Exception e) {
            // unreachable is reported above
        }
        for (limitium.art.camunda.backend.git.Instances.Overlap o : overlaps()) {
            if (o.server.equals(d.name)) {
                problems.add("the " + o.otherInstance + " instance deploys to this topic too (its application " + o.otherServer
                        + "); deploying is refused until each instance has topics of its own");
            }
        }
        s.put("problems", problems);
        return s;
    }

    /**
     * The topic's records grouped into deployments, newest first: one publish of a version is one
     * entry, with the files it put there and the ones it stopped.
     */
    static List<Map<String, Object>> deployments(List<RuleTarget.Published> records) {
        List<Map<String, Object>> groups = new ArrayList<>();
        Map<String, Object> current = null;
        String currentKey = null;
        for (RuleTarget.Published p : records) {
            Envelope e = p.envelope;
            String key = e == null ? currentKey : e.commit + "|" + e.publishedAt + "|" + e.publishedBy;
            if (current == null || !String.valueOf(key).equals(currentKey)) {
                current = new LinkedHashMap<>();
                current.put("at", e != null && e.publishedAt != null ? e.publishedAt : p.at);
                current.put("commit", e == null ? null : e.commit);
                current.put("release", e == null ? null : e.release);
                current.put("line", e == null ? null : e.line);
                current.put("workspace", e == null ? null : e.workspace);
                current.put("owner", e == null ? null : e.owner);
                current.put("by", e == null ? null : e.publishedBy);
                current.put("files", new ArrayList<String>());
                current.put("stopped", new ArrayList<String>());
                groups.add(current);
                currentKey = key;
            }
            @SuppressWarnings("unchecked")
            List<String> bucket = (List<String>) current.get(e == null ? "stopped" : "files");
            if (!bucket.contains(p.key)) {
                bucket.add(p.key);
            }
        }
        List<Map<String, Object>> newestFirst = new ArrayList<>(groups);
        java.util.Collections.reverse(newestFirst);
        return newestFirst;
    }

    // --- tests -------------------------------------------------------------------------------

    /** Every test at a commit, for showing: remembered per commit (deploys never use this). */
    public TestGate.Report testsForDisplay(String revision) throws IOException {
        try {
            return gate.runForDisplay(revision);
        } catch (TestGate.NoSuchCommit e) {
            throw new Refused("NO_SUCH_COMMIT", e.getMessage());
        }
    }

    public TestGate.Report testsAt(String revision) throws IOException {
        try {
            return gate.run(revision);
        } catch (TestGate.NoSuchCommit e) {
            throw new Refused("NO_SUCH_COMMIT", e.getMessage());
        }
    }

    // --- deploying ---------------------------------------------------------------------------

    public Map<String, Object> deployRelease(String application, String version, String by) throws IOException {
        if (version == null || !version.matches("v\\d+\\.\\d+\\.\\d+")) {
            throw new Refused("NOT_A_VERSION", "only a released version can be deployed");
        }
        String commit = store.resolve("refs/tags/" + version);
        if (commit == null) {
            throw new Refused("NO_SUCH_VERSION", "there is no version " + version);
        }
        String[] parts = version.substring(1).split("\\.");
        Envelope template = new Envelope();
        template.release = version;
        template.line = "release/" + parts[0] + "." + parts[1];
        return deploy(find(application), commit, template, by);
    }

    /** Publishes a space's saved version to one application, if this instance allows drafts. */
    public Map<String, Object> publishDraft(String application, String spaceId, String by) throws IOException, org.eclipse.jgit.api.errors.GitAPIException {
        if (!properties.isAllowDraftPublish()) {
            throw new Refused("DRAFT_PUBLISH_DISABLED", "drafts are not published from the " + properties.getEnv() + " instance");
        }
        WorkspaceId id = WorkspaceId.parse(spaceId);
        Workspace space = id == null ? null : findSpace(spaceId);
        if (space == null) {
            throw new Refused("NO_SUCH_SPACE", "no such space");
        }
        if (space.dirty) {
            throw new Refused("UNSAVED", "save a version first — only a saved version can run, so anyone can look it up");
        }
        String saved = store.sharedHead(id);
        if (saved == null || !saved.equals(space.head)) {
            throw new Refused("NOT_SAVED", "this copy is not the space's saved version; refresh or save first");
        }
        Envelope template = new Envelope();
        template.line = space.targetLine;
        template.workspace = spaceId;
        template.owner = space.owner;
        template.baseline = store.baselineOfSpace(id)[0];
        return deploy(find(application), saved, template, by);
    }

    /** For a space: every application, whether it reads a file the space changed, and what it runs now. */
    public List<Map<String, Object>> draftPreview(String spaceId) throws IOException, org.eclipse.jgit.api.errors.GitAPIException {
        WorkspaceId id = WorkspaceId.parse(spaceId);
        Workspace space = id == null ? null : findSpace(spaceId);
        if (space == null) {
            throw new Refused("NO_SUCH_SPACE", "no such space");
        }
        String baseCommit = store.resolve(store.baselineOfSpace(id)[0]);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Application d : applications) {
            List<String> changed = new ArrayList<>();
            for (String file : d.files) {
                byte[] now = store.readAt(space.head, "dmn/" + file + ".dmn");
                byte[] before = baseCommit == null ? null : store.readAt(baseCommit, "dmn/" + file + ".dmn");
                if (!Arrays.equals(now, before)) {
                    changed.add(file);
                }
            }
            Map<String, Object> row = state(d);
            row.put("changed", changed);
            result.add(row);
        }
        return result;
    }

    /** Publishes again exactly what the application's topic says it was last given. */
    public Map<String, Object> redeploy(String application, String by) throws IOException {
        Application d = find(application);
        Map<String, Object> last = lastDeployment(d, false);
        if (last == null) {
            throw new Refused("NOTHING_DEPLOYED", d.name + " has nothing deployed yet, so there is nothing to redeploy");
        }
        // A version is deployed as a version: through the tag, its record and every test, again.
        if (last.get("release") != null) {
            return deployRelease(application, (String) last.get("release"), by);
        }
        // What runs is a draft: only an instance that may publish drafts sends one again.
        if (!properties.isAllowDraftPublish()) {
            throw new Refused("NOT_A_VERSION", d.name + " runs a draft, and this instance deploys only released versions; deploy one");
        }
        Envelope template = new Envelope();
        template.release = (String) last.get("release");
        template.line = (String) last.get("line");
        template.workspace = (String) last.get("workspace");
        template.owner = (String) last.get("owner");
        return deploy(d, (String) last.get("commit"), template, by);
    }

    /** Puts the application's last released version back (undoing a draft). */
    public Map<String, Object> restore(String application, String by) throws IOException {
        Application d = find(application);
        Map<String, Object> last = lastDeployment(d, true);
        if (last == null) {
            throw new Refused("NO_RELEASE", d.name + " has had no release deployed yet, so there is nothing to put back");
        }
        return deployRelease(application, (String) last.get("release"), by);
    }

    private Map<String, Object> lastDeployment(Application d, boolean releaseOnly) {
        try {
            for (Map<String, Object> dep : deployments(d.target.history())) {
                if (dep.get("commit") != null && (!releaseOnly || dep.get("release") != null)) {
                    return dep;
                }
            }
        } catch (Exception e) {
            throw new Refused("UNREACHABLE", d.name + "'s topic could not be read: " + plain(e));
        }
        return null;
    }

    /**
     * The deploy itself: the application's own topic only, every test first, the files it reads at that
     * version, and a stop for any file it no longer reads. A failed publish is reported, never hidden:
     * redeploying repeats it.
     */
    private Map<String, Object> deploy(Application d, String commit, Envelope template, String by) throws IOException {
        List<limitium.art.camunda.backend.git.Instances.Overlap> shared = new ArrayList<>();
        for (limitium.art.camunda.backend.git.Instances.Overlap o : overlaps()) {
            if (o.server.equals(d.name)) {
                shared.add(o);
            }
        }
        if (!shared.isEmpty()) {
            throw new Refused("NOT_EXCLUSIVE", "each instance deploys to topics of its own, and the " + shared.get(0).otherInstance
                    + " instance deploys to " + d.name + "'s topic too; nothing was deployed", shared);
        }
        ReentrantLock lock = deploying.computeIfAbsent(d.name, k -> new ReentrantLock());
        lock.lock();
        try {
            TestGate.Report report = testsAt(commit);
            if (!report.passed && properties.isRequireTests()) {
                throw new Refused("TESTS_FAILED", report.failed + (report.failed == 1 ? " test fails" : " tests fail")
                        + " at " + commit.substring(0, 7) + "; nothing was deployed", report);
            }
            List<String> missing = new ArrayList<>();
            String now = Instant.now().toString();
            List<Envelope> envelopes = new ArrayList<>();
            for (String file : d.files) {
                byte[] dmn = store.readAt(commit, "dmn/" + file + ".dmn");
                if (dmn == null) {
                    missing.add(file);
                    continue;
                }
                Envelope e = new Envelope();
                e.name = file;
                e.dmnXml = new String(dmn, StandardCharsets.UTF_8);
                e.commit = commit;
                e.release = template.release;
                e.line = template.line;
                e.workspace = template.workspace;
                e.owner = template.owner;
                e.baseline = template.baseline;
                e.publishedAt = now;
                e.publishedBy = by;
                e.env = properties.getEnv();
                envelopes.add(e);
            }
            if (!missing.isEmpty()) {
                throw new Refused("MISSING_FILES", d.name + " reads " + String.join(", ", missing)
                        + ", which this version does not have; nothing was deployed");
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("application", d.name);
            result.put("commit", commit);
            result.put("files", d.files);
            result.put("tests", report);
            try {
                Set<String> stop = new LinkedHashSet<>(d.target.readBack().keySet());
                stop.removeAll(d.files);
                d.target.publish(envelopes, stop);
                result.put("complete", true);
                result.put("stopped", stop);
            } catch (Exception e) {
                log.warn("deploying to {} failed: {}", d.name, plain(e));
                result.put("complete", false);
                result.put("error", plain(e));
            }
            return result;
        } finally {
            lock.unlock();
        }
    }

    private Workspace findSpace(String spaceId) throws IOException, org.eclipse.jgit.api.errors.GitAPIException {
        for (Workspace w : store.workspaces()) {
            if (w.id.equals(spaceId)) {
                return w;
            }
        }
        return null;
    }

    private static String plain(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t && (t.getMessage() == null || t instanceof java.util.concurrent.ExecutionException)) {
            t = t.getCause();
        }
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }
}
