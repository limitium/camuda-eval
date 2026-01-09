package limitium.art.camunda.evaluator;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.camunda.bpm.engine.variable.Variables;
import org.camunda.bpm.engine.variable.VariableMap;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Performance test for typed-decisions.dmn
 * Tests the performance of evaluating BoolDecision, NumDecision, and StrDecision
 */
public class TypedDecisionsPerformanceTest {

    private DmnEvaluator evaluator;
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
        evaluator = new DmnEvaluator();
        dmn = readResource("typed-decisions.dmn");
        random = new Random(42); // Fixed seed for reproducible results
    }

    @Test
    void performanceTestBoolDecision() {
        DmnEvaluator.DmnDecisionEvaluator boolDec = evaluator.loadRuleFromString(dmn, "BoolDecision");
        
        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            String flag = BOOL_INPUTS[random.nextInt(BOOL_INPUTS.length)];
            VariableMap vars = Variables.createVariables().putValue("flag", flag);
            boolDec.evaluateToBoolean(vars);
        }
        
        // Benchmark
        long startTime = System.nanoTime();
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            String flag = BOOL_INPUTS[random.nextInt(BOOL_INPUTS.length)];
            VariableMap vars = Variables.createVariables().putValue("flag", flag);
            Boolean result = boolDec.evaluateToBoolean(vars);
            assertNotNull(result);
        }
        long endTime = System.nanoTime();
        
        long totalNanos = endTime - startTime;
        double avgNanos = (double) totalNanos / BENCHMARK_ITERATIONS;
        double avgMicros = avgNanos / 1000.0;
        double avgMillis = avgMicros / 1000.0;
        
        System.out.println("BoolDecision Performance:");
        System.out.println("  Iterations: " + BENCHMARK_ITERATIONS);
        System.out.println("  Total time: " + (totalNanos / 1_000_000.0) + " ms");
        System.out.println("  Average time: " + avgNanos + " ns (" + avgMicros + " μs, " + avgMillis + " ms)");
        System.out.println("  Throughput: " + (BENCHMARK_ITERATIONS * 1_000_000_000.0 / totalNanos) + " evaluations/sec");
        
        // Assert that average evaluation time is reasonable (less than 1ms per evaluation)
        assertTrue(avgMillis < 1.0, "Average evaluation time should be less than 1ms, but was " + avgMillis + " ms");
    }

    @Test
    void performanceTestNumDecision() {
        DmnEvaluator.DmnDecisionEvaluator numDec = evaluator.loadRuleFromString(dmn, "NumDecision");
        
        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            Number num = random.nextDouble() * 10000 - 5000; // Random number between -5000 and 5000
            VariableMap vars = Variables.createVariables().putValue("n", num);
            numDec.evaluateToNumber(vars);
        }
        
        // Benchmark
        long startTime = System.nanoTime();
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            Number num = random.nextDouble() * 10000 - 5000; // Random number between -5000 and 5000
            VariableMap vars = Variables.createVariables().putValue("n", num);
            Number result = numDec.evaluateToNumber(vars);
            assertNotNull(result);
        }
        long endTime = System.nanoTime();
        
        long totalNanos = endTime - startTime;
        double avgNanos = (double) totalNanos / BENCHMARK_ITERATIONS;
        double avgMicros = avgNanos / 1000.0;
        double avgMillis = avgMicros / 1000.0;
        
        System.out.println("NumDecision Performance:");
        System.out.println("  Iterations: " + BENCHMARK_ITERATIONS);
        System.out.println("  Total time: " + (totalNanos / 1_000_000.0) + " ms");
        System.out.println("  Average time: " + avgNanos + " ns (" + avgMicros + " μs, " + avgMillis + " ms)");
        System.out.println("  Throughput: " + (BENCHMARK_ITERATIONS * 1_000_000_000.0 / totalNanos) + " evaluations/sec");
        
        // Assert that average evaluation time is reasonable (less than 1ms per evaluation)
        assertTrue(avgMillis < 1.0, "Average evaluation time should be less than 1ms, but was " + avgMillis + " ms");
    }

    @Test
    void performanceTestStrDecision() {
        DmnEvaluator.DmnDecisionEvaluator strDec = evaluator.loadRuleFromString(dmn, "StrDecision");
        
        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            String input = STR_INPUTS[random.nextInt(STR_INPUTS.length)];
            VariableMap vars = Variables.createVariables().putValue("x", input);
            strDec.evaluateToString(vars);
        }
        
        // Benchmark
        long startTime = System.nanoTime();
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            String input = STR_INPUTS[random.nextInt(STR_INPUTS.length)];
            VariableMap vars = Variables.createVariables().putValue("x", input);
            String result = strDec.evaluateToString(vars);
            assertNotNull(result);
        }
        long endTime = System.nanoTime();
        
        long totalNanos = endTime - startTime;
        double avgNanos = (double) totalNanos / BENCHMARK_ITERATIONS;
        double avgMicros = avgNanos / 1000.0;
        double avgMillis = avgMicros / 1000.0;
        
        System.out.println("StrDecision Performance:");
        System.out.println("  Iterations: " + BENCHMARK_ITERATIONS);
        System.out.println("  Total time: " + (totalNanos / 1_000_000.0) + " ms");
        System.out.println("  Average time: " + avgNanos + " ns (" + avgMicros + " μs, " + avgMillis + " ms)");
        System.out.println("  Throughput: " + (BENCHMARK_ITERATIONS * 1_000_000_000.0 / totalNanos) + " evaluations/sec");
        
        // Assert that average evaluation time is reasonable (less than 1ms per evaluation)
        assertTrue(avgMillis < 1.0, "Average evaluation time should be less than 1ms, but was " + avgMillis + " ms");
    }

    @Test
    void performanceTestAllDecisions() {
        DmnEvaluator.DmnDecisionEvaluator boolDec = evaluator.loadRuleFromString(dmn, "BoolDecision");
        DmnEvaluator.DmnDecisionEvaluator numDec = evaluator.loadRuleFromString(dmn, "NumDecision");
        DmnEvaluator.DmnDecisionEvaluator strDec = evaluator.loadRuleFromString(dmn, "StrDecision");
        
        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            String flag = BOOL_INPUTS[random.nextInt(BOOL_INPUTS.length)];
            Number num = random.nextDouble() * 10000 - 5000;
            String strInput = STR_INPUTS[random.nextInt(STR_INPUTS.length)];
            boolDec.evaluateToBoolean(Variables.createVariables().putValue("flag", flag));
            numDec.evaluateToNumber(Variables.createVariables().putValue("n", num));
            strDec.evaluateToString(Variables.createVariables().putValue("x", strInput));
        }
        
        // Benchmark all decisions together
        long startTime = System.nanoTime();
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            String flag = BOOL_INPUTS[random.nextInt(BOOL_INPUTS.length)];
            Number num = random.nextDouble() * 10000 - 5000;
            String strInput = STR_INPUTS[random.nextInt(STR_INPUTS.length)];
            Boolean boolResult = boolDec.evaluateToBoolean(Variables.createVariables().putValue("flag", flag));
            Number numResult = numDec.evaluateToNumber(Variables.createVariables().putValue("n", num));
            String strResult = strDec.evaluateToString(Variables.createVariables().putValue("x", strInput));
            
            assertNotNull(boolResult);
            assertNotNull(numResult);
            assertNotNull(strResult);
        }
        long endTime = System.nanoTime();
        
        long totalNanos = endTime - startTime;
        long evaluations = BENCHMARK_ITERATIONS * 3; // Three decisions per iteration
        double avgNanos = (double) totalNanos / evaluations;
        double avgMicros = avgNanos / 1000.0;
        double avgMillis = avgMicros / 1000.0;
        
        System.out.println("All Decisions Combined Performance:");
        System.out.println("  Iterations: " + BENCHMARK_ITERATIONS + " (3 decisions each)");
        System.out.println("  Total evaluations: " + evaluations);
        System.out.println("  Total time: " + (totalNanos / 1_000_000.0) + " ms");
        System.out.println("  Average time per evaluation: " + avgNanos + " ns (" + avgMicros + " μs, " + avgMillis + " ms)");
        System.out.println("  Throughput: " + (evaluations * 1_000_000_000.0 / totalNanos) + " evaluations/sec");
        
        // Assert that average evaluation time is reasonable (less than 1ms per evaluation)
        assertTrue(avgMillis < 1.0, "Average evaluation time should be less than 1ms, but was " + avgMillis + " ms");
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

