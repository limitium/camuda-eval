package limitium.art.camunda.backend.git;

import limitium.art.camunda.backend.config.GitProperties;
import limitium.art.camunda.backend.lifecycle.TestGate;
import limitium.art.camunda.backend.service.TestExecutionService;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** History at every grain: each lists the commits that changed that thing, and only those. */
class ElementHistoryTest {

    /** Rules as id → [region, code, amount]; the order given is the table's order. */
    static String dmn(Map<String, String[]> rules) {
        StringBuilder rows = new StringBuilder();
        for (Map.Entry<String, String[]> r : rules.entrySet()) {
            String[] v = r.getValue();
            rows.append("      <rule id=\"").append(r.getKey()).append("\">")
                    .append("<inputEntry id=\"").append(r.getKey()).append("i\"><text>").append(v[0]).append("</text></inputEntry>")
                    .append("<outputEntry id=\"").append(r.getKey()).append("c\"><text>").append(v[1]).append("</text></outputEntry>")
                    .append("<outputEntry id=\"").append(r.getKey()).append("a\"><text>").append(v[2]).append("</text></outputEntry>")
                    .append("</rule>\n");
        }
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<definitions id=\"Defs\" name=\"Defs\" namespace=\"http://camunda.org/schema/1.0/dmn\" xmlns=\"https://www.omg.org/spec/DMN/20191111/MODEL/\">\n"
                + "  <decision id=\"Fee\" name=\"Fee\">\n"
                + "    <decisionTable id=\"DT\" hitPolicy=\"FIRST\">\n"
                + "      <input id=\"region\" label=\"Region\"><inputExpression id=\"ie\" typeRef=\"string\"><text>region</text></inputExpression></input>\n"
                + "      <output id=\"code\" label=\"Fee Code\" name=\"feeCode\" typeRef=\"string\"/>\n"
                + "      <output id=\"amount\" label=\"Amount\" name=\"amount\" typeRef=\"number\"/>\n"
                + rows
                + "    </decisionTable>\n  </decision>\n</definitions>\n";
    }

    /** Cases for the EU rule, expecting what it gives, so landing's test run is green. */
    static String spec(int cases, String euCode) {
        StringBuilder rows = new StringBuilder();
        for (int i = 0; i < cases; i++) {
            rows.append("      - [case ").append(i).append(", \"EU\", ").append(euCode).append("]\n");
        }
        return "tests:\n  - decision: Fee\n    columns: [scenario, region, \"=> feeCode\"]\n    rows:\n" + rows;
    }

    private GitRuleStore store;
    private ElementHistory history;
    private final Map<String, String[]> rules = new LinkedHashMap<>();
    private int cases = 1;

    @BeforeEach
    void setUp() throws Exception {
        Path root = Path.of("build/tmp/history-test", String.valueOf(System.nanoTime()));
        GitProperties p = new GitProperties();
        p.setEnabled(true);
        store = new GitRuleStore(p, root.resolve("ws"));
        store.initialise();
        history = new ElementHistory(store);

        rules.put("r1", new String[]{"\"EU\"", "\"EU-STD\"", "5"});
        rules.put("r2", new String[]{"\"US\"", "\"US-STD\"", "7"});
        commitOnMain("c1 first rules");
        rules.get("r1")[1] = "\"EU-FLAT\"";
        commitOnMain("c2 EU code");
        rules.get("r1")[2] = "6";
        commitOnMain("c3 EU amount");
        rules.get("r2")[1] = "\"US-FLAT\"";
        commitOnMain("c4 US code");
        rules.put("r3", new String[]{"-", "\"OTHER\"", "9"});
        commitOnMain("c5 a rule for the rest");
        cases = 2;
        commitOnMain("c6 one more case");
    }

    private void commitOnMain(String message) throws Exception {
        Path main = store.mainCheckout();
        try (Git git = Git.open(main.toFile())) {
            git.checkout().setName("main").call();
            Files.writeString(main.resolve("dmn/fee.dmn"), dmn(rules));
            Files.writeString(main.resolve("yaml/fee.yaml"), spec(cases, rules.containsKey("r1") ? rules.get("r1")[1] : "\"OTHER\""));
            git.add().addFilepattern(".").call();
            git.commit().setMessage(message).call();
        }
    }

    private ElementHistory.Result at(String decision, String rule, String column) throws Exception {
        return history.at("main", new ElementHistory.Subject("fee", decision, rule, column));
    }

    private static List<String> messages(ElementHistory.Result r) {
        return r.entries.stream().map(e -> e.uncommitted ? "uncommitted" : e.message.split(" ")[0]).collect(Collectors.toList());
    }

    @Test
    @DisplayName("a result cell: only the commits that changed that cell, each with the value before and after")
    void cell() throws Exception {
        ElementHistory.Result code = at("Fee", "r1", "code");
        assertEquals("CELL", code.level);
        assertEquals(Arrays.asList("c2", "c1"), messages(code));
        assertEquals("\"EU-STD\"", code.entries.get(0).before);
        assertEquals("\"EU-FLAT\"", code.entries.get(0).after);
        assertEquals("ADDED", code.entries.get(1).change);
        assertNull(code.entries.get(1).before);

        assertEquals(Arrays.asList("c3", "c1"), messages(at("Fee", "r1", "amount")));
        assertEquals(Arrays.asList("c4", "c1"), messages(at("Fee", "r2", "code")));
    }

    @Test
    @DisplayName("a rule: every commit that changed any of its cells, with the rule's diff")
    void rule() throws Exception {
        ElementHistory.Result r1 = at("Fee", "r1", null);
        assertEquals(Arrays.asList("c3", "c2", "c1"), messages(r1));
        assertEquals(1, r1.entries.get(0).diff.decisions.get(0).rules.size(), "the diff holds that rule only");
        assertEquals(Integer.valueOf(1), r1.entries.get(0).ruleAfter);
    }

    @Test
    @DisplayName("a column: its cells in every rule, a rule added with a value in it included")
    void column() throws Exception {
        ElementHistory.Result code = at("Fee", null, "code");
        assertEquals(Arrays.asList("c5", "c4", "c2", "c1"), messages(code));
        ElementHistory.CellChange added = code.entries.get(0).cells.get(0);
        assertEquals("ADDED", added.change);
        assertEquals(Integer.valueOf(3), added.ruleAfter);
        assertEquals("\"OTHER\"", added.after);
        assertEquals(1, code.entries.get(1).cells.size(), "c4 changed one rule's code");
    }

    @Test
    @DisplayName("a table: every rule change; the domain: the tests as well")
    void tableAndDomain() throws Exception {
        assertEquals(Arrays.asList("c5", "c4", "c3", "c2", "c1"), messages(at("Fee", null, null)));
        ElementHistory.Result domain = at(null, null, null);
        assertEquals(Arrays.asList("c6", "c5", "c4", "c3", "c2", "c1"), messages(domain));
        assertTrue(domain.entries.get(0).testsChanged);
        assertEquals(Integer.valueOf(1), domain.entries.get(0).casesBefore);
        assertEquals(Integer.valueOf(2), domain.entries.get(0).casesAfter);
    }

    @Test
    @DisplayName("a rule that really moved is a change of it; one shifted by a removal above is not")
    void moves() throws Exception {
        rules.remove("r1");
        commitOnMain("c7 drop EU");
        assertEquals(Arrays.asList("c4", "c1"), messages(at("Fee", "r2", null)), "r2 became rule 1 without moving");

        Map<String, String[]> swapped = new LinkedHashMap<>();
        swapped.put("r3", rules.get("r3"));
        swapped.put("r2", rules.get("r2"));
        rules.clear();
        rules.putAll(swapped);
        commitOnMain("c8 rest first");
        ElementHistory.Result r2 = at("Fee", "r2", null);
        assertEquals(Arrays.asList("c8", "c4", "c1"), messages(r2));
        assertEquals("MOVED", r2.entries.get(0).change);
        assertEquals(Integer.valueOf(1), r2.entries.get(0).ruleBefore);
        assertEquals(Integer.valueOf(2), r2.entries.get(0).ruleAfter);
        assertEquals(Arrays.asList("c4", "c1"), messages(at("Fee", "r2", "code")), "a cell has no place of its own");
    }

    @Test
    @DisplayName("landed work: the space's own commit is listed, not the landing that brought it")
    void throughALanding() throws Exception {
        WorkspaceId id = WorkspaceId.of(WorkspaceId.Kind.FEATURE, "alice", "us-amount");
        store.createWorkspace(id, "main", "main");
        Map<String, String[]> mine = new LinkedHashMap<>();
        rules.forEach((k, v) -> mine.put(k, v.clone()));
        mine.get("r2")[2] = "8";
        Files.writeString(store.checkoutOf(id).resolve("dmn/fee.dmn"), dmn(mine));
        store.commit(id, "s1 US amount", "alice");

        // main moves on meanwhile, in another rule, so the landing is a real merge
        rules.get("r1")[2] = "4";
        commitOnMain("c7 EU amount again");
        Landing landing = new Landing(store, new RemoteSync(store), new TestGate(store, new TestExecutionService()));
        Landing.Result landed = landing.land(id, null, null, true, "alice");
        assertEquals("LANDED", landed.status, landed.message);

        ElementHistory.Result cell = at("Fee", "r2", "amount");
        assertEquals(Arrays.asList("s1", "c1"), messages(cell));
        assertFalse(cell.entries.get(0).merge);
        assertEquals("alice", cell.entries.get(0).author);
        List<String> table = messages(at("Fee", null, null));
        assertEquals(Arrays.asList("c7", "s1", "c5", "c4", "c3", "c2", "c1"), table, "the landing made nothing itself");
    }

    @Test
    @DisplayName("a work space: what it saved but did not commit comes first")
    void uncommittedInASpace() throws Exception {
        WorkspaceId id = WorkspaceId.of(WorkspaceId.Kind.FEATURE, "alice", "draft");
        store.createWorkspace(id, "main", "main");
        rules.get("r1")[1] = "\"EU-NEW\"";
        Files.writeString(store.checkoutOf(id).resolve("dmn/fee.dmn"), dmn(rules));

        ElementHistory.Result cell = history.ofSpace(id, new ElementHistory.Subject("fee", "Fee", "r1", "code"));
        assertEquals(Arrays.asList("uncommitted", "c2", "c1"), messages(cell));
        assertNull(cell.entries.get(0).sha);
        assertEquals("\"EU-NEW\"", cell.entries.get(0).after);
        assertEquals(Arrays.asList("c4", "c1"), messages(history.ofSpace(id, new ElementHistory.Subject("fee", "Fee", "r2", "code"))));
    }
}
