package limitium.art.camunda.backend.model;

public class TestResult {
    public final String testName;
    public final boolean passed;
    public final String errorMessage;
    public final long executionTimeMs;
    
    public TestResult(String testName, boolean passed, String errorMessage, long executionTimeMs) {
        this.testName = testName;
        this.passed = passed;
        this.errorMessage = errorMessage;
        this.executionTimeMs = executionTimeMs;
    }
}
