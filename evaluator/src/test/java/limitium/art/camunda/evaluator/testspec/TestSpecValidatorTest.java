package limitium.art.camunda.evaluator.testspec;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestSpecValidatorTest {

    private static String pricingDmn(String inputVariable, String outputName) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<definitions id=\"Defs\" name=\"Defs\" namespace=\"http://camunda.org/schema/1.0/dmn\"\n"
                + "  xmlns=\"https://www.omg.org/spec/DMN/20191111/MODEL/\">\n"
                + "  <decision id=\"Pricing\" name=\"Pricing\">\n"
                + "    <decisionTable id=\"DT\" hitPolicy=\"UNIQUE\">\n"
                + "      <input id=\"in_amount\"><inputExpression id=\"ie_amount\" typeRef=\"number\">"
                + "<text>amount</text></inputExpression></input>\n"
                + "      <input id=\"in_region\"><inputExpression id=\"ie_region\" typeRef=\"string\">"
                + "<text>" + inputVariable + "</text></inputExpression></input>\n"
                + "      <output id=\"out_1\" name=\"" + outputName + "\" typeRef=\"string\"/>\n"
                + "      <rule id=\"r1\"><inputEntry id=\"e1\"><text>-</text></inputEntry>"
                + "<inputEntry id=\"e2\"><text>-</text></inputEntry>"
                + "<outputEntry id=\"e3\"><text>\"0%\"</text></outputEntry></rule>\n"
                + "    </decisionTable>\n"
                + "  </decision>\n"
                + "</definitions>\n";
    }

    private static final String SPEC =
            "tests:\n"
            + "  - decision: Pricing\n"
            + "    columns: [scenario, amount, region, \"=> discount\"]\n"
            + "    rows:\n"
            + "      - [any, 50, EU, \"0%\"]\n";

    @Test
    void aMatchingPairHasNoProblems() {
        List<TestSpecValidator.Problem> problems =
                TestSpecValidator.validate(pricingDmn("region", "discount"), SPEC);
        assertEquals(0, problems.size(), describe(problems));
    }

    // --- the point of the whole thing ---------------------------------------------------------

    @Test
    @DisplayName("renaming an input to an unrelated word is still identified, given the old DMN")
    void renamedInputNamesTheNewVariable() {
        // The spec still parses perfectly; only comparison with the DMN reveals it tests nothing.
        // "region" and "market" share no letters, so only the diff can connect them.
        List<TestSpecValidator.Problem> problems = TestSpecValidator.validate(
                pricingDmn("market", "discount"), SPEC, pricingDmn("region", "discount"));

        TestSpecValidator.Problem error = onlyError(problems);
        assertEquals("region", error.column);
        assertTrue(error.message.contains("no condition of Pricing checks 'region'"), error.message);
        assertTrue(error.message.contains("renamed to 'market'"),
                "the differ matched the column by id and saw the expression change: " + error.message);
        assertTrue(TestSpecValidator.hasErrors(problems));
    }

    @Test
    @DisplayName("without the previous version the error still fires, just without the rename")
    void renamedInputStillErrorsWithoutHistory() {
        List<TestSpecValidator.Problem> problems =
                TestSpecValidator.validate(pricingDmn("market", "discount"), SPEC);

        TestSpecValidator.Problem error = onlyError(problems);
        assertTrue(error.message.contains("no condition of Pricing checks 'region'"), error.message);
        assertFalse(error.message.contains("renamed to"),
                "no history means no claim about what happened: " + error.message);
    }

    @Test
    @DisplayName("renaming an output breaks the expectation column")
    void renamedOutputIsAnError() {
        List<TestSpecValidator.Problem> problems = TestSpecValidator.validate(
                pricingDmn("region", "rebate"), SPEC, pricingDmn("region", "discount"));

        TestSpecValidator.Problem error = onlyError(problems);
        assertEquals("discount", error.column);
        assertTrue(error.message.contains("do not give"), error.message);
        assertTrue(error.message.contains("renamed to 'rebate'"), error.message);
    }

    @Test
    void renamedDecisionIsAnError() {
        String dmn = pricingDmn("region", "discount").replace("\"Pricing\"", "\"PricingV2\"");
        List<TestSpecValidator.Problem> problems = TestSpecValidator.validate(dmn, SPEC);

        TestSpecValidator.Problem error = onlyError(problems);
        assertTrue(error.message.contains("is not in the DMN file"), error.message);
        assertTrue(error.message.contains("did you mean 'PricingV2'?"), error.message);
    }

    // --- warnings do not block -----------------------------------------------------------------

    @Test
    @DisplayName("a new input with no test column warns, because wildcarding it is legitimate")
    void unsuppliedVariableIsOnlyAWarning() {
        String spec = "tests:\n"
                + "  - decision: Pricing\n"
                + "    columns: [scenario, amount, \"=> discount\"]\n"
                + "    rows:\n"
                + "      - [any, 50, \"0%\"]\n";

        List<TestSpecValidator.Problem> problems =
                TestSpecValidator.validate(pricingDmn("region", "discount"), spec);

        assertFalse(TestSpecValidator.hasErrors(problems),
                "the coverage gate catches the real consequence: " + describe(problems));
        assertEquals(1, problems.size());
        assertEquals(TestSpecValidator.Problem.Severity.WARNING, problems.get(0).severity);
        assertTrue(problems.get(0).message.contains("evaluates as null"), problems.get(0).message);
    }

    @Test
    void anUntestedDecisionWarns() {
        List<TestSpecValidator.Problem> problems =
                TestSpecValidator.validate(pricingDmn("region", "discount"), "tests: []\n");

        assertFalse(TestSpecValidator.hasErrors(problems));
        assertEquals(1, problems.size());
        assertTrue(problems.get(0).message.contains("has no tests"), problems.get(0).message);
    }

    @Test
    void anEmptyBlockWarns() {
        String spec = "tests:\n"
                + "  - decision: Pricing\n"
                + "    columns: [scenario, amount, region, \"=> discount\"]\n"
                + "    rows: []\n";

        List<TestSpecValidator.Problem> problems =
                TestSpecValidator.validate(pricingDmn("region", "discount"), spec);
        assertFalse(TestSpecValidator.hasErrors(problems));
        assertTrue(problems.get(0).message.contains("no test rows"), describe(problems));
    }

    // --- malformed input -----------------------------------------------------------------------

    @Test
    void anUnparseableSpecIsOneErrorRatherThanAnException() {
        List<TestSpecValidator.Problem> problems = TestSpecValidator.validate(
                pricingDmn("region", "discount"),
                "tests:\n  - decision: Pricing\n    rows: []\n");

        assertTrue(TestSpecValidator.hasErrors(problems));
        assertTrue(problems.get(0).message.contains("columns"), problems.get(0).message);
    }

    @Test
    @DisplayName("an unrelated name gets no suggestion, because a wrong one misdirects")
    void distantNamesAreNotSuggested() {
        assertEquals("", TestSpecValidator.suggestion("region",
                java.util.Arrays.asList("x", "somethingEntirelyDifferent")));
        assertTrue(TestSpecValidator.suggestion("region", java.util.Arrays.asList("regions"))
                .contains("did you mean"));
    }

    private static TestSpecValidator.Problem onlyError(List<TestSpecValidator.Problem> problems) {
        TestSpecValidator.Problem found = null;
        for (TestSpecValidator.Problem problem : problems) {
            if (problem.severity == TestSpecValidator.Problem.Severity.ERROR) {
                assertTrue(found == null, "expected one error, got: " + describe(problems));
                found = problem;
            }
        }
        assertTrue(found != null, "expected an error, got: " + describe(problems));
        return found;
    }

    private static String describe(List<TestSpecValidator.Problem> problems) {
        StringBuilder sb = new StringBuilder();
        for (TestSpecValidator.Problem problem : problems) {
            sb.append("\n  ").append(problem.severity).append(' ').append(problem.message);
        }
        return sb.length() == 0 ? "(none)" : sb.toString();
    }
}
