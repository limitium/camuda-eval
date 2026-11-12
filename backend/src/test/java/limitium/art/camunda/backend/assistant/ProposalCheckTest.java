package limitium.art.camunda.backend.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What the model proposes reaches the screen only as the engine would run it. */
class ProposalCheckTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** What the table screen sends for the demo's Fee Code: three conditions, two results, two rules. */
    static final String CONTEXT = "{\"openTable\":\"FeeCode\",\"tables\":[{\"id\":\"FeeCode\",\"name\":\"Fee Code\",\"hitPolicy\":\"FIRST\","
            + "\"inputs\":[{\"key\":\"country\",\"label\":\"Country\",\"expression\":\"country\",\"type\":\"string\"},"
            + "{\"key\":\"market\",\"label\":\"Market\",\"expression\":\"market\",\"type\":\"string\"},"
            + "{\"key\":\"amount\",\"label\":\"Amount\",\"expression\":\"amount\",\"type\":\"number\"}],"
            + "\"outputs\":[{\"name\":\"feeCode\",\"label\":\"Fee Code\",\"type\":\"string\"},{\"name\":\"feeCalculatorId\",\"label\":\"Fee Calculator\",\"type\":\"string\"}],"
            + "\"rules\":[{\"inputs\":[\"\\\"US\\\"\",\"-\",\">= 1000000\"],\"outputs\":[\"\\\"US-LRG\\\"\",\"\\\"TIERED\\\"\"]},"
            + "{\"inputs\":[\"-\",\"-\",\"-\"],\"outputs\":[\"\\\"XX-MIN\\\"\",\"\\\"BPS-MIN\\\"\"]}],"
            + "\"supplied\":[\"country\",\"market\",\"amount\"]}]}";

    static final String XML = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<definitions xmlns=\"https://www.omg.org/spec/DMN/20191111/MODEL/\" id=\"d\" name=\"Fee\" namespace=\"http://camunda.org/schema/1.0/dmn\">"
            + "<decision id=\"FeeCode\" name=\"Fee Code\"><decisionTable id=\"t\" hitPolicy=\"FIRST\">"
            + "<input id=\"i1\"><inputExpression id=\"e1\" typeRef=\"string\"><text>country</text></inputExpression></input>"
            + "<input id=\"i2\"><inputExpression id=\"e2\" typeRef=\"string\"><text>market</text></inputExpression></input>"
            + "<input id=\"i3\"><inputExpression id=\"e3\" typeRef=\"number\"><text>amount</text></inputExpression></input>"
            + "<output id=\"o1\" name=\"feeCode\" typeRef=\"string\"/><output id=\"o2\" name=\"feeCalculatorId\" typeRef=\"string\"/>"
            + "<rule id=\"r1\"><inputEntry id=\"a1\"><text>\"US\"</text></inputEntry><inputEntry id=\"a2\"><text>-</text></inputEntry><inputEntry id=\"a3\"><text>&gt;= 1000000</text></inputEntry>"
            + "<outputEntry id=\"b1\"><text>\"US-LRG\"</text></outputEntry><outputEntry id=\"b2\"><text>\"TIERED\"</text></outputEntry></rule>"
            + "<rule id=\"r2\"><inputEntry id=\"c1\"><text>-</text></inputEntry><inputEntry id=\"c2\"><text>-</text></inputEntry><inputEntry id=\"c3\"><text>-</text></inputEntry>"
            + "<outputEntry id=\"d1\"><text>\"XX-MIN\"</text></outputEntry><outputEntry id=\"d2\"><text>\"BPS-MIN\"</text></outputEntry></rule>"
            + "</decisionTable></decision></definitions>";

    private static ProposalCheck.Outcome check(String proposal) throws Exception {
        return new ProposalCheck(JSON.readTree(CONTEXT), XML).check(JSON.readTree(proposal));
    }

    private static String rule(String when, String then) {
        return "{\"type\":\"add_rule\",\"table\":\"Fee Code\",\"position\":2,\"when\":" + when + ",\"then\":" + then + ",\"description\":\"German large trades\"}";
    }

    private static final String THEN = "{\"feeCode\":\"\\\"DE-LRG\\\"\",\"feeCalculatorId\":\"\\\"TIERED\\\"\"}";

    @Test
    @DisplayName("a rule the engine accepts comes back as one rule's cells, in column order, conditions left out as any")
    void acceptedRule() throws Exception {
        ProposalCheck.Outcome o = check(rule("{\"Country\":\"\\\"DE\\\"\",\"amount\":\">= 500000\"}", THEN));
        assertTrue(o.ok(), o.problems.toString());
        assertEquals("FeeCode", o.proposal.path("table").asText());
        assertEquals("[\"\\\"DE\\\"\",\"-\",\">= 500000\"]", o.proposal.path("inputs").toString());
        assertEquals("[\"\\\"DE-LRG\\\"\",\"\\\"TIERED\\\"\"]", o.proposal.path("outputs").toString());
        assertEquals(2, o.proposal.path("position").asInt());
        assertEquals("German large trades", o.proposal.path("description").asText());
    }

    @Test
    @DisplayName("every cell the engine refuses is refused, with the engine's reason or the usual fix")
    void refusedCells() throws Exception {
        String bare = check(rule("{\"country\":\"DE\"}", THEN)).problems.toString();
        assertTrue(bare.contains("text goes in double quotes, like `\"DE\"`"), bare);

        assertFalse(check(rule("{\"amount\":\"= 100\"}", THEN)).ok());
        assertFalse(check(rule("{\"amount\":\"100..500\"}", THEN)).ok());
        assertFalse(check(rule("{\"amount\":\"\\\"100\\\"\"}", THEN)).ok());
        assertFalse(check(rule("{\"country\":\"\\\"DE\\\"\"}", "{\"feeCode\":\"-\",\"feeCalculatorId\":\"\\\"TIERED\\\"\"}")).ok());
        assertFalse(check(rule("{\"country\":\"\\\"DE\\\"\"}", "{\"feeCode\":\"Gold\",\"feeCalculatorId\":\"\\\"TIERED\\\"\"}")).ok());

        // What it accepts: ranges, lists, negation, comparisons with another value, worked-out results.
        assertTrue(check(rule("{\"amount\":\"[100..500)\",\"market\":\"\\\"LSE\\\",\\\"XETRA\\\"\",\"country\":\"not(\\\"US\\\")\"}", THEN)).ok());
        assertTrue(check(rule("{\"amount\":\"> 0\"}", "{\"feeCode\":\"\\\"FEE-\\\" + market\",\"feeCalculatorId\":\"null\"}")).ok());
    }

    @Test
    @DisplayName("names that are not the table's, and results left out, are refused by name")
    void wrongColumns() throws Exception {
        String unknown = check(rule("{\"region\":\"\\\"EU\\\"\"}", THEN)).problems.toString();
        assertTrue(unknown.contains("`region`, which is not a condition of Fee Code; its conditions are `country`, `market`, `amount`"), unknown);
        String missing = check(rule("{\"country\":\"\\\"DE\\\"\"}", "{\"feeCode\":\"\\\"DE\\\"\"}")).problems.toString();
        assertTrue(missing.contains("gives nothing for `feeCalculatorId`"), missing);
        String table = check("{\"type\":\"add_rule\",\"table\":\"Fees\",\"when\":{},\"then\":{}}").problems.toString();
        assertTrue(table.contains("the tables here are \"Fee Code\""), table);
    }

    @Test
    @DisplayName("a position outside the table is clamped to it")
    void positionClamped() throws Exception {
        ProposalCheck.Outcome o = check(rule("{\"country\":\"\\\"DE\\\"\"}", THEN).replace("\"position\":2", "\"position\":-5"));
        assertTrue(o.ok(), o.problems.toString());
        assertEquals(1, o.proposal.path("position").asInt());
    }

    @Test
    @DisplayName("under first-match, a rule below one that takes its values is refused, saying where it goes")
    void shadowedRule() throws Exception {
        // At the end it sits under the catch-all, rule 2, which takes every trade first.
        String below = check(rule("{\"country\":\"\\\"DE\\\"\"}", THEN).replace("\"position\":2", "\"position\":3")).problems.toString();
        assertTrue(below.contains("as rule 3 it would never apply: for country \"DE\", rule 2 matches first"), below);
        assertTrue(below.contains("Give it position 2, just above that rule"), below);
        // Under rule 1 a big US trade is rule 1's; a small one is not, so placed there it applies.
        String shadowed = check(rule("{\"country\":\"\\\"US\\\"\",\"amount\":\">= 2000000\"}", THEN)).problems.toString();
        assertTrue(shadowed.contains("rule 1 matches first"), shadowed);
        assertTrue(check(rule("{\"country\":\"\\\"US\\\"\",\"amount\":\"< 1000\"}", THEN)).ok());
    }

    @Test
    @DisplayName("text without quotes that the engine would quietly read as a sum of names is refused")
    void hyphenatedText() throws Exception {
        // The engine reads JP-LRG as JP minus LRG and gives no value, without an error.
        String result = check(rule("{\"country\":\"\\\"JP\\\"\"}", "{\"feeCode\":\"JP-LRG\",\"feeCalculatorId\":\"\\\"TIERED\\\"\"}")).problems.toString();
        assertTrue(result.contains("like `\"JP-LRG\"`"), result);
        String condition = check(rule("{\"market\":\"NYSE, XETRA\"}", THEN)).problems.toString();
        assertTrue(condition.contains("like `\"NYSE\",\"XETRA\"`"), condition);
        // Names that exist, and FEEL's own words, are not mistaken for it.
        assertTrue(check(rule("{\"country\":\"not(\\\"US\\\")\",\"amount\":\"> 0\"}", "{\"feeCode\":\"if amount > 10 then \\\"A\\\" else \\\"B\\\"\",\"feeCalculatorId\":\"string(amount)\"}")).ok());
    }

    @Test
    @DisplayName("a test case arrives saying what the table gives for it today")
    void testCaseRunToday() throws Exception {
        ProposalCheck.Outcome agrees = check("{\"type\":\"add_test\",\"table\":\"FeeCode\",\"scenario\":\"big US\",\"given\":{\"country\":\"US\",\"market\":\"NYSE\",\"amount\":2000000},\"expect\":{\"feeCode\":\"US-LRG\"}}");
        assertTrue(agrees.ok(), agrees.problems.toString());
        assertTrue(agrees.proposal.path("today").path("agrees").asBoolean());
        assertEquals(2000000, agrees.proposal.path("given").path("amount").asInt());

        ProposalCheck.Outcome differs = check("{\"type\":\"add_test\",\"table\":\"Fee Code\",\"given\":{\"Country\":\"DE\",\"amount\":10},\"expect\":{\"feeCode\":\"DE-STD\"}}");
        assertTrue(differs.ok(), differs.problems.toString());
        JsonNode today = differs.proposal.path("today");
        assertFalse(today.path("agrees").asBoolean());
        assertEquals("XX-MIN", today.path("results").path(0).path("feeCode").asText());
        assertEquals("country", differs.proposal.path("given").fieldNames().next());

        String wrong = check("{\"type\":\"add_test\",\"table\":\"Fee Code\",\"given\":{\"region\":\"EU\"},\"expect\":{\"fee\":1}}").problems.toString();
        assertTrue(wrong.contains("`region`") && wrong.contains("`fee`"), wrong);
    }

    @Test
    @DisplayName("anything but one rule or one case is refused")
    void onlyTwoKinds() throws Exception {
        ProposalCheck.Outcome o = check("{\"type\":\"remove_rule\",\"table\":\"Fee Code\"}");
        assertFalse(o.ok());
        assertNull(o.proposal);
    }
}
