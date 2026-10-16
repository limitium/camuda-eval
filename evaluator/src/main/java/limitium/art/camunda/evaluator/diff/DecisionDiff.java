package limitium.art.camunda.evaluator.diff;

import java.util.Collections;
import java.util.List;

public class DecisionDiff {
    public final String id;
    public final String leftName;
    public final String rightName;
    public final ChangeType change;
    /** True when either side is not a decision table (a literal expression, say). Rules are then empty rather than an error. */
    public final boolean nonTabular;
    public final String leftHitPolicy;
    public final String rightHitPolicy;
    public final List<ColumnDiff> inputs;
    public final List<ColumnDiff> outputs;
    public final List<RuleDiff> rules;

    public DecisionDiff(String id, String leftName, String rightName, ChangeType change, boolean nonTabular,
                        String leftHitPolicy, String rightHitPolicy,
                        List<ColumnDiff> inputs, List<ColumnDiff> outputs, List<RuleDiff> rules) {
        this.id = id;
        this.leftName = leftName;
        this.rightName = rightName;
        this.change = change;
        this.nonTabular = nonTabular;
        this.leftHitPolicy = leftHitPolicy;
        this.rightHitPolicy = rightHitPolicy;
        this.inputs = Collections.unmodifiableList(inputs);
        this.outputs = Collections.unmodifiableList(outputs);
        this.rules = Collections.unmodifiableList(rules);
    }
}
