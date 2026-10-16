package limitium.art.camunda.evaluator.diff;

import java.util.Collections;
import java.util.List;

/**
 * A pairing of a rule on each side. {@code matchedBy} records how the pairing was found
 * ("id", "inputs", "position") so a heuristic match is visibly a heuristic.
 */
public class RuleDiff {
    public final String leftRuleId;
    public final String rightRuleId;
    public final ChangeType change;
    public final int leftIndex;
    public final int rightIndex;
    public final String leftDescription;
    public final String rightDescription;
    public final List<CellDiff> inputs;
    public final List<CellDiff> outputs;
    public final String matchedBy;

    public RuleDiff(String leftRuleId, String rightRuleId, ChangeType change,
                    int leftIndex, int rightIndex,
                    String leftDescription, String rightDescription,
                    List<CellDiff> inputs, List<CellDiff> outputs, String matchedBy) {
        this.leftRuleId = leftRuleId;
        this.rightRuleId = rightRuleId;
        this.change = change;
        this.leftIndex = leftIndex;
        this.rightIndex = rightIndex;
        this.leftDescription = leftDescription;
        this.rightDescription = rightDescription;
        this.inputs = Collections.unmodifiableList(inputs);
        this.outputs = Collections.unmodifiableList(outputs);
        this.matchedBy = matchedBy;
    }
}
