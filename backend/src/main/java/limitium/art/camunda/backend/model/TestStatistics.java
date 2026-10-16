package limitium.art.camunda.backend.model;

import java.util.List;

public class TestStatistics {
    public final int totalTests;
    public final int passedTests;
    public final int failedTests;
    public final int coveragePercent;
    public final List<TestResult> results;
    public final CoverageStatistics coverageStats;
    
    public TestStatistics(int totalTests, int passedTests, int failedTests, int coveragePercent, List<TestResult> results, CoverageStatistics coverageStats) {
        this.totalTests = totalTests;
        this.passedTests = passedTests;
        this.failedTests = failedTests;
        this.coveragePercent = coveragePercent;
        this.results = results;
        this.coverageStats = coverageStats;
    }
}
