package limitium.art.camunda.backend.deploy;

import limitium.art.camunda.backend.config.GitProperties;
import limitium.art.camunda.backend.git.GitRuleStore;
import limitium.art.camunda.backend.git.WorkspaceId;
import limitium.art.camunda.backend.lifecycle.TestGate;
import limitium.art.camunda.backend.service.TestExecutionService;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import limitium.art.camunda.backend.deploy.RuleTarget.Published;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A deploy publishes every domain at one version to every server, only after every domain's tests
 * pass there; what runs is read back from the servers.
 */
class DeployServiceTest {

    static String dmn(String decision, String discount) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<definitions id=\"Defs\" name=\"Defs\" namespace=\"http://camunda.org/schema/1.0/dmn\"\n"
                + "  xmlns=\"https://www.omg.org/spec/DMN/20191111/MODEL/\">\n"
                + "  <decision id=\"" + decision + "\" name=\"" + decision + "\">\n"
                + "    <decisionTable id=\"DT\" hitPolicy=\"UNIQUE\">\n"
                + "      <input id=\"in\"><inputExpression id=\"ie\" typeRef=\"number\"><text>amount</text></inputExpression></input>\n"
                + "      <output id=\"out\" name=\"discount\" typeRef=\"string\"/>\n"
                + "      <rule id=\"r1\"><inputEntry id=\"e1\"><text>-</text></inputEntry>"
                + "<outputEntry id=\"e2\"><text>\"" + discount + "\"</text></outputEntry></rule>\n"
                + "    </decisionTable>\n"
                + "  </decision>\n"
                + "</definitions>\n";
    }

    static String spec(String decision, String expected) {
        return "tests:\n  - decision: " + decision + "\n    columns: [scenario, amount, \"=> discount\"]\n"
                + "    rows:\n      - [any, 10, \"" + expected + "\"]\n";
    }

    private Path root;
    private GitRuleStore store;
    private FileTarget pricingTopic;
    private FileTarget billingTopic;
    private DeployService deploys;
    private final DeployProperties properties = new DeployProperties();

    @BeforeEach
    void setUp() throws Exception {
        root = Path.of("build/tmp/deploy-test", String.valueOf(System.nanoTime()));
        GitProperties git = new GitProperties();
        git.setEnabled(true);
        store = new GitRuleStore(git, root.resolve("ws"));
        store.initialise();
        pricingTopic = new FileTarget("pricing", root.resolve("topics/pricing"));
        billingTopic = new FileTarget("billing", root.resolve("topics/billing"));
        // pricing reads its own rules; billing reads fees and the shared pricing rules
        deploys = deploys(new DeployService.Application("pricing", null, pricingTopic),
                new DeployService.Application("billing", List.of("fees", "pricing"), billingTopic));
        Path main = store.mainCheckout();
        Files.writeString(main.resolve("dmn/pricing.dmn"), dmn("Pricing", "5%"));
        Files.writeString(main.resolve("yaml/pricing.yaml"), spec("Pricing", "5%"));
        Files.writeString(main.resolve("dmn/fees.dmn"), dmn("Fees", "1%"));
        try (Git g = Git.open(main.toFile())) {
            g.add().addFilepattern(".").call();
            g.commit().setMessage("domains").call();
            g.branchCreate().setName("release/1.1").call();
            g.tag().setName("v1.1.0").setAnnotated(true).setMessage("first").call();
        }
    }

    private DeployService deploys(DeployService.Application... domains) {
        return new DeployService(store, new ArrayList<>(List.of(domains)), new TestGate(store, new TestExecutionService()), properties);
    }

    @Test
    @DisplayName("a domain gets exactly the files it reads, shared ones included; what it runs is read back")
    void domainGetsItsFiles() throws Exception {
        deploys.deployRelease("billing", "v1.1.0", "alice");
        Map<String, Envelope> held = billingTopic.readBack();
        assertEquals(List.of("fees", "pricing"), new ArrayList<>(held.keySet()));
        assertEquals("v1.1.0", held.get("pricing").release);
        assertTrue(pricingTopic.readBack().isEmpty(), "other domains are untouched");
        Map<String, Object> billing = deploys.overview().get(1);
        assertEquals(true, billing.get("inStep"));
    }

    @Test
    @DisplayName("tests run first: one failing test anywhere and nothing is deployed")
    void failingTestsRefuse() throws Exception {
        Path main = store.mainCheckout();
        Files.writeString(main.resolve("yaml/fees.yaml"), spec("Fees", "9%")); // fees gives 1%
        try (Git g = Git.open(main.toFile())) {
            g.add().addFilepattern(".").call();
            g.commit().setMessage("a wrong expectation").call();
            g.tag().setName("v1.1.1").setAnnotated(true).setMessage("broken").call();
        }
        DeployService.Refused refused = assertThrows(DeployService.Refused.class, () -> deploys.deployRelease("pricing", "v1.1.1", "alice"));
        assertEquals("TESTS_FAILED", refused.code);
        TestGate.Report report = (TestGate.Report) refused.details;
        assertFalse(report.passed);
        assertTrue(pricingTopic.readBack().isEmpty(), "nothing reached the topic");
    }

    @Test
    @DisplayName("a version lacking a file the domain reads is refused, never half-deployed")
    void missingFileRefused() throws Exception {
        try (Git g = Git.open(store.mainCheckout().toFile())) {
            g.rm().addFilepattern("dmn/fees.dmn").call();
            g.commit().setMessage("fees retired").call();
            g.tag().setName("v1.1.2").setAnnotated(true).setMessage("no fees").call();
        }
        assertEquals("MISSING_FILES", assertThrows(DeployService.Refused.class, () -> deploys.deployRelease("billing", "v1.1.2", "alice")).code);
        deploys.deployRelease("pricing", "v1.1.2", "alice");
        assertEquals(List.of("pricing"), new ArrayList<>(pricingTopic.readBack().keySet()));
    }

    @Test
    @DisplayName("history is read from the topic, newest first; restore puts the last release back after a draft")
    void historyAndRestore() throws Exception {
        properties.setAllowDraftPublish(true);
        deploys.deployRelease("pricing", "v1.1.0", "alice");
        WorkspaceId id = WorkspaceId.of(WorkspaceId.Kind.FEATURE, "bob", "promo");
        store.createWorkspace(id, "main", "main");
        Files.writeString(store.checkoutOf(id).resolve("dmn/pricing.dmn"), dmn("Pricing", "7%"));
        Files.writeString(store.checkoutOf(id).resolve("yaml/pricing.yaml"), spec("Pricing", "7%"));
        store.commit(id, "promo", "bob");
        List<Map<String, Object>> preview = deploys.draftPreview(id.branch());
        assertEquals(List.of("pricing"), preview.get(0).get("changed"));
        assertEquals(List.of("pricing"), preview.get(1).get("changed"), "billing reads the shared pricing rules too");

        deploys.publishDraft("pricing", id.branch(), "bob");
        assertEquals(id.branch(), pricingTopic.readBack().get("pricing").workspace);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> history = (List<Map<String, Object>>) deploys.detail("pricing").get("history");
        assertEquals(2, history.size());
        assertEquals(id.branch(), history.get(0).get("workspace"), "newest first");
        assertEquals("v1.1.0", history.get(1).get("release"));

        deploys.restore("pricing", "alice");
        assertEquals("v1.1.0", pricingTopic.readBack().get("pricing").release);
    }

    @Test
    @DisplayName("deploys are idempotent: a topic that missed one is named, and redeploying puts it in step")
    void redeployPutsTheTopicInStep() throws Exception {
        Broken broken = new Broken();
        DeployService withBroken = deploys(new DeployService.Application("pricing", null, broken));
        Map<String, Object> first = withBroken.deployRelease("pricing", "v1.1.0", "alice");
        assertEquals(false, first.get("complete"));
        broken.up = true;
        broken.history.clear();
        withBroken.deployRelease("pricing", "v1.1.0", "alice");
        Map<String, Object> again = withBroken.redeploy("pricing", "alice");
        assertEquals(true, again.get("complete"));
        assertEquals("v1.1.0", broken.held.get("pricing").release);
    }

    @Test
    @DisplayName("instances deploy to topics of their own: a topic another instance declares blocks deploying")
    void sharedTopicRefused() throws Exception {
        limitium.art.camunda.backend.git.Instances instances = new limitium.art.camunda.backend.git.Instances(store);
        deploys.instances(instances);
        Map<String, Object> claimed = new java.util.LinkedHashMap<>(pricingTopic.describe());
        claimed.put("name", "their-pricing");
        instances.declare("stg", List.of(claimed));
        DeployService.Refused refused = assertThrows(DeployService.Refused.class, () -> deploys.deployRelease("pricing", "v1.1.0", "alice"));
        assertEquals("NOT_EXCLUSIVE", refused.code);
        assertTrue(pricingTopic.readBack().isEmpty());
        deploys.deployRelease("billing", "v1.1.0", "alice");
        assertEquals(2, billingTopic.readBack().size(), "billing's topic is its own");
    }

    /** A topic that is down until told otherwise. */
    static final class Broken implements RuleTarget {
        boolean up;
        final Map<String, Envelope> held = new java.util.TreeMap<>();
        final List<Published> history = new ArrayList<>();

        public String name() {
            return "broken";
        }

        public Map<String, Object> describe() {
            return Map.of("name", "broken", "type", "file", "topic", "broken", "where", "/nowhere");
        }

        public void publish(List<Envelope> envelopes, Collection<String> stop) {
            if (!up) {
                throw new IllegalStateException("connection refused");
            }
            for (Envelope e : envelopes) {
                held.put(e.name, e);
                Published p = new Published();
                p.key = e.name;
                p.envelope = e.summary();
                history.add(p);
            }
        }

        public Map<String, Envelope> readBack() {
            if (!up) {
                throw new IllegalStateException("connection refused");
            }
            return held;
        }

        public List<Published> history() {
            return history;
        }

        public List<String> check() {
            return List.of();
        }
    }
}
