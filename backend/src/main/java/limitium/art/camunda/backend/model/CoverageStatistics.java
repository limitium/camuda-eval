package limitium.art.camunda.backend.model;

import java.util.Map;

public class CoverageStatistics {
    public final int totalRules;
    public final int coveredRules;
    public final double coveragePercent;
    public final Map<String, DecisionCoverage> decisions;
    
    public CoverageStatistics(int totalRules, int coveredRules, double coveragePercent, Map<String, DecisionCoverage> decisions) {
        this.totalRules = totalRules;
        this.coveredRules = coveredRules;
        this.coveragePercent = coveragePercent;
        this.decisions = decisions;
    }
}
