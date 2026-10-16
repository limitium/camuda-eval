package limitium.art.camunda.evaluator.diff;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DmnMergerTest {

    // --- the case whole-file merging got wrong -------------------------------------------------

    @Test
    @DisplayName("two people editing different rules of one table both keep their work")
    void disjointRuleEditsBothSurvive() {
        String base = table()
                .rule("r1", "< 10", "\"LOW\"")
                .rule("r2", ">= 10", "\"HIGH\"")
                .xml();
        String ours = table()
                .rule("r1", "< 10", "\"TINY\"")     // we edited rule 1
                .rule("r2", ">= 10", "\"HIGH\"")
                .xml();
        String theirs = table()
                .rule("r1", "< 10", "\"LOW\"")
                .rule("r2", ">= 10", "\"HUGE\"")    // they edited rule 2
                .xml();

        MergeResult result = DmnMerger.merge(base, ours, theirs);
        assertTrue(result.clean, "compatible edits must not conflict");
        assertNotNull(result.mergedXml);

        // Both edits are present in the merged document.
        DmnDiff againstOurs = DmnDiffer.diff(ours, result.mergedXml);
        assertEquals(1, againstOurs.summary.rulesChanged, "their edit was applied on top of ours");

        DecisionTableReader.Table merged = onlyTable(result.mergedXml);
        assertEquals("\"TINY\"", merged.rules.get(0).outputEntries.get(0), "our edit survived");
        assertEquals("\"HUGE\"", merged.rules.get(1).outputEntries.get(0), "their edit survived");
    }

    @Test
    @DisplayName("a rule added on each side yields a table containing both")
    void rulesAddedOnBothSidesAreKept() {
        String base = table().rule("r1", "< 10", "\"LOW\"").xml();
        String ours = table().rule("r1", "< 10", "\"LOW\"").rule("r2", "< 20", "\"MID\"").xml();
        String theirs = table().rule("r1", "< 10", "\"LOW\"").rule("r3", "< 30", "\"BIG\"").xml();

        MergeResult result = DmnMerger.merge(base, ours, theirs);
        assertTrue(result.clean);
        assertEquals(1, result.rulesAdded);
        assertEquals(3, onlyTable(result.mergedXml).rules.size());
    }

    // --- conflicts are one cell wide ------------------------------------------------------------

    @Test
    @DisplayName("only the cell both sides changed conflicts; their other edits still apply")
    void sameCellConflictsButRestStillMerges() {
        String base = table()
                .rule("r1", "< 10", "\"LOW\"")
                .rule("r2", ">= 10", "\"HIGH\"")
                .xml();
        String ours = table()
                .rule("r1", "< 10", "\"TINY\"")
                .rule("r2", ">= 10", "\"HIGH\"")
                .xml();
        String theirs = table()
                .rule("r1", "< 10", "\"SMALL\"")   // contested
                .rule("r2", ">= 10", "\"HUGE\"")   // compatible
                .xml();

        MergeResult result = DmnMerger.merge(base, ours, theirs);
        assertFalse(result.clean);
        assertEquals(1, result.conflicts.size(), "exactly one cell is contested");

        MergeConflict conflict = result.conflicts.get(0);
        assertEquals(MergeConflict.Kind.CELL, conflict.kind);
        assertEquals("r1", conflict.ruleId);
        assertEquals("Output_1", conflict.columnId);
        assertEquals("\"LOW\"", conflict.base);
        assertEquals("\"TINY\"", conflict.ours);
        assertEquals("\"SMALL\"", conflict.theirs);
    }

    @Test
    @DisplayName("resolving the contested cell completes the merge and keeps the uncontested edit")
    void resolutionCompletesTheMerge() {
        String base = table()
                .rule("r1", "< 10", "\"LOW\"")
                .rule("r2", ">= 10", "\"HIGH\"")
                .xml();
        String ours = table()
                .rule("r1", "< 10", "\"TINY\"")
                .rule("r2", ">= 10", "\"HIGH\"")
                .xml();
        String theirs = table()
                .rule("r1", "< 10", "\"SMALL\"")
                .rule("r2", ">= 10", "\"HUGE\"")
                .xml();

        String conflictId = DmnMerger.merge(base, ours, theirs).conflicts.get(0).id;

        Map<String, Resolution> resolutions = new HashMap<String, Resolution>();
        resolutions.put(conflictId, Resolution.THEIRS);
        MergeResult result = DmnMerger.merge(base, ours, theirs, resolutions);

        assertTrue(result.clean);
        DecisionTableReader.Table merged = onlyTable(result.mergedXml);
        assertEquals("\"SMALL\"", merged.rules.get(0).outputEntries.get(0), "we chose theirs");
        assertEquals("\"HUGE\"", merged.rules.get(1).outputEntries.get(0),
                "the uncontested edit is not lost by resolving the contested one");
    }

    @Test
    void identicalEditsOnBothSidesDoNotConflict() {
        String base = table().rule("r1", "< 10", "\"LOW\"").xml();
        String same = table().rule("r1", "< 10", "\"TINY\"").xml();

        MergeResult result = DmnMerger.merge(base, same, same);
        assertTrue(result.clean, "the same change made twice is not a conflict");
        assertEquals("\"TINY\"", onlyTable(result.mergedXml).rules.get(0).outputEntries.get(0));
    }

    // --- structural conflicts ---------------------------------------------------------------------

    @Test
    void deleteVersusModifyConflicts() {
        String base = table().rule("r1", "< 10", "\"LOW\"").rule("r2", ">= 10", "\"HIGH\"").xml();
        String ours = table().rule("r1", "< 10", "\"LOW\"").rule("r2", ">= 10", "\"CHANGED\"").xml();
        String theirs = table().rule("r1", "< 10", "\"LOW\"").xml();

        MergeResult result = DmnMerger.merge(base, ours, theirs);
        assertFalse(result.clean);
        assertEquals(MergeConflict.Kind.RULE_DELETE_MODIFY, result.conflicts.get(0).kind);
    }

    @Test
    @DisplayName("a rule deleted on one side and untouched on the other is simply deleted")
    void cleanDeletionIsApplied() {
        String base = table().rule("r1", "< 10", "\"LOW\"").rule("r2", ">= 10", "\"HIGH\"").xml();
        String ours = table().rule("r1", "< 10", "\"LOW\"").rule("r2", ">= 10", "\"HIGH\"").xml();
        String theirs = table().rule("r1", "< 10", "\"LOW\"").xml();

        MergeResult result = DmnMerger.merge(base, ours, theirs);
        assertTrue(result.clean);
        assertEquals(1, result.rulesRemoved);
        assertEquals(1, onlyTable(result.mergedXml).rules.size());
    }

    @Test
    void hitPolicyChangedDifferentlyConflicts() {
        String base = table().hitPolicy("UNIQUE").rule("r1", "< 10", "\"LOW\"").xml();
        String ours = table().hitPolicy("FIRST").rule("r1", "< 10", "\"LOW\"").xml();
        String theirs = table().hitPolicy("COLLECT").rule("r1", "< 10", "\"LOW\"").xml();

        MergeResult result = DmnMerger.merge(base, ours, theirs);
        assertFalse(result.clean);
        assertEquals(MergeConflict.Kind.HIT_POLICY, result.conflicts.get(0).kind);
    }

    @Test
    void hitPolicyChangedOnOneSideIsApplied() {
        String base = table().hitPolicy("UNIQUE").rule("r1", "< 10", "\"LOW\"").xml();
        String ours = table().hitPolicy("UNIQUE").rule("r1", "< 10", "\"LOW\"").xml();
        String theirs = table().hitPolicy("FIRST").rule("r1", "< 10", "\"LOW\"").xml();

        MergeResult result = DmnMerger.merge(base, ours, theirs);
        assertTrue(result.clean);
        assertEquals("FIRST", onlyTable(result.mergedXml).hitPolicy);
    }

    @Test
    @DisplayName("different columns added on each side are both kept, with each side's values")
    void bothSidesAddingDifferentColumnsKeepsBoth() {
        String base = table().rule("r1", "< 10", "\"LOW\"").xml();
        String ours = table().input("Input_2", "region", "string")
                .rule("r1", "< 10", "\"EU\"", "\"LOW\"").xml();
        String theirs = table().input("Input_3", "channel", "string")
                .rule("r1", "< 10", "\"WEB\"", "\"LOW\"").xml();

        MergeResult result = DmnMerger.merge(base, ours, theirs);
        assertTrue(result.clean, String.valueOf(result.conflicts));
        DecisionTableReader.Table merged = DecisionTableReader.read(result.mergedXml).values().iterator().next();
        assertEquals(3, merged.inputs.size());
        assertEquals(java.util.Arrays.asList("< 10", "\"EU\"", "\"WEB\""), merged.rules.get(0).inputEntries);
    }

    @Test
    @DisplayName("a column removed on one side and retyped on the other is COLUMN_DELETE_MODIFY")
    void columnDeletedAndModifiedConflicts() {
        String base = table().input("Input_2", "region", "string")
                .rule("r1", "< 10", "\"EU\"", "\"LOW\"").xml();
        String ours = table().rule("r1", "< 10", "\"LOW\"").xml();          // we dropped region
        String theirs = table().input("Input_2", "region", "number")          // they retyped it
                .rule("r1", "< 10", "\"EU\"", "\"LOW\"").xml();

        MergeResult result = DmnMerger.merge(base, ours, theirs);
        assertFalse(result.clean);
        assertEquals(MergeConflict.Kind.COLUMN_DELETE_MODIFY,
                only(result, MergeConflict.Kind.COLUMN_DELETE_MODIFY).kind);
    }

    @Test
    @DisplayName("removing a column that new rules rely on is caught, though the rows look intact")
    void columnDeletedWhileInUseConflicts() {
        // The merged rows would still exist and still look complete, having silently lost a
        // condition. This is the only conflict where doing nothing produces a plausible wrong table.
        String base = table().input("Input_2", "region", "string")
                .rule("r1", "< 10", "\"EU\"", "\"LOW\"").xml();
        String ours = table().rule("r1", "< 10", "\"LOW\"").xml();          // we dropped region
        String theirs = table().input("Input_2", "region", "string")
                .rule("r1", "< 10", "\"EU\"", "\"LOW\"")
                .rule("r2", "< 10", "\"US\"", "\"MID\"").xml();           // they added a rule using it

        MergeResult result = DmnMerger.merge(base, ours, theirs);
        assertFalse(result.clean, "dropping the column would silently weaken their new rule");
        MergeConflict conflict = only(result, MergeConflict.Kind.COLUMN_DELETE_IN_USE);
        assertTrue(conflict.detail.contains("silently"), "the message must say why it matters");
    }

    @Test
    void columnTypeChangedDifferentlyConflicts() {
        String base = table().rule("r1", "< 10", "\"LOW\"").xml();
        String ours = table().inputType("string").rule("r1", "< 10", "\"LOW\"").xml();
        String theirs = table().inputType("integer").rule("r1", "< 10", "\"LOW\"").xml();

        MergeResult result = DmnMerger.merge(base, ours, theirs);
        assertFalse(result.clean);
        MergeConflict conflict = only(result, MergeConflict.Kind.COLUMN_TYPE);
        assertEquals("string", conflict.ours);
        assertEquals("integer", conflict.theirs);
    }

    @Test
    @DisplayName("the same new rule added on both sides with different outputs is RULE_ADD_ADD")
    void ruleAddedOnBothSidesWithDifferentOutputs() {
        String base = table().rule("r1", "< 10", "\"LOW\"").xml();
        String ours = table().rule("r1", "< 10", "\"LOW\"").rule("r2", ">= 10", "\"HIGH\"").xml();
        String theirs = table().rule("r1", "< 10", "\"LOW\"").rule("r9", ">= 10", "\"HUGE\"").xml();

        MergeResult result = DmnMerger.merge(base, ours, theirs);
        assertFalse(result.clean);
        assertEquals(MergeConflict.Kind.RULE_ADD_ADD, only(result, MergeConflict.Kind.RULE_ADD_ADD).kind);
    }

    @Test
    @DisplayName("the same new rule added identically on both sides is not a conflict")
    void identicalRuleAddedOnBothSidesIsClean() {
        String base = table().rule("r1", "< 10", "\"LOW\"").xml();
        String added = table().rule("r1", "< 10", "\"LOW\"").rule("r2", ">= 10", "\"HIGH\"").xml();

        MergeResult result = DmnMerger.merge(base, added, added);
        assertTrue(result.clean);
        assertEquals(2, onlyTable(result.mergedXml).rules.size(), "not duplicated");
    }

    @Test
    @DisplayName("both sides reordering rules conflicts, whatever the hit policy")
    void bothSidesReorderingConflicts() {
        String base = table()
                .rule("r1", "< 10", "\"LOW\"")
                .rule("r2", "[10..20)", "\"MID\"")
                .rule("r3", ">= 20", "\"HIGH\"").xml();
        String ours = table()
                .rule("r2", "[10..20)", "\"MID\"")
                .rule("r1", "< 10", "\"LOW\"")
                .rule("r3", ">= 20", "\"HIGH\"").xml();
        String theirs = table()
                .rule("r1", "< 10", "\"LOW\"")
                .rule("r3", ">= 20", "\"HIGH\"")
                .rule("r2", "[10..20)", "\"MID\"").xml();

        MergeResult result = DmnMerger.merge(base, ours, theirs);
        assertFalse(result.clean, "order is always significant, even under UNIQUE");
        assertEquals(MergeConflict.Kind.RULE_ORDER, only(result, MergeConflict.Kind.RULE_ORDER).kind);
    }

    @Test
    @DisplayName("a decision deleted on one side and edited on the other is DECISION_DELETE_MODIFY")
    void decisionDeletedAndModifiedConflicts() {
        String base = doc(pricing("\"LOW\""), FEES);
        String ours = doc(pricing("\"CHANGED\""), FEES);   // we edited Pricing
        String theirs = doc(FEES);                          // they deleted Pricing

        MergeResult result = DmnMerger.merge(base, ours, theirs);
        assertFalse(result.clean, "deleting a decision someone edited must not be silent");
        assertEquals(MergeConflict.Kind.DECISION_DELETE_MODIFY,
                only(result, MergeConflict.Kind.DECISION_DELETE_MODIFY).kind);
    }

    @Test
    @DisplayName("a decision only the other side added is carried over, not raised as a conflict")
    void decisionAddedOnTheirSideIsCarried() {
        String base = doc(pricing("\"LOW\""));
        String theirs = doc(pricing("\"LOW\""), FEES);

        MergeResult result = DmnMerger.merge(base, base, theirs);
        assertTrue(result.clean, "adding a decision is a clean change: " + kinds(result));

        Map<String, DecisionTableReader.Table> tables = DecisionTableReader.read(result.mergedXml);
        assertEquals(2, tables.size());
        assertTrue(tables.containsKey("Fees"), "their new decision was carried across");
        assertEquals(1, tables.get("Fees").rules.size());
        assertEquals("amount", tables.get("Fees").inputs.get(0).expression);
        assertEquals("fee", tables.get("Fees").outputs.get(0).expression);
        assertEquals("\"1%\"", tables.get("Fees").rules.get(0).outputEntries.get(0));
    }

    @Test
    @DisplayName("a decision only the other side deleted is removed, not left behind")
    void decisionDeletedOnTheirSideIsApplied() {
        String base = doc(pricing("\"LOW\""), FEES);
        String theirs = doc(pricing("\"LOW\""));

        MergeResult result = DmnMerger.merge(base, base, theirs);
        assertTrue(result.clean, kinds(result));
        Map<String, DecisionTableReader.Table> tables = DecisionTableReader.read(result.mergedXml);
        assertEquals(1, tables.size(), "their deletion applied");
        assertTrue(tables.containsKey("Pricing"));
    }

    @Test
    @DisplayName("a renamed decision is a cosmetic conflict, separable from semantic ones")
    void decisionRenameIsCosmetic() {
        String base = table().rule("r1", "< 10", "\"LOW\"").xmlWithDecision("Pricing", "Pricing");
        String ours = table().rule("r1", "< 10", "\"LOW\"").xmlWithDecision("Pricing", "Price rules");
        String theirs = table().rule("r1", "< 10", "\"LOW\"").xmlWithDecision("Pricing", "Pricing v2");

        MergeResult result = DmnMerger.merge(base, ours, theirs);
        assertFalse(result.clean);
        assertEquals(1, result.conflicts.size());
        assertEquals(MergeConflict.Tier.COSMETIC, result.conflicts.get(0).tier,
                "a rename never changes evaluation, so it must be bulk-resolvable");
    }

    @Test
    @DisplayName("every conflict carries the answers it accepts, so the UI never switches on kind")
    void conflictsCarryTheirChoices() {
        String base = table().rule("r1", "< 10", "\"LOW\"").xml();
        String ours = table().rule("r1", "< 10", "\"OURS\"").xml();
        String theirs = table().rule("r1", "< 10", "\"THEIRS\"").xml();

        MergeConflict conflict = DmnMerger.merge(base, ours, theirs).conflicts.get(0);
        assertEquals(2, conflict.choices.size());
        assertEquals(Resolution.OURS, conflict.choices.get(0).key);
        assertEquals("\"OURS\"", conflict.choices.get(0).value);
        assertEquals(Resolution.THEIRS, conflict.choices.get(1).key);
        assertEquals("\"THEIRS\"", conflict.choices.get(1).value);
        assertTrue(conflict.label.contains("< 10"), "the label names the affected row: " + conflict.label);
    }

    // --- the unattended-merge safety rule ----------------------------------------------------------

    @Test
    @DisplayName("a rule identifiable only by similarity is refused, because merge-up runs unattended")
    void lowConfidenceMatchIsRefused() {
        // Their side regenerated the id AND changed the input, so neither the id pass nor the
        // input-tuple pass can pair it. Only similarity could, and that is a guess.
        String base = table().rule("r1", "< 10", "\"LOW\"").xml();
        String ours = table().rule("r1", "< 10", "\"LOW\"").xml();
        String theirs = table().rule("regenerated", "< 20", "\"LOW\"").xml();

        MergeResult result = DmnMerger.merge(base, ours, theirs);
        assertFalse(result.clean, "a diff may guess here; an unattended merge must not");
        assertEquals(MergeConflict.Kind.LOW_CONFIDENCE_MATCH,
                only(result, MergeConflict.Kind.LOW_CONFIDENCE_MATCH).kind);
    }

    @Test
    @DisplayName("regenerated ids alone still merge, because the input tuple identifies the rule")
    void regeneratedIdsStillMergeOnInputTuple() {
        String base = table().rule("r1", "< 10", "\"LOW\"").xml();
        String ours = table().rule("r1", "< 10", "\"LOW\"").xml();
        String theirs = table().rule("regenerated", "< 10", "\"CHANGED\"").xml();

        MergeResult result = DmnMerger.merge(base, ours, theirs);
        assertTrue(result.clean, "same inputs means same rule; only the payload moved");
        assertEquals("\"CHANGED\"", onlyTable(result.mergedXml).rules.get(0).outputEntries.get(0));
    }

    // --- output sanity -----------------------------------------------------------------------------

    @Test
    @DisplayName("the merged document is valid DMN that round-trips through the reader")
    void mergedOutputIsReadableDmn() {
        String base = table().rule("r1", "< 10", "\"LOW\"").xml();
        String ours = table().rule("r1", "< 10", "\"TINY\"").xml();
        String theirs = table().rule("r1", "< 10", "\"LOW\"").rule("r2", ">= 10", "\"HIGH\"").xml();

        MergeResult result = DmnMerger.merge(base, ours, theirs);
        assertTrue(result.clean, kinds(result));

        DecisionTableReader.Table merged = onlyTable(result.mergedXml);
        assertTrue(merged.tabular);
        assertEquals(2, merged.rules.size());
        assertEquals(1, merged.inputs.size());
        assertEquals(1, merged.outputs.size());
        assertEquals("amount", merged.inputs.get(0).expression, "column structure is intact");
    }

    @Test
    void noChangesOnEitherSideIsACleanNoOp() {
        String xml = table().rule("r1", "< 10", "\"LOW\"").xml();
        MergeResult result = DmnMerger.merge(xml, xml, xml);
        assertTrue(result.clean);
        assertEquals(0, result.cellsTakenFromTheirs);
        assertTrue(DmnDiffer.diff(xml, result.mergedXml).identical);
    }

    // --- every conflict is reported, and each carries its provenance --------------------------------

    @Test
    @DisplayName("a structural conflict does not hide the cell conflicts behind it")
    void allConflictsAreReportedTogether() {
        // We drop the Region column and change an output; they retype Region and change the same
        // output differently. Both disagreements are real and both must be visible at once.
        String base = table().input("Input_2", "region", "string")
                .rule("r1", "< 10", "\"EU\"", "\"LOW\"").xml();
        String ours = table()
                .rule("r1", "< 10", "\"OURS\"").xml();
        String theirs = table().input("Input_2", "region", "number")
                .rule("r1", "< 10", "\"EU\"", "\"THEIRS\"").xml();

        MergeResult result = DmnMerger.merge(base, ours, theirs);
        assertFalse(result.clean);
        assertNotNull(only(result, MergeConflict.Kind.COLUMN_DELETE_MODIFY));
        assertNotNull(only(result, MergeConflict.Kind.CELL),
                "stopping at the column conflict would hide the contested output: " + kinds(result));
    }

    @Test
    @DisplayName("a column removed on one side does not shift cells and invent conflicts")
    void removingAColumnDoesNotCascadeIntoCellConflicts() {
        // Region is dropped by us and untouched by them; Discount is edited only by them. Aligning
        // cells by position rather than identity would compare Discount against Region here.
        String base = table().input("Input_2", "region", "string")
                .rule("r1", "< 10", "\"EU\"", "\"LOW\"").xml();
        String ours = table().rule("r1", "< 10", "\"LOW\"").xml();
        String theirs = table().input("Input_2", "region", "string")
                .rule("r1", "< 10", "\"EU\"", "\"HIGH\"").xml();

        MergeResult result = DmnMerger.merge(base, ours, theirs);
        for (MergeConflict conflict : result.conflicts) {
            assertTrue(conflict.kind != MergeConflict.Kind.CELL,
                    "no cell is genuinely contested here, but got " + conflict.label);
        }
    }

    @Test
    @DisplayName("each conflict names the commits that produced the other side's value")
    void conflictsCarryTheirOriginatingCommits() {
        String base = table()
                .rule("r1", "< 10", "\"LOW\"")
                .rule("r2", ">= 10", "\"HIGH\"").xml();
        String ours = table()
                .rule("r1", "< 10", "\"OURS_A\"")
                .rule("r2", ">= 10", "\"OURS_B\"").xml();
        String theirs = table()
                .rule("r1", "< 10", "\"THEIRS_A\"")
                .rule("r2", ">= 10", "\"THEIRS_B\"").xml();

        // One local change collides with several incoming commits, and they differ per conflict:
        // the first cell was churned twice on their branch, the second changed once.
        ChangeAttribution attribution = new ChangeAttribution() {
            public List<Attribution> forConflict(String conflictId, Resolution side) {
                if (side != Resolution.THEIRS) {
                    return Collections.emptyList();
                }
                if (conflictId.endsWith("|r1|Output_1")) {
                    return Arrays.asList(
                            new Attribution("cccc333", "cccc333",
                                    "revert to the agreed small-order rate", "bob", "2026-09-27T14:00:00Z"),
                            new Attribution("aaaa111", "aaaa111",
                                    "lower the small-order discount", "bob", "2026-09-27T09:00:00Z"));
                }
                if (conflictId.endsWith("|r2|Output_1")) {
                    return Collections.singletonList(new Attribution("bbbb222", "bbbb222",
                            "raise the bulk discount for Q4", "carol", "2026-09-28T11:30:00Z"));
                }
                return Collections.emptyList();
            }
        };

        MergeResult result = DmnMerger.merge(base, ours, theirs,
                Collections.<String, Resolution>emptyMap(), attribution);
        assertEquals(2, result.conflicts.size());

        Map<String, List<Attribution>> byRule = new HashMap<String, List<Attribution>>();
        for (MergeConflict conflict : result.conflicts) {
            byRule.put(conflict.ruleId, conflict.theirsChanges);
        }

        assertEquals(2, byRule.get("r1").size(), "one conflict can have several source commits");
        assertEquals("revert to the agreed small-order rate", byRule.get("r1").get(0).message,
                "most recent first");
        assertEquals("lower the small-order discount", byRule.get("r1").get(1).message);

        assertEquals(1, byRule.get("r2").size());
        assertEquals("raise the bulk discount for Q4", byRule.get("r2").get(0).message);
        assertEquals("carol", byRule.get("r2").get(0).author,
                "sources differ per conflict within the one merge");
    }

    @Test
    void attributionIsOptional() {
        String base = table().rule("r1", "< 10", "\"LOW\"").xml();
        String ours = table().rule("r1", "< 10", "\"OURS\"").xml();
        String theirs = table().rule("r1", "< 10", "\"THEIRS\"").xml();

        MergeConflict conflict = DmnMerger.merge(base, ours, theirs).conflicts.get(0);
        assertTrue(conflict.theirsChanges.isEmpty(), "no history supplied, so none is claimed");
    }

    private static final String FEES = "  <decision id=\"Fees\" name=\"Fees\">\n"
            + "    <decisionTable id=\"FeesTable\" hitPolicy=\"UNIQUE\">\n"
            + "      <input id=\"Fees_in\">\n"
            + "        <inputExpression id=\"Fees_ie\" typeRef=\"number\"><text>amount</text></inputExpression>\n"
            + "      </input>\n"
            + "      <output id=\"Fees_out\" name=\"fee\" typeRef=\"string\"/>\n"
            + "      <rule id=\"Fees_r1\">\n"
            + "        <inputEntry id=\"Fees_ie1\"><text>&gt; 0</text></inputEntry>\n"
            + "        <outputEntry id=\"Fees_oe1\"><text>\"1%\"</text></outputEntry>\n"
            + "      </rule>\n"
            + "    </decisionTable>\n"
            + "  </decision>\n";

    private static String pricing(String discount) {
        return "  <decision id=\"Pricing\" name=\"Pricing\">\n"
                + "    <decisionTable id=\"PricingTable\" hitPolicy=\"UNIQUE\">\n"
                + "      <input id=\"P_in\">\n"
                + "        <inputExpression id=\"P_ie\" typeRef=\"number\"><text>amount</text></inputExpression>\n"
                + "      </input>\n"
                + "      <output id=\"P_out\" name=\"discount\" typeRef=\"string\"/>\n"
                + "      <rule id=\"P_r1\">\n"
                + "        <inputEntry id=\"P_ie1\"><text>&lt; 10</text></inputEntry>\n"
                + "        <outputEntry id=\"P_oe1\"><text>" + discount + "</text></outputEntry>\n"
                + "      </rule>\n"
                + "    </decisionTable>\n"
                + "  </decision>\n";
    }

    /** Wraps decision blocks in a definitions element. */
    private static String doc(String... decisions) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<definitions id=\"Defs\" name=\"Defs\" namespace=\"http://camunda.org/schema/1.0/dmn\"\n");
        sb.append("  xmlns=\"https://www.omg.org/spec/DMN/20191111/MODEL/\">\n");
        for (String decision : decisions) {
            sb.append(decision);
        }
        sb.append("</definitions>\n");
        return sb.toString();
    }

    /** The single conflict of a given kind, asserting there is exactly one. */
    private static MergeConflict only(MergeResult result, MergeConflict.Kind kind) {
        MergeConflict found = null;
        for (MergeConflict conflict : result.conflicts) {
            if (conflict.kind == kind) {
                assertTrue(found == null, "expected exactly one " + kind + " conflict");
                found = conflict;
            }
        }
        assertNotNull(found, "expected a " + kind + " conflict, got " + kinds(result));
        return found;
    }

    private static String kinds(MergeResult result) {
        StringBuilder sb = new StringBuilder();
        for (MergeConflict conflict : result.conflicts) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(conflict.kind);
        }
        return sb.length() == 0 ? "none" : sb.toString();
    }

    private static DecisionTableReader.Table onlyTable(String xml) {
        assertNotNull(xml, "merge produced no document");
        Map<String, DecisionTableReader.Table> tables = DecisionTableReader.read(xml);
        assertEquals(1, tables.size());
        return tables.values().iterator().next();
    }

    private static DmnFixtures.Builder table() {
        return DmnFixtures.table();
    }
}
