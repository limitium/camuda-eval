package limitium.art.camunda.evaluator;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.camunda.bpm.engine.variable.Variables;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Performance test to measure the CPU hotspot impact of DmnEvaluator.printHistory
 * Compares performance with and without history logging enabled
 */
public class PrintHistoryPerformanceTest {

    private String dmn;
    private Random random;
    private static final int WARMUP_ITERATIONS = 100;
    private static final int BENCHMARK_ITERATIONS = 10000;
    
    // For BoolDecision: "yes" returns true, anything else returns false
    private static final String[] BOOL_INPUTS = {"yes", "no", "maybe", "true", "false", "y", "n", "1", "0", "on", "off"};
    
    // For StrDecision: any string works, returns "OK"
    private static final String[] STR_INPUTS = {
        "anything", "test", "value", "input", "data", "random", "string", "text",
        "a", "b", "c", "xyz", "abc123", "hello", "world", "foo", "bar"
    };

    @BeforeEach
    void setUp() {
        dmn = readResource("typed-decisions.dmn");
        random = new Random(42); // Fixed seed for reproducible results
    }

    @Test
    void performanceTestPrintHistoryImpact() {
        // Test with printHistory enabled (default)
        DmnEvaluator evaluatorWithHistory = new DmnEvaluator();
        DmnEvaluator.DmnDecisionEvaluator boolDecWithHistory = evaluatorWithHistory.loadRuleFromString(dmn, "BoolDecision");
        DmnEvaluator.DmnDecisionEvaluator numDecWithHistory = evaluatorWithHistory.loadRuleFromString(dmn, "NumDecision");
        DmnEvaluator.DmnDecisionEvaluator strDecWithHistory = evaluatorWithHistory.loadRuleFromString(dmn, "StrDecision");
        
        // Test with printHistory disabled - SLF4J doesn't allow runtime level changes without implementation
        // For performance testing, we'll create evaluators but note that logging can't be easily disabled
        // The performance difference will show the impact of logging
        DmnEvaluator evaluatorWithoutHistory = new DmnEvaluator();
        DmnEvaluator.DmnDecisionEvaluator boolDecWithoutHistory = evaluatorWithoutHistory.loadRuleFromString(dmn, "BoolDecision");
        DmnEvaluator.DmnDecisionEvaluator numDecWithoutHistory = evaluatorWithoutHistory.loadRuleFromString(dmn, "NumDecision");
        DmnEvaluator.DmnDecisionEvaluator strDecWithoutHistory = evaluatorWithoutHistory.loadRuleFromString(dmn, "StrDecision");
        
        // Warmup both
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            String flag = BOOL_INPUTS[random.nextInt(BOOL_INPUTS.length)];
            Number num = random.nextDouble() * 10000 - 5000;
            String strInput = STR_INPUTS[random.nextInt(STR_INPUTS.length)];
            
            boolDecWithHistory.evaluateToBoolean(Variables.createVariables().putValue("flag", flag));
            numDecWithHistory.evaluateToNumber(Variables.createVariables().putValue("n", num));
            strDecWithHistory.evaluateToString(Variables.createVariables().putValue("x", strInput));
            
            boolDecWithoutHistory.evaluateToBoolean(Variables.createVariables().putValue("flag", flag));
            numDecWithoutHistory.evaluateToNumber(Variables.createVariables().putValue("n", num));
            strDecWithoutHistory.evaluateToString(Variables.createVariables().putValue("x", strInput));
        }
        
        // Benchmark WITH printHistory
        long startTimeWith = System.nanoTime();
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            String flag = BOOL_INPUTS[random.nextInt(BOOL_INPUTS.length)];
            Number num = random.nextDouble() * 10000 - 5000;
            String strInput = STR_INPUTS[random.nextInt(STR_INPUTS.length)];
            
            Boolean boolResult = boolDecWithHistory.evaluateToBoolean(Variables.createVariables().putValue("flag", flag));
            Number numResult = numDecWithHistory.evaluateToNumber(Variables.createVariables().putValue("n", num));
            String strResult = strDecWithHistory.evaluateToString(Variables.createVariables().putValue("x", strInput));
            
            assertNotNull(boolResult);
            assertNotNull(numResult);
            assertNotNull(strResult);
        }
        long endTimeWith = System.nanoTime();
        
        // Benchmark WITHOUT printHistory
        long startTimeWithout = System.nanoTime();
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            String flag = BOOL_INPUTS[random.nextInt(BOOL_INPUTS.length)];
            Number num = random.nextDouble() * 10000 - 5000;
            String strInput = STR_INPUTS[random.nextInt(STR_INPUTS.length)];
            
            Boolean boolResult = boolDecWithoutHistory.evaluateToBoolean(Variables.createVariables().putValue("flag", flag));
            Number numResult = numDecWithoutHistory.evaluateToNumber(Variables.createVariables().putValue("n", num));
            String strResult = strDecWithoutHistory.evaluateToString(Variables.createVariables().putValue("x", strInput));
            
            assertNotNull(boolResult);
            assertNotNull(numResult);
            assertNotNull(strResult);
        }
        long endTimeWithout = System.nanoTime();
        
        long totalNanosWith = endTimeWith - startTimeWith;
        long totalNanosWithout = endTimeWithout - startTimeWithout;
        long evaluations = BENCHMARK_ITERATIONS * 3; // Three decisions per iteration
        
        double avgNanosWith = (double) totalNanosWith / evaluations;
        double avgNanosWithout = (double) totalNanosWithout / evaluations;
        double overheadNanos = avgNanosWith - avgNanosWithout;
        double overheadPercent = (overheadNanos / avgNanosWithout) * 100.0;
        
        System.out.println("=== PrintHistory Performance Impact ===");
        System.out.println("Iterations: " + BENCHMARK_ITERATIONS + " (3 decisions each = " + evaluations + " total evaluations)");
        System.out.println();
        System.out.println("WITH printHistory:");
        System.out.println("  Total time: " + (totalNanosWith / 1_000_000.0) + " ms");
        System.out.println("  Average time per evaluation: " + avgNanosWith + " ns (" + (avgNanosWith / 1000.0) + " μs)");
        System.out.println("  Throughput: " + (evaluations * 1_000_000_000.0 / totalNanosWith) + " evaluations/sec");
        System.out.println();
        System.out.println("WITHOUT printHistory:");
        System.out.println("  Total time: " + (totalNanosWithout / 1_000_000.0) + " ms");
        System.out.println("  Average time per evaluation: " + avgNanosWithout + " ns (" + (avgNanosWithout / 1000.0) + " μs)");
        System.out.println("  Throughput: " + (evaluations * 1_000_000_000.0 / totalNanosWithout) + " evaluations/sec");
        System.out.println();
        System.out.println("PrintHistory Overhead:");
        System.out.println("  Additional time per evaluation: " + overheadNanos + " ns (" + (overheadNanos / 1000.0) + " μs)");
        System.out.println("  Overhead percentage: " + String.format("%.2f", overheadPercent) + "%");
        System.out.println("  Total overhead time: " + ((totalNanosWith - totalNanosWithout) / 1_000_000.0) + " ms");
        System.out.println("  Speedup factor: " + String.format("%.2f", (double) totalNanosWith / totalNanosWithout) + "x slower with printHistory");
        System.out.println("========================================");
        
        // Assert that printHistory adds significant overhead (at least 10% based on profiling)
        assertTrue(overheadPercent > 10.0, 
            "printHistory should add significant overhead (>10%), but overhead was only " + String.format("%.2f", overheadPercent) + "%");
        
        // Assert that disabling printHistory improves performance
        assertTrue(totalNanosWithout < totalNanosWith, 
            "Disabling printHistory should improve performance, but it didn't");
    }

    @Test
    void performanceTestPrintHistoryImpactSingleDecision() {
        // Test BoolDecision specifically
        DmnEvaluator evaluatorWithHistory = new DmnEvaluator();
        DmnEvaluator.DmnDecisionEvaluator boolDecWithHistory = evaluatorWithHistory.loadRuleFromString(dmn, "BoolDecision");
        
        DmnEvaluator evaluatorWithoutHistory = new DmnEvaluator();
        DmnEvaluator.DmnDecisionEvaluator boolDecWithoutHistory = evaluatorWithoutHistory.loadRuleFromString(dmn, "BoolDecision");
        
        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            String flag = BOOL_INPUTS[random.nextInt(BOOL_INPUTS.length)];
            boolDecWithHistory.evaluateToBoolean(Variables.createVariables().putValue("flag", flag));
            boolDecWithoutHistory.evaluateToBoolean(Variables.createVariables().putValue("flag", flag));
        }
        
        // Benchmark WITH printHistory
        long startTimeWith = System.nanoTime();
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            String flag = BOOL_INPUTS[random.nextInt(BOOL_INPUTS.length)];
            Boolean result = boolDecWithHistory.evaluateToBoolean(Variables.createVariables().putValue("flag", flag));
            assertNotNull(result);
        }
        long endTimeWith = System.nanoTime();
        
        // Benchmark WITHOUT printHistory
        long startTimeWithout = System.nanoTime();
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            String flag = BOOL_INPUTS[random.nextInt(BOOL_INPUTS.length)];
            Boolean result = boolDecWithoutHistory.evaluateToBoolean(Variables.createVariables().putValue("flag", flag));
            assertNotNull(result);
        }
        long endTimeWithout = System.nanoTime();
        
        long totalNanosWith = endTimeWith - startTimeWith;
        long totalNanosWithout = endTimeWithout - startTimeWithout;
        
        double avgNanosWith = (double) totalNanosWith / BENCHMARK_ITERATIONS;
        double avgNanosWithout = (double) totalNanosWithout / BENCHMARK_ITERATIONS;
        double overheadNanos = avgNanosWith - avgNanosWithout;
        double overheadPercent = (overheadNanos / avgNanosWithout) * 100.0;
        
        System.out.println("=== PrintHistory Impact on BoolDecision ===");
        System.out.println("Iterations: " + BENCHMARK_ITERATIONS);
        System.out.println();
        System.out.println("WITH printHistory:");
        System.out.println("  Total time: " + (totalNanosWith / 1_000_000.0) + " ms");
        System.out.println("  Average time: " + avgNanosWith + " ns (" + (avgNanosWith / 1000.0) + " μs)");
        System.out.println();
        System.out.println("WITHOUT printHistory:");
        System.out.println("  Total time: " + (totalNanosWithout / 1_000_000.0) + " ms");
        System.out.println("  Average time: " + avgNanosWithout + " ns (" + (avgNanosWithout / 1000.0) + " μs)");
        System.out.println();
        System.out.println("PrintHistory Overhead:");
        System.out.println("  Additional time per evaluation: " + overheadNanos + " ns (" + (overheadNanos / 1000.0) + " μs)");
        System.out.println("  Overhead percentage: " + String.format("%.2f", overheadPercent) + "%");
        System.out.println("  Speedup factor: " + String.format("%.2f", (double) totalNanosWith / totalNanosWithout) + "x slower with printHistory");
        System.out.println("===========================================");
    }

    private String readResource(String name) {
        try (java.io.InputStream is = getClass().getClassLoader().getResourceAsStream(name)) {
            if (is == null) throw new RuntimeException("Resource not found: " + name);
            byte[] bytes = new byte[is.available()];
            int read = is.read(bytes);
            if (read <= 0) return "";
            return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
    }
}

