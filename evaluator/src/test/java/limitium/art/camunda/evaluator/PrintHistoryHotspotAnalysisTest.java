package limitium.art.camunda.evaluator;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.camunda.bpm.engine.variable.Variables;

import java.util.Random;
import java.util.List;
import org.camunda.bpm.dmn.engine.delegate.DmnDecisionTableEvaluationEvent;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Performance test to identify the hotspot in printHistory
 * Measures string building vs logger call separately
 */
public class PrintHistoryHotspotAnalysisTest {

    private DmnEvaluator evaluator;
    private String dmn;
    private Random random;
    private static final int WARMUP_ITERATIONS = 100;
    private static final int BENCHMARK_ITERATIONS = 10000;
    
    private static final String[] BOOL_INPUTS = {"yes", "no", "maybe", "true", "false", "y", "n", "1", "0", "on", "off"};
    private static final String[] STR_INPUTS = {
        "anything", "test", "value", "input", "data", "random", "string", "text",
        "a", "b", "c", "xyz", "abc123", "hello", "world", "foo", "bar"
    };

    @BeforeEach
    void setUp() {
        evaluator = new DmnEvaluator();
        dmn = readResource("typed-decisions.dmn");
        random = new Random(42);
    }

    @Test
    void analyzePrintHistoryHotspot() throws Exception {
        DmnEvaluator.DmnDecisionEvaluator boolDec = evaluator.loadRuleFromString(dmn, "BoolDecision");
        DmnEvaluator.DmnDecisionEvaluator numDec = evaluator.loadRuleFromString(dmn, "NumDecision");
        DmnEvaluator.DmnDecisionEvaluator strDec = evaluator.loadRuleFromString(dmn, "StrDecision");
        
        // Collect events for analysis
        List<DmnDecisionTableEvaluationEvent> events = new java.util.ArrayList<>();
        
        // Warmup and collect events
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            String flag = BOOL_INPUTS[random.nextInt(BOOL_INPUTS.length)];
            Number num = random.nextDouble() * 10000 - 5000;
            String strInput = STR_INPUTS[random.nextInt(STR_INPUTS.length)];
            
            boolDec.evaluateToBoolean(Variables.createVariables().putValue("flag", flag));
            numDec.evaluateToNumber(Variables.createVariables().putValue("n", num));
            strDec.evaluateToString(Variables.createVariables().putValue("x", strInput));
        }
        
        // Get events from one evaluation for analysis - use reflection to access listener
        String flag = BOOL_INPUTS[random.nextInt(BOOL_INPUTS.length)];
        try {
            java.lang.reflect.Field listenerField = DmnEvaluator.class.getDeclaredField("listener");
            listenerField.setAccessible(true);
            Object listener = listenerField.get(evaluator);
            java.lang.reflect.Method clearMethod = listener.getClass().getMethod("clearEvents");
            java.lang.reflect.Method getEventsMethod = listener.getClass().getMethod("getEvents");
            
            clearMethod.invoke(listener);
            boolDec.evaluateToBoolean(Variables.createVariables().putValue("flag", flag));
            @SuppressWarnings("unchecked")
            List<DmnDecisionTableEvaluationEvent> collectedEvents = (List<DmnDecisionTableEvaluationEvent>) getEventsMethod.invoke(listener);
            events.addAll(collectedEvents);
        } catch (Exception e) {
            throw new RuntimeException("Failed to access listener", e);
        }
        
        // Test 1: Measure string building only (no logging)
        long startStringBuild = System.nanoTime();
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            String result = buildHistoryString(events, "BoolDecision");
            assertNotNull(result);
        }
        long endStringBuild = System.nanoTime();
        long stringBuildTime = endStringBuild - startStringBuild;
        
        // Test 2: Measure logger call only (with pre-built string)
        String preBuiltString = buildHistoryString(events, "BoolDecision");
        org.slf4j.Logger testLogger = org.slf4j.LoggerFactory.getLogger("test");
        long startLogger = System.nanoTime();
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            if (testLogger.isInfoEnabled()) {
                testLogger.info(preBuiltString);
            }
        }
        long endLogger = System.nanoTime();
        long loggerTime = endLogger - startLogger;
        
        // Test 3: Measure full printHistory (both string building + logging)
        long startFull = System.nanoTime();
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            printHistoryFull(events, "BoolDecision");
        }
        long endFull = System.nanoTime();
        long fullTime = endFull - startFull;
        
        // Calculate percentages
        double stringBuildPercent = (stringBuildTime * 100.0) / fullTime;
        double loggerPercent = (loggerTime * 100.0) / fullTime;
        double otherPercent = 100.0 - stringBuildPercent - loggerPercent;
        
        System.out.println("=== PrintHistory Hotspot Analysis ===");
        System.out.println("Iterations: " + BENCHMARK_ITERATIONS);
        System.out.println();
        System.out.println("String Building Only:");
        System.out.println("  Time: " + (stringBuildTime / 1_000_000.0) + " ms");
        System.out.println("  Average: " + (stringBuildTime / (double) BENCHMARK_ITERATIONS) + " ns");
        System.out.println("  Percentage of total: " + String.format("%.2f", stringBuildPercent) + "%");
        System.out.println();
        System.out.println("Logger Call Only:");
        System.out.println("  Time: " + (loggerTime / 1_000_000.0) + " ms");
        System.out.println("  Average: " + (loggerTime / (double) BENCHMARK_ITERATIONS) + " ns");
        System.out.println("  Percentage of total: " + String.format("%.2f", loggerPercent) + "%");
        System.out.println();
        System.out.println("Full printHistory (String Building + Logger):");
        System.out.println("  Time: " + (fullTime / 1_000_000.0) + " ms");
        System.out.println("  Average: " + (fullTime / (double) BENCHMARK_ITERATIONS) + " ns");
        System.out.println();
        System.out.println("Other overhead: " + String.format("%.2f", otherPercent) + "%");
        System.out.println();
        System.out.println("=== Conclusion ===");
        if (stringBuildPercent > loggerPercent) {
            System.out.println("MAJOR HOTSPOT: String Building (" + String.format("%.2f", stringBuildPercent) + "%)");
            System.out.println("Minor contributor: Logger Call (" + String.format("%.2f", loggerPercent) + "%)");
        } else {
            System.out.println("MAJOR HOTSPOT: Logger Call (" + String.format("%.2f", loggerPercent) + "%)");
            System.out.println("Minor contributor: String Building (" + String.format("%.2f", stringBuildPercent) + "%)");
        }
        System.out.println("=====================================");
    }
    
    // Replicate the string building logic from printHistory
    private String buildHistoryString(List<DmnDecisionTableEvaluationEvent> events, String decisionKey) {
        StringBuilder msg = new StringBuilder(events.size() * 80 + 60);
        msg.append("\n=== DMN History: ").append(decisionKey).append(" ===\n");
        
        for (DmnDecisionTableEvaluationEvent evt : events) {
            msg.append("decisionTable:").append(evt.getDecisionTable().getKey());
            msg.append(" inputs:");
            for (org.camunda.bpm.dmn.engine.delegate.DmnEvaluatedInput inp : evt.getInputs()) {
                msg.append(' ').append(inp.getName()).append('=');
                Object val = inp.getValue().getValue();
                if (val == null) msg.append("null");
                else if (val instanceof String) msg.append((String) val);
                else msg.append(val);
            }
            msg.append(" matchedRules:");
            for (org.camunda.bpm.dmn.engine.delegate.DmnEvaluatedDecisionRule rule : evt.getMatchingRules()) {
                msg.append(" ruleId=").append(rule.getId()).append(" outputs:");
                for (java.util.Map.Entry<String, org.camunda.bpm.dmn.engine.delegate.DmnEvaluatedOutput> entry : rule.getOutputEntries().entrySet()) {
                    msg.append(' ').append(entry.getKey()).append('=');
                    Object val = entry.getValue().getValue().getValue();
                    if (val == null) msg.append("null");
                    else if (val instanceof String) msg.append((String) val);
                    else msg.append(val);
                }
            }
            msg.append('\n');
        }
        
        msg.append("=== End ===\n");
        return msg.toString();
    }
    
    // Replicate the full printHistory logic
    private void printHistoryFull(List<DmnDecisionTableEvaluationEvent> events, String decisionKey) {
        org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger("test");
        if (!logger.isInfoEnabled()) {
            return;
        }
        
        StringBuilder msg = new StringBuilder(events.size() * 80 + 60);
        msg.append("\n=== DMN History: ").append(decisionKey).append(" ===\n");
        
        for (DmnDecisionTableEvaluationEvent evt : events) {
            msg.append("decisionTable:").append(evt.getDecisionTable().getKey());
            msg.append(" inputs:");
            for (org.camunda.bpm.dmn.engine.delegate.DmnEvaluatedInput inp : evt.getInputs()) {
                msg.append(' ').append(inp.getName()).append('=');
                Object val = inp.getValue().getValue();
                if (val == null) msg.append("null");
                else if (val instanceof String) msg.append((String) val);
                else msg.append(val);
            }
            msg.append(" matchedRules:");
            for (org.camunda.bpm.dmn.engine.delegate.DmnEvaluatedDecisionRule rule : evt.getMatchingRules()) {
                msg.append(" ruleId=").append(rule.getId()).append(" outputs:");
                for (java.util.Map.Entry<String, org.camunda.bpm.dmn.engine.delegate.DmnEvaluatedOutput> entry : rule.getOutputEntries().entrySet()) {
                    msg.append(' ').append(entry.getKey()).append('=');
                    Object val = entry.getValue().getValue().getValue();
                    if (val == null) msg.append("null");
                    else if (val instanceof String) msg.append((String) val);
                    else msg.append(val);
                }
            }
            msg.append('\n');
        }
        
        msg.append("=== End ===\n");
        logger.info(msg.toString());
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

