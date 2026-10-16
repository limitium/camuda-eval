package limitium.art.camunda.evaluator.testspec;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestSpecTest {

    private static final String PRICING =
            "tests:\n"
            + "  - decision: Pricing\n"
            + "    columns: [scenario, amount, region, \"=> discount\"]\n"
            + "    rows:\n"
            + "      - [EU small order, 50,  EU, \"0%\"]\n"
            + "      - [EU bulk,        600, EU, \"10%\"]\n";

    @Test
    void readsColumnsAndRows() {
        TestSpec spec = TestSpecReader.read(PRICING);
        assertEquals(1, spec.decisions.size());

        DecisionTests decision = spec.decisions.get(0);
        assertEquals("Pricing", decision.decision);
        assertEquals(Arrays.asList("amount", "region"), decision.inputColumns);
        assertEquals(Arrays.asList("discount"), decision.outputColumns,
                "the => prefix marks an expectation and is not part of the output name");
        assertEquals(2, decision.cases.size());

        TestCase first = decision.cases.get(0);
        assertEquals("EU small order", first.scenario);
        assertEquals(Arrays.asList((Object) Integer.valueOf(50), "EU"), first.inputs);
        assertEquals(Arrays.asList((Object) "0%"), first.expected);
    }

    @Test
    @DisplayName("cells are values, not FEEL: 50 is a number and EU is a string")
    void cellsAreTypedValues() {
        Map<String, Object> inputs = TestSpecReader.read(PRICING)
                .decisions.get(0).cases.get(0)
                .inputsAsMap(Arrays.asList("amount", "region"));

        assertEquals(Integer.valueOf(50), inputs.get("amount"));
        assertEquals("EU", inputs.get("region"));
    }

    @Test
    @DisplayName("a block per decision, so tables with different inputs do not distort each other")
    void severalDecisionsEachKeepTheirOwnColumns() {
        String yaml = PRICING
                + "\n"
                + "  - decision: Fees\n"
                + "    columns: [scenario, amount, \"=> fee\", \"=> tier\"]\n"
                + "    rows:\n"
                + "      - [zero, 0, \"0\", basic]\n";

        TestSpec spec = TestSpecReader.read(yaml);
        assertEquals(2, spec.decisions.size());
        assertEquals(3, spec.caseCount());

        DecisionTests fees = spec.decisions.get(1);
        assertEquals(Arrays.asList("amount"), fees.inputColumns);
        assertEquals(Arrays.asList("fee", "tier"), fees.outputColumns,
                "several expected outputs per row, which the old one-value 'out' could not express");
        assertEquals(Arrays.asList((Object) "0", "basic"), fees.cases.get(0).expected);
    }

    @Test
    @DisplayName("a list cell carries a COLLECT decision's expected results")
    void listExpectation() {
        String yaml = "tests:\n"
                + "  - decision: MultiHit\n"
                + "    columns: [scenario, x, \"=> out\"]\n"
                + "    rows:\n"
                + "      - [collects in order, anything, [A, B]]\n";

        Object expected = TestSpecReader.read(yaml).decisions.get(0).cases.get(0).expected.get(0);
        assertEquals(Arrays.asList("A", "B"), expected);
    }

    // --- canonical output ------------------------------------------------------------------

    @Test
    @DisplayName("header and rows form one table: every cell starts in the same column")
    void writesAlignedColumns() {
        String written = TestSpecWriter.write(TestSpecReader.read(PRICING));

        List<Integer> bracketAt = new java.util.ArrayList<Integer>();
        List<Integer> secondCellAt = new java.util.ArrayList<Integer>();
        for (String line : written.split("\n")) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("columns: [") && !trimmed.startsWith("- [")) {
                continue;
            }
            bracketAt.add(Integer.valueOf(line.indexOf('[')));
            int cell = line.indexOf(',') + 1;
            while (cell < line.length() && line.charAt(cell) == ' ') {
                cell++;
            }
            secondCellAt.add(Integer.valueOf(cell));
        }

        assertEquals(3, bracketAt.size(), "one header and two rows:\n" + written);
        for (int i = 1; i < bracketAt.size(); i++) {
            assertEquals(bracketAt.get(0), bracketAt.get(i), "brackets align:\n" + written);
            assertEquals(secondCellAt.get(0), secondCellAt.get(i),
                    "second cell starts in the same column:\n" + written);
        }
        assertTrue(written.contains("\"=> discount\""), "the expectation header is quoted:\n" + written);
    }

    @Test
    void roundTripsWithoutDrift() {
        String once = TestSpecWriter.write(TestSpecReader.read(PRICING));
        String twice = TestSpecWriter.write(TestSpecReader.read(once));
        assertEquals(once, twice, "canonical output must be a fixed point");

        TestSpec reread = TestSpecReader.read(once);
        assertEquals(2, reread.decisions.get(0).cases.size());
        assertEquals(Integer.valueOf(600), reread.decisions.get(0).cases.get(1).inputs.get(0));
    }

    @Test
    @DisplayName("values that would parse as something else are quoted on the way out")
    void quotesAmbiguousValues() {
        String yaml = "tests:\n"
                + "  - decision: D\n"
                + "    columns: [scenario, flag, \"=> out\"]\n"
                + "    rows:\n"
                + "      - [\"looks boolean\", \"yes\", \"no\"]\n"
                + "      - [\"looks numeric\", \"007\", \"1.5\"]\n";

        String written = TestSpecWriter.write(TestSpecReader.read(yaml));
        assertTrue(written.contains("\"yes\""), "a string 'yes' must not become a boolean:\n" + written);
        assertTrue(written.contains("\"007\""), "a string '007' must not become a number:\n" + written);

        TestCase reread = TestSpecReader.read(written).decisions.get(0).cases.get(0);
        assertEquals("yes", reread.inputs.get(0));
        assertEquals("no", reread.expected.get(0));
    }

    // --- errors name the place ---------------------------------------------------------------

    @Test
    void rowWithWrongCellCountIsRejectedByPosition() {
        String yaml = "tests:\n"
                + "  - decision: Pricing\n"
                + "    columns: [scenario, amount, \"=> discount\"]\n"
                + "    rows:\n"
                + "      - [ok, 1, \"0%\"]\n"
                + "      - [short, 2]\n";

        TestSpecReader.TestSpecException error = assertThrows(TestSpecReader.TestSpecException.class,
                new org.junit.jupiter.api.function.Executable() {
                    public void execute() {
                        TestSpecReader.read(yaml);
                    }
                });
        assertTrue(error.getMessage().contains("row 2"), error.getMessage());
        assertTrue(error.getMessage().contains("Pricing"), error.getMessage());
    }

    @Test
    void missingExpectationColumnIsRejected() {
        String yaml = "tests:\n"
                + "  - decision: Pricing\n"
                + "    columns: [scenario, amount]\n"
                + "    rows: []\n";

        TestSpecReader.TestSpecException error = assertThrows(TestSpecReader.TestSpecException.class,
                new org.junit.jupiter.api.function.Executable() {
                    public void execute() {
                        TestSpecReader.read(yaml);
                    }
                });
        assertTrue(error.getMessage().contains("=>"), "the message should show how to fix it: "
                + error.getMessage());
    }

    @Test
    void missingScenarioColumnIsRejected() {
        String yaml = "tests:\n"
                + "  - decision: Pricing\n"
                + "    columns: [amount, \"=> discount\"]\n"
                + "    rows: []\n";

        assertThrows(TestSpecReader.TestSpecException.class,
                new org.junit.jupiter.api.function.Executable() {
                    public void execute() {
                        TestSpecReader.read(yaml);
                    }
                });
    }

    @Test
    @DisplayName("an empty spec is written as an empty list, never a bare key that parses to null")
    void emptySpecCanonicalFormIsAnEmptyList() {
        String written = TestSpecWriter.write(TestSpecReader.read("tests:\n"));
        assertEquals("tests: []\n", written);
        assertEquals(0, TestSpecReader.read(written).decisions.size());
    }

    @Test
    void emptyFileIsNoTestsRatherThanAnError() {
        assertEquals(0, TestSpecReader.read("").decisions.size());
        assertEquals(0, TestSpecReader.read("tests:\n").decisions.size());
    }
}
