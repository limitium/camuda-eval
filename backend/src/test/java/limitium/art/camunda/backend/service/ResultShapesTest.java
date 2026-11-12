package limitium.art.camunda.backend.service;

import limitium.art.camunda.backend.model.TestStatistics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Results are read the way DMN gives them: nothing, one value, or every matched rule's value in order. */
class ResultShapesTest {

    private static final String DMN = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<definitions xmlns=\"https://www.omg.org/spec/DMN/20191111/MODEL/\" id=\"D\" name=\"D\" namespace=\"x\">\n"
            + "  <decision id=\"Size\" name=\"Amount Class\"><decisionTable id=\"S\" hitPolicy=\"FIRST\">\n"
            + "    <input id=\"si\"><inputExpression id=\"sie\" typeRef=\"number\"><text>amount</text></inputExpression></input>\n"
            + "    <output id=\"so\" name=\"size\" typeRef=\"string\"/>\n"
            + "    <rule id=\"s1\"><inputEntry id=\"s1i\"><text>&gt; 1000</text></inputEntry><outputEntry id=\"s1o\"><text>\"big\"</text></outputEntry></rule>\n"
            + "    <rule id=\"s2\"><inputEntry id=\"s2i\"><text>-</text></inputEntry><outputEntry id=\"s2o\"><text>\"small\"</text></outputEntry></rule>\n"
            + "  </decisionTable></decision>\n"
            + "  <decision id=\"Fees\" name=\"Fees\">\n"
            + "    <informationRequirement id=\"r\"><requiredDecision href=\"#Size\"/></informationRequirement>\n"
            + "    <decisionTable id=\"F\" hitPolicy=\"COLLECT\">\n"
            + "    <input id=\"fi\"><inputExpression id=\"fie\" typeRef=\"string\"><text>size</text></inputExpression></input>\n"
            + "    <output id=\"fo\" name=\"fee\" typeRef=\"number\"/>\n"
            + "    <rule id=\"f1\"><inputEntry id=\"f1i\"><text>\"big\"</text></inputEntry><outputEntry id=\"f1o\"><text>10</text></outputEntry></rule>\n"
            + "    <rule id=\"f2\"><inputEntry id=\"f2i\"><text>\"big\"</text></inputEntry><outputEntry id=\"f2o\"><text>5</text></outputEntry></rule>\n"
            + "    <rule id=\"f3\"><inputEntry id=\"f3i\"><text>\"medium\"</text></inputEntry><outputEntry id=\"f3o\"><text>1</text></outputEntry></rule>\n"
            + "  </decisionTable></decision>\n"
            + "</definitions>\n";

    private static TestStatistics run(String rows) throws Exception {
        String spec = "tests:\n"
                + "  - decision: Fees\n"
                + "    columns: [scenario, amount, \"=> size\", \"=> fee\"]\n"
                + "    rows:\n" + rows;
        return new TestExecutionService().runYamlTests(DMN, spec, line -> { });
    }

    @Test
    @DisplayName("several matched rules give every value in order, checked by a list")
    void severalRulesGiveAList() throws Exception {
        TestStatistics stats = run("      - [two fees, 5000, big, [10, 5]]\n");
        assertTrue(stats.results.get(0).passed, stats.results.get(0).errorMessage);
    }

    @Test
    @DisplayName("several matched rules against one expected value fail in words, naming the list")
    void severalRulesAgainstOneValue() throws Exception {
        TestStatistics stats = run("      - [one fee expected, 5000, big, 10]\n");
        String error = stats.results.get(0).errorMessage;
        assertFalse(stats.results.get(0).passed);
        assertTrue(error.contains("fee: expected 10, but 2 rules of Fees matched, giving [10, 5]"), error);
    }

    @Test
    @DisplayName("no rule matching is said as such, not as an engine exception")
    void noRuleMatched() throws Exception {
        TestStatistics stats = run("      - [small has no fee, 10, small, 3]\n"
                + "      - [nothing expected, 10, small, ~]\n");
        String error = stats.results.get(0).errorMessage;
        assertTrue(error.contains("fee: expected 3, but no rule of Fees matched"), error);
        assertFalse(error.contains("Cannot invoke"), error);
        assertTrue(stats.results.get(1).passed, "a blank expectation is met by nothing: " + stats.results.get(1).errorMessage);
    }

    @Test
    @DisplayName("a wrong step names the table that gave it")
    void wrongStepNamesItsTable() throws Exception {
        TestStatistics stats = run("      - [wrong size, 5000, small, [10, 5]]\n");
        String error = stats.results.get(0).errorMessage;
        assertTrue(error.contains("size: expected small, Amount Class gave big"), error);
        assertEquals(1, stats.results.size());
    }
}
