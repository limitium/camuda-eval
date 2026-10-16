package limitium.art.camunda.backend.service;

import limitium.art.camunda.backend.model.TestStatistics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A test of a chain checks every step it expects, each against the table that gives it. */
class ChainTestTest {

    private static final String DMN = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<definitions xmlns=\"https://www.omg.org/spec/DMN/20191111/MODEL/\" id=\"D\" name=\"D\" namespace=\"x\">\n"
            + "  <decision id=\"Tier\" name=\"Tier\"><decisionTable id=\"T\" hitPolicy=\"FIRST\">\n"
            + "    <input id=\"ti\"><inputExpression id=\"tie\" typeRef=\"number\"><text>spend</text></inputExpression></input>\n"
            + "    <output id=\"to\" name=\"tier\" typeRef=\"string\"/>\n"
            + "    <rule id=\"t1\"><inputEntry id=\"t1i\"><text>&gt;= 1000</text></inputEntry><outputEntry id=\"t1o\"><text>\"gold\"</text></outputEntry></rule>\n"
            + "    <rule id=\"t2\"><inputEntry id=\"t2i\"><text>-</text></inputEntry><outputEntry id=\"t2o\"><text>\"standard\"</text></outputEntry></rule>\n"
            + "  </decisionTable></decision>\n"
            + "  <decision id=\"Discount\" name=\"Discount\">\n"
            + "    <informationRequirement id=\"r\"><requiredDecision href=\"#Tier\"/></informationRequirement>\n"
            + "    <decisionTable id=\"DT\" hitPolicy=\"FIRST\">\n"
            + "    <input id=\"di\"><inputExpression id=\"die\" typeRef=\"string\"><text>tier</text></inputExpression></input>\n"
            + "    <output id=\"do\" name=\"discount\" typeRef=\"string\"/>\n"
            + "    <rule id=\"d1\"><inputEntry id=\"d1i\"><text>\"gold\"</text></inputEntry><outputEntry id=\"d1o\"><text>\"15%\"</text></outputEntry></rule>\n"
            + "    <rule id=\"d2\"><inputEntry id=\"d2i\"><text>-</text></inputEntry><outputEntry id=\"d2o\"><text>\"0%\"</text></outputEntry></rule>\n"
            + "  </decisionTable></decision>\n"
            + "</definitions>\n";

    @Test
    @DisplayName("supplying what a table on the way gives tests the last table alone")
    void aTableAlone() throws Exception {
        String spec = "tests:\n"
                + "  - decision: Discount\n"
                + "    columns: [scenario, tier, \"=> discount\"]\n"
                + "    rows:\n"
                + "      - [gold given directly, gold, 15%]\n"
                + "      - [standard given directly, standard, 0%]\n"
                + "  - decision: Discount\n"
                + "    columns: [scenario, spend, \"=> tier\", \"=> discount\"]\n"
                + "    rows:\n"
                + "      - [whole chain, 2000, gold, 15%]\n";
        TestStatistics stats = new TestExecutionService().runYamlTests(DMN, spec, line -> { });
        assertEquals(3, stats.results.size());
        stats.results.forEach(r -> assertTrue(r.passed, r.testName + ": " + r.errorMessage));
        assertEquals("Discount #1 / gold given directly", stats.results.get(0).testName, "sets of one table are told apart");
        assertEquals("Discount #2 / whole chain", stats.results.get(2).testName);
    }

    @Test
    @DisplayName("expecting a step that does not run is an error, said plainly")
    void expectingACutStep() {
        String spec = "tests:\n"
                + "  - decision: Discount\n"
                + "    columns: [scenario, tier, \"=> tier\", \"=> discount\"]\n"
                + "    rows:\n"
                + "      - [nonsense, gold, gold, 15%]\n";
        var problems = limitium.art.camunda.evaluator.testspec.TestSpecValidator.validate(DMN, spec);
        assertTrue(problems.stream().anyMatch(p -> p.message.contains("does not run")), problems.toString());
    }

    @Test
    @DisplayName("a chain case passes only when every step it expects is right")
    void everyStepIsChecked() throws Exception {
        String spec = "tests:\n"
                + "  - decision: Discount\n"
                + "    columns: [scenario, spend, \"=> tier\", \"=> discount\"]\n"
                + "    rows:\n"
                + "      - [big spender, 2000, gold, 15%]\n"
                + "      - [wrong step, 2000, standard, 15%]\n";
        TestStatistics stats = new TestExecutionService().runYamlTests(DMN, spec, line -> { });
        assertEquals(2, stats.results.size());
        assertTrue(stats.results.get(0).passed, String.valueOf(stats.results.get(0).errorMessage));
        assertTrue(!stats.results.get(1).passed, "the right final result does not hide a wrong step");
        assertTrue(stats.results.get(1).errorMessage.contains("tier"), stats.results.get(1).errorMessage);
        assertEquals("Discount / big spender", stats.results.get(0).testName, "one set keeps the plain name");
    }
}
