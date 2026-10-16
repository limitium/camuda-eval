package limitium.art.camunda.evaluator.diff;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The reworked conflict set, on domains of several connected tables: everything one side changed is
 * taken, columns included, and only real disagreements — including re-wired connections — are asked.
 */
class DomainMergeTest {

    /** A domain: Tier (amount -> tier), then Discount (tier -> discount). */
    static String domain(String tierOut, String tierLabel, String discountChecks, String extraDecision) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<definitions id=\"Defs\" name=\"Pricing\" namespace=\"n\" xmlns=\"https://www.omg.org/spec/DMN/20191111/MODEL/\">"
                + "<decision id=\"Tier\" name=\"Tier\"><decisionTable id=\"TierT\" hitPolicy=\"UNIQUE\">"
                + "<input id=\"TierIn\" label=\"Amount\"><inputExpression id=\"TierInE\" typeRef=\"number\"><text>amount</text></inputExpression></input>"
                + "<output id=\"TierOut\" label=\"" + tierLabel + "\" name=\"" + tierOut + "\" typeRef=\"string\"/>"
                + "<rule id=\"t1\"><inputEntry id=\"t1i\"><text>&lt; 100</text></inputEntry><outputEntry id=\"t1o\"><text>\"basic\"</text></outputEntry></rule>"
                + "<rule id=\"t2\"><inputEntry id=\"t2i\"><text>&gt;= 100</text></inputEntry><outputEntry id=\"t2o\"><text>\"gold\"</text></outputEntry></rule>"
                + "</decisionTable></decision>"
                + "<decision id=\"Discount\" name=\"Discount\">"
                + "<informationRequirement id=\"ir1\"><requiredDecision href=\"#Tier\"/></informationRequirement>"
                + "<decisionTable id=\"DiscountT\" hitPolicy=\"UNIQUE\">"
                + "<input id=\"DiscIn\" label=\"Tier\"><inputExpression id=\"DiscInE\" typeRef=\"string\"><text>" + discountChecks + "</text></inputExpression></input>"
                + "<output id=\"DiscOut\" name=\"discount\" typeRef=\"string\"/>"
                + "<rule id=\"d1\"><inputEntry id=\"d1i\"><text>\"gold\"</text></inputEntry><outputEntry id=\"d1o\"><text>\"10%\"</text></outputEntry></rule>"
                + "<rule id=\"d2\"><inputEntry id=\"d2i\"><text>-</text></inputEntry><outputEntry id=\"d2o\"><text>\"0%\"</text></outputEntry></rule>"
                + "</decisionTable></decision>"
                + extraDecision
                + "</definitions>";
    }

    static String base() {
        return domain("tier", "Tier", "tier", "");
    }

    /** A table giving {@code gives} from amount. */
    static String extra(String id, String gives) {
        return "<decision id=\"" + id + "\" name=\"" + id + "\"><decisionTable id=\"" + id + "T\" hitPolicy=\"UNIQUE\">"
                + "<input id=\"" + id + "In\"><inputExpression id=\"" + id + "InE\" typeRef=\"number\"><text>amount</text></inputExpression></input>"
                + "<output id=\"" + id + "Out\" name=\"" + gives + "\" typeRef=\"string\"/>"
                + "<rule id=\"" + id + "r\"><inputEntry id=\"" + id + "ri\"><text>-</text></inputEntry><outputEntry id=\"" + id + "ro\"><text>\"x\"</text></outputEntry></rule>"
                + "</decisionTable></decision>";
    }

    static MergeConflict only(MergeResult result, MergeConflict.Kind kind) {
        MergeConflict found = null;
        for (MergeConflict c : result.conflicts) {
            if (c.kind == kind) {
                assertNull(found, "one " + kind + " expected");
                found = c;
            }
        }
        assertNotNull(found, kind + " expected in " + result.conflicts);
        return found;
    }

    @Test
    @DisplayName("a result renamed on one side carries its consumers' check along only if that side renamed both")
    void renamedResultTravelsWithItsConsumer() {
        String theirs = domain("level", "Tier", "level", "");   // they renamed tier -> level, and the check with it
        MergeResult result = DmnMerger.merge(base(), base(), theirs);
        assertTrue(result.clean, String.valueOf(result.conflicts));
        Map<String, DecisionTableReader.Table> merged = DecisionTableReader.read(result.mergedXml);
        assertEquals("level", merged.get("Tier").outputs.get(0).expression);
        assertEquals("level", merged.get("Discount").inputs.get(0).expression);
        assertEquals(Collections.singletonList("Tier"), merged.get("Discount").requiredDecisions);
    }

    @Test
    @DisplayName("a rename on one side and a new consumer of the old name on the other is CONNECTION_LOST")
    void renameAgainstNewConsumerIsALostConnection() {
        String ours = domain("level", "Tier", "level", "");                                   // renamed tier -> level
        String theirs = domain("tier", "Tier", "tier", consumer("Fee", "tier"));              // added Fee checking tier
        MergeResult result = DmnMerger.merge(base(), ours, theirs);
        MergeConflict lost = only(result, MergeConflict.Kind.CONNECTION_LOST);
        assertEquals("Fee", lost.decisionId);
        assertEquals(2, lost.choices.size(), "leave it, or follow the rename");
        assertNotNull(result.previewXml, "there is always something to look at while deciding");
        assertNull(result.mergedXml);

        MergeResult followed = DmnMerger.merge(base(), ours, theirs,
                Collections.singletonMap(lost.id, Resolution.THEIRS));
        assertTrue(followed.clean, String.valueOf(followed.conflicts));
        Map<String, DecisionTableReader.Table> merged = DecisionTableReader.read(followed.mergedXml);
        assertEquals("level", merged.get("Fee").inputs.get(0).expression);
        assertEquals(Collections.singletonList("Tier"), merged.get("Fee").requiredDecisions, "and it is connected again");
    }

    @Test
    @DisplayName("two tables giving the same new name only after the merge is RESULT_CLASH")
    void clashIntroducedByTheMerge() {
        String ours = domain("tier", "Tier", "tier", extra("Segment", "band"));
        String theirs = domain("tier", "Tier", "tier", extra("Region", "band"));
        MergeResult result = DmnMerger.merge(base(), ours, theirs);
        MergeConflict clash = only(result, MergeConflict.Kind.RESULT_CLASH);
        MergeResult resolved = DmnMerger.merge(base(), ours, theirs, Collections.singletonMap(clash.id, Resolution.OURS));
        assertTrue(resolved.clean, String.valueOf(resolved.conflicts));
        Map<String, DecisionTableReader.Table> merged = DecisionTableReader.read(resolved.mergedXml);
        assertEquals("band", merged.get("Segment").outputs.get(0).expression);
        assertFalse("band".equals(merged.get("Region").outputs.get(0).expression), "the other was renamed");
    }

    @Test
    @DisplayName("the same table added on both sides, differently, is DECISION_ADD_ADD — never one silently lost")
    void sameTableAddedTwice() {
        String ours = domain("tier", "Tier", "tier", extra("Fee", "fee"));
        String theirs = domain("tier", "Tier", "tier", extra("Fee", "charge"));
        MergeResult result = DmnMerger.merge(base(), ours, theirs);
        MergeConflict added = only(result, MergeConflict.Kind.DECISION_ADD_ADD);
        MergeResult theirsWins = DmnMerger.merge(base(), ours, theirs, Collections.singletonMap(added.id, Resolution.THEIRS));
        assertEquals("charge", DecisionTableReader.read(theirsWins.mergedXml).get("Fee").outputs.get(0).expression);
    }

    @Test
    @DisplayName("both renaming the same result differently is COLUMN_RENAME")
    void bothRenamed() {
        MergeResult result = DmnMerger.merge(base(), domain("level", "Tier", "level", ""), domain("band", "Tier", "band", ""));
        assertEquals(MergeConflict.Kind.COLUMN_RENAME, result.conflicts.get(0).kind);
    }

    @Test
    @DisplayName("a table only the other side added is connected to what it checks")
    void addedTableIsConnected() {
        MergeResult result = DmnMerger.merge(base(), base(), domain("tier", "Tier", "tier", consumer("Fee", "tier")));
        assertTrue(result.clean, String.valueOf(result.conflicts));
        assertEquals(Collections.singletonList("Tier"), DecisionTableReader.read(result.mergedXml).get("Fee").requiredDecisions);
    }

    @Test
    @DisplayName("a heading changed on each side is a cosmetic TEXT conflict; changed on one side it is taken")
    void headings() {
        MergeResult one = DmnMerger.merge(base(), base(), domain("tier", "Level", "tier", ""));
        assertTrue(one.clean);
        assertEquals("Level", DecisionTableReader.read(one.mergedXml).get("Tier").outputs.get(0).label);
        MergeResult both = DmnMerger.merge(base(), domain("tier", "Band", "tier", ""), domain("tier", "Level", "tier", ""));
        MergeConflict text = only(both, MergeConflict.Kind.TEXT);
        assertEquals(MergeConflict.Tier.COSMETIC, text.tier);
    }

    static String consumer(String id, String checks) {
        return "<decision id=\"" + id + "\" name=\"" + id + "\"><decisionTable id=\"" + id + "T\" hitPolicy=\"UNIQUE\">"
                + "<input id=\"" + id + "In\"><inputExpression id=\"" + id + "InE\" typeRef=\"string\"><text>" + checks + "</text></inputExpression></input>"
                + "<output id=\"" + id + "Out\" name=\"" + id.toLowerCase() + "\" typeRef=\"number\"/>"
                + "<rule id=\"" + id + "r\"><inputEntry id=\"" + id + "ri\"><text>-</text></inputEntry><outputEntry id=\"" + id + "ro\"><text>1</text></outputEntry></rule>"
                + "</decisionTable></decision>";
    }
}
