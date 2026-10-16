package limitium.art.camunda.backend.model;

import java.util.List;

public class DecisionCoverage {
    public final int totalRules;
    public final int coveredRules;
    public final double coverage;
    public final List<String> uncoveredRules;
    
    public DecisionCoverage(int totalRules, int coveredRules, double coverage, List<String> uncoveredRules) {
        this.totalRules = totalRules;
        this.coveredRules = coveredRules;
        this.coverage = coverage;
        this.uncoveredRules = uncoveredRules;
    }
}
