package limitium.art.camunda.evaluator.diff;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DmnDifferTest {

    // --- the two headline cases ---------------------------------------------------------------

    @Test
    @DisplayName("editing one threshold is one changed rule, not a remove plus an add")
    void inputThresholdChangeIsOneChangedRule() {
        // Ids are regenerated too, so only the LCS pass can pair these.
        String left = table()
                .rule("r1", "< 10", "\"LOW\"")
                .rule("r2", ">= 10", "\"HIGH\"")
                .xml();
        String right = table()
                .rule("x9", "< 20", "\"LOW\"")
                .rule("x8", ">= 10", "\"HIGH\"")
                .xml();

        DecisionDiff decision = onlyDecision(DmnDiffer.diff(left, right));
        assertEquals(2, decision.rules.size(), "the edited rule must pair, not split into add+remove");

        RuleDiff changed = ruleAt(decision, 0);
        assertEquals(ChangeType.CHANGED, changed.change);
        assertEquals("position", changed.matchedBy, "paired by the LCS pass");
        assertEquals("< 10", changed.inputs.get(0).left);
        assertEquals("< 20", changed.inputs.get(0).right);
        assertEquals(ChangeType.UNCHANGED, changed.outputs.get(0).change);

        assertEquals(ChangeType.UNCHANGED, ruleAt(decision, 1).change);
        assertEquals(0, DmnDiffer.diff(left, right).summary.rulesAdded);
        assertEquals(0, DmnDiffer.diff(left, right).summary.rulesRemoved);
    }

    @Test
    @DisplayName("regenerated rule ids with identical content report no changes at all")
    void regeneratedIdsAreNotChanges() {
        String left = table()
                .rule("Rule_0aaa", "< 10", "\"LOW\"")
                .rule("Rule_0bbb", ">= 10", "\"HIGH\"")
                .xml();
        String right = table()
                .rule("Rule_1ccc", "< 10", "\"LOW\"")
                .rule("Rule_1ddd", ">= 10", "\"HIGH\"")
                .xml();

        DmnDiff diff = DmnDiffer.diff(left, right);
        assertTrue(diff.identical, "dmn-js regenerates ids on every save; that must not read as a change");

        DecisionDiff decision = onlyDecision(diff);
        assertEquals("inputs", ruleAt(decision, 0).matchedBy, "paired on the input tuple");
        assertEquals("inputs", ruleAt(decision, 1).matchedBy);
    }

    // --- basic change kinds --------------------------------------------------------------------

    @Test
    void identicalDocumentsAreIdentical() {
        String xml = table().rule("r1", "< 10", "\"LOW\"").xml();
        DmnDiff diff = DmnDiffer.diff(xml, xml);
        assertTrue(diff.identical);
        assertEquals(ChangeType.UNCHANGED, onlyDecision(diff).change);
    }

    @Test
    void ruleAdded() {
        String left = table().rule("r1", "< 10", "\"LOW\"").xml();
        String right = table().rule("r1", "< 10", "\"LOW\"").rule("r2", ">= 10", "\"HIGH\"").xml();

        DmnDiff diff = DmnDiffer.diff(left, right);
        assertFalse(diff.identical);
        assertEquals(1, diff.summary.rulesAdded);
        assertEquals(0, diff.summary.rulesRemoved);
        assertEquals(ChangeType.ADDED, ruleAt(onlyDecision(diff), 1).change);
    }

    @Test
    void ruleRemoved() {
        String left = table().rule("r1", "< 10", "\"LOW\"").rule("r2", ">= 10", "\"HIGH\"").xml();
        String right = table().rule("r1", "< 10", "\"LOW\"").xml();

        DmnDiff diff = DmnDiffer.diff(left, right);
        assertEquals(1, diff.summary.rulesRemoved);
        assertEquals(ChangeType.REMOVED, ruleAt(onlyDecision(diff), 1).change);
    }

    @Test
    void outputOnlyChangeKeepsInputsUnchanged() {
        String left = table().rule("r1", "< 10", "\"LOW\"").xml();
        String right = table().rule("r1", "< 10", "\"MEDIUM\"").xml();

        RuleDiff rule = ruleAt(onlyDecision(DmnDiffer.diff(left, right)), 0);
        assertEquals(ChangeType.CHANGED, rule.change);
        assertEquals("id", rule.matchedBy);
        assertEquals(ChangeType.UNCHANGED, rule.inputs.get(0).change);
        assertEquals(ChangeType.CHANGED, rule.outputs.get(0).change);
        assertEquals("\"LOW\"", rule.outputs.get(0).left);
        assertEquals("\"MEDIUM\"", rule.outputs.get(0).right);
    }

    @Test
    @DisplayName("reordering rules is MOVED, because order is semantic under FIRST")
    void reorderIsMoved() {
        String left = table().hitPolicy("FIRST")
                .rule("r1", "< 10", "\"LOW\"")
                .rule("r2", ">= 10", "\"HIGH\"")
                .xml();
        String right = table().hitPolicy("FIRST")
                .rule("r2", ">= 10", "\"HIGH\"")
                .rule("r1", "< 10", "\"LOW\"")
                .xml();

        DmnDiff diff = DmnDiffer.diff(left, right);
        assertFalse(diff.identical, "a reorder changes which rule fires first");
        assertEquals(2, diff.summary.rulesMoved);
    }

    @Test
    void hitPolicyChangeIsReported() {
        String left = table().hitPolicy("UNIQUE").rule("r1", "< 10", "\"LOW\"").xml();
        String right = table().hitPolicy("FIRST").rule("r1", "< 10", "\"LOW\"").xml();

        DmnDiff diff = DmnDiffer.diff(left, right);
        assertTrue(diff.summary.hitPolicyChanged);
        assertEquals("UNIQUE", onlyDecision(diff).leftHitPolicy);
        assertEquals("FIRST", onlyDecision(diff).rightHitPolicy);
    }

    @Test
    @DisplayName("aggregation is part of the hit policy, so COLLECT SUM differs from plain COLLECT")
    void collectAggregationIsPartOfHitPolicy() {
        String left = table().hitPolicy("COLLECT").rule("r1", "< 10", "\"LOW\"").xml();
        String right = table().hitPolicy("COLLECT").aggregation("SUM").rule("r1", "< 10", "\"LOW\"").xml();

        assertTrue(DmnDiffer.diff(left, right).summary.hitPolicyChanged);
    }

    // --- columns ---------------------------------------------------------------------------------

    @Test
    @DisplayName("an added input column aligns into the merged list and the old side reads as absent")
    void addedInputColumnMergesAndFills() {
        String left = table().rule("r1", "< 10", "\"LOW\"").xml();
        String right = table()
                .input("Input_2", "region", "string")
                .rule("r1", "< 10", "\"EU\"", "\"LOW\"")
                .xml();

        DmnDiff diff = DmnDiffer.diff(left, right);
        DecisionDiff decision = onlyDecision(diff);

        assertEquals(2, decision.inputs.size(), "merged column list holds the union of both sides");
        assertEquals(ChangeType.UNCHANGED, decision.inputs.get(0).change);
        assertEquals(ChangeType.ADDED, decision.inputs.get(1).change);
        assertEquals(-1, decision.inputs.get(1).leftIndex, "absent on the left");
        assertEquals(1, diff.summary.columnsAdded);

        RuleDiff rule = ruleAt(decision, 0);
        assertEquals(2, rule.inputs.size(), "cells are aligned to the merged columns");
        assertEquals("< 10", rule.inputs.get(0).left);
        assertNull(rule.inputs.get(1).left, "no value on a side that lacks the column");
        assertEquals("\"EU\"", rule.inputs.get(1).right);
    }

    @Test
    @DisplayName("outputs pair on their variable name, which survives an id change")
    void outputColumnMatchesByName() {
        String left = table().rule("r1", "< 10", "\"LOW\"").xml();
        String right = table().output("Output_regenerated", "result", "string")
                .rule("r1", "< 10", "\"LOW\"").xml();

        DmnDiff diff = DmnDiffer.diff(left, right);
        assertEquals(1, onlyDecision(diff).outputs.size(), "must not read as one added plus one removed column");
        assertTrue(diff.identical);
    }

    // --- decisions -------------------------------------------------------------------------------

    @Test
    void decisionAddedAndRemoved() {
        String left = table().rule("r1", "< 10", "\"LOW\"").xmlWithDecision("Decision_A", "Pricing");
        String right = table().rule("r1", "< 10", "\"LOW\"").xmlWithDecision("Decision_B", "Fees");

        DmnDiff diff = DmnDiffer.diff(left, right);
        assertEquals(1, diff.summary.decisionsAdded);
        assertEquals(1, diff.summary.decisionsRemoved);
        assertEquals(2, diff.decisions.size());
    }

    @Test
    @DisplayName("a decision whose id changed but whose name did not is still the same decision")
    void decisionMatchesByNameWhenIdChanges() {
        String left = table().rule("r1", "< 10", "\"LOW\"").xmlWithDecision("Decision_old", "Pricing");
        String right = table().rule("r1", "< 10", "\"LOW\"").xmlWithDecision("Decision_new", "Pricing");

        DmnDiff diff = DmnDiffer.diff(left, right);
        assertEquals(1, diff.decisions.size(), "must not read as one added plus one removed decision");
        assertEquals(0, diff.summary.decisionsAdded);
        assertEquals(0, diff.summary.decisionsRemoved);
    }

    // --- tolerance: the cases DmnEvaluator.loadAllRules throws on ---------------------------------

    @Test
    @DisplayName("a decision table with no rules diffs cleanly instead of throwing")
    void zeroRuleTableDoesNotThrow() {
        String empty = table().xml();
        String withRule = table().rule("r1", "< 10", "\"LOW\"").xml();

        DmnDiff diff = DmnDiffer.diff(empty, withRule);
        assertEquals(1, diff.summary.rulesAdded);
        assertTrue(DmnDiffer.diff(empty, empty).identical);
    }

    @Test
    @DisplayName("a literal-expression decision is reported as non-tabular, not an error")
    void literalExpressionDecisionIsNonTabular() {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<definitions id=\"Defs\" name=\"Defs\" namespace=\"http://camunda.org/schema/1.0/dmn\"\n"
                + "  xmlns=\"https://www.omg.org/spec/DMN/20191111/MODEL/\">\n"
                + "  <decision id=\"Decision_1\" name=\"Literal\">\n"
                + "    <literalExpression id=\"LiteralExpression_1\">\n"
                + "      <text>amount * 2</text>\n"
                + "    </literalExpression>\n"
                + "  </decision>\n"
                + "</definitions>\n";

        DmnDiff diff = DmnDiffer.diff(xml, xml);
        assertTrue(onlyDecision(diff).nonTabular);
        assertTrue(diff.identical);
    }

    // --- normalization ---------------------------------------------------------------------------

    @Test
    @DisplayName("wildcard spellings and whitespace noise are not changes")
    void cosmeticDifferencesAreNotChanges() {
        String left = table().rule("r1", "-", "\"LOW\"").xml();
        String right = table().rule("r1", "", "\"LOW\" ").xml();
        assertTrue(DmnDiffer.diff(left, right).identical, "empty and \"-\" both mean 'matches anything'");

        String spaced = table().rule("r1", ">=   10", "\"LOW\"").xml();
        String tight = table().rule("r1", ">= 10", "\"LOW\"").xml();
        assertTrue(DmnDiffer.diff(spaced, tight).identical, "whitespace runs collapse");
    }

    // --- helpers ---------------------------------------------------------------------------------

    private static void assertNull(Object value, String message) {
        assertTrue(value == null, message);
    }

    private static DecisionDiff onlyDecision(DmnDiff diff) {
        assertEquals(1, diff.decisions.size(), "expected exactly one decision");
        return diff.decisions.get(0);
    }

    /** Rules are returned in merged display order, so index here is display position. */
    private static RuleDiff ruleAt(DecisionDiff decision, int index) {
        assertTrue(index < decision.rules.size(),
                "expected at least " + (index + 1) + " rules, got " + decision.rules.size());
        RuleDiff rule = decision.rules.get(index);
        assertNotNull(rule);
        return rule;
    }

    private static DmnFixtures.Builder table() {
        return DmnFixtures.table();
    }
}
