package limitium.art.camunda.evaluator.diff;

import limitium.art.camunda.evaluator.testspec.DecisionTests;
import limitium.art.camunda.evaluator.testspec.TestSpec;
import limitium.art.camunda.evaluator.testspec.TestSpecReader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests merge case by case: every case one side touched is kept; only real disagreements are asked. */
class TestSpecMergerTest {

    static String spec(String... rows) {
        StringBuilder sb = new StringBuilder("tests:\n  - decision: Discount\n    columns: [scenario, tier, \"=> discount\"]\n    rows:\n");
        for (String row : rows) {
            sb.append("      - ").append(row).append('\n');
        }
        return sb.toString();
    }

    static DecisionTests only(String yaml) {
        TestSpec spec = TestSpecReader.read(yaml);
        assertEquals(1, spec.decisions.size());
        return spec.decisions.get(0);
    }

    static final String BASE = spec("[gold, gold, \"10%\"]", "[basic, basic, \"0%\"]");

    @Test
    @DisplayName("a case added on each side and a case changed on one side all survive")
    void disjointWorkMerges() {
        String ours = spec("[gold, gold, \"12%\"]", "[basic, basic, \"0%\"]", "[silver, silver, \"5%\"]");
        String theirs = spec("[gold, gold, \"10%\"]", "[basic, basic, \"0%\"]", "[vip, vip, \"20%\"]");
        MergeResult r = TestSpecMerger.merge(BASE, ours, theirs, null);
        assertTrue(r.clean, String.valueOf(r.conflicts));
        DecisionTests merged = only(r.mergedXml);
        assertEquals(4, merged.cases.size());
        assertEquals("12%", merged.cases.get(0).expected.get(0));
    }

    @Test
    @DisplayName("both changing what one case expects is TEST_EXPECTATION, answered by side")
    void bothChangedOneCase() {
        String ours = spec("[gold, gold, \"12%\"]", "[basic, basic, \"0%\"]");
        String theirs = spec("[gold, gold, \"15%\"]", "[basic, basic, \"0%\"]");
        MergeResult r = TestSpecMerger.merge(BASE, ours, theirs, null);
        assertFalse(r.clean);
        MergeConflict c = r.conflicts.get(0);
        assertEquals(MergeConflict.Kind.TEST_EXPECTATION, c.kind);
        MergeResult answered = TestSpecMerger.merge(BASE, ours, theirs, Collections.singletonMap(c.id, Resolution.THEIRS));
        assertEquals("15%", only(answered.mergedXml).cases.get(0).expected.get(0));
    }

    @Test
    @DisplayName("removing a case the other side changed is TEST_DELETE_MODIFY; removing an untouched one just removes it")
    void deleteModify() {
        String ours = spec("[basic, basic, \"0%\"]");
        MergeResult untouched = TestSpecMerger.merge(BASE, ours, BASE, null);
        assertTrue(untouched.clean);
        assertEquals(1, only(untouched.mergedXml).cases.size());
        String theirs = spec("[gold, gold, \"11%\"]", "[basic, basic, \"0%\"]");
        MergeResult r = TestSpecMerger.merge(BASE, ours, theirs, null);
        assertEquals(MergeConflict.Kind.TEST_DELETE_MODIFY, r.conflicts.get(0).kind);
    }

    @Test
    @DisplayName("the same new case added on both sides with different expectations is TEST_ADD_ADD")
    void addAdd() {
        MergeResult r = TestSpecMerger.merge(BASE,
                spec("[gold, gold, \"10%\"]", "[basic, basic, \"0%\"]", "[vip, vip, \"20%\"]"),
                spec("[gold, gold, \"10%\"]", "[basic, basic, \"0%\"]", "[vip, vip, \"25%\"]"), null);
        assertEquals(Arrays.asList(MergeConflict.Kind.TEST_ADD_ADD), Arrays.asList(r.conflicts.get(0).kind));
    }

    @Test
    @DisplayName("an expectation column added on one side is kept, and the other side's cases simply do not fill it")
    void addedExpectationColumn() {
        String theirs = "tests:\n  - decision: Discount\n    columns: [scenario, tier, \"=> discount\", \"=> label\"]\n    rows:\n"
                + "      - [gold, gold, \"10%\", Gold]\n      - [basic, basic, \"0%\", Basic]\n";
        String ours = spec("[gold, gold, \"10%\"]", "[basic, basic, \"0%\"]", "[vip, vip, \"20%\"]");
        MergeResult r = TestSpecMerger.merge(BASE, ours, theirs, null);
        assertTrue(r.clean, String.valueOf(r.conflicts));
        DecisionTests merged = only(r.mergedXml);
        assertEquals(Arrays.asList("discount", "label"), merged.outputColumns);
        assertEquals("Gold", merged.cases.get(0).expected.get(1));
    }

    @Test
    @DisplayName("stopping to check a column the other side's new cases fill is TEST_COLUMN_DELETE_IN_USE")
    void removedColumnInUse() {
        String base = "tests:\n  - decision: Discount\n    columns: [scenario, tier, \"=> discount\", \"=> label\"]\n    rows:\n"
                + "      - [gold, gold, \"10%\", Gold]\n";
        String ours = "tests:\n  - decision: Discount\n    columns: [scenario, tier, \"=> discount\"]\n    rows:\n"
                + "      - [gold, gold, \"10%\"]\n";
        String theirs = base + "      - [vip, vip, \"20%\", VIP]\n";
        MergeResult r = TestSpecMerger.merge(base, ours, theirs, null);
        assertEquals(MergeConflict.Kind.TEST_COLUMN_DELETE_IN_USE, r.conflicts.get(0).kind);
    }
}
