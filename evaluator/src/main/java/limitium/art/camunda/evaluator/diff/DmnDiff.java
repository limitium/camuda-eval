package limitium.art.camunda.evaluator.diff;

import java.util.Collections;
import java.util.List;

public class DmnDiff {
    public final String leftLabel;
    public final String rightLabel;
    public final boolean identical;
    public final DiffSummary summary;
    public final List<DecisionDiff> decisions;

    public DmnDiff(String leftLabel, String rightLabel, boolean identical,
                   DiffSummary summary, List<DecisionDiff> decisions) {
        this.leftLabel = leftLabel;
        this.rightLabel = rightLabel;
        this.identical = identical;
        this.summary = summary;
        this.decisions = Collections.unmodifiableList(decisions);
    }
}
