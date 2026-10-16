package limitium.art.camunda.evaluator.diff;

public class DiffSummary {
    public final int decisionsAdded;
    public final int decisionsRemoved;
    public final int decisionsChanged;
    public final int rulesAdded;
    public final int rulesRemoved;
    public final int rulesChanged;
    public final int rulesMoved;
    public final int columnsAdded;
    public final int columnsRemoved;
    public final int columnsChanged;
    public final boolean hitPolicyChanged;

    public DiffSummary(int decisionsAdded, int decisionsRemoved, int decisionsChanged,
                       int rulesAdded, int rulesRemoved, int rulesChanged, int rulesMoved,
                       int columnsAdded, int columnsRemoved, int columnsChanged,
                       boolean hitPolicyChanged) {
        this.decisionsAdded = decisionsAdded;
        this.decisionsRemoved = decisionsRemoved;
        this.decisionsChanged = decisionsChanged;
        this.rulesAdded = rulesAdded;
        this.rulesRemoved = rulesRemoved;
        this.rulesChanged = rulesChanged;
        this.rulesMoved = rulesMoved;
        this.columnsAdded = columnsAdded;
        this.columnsRemoved = columnsRemoved;
        this.columnsChanged = columnsChanged;
        this.hitPolicyChanged = hitPolicyChanged;
    }
}
