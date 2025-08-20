package limitium.art.camunda.evaluator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.camunda.bpm.engine.variable.Variables;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.io.InputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public class DnmYamlTestErrors {
    @Test
    void testMissingDmnFile() {
        DmnEvaluator evaluator = new DmnEvaluator();
        assertThrows(DmnEvaluator.DmnFileNotFoundException.class, () -> evaluator.loadAllDecisionKeys(Paths.get("src/test/resources/no-such-file.dmn")));
    }

    @Test
    void testMissingDecision() {
        DmnEvaluator evaluator = new DmnEvaluator();
        String dmn = readResource("ambiguous-decision.dmn");
        assertThrows(DmnEvaluator.DmnDecisionNotFoundException.class, () -> evaluator.loadAllRulesFromString(dmn, "NoSuchDecision"));
    }

    @Test
    void testMissingRule() {
        DmnEvaluator evaluator = new DmnEvaluator();
        // Use a real DMN file with a decision that exists but has no rules
        // We'll use a custom DMN file for this test: /empty-decision.dmn with decision id 'EmptyDecision'
        String dmn = readResource("empty-decision.dmn");
        assertThrows(DmnEvaluator.DmnRuleNotFoundException.class, () -> evaluator.loadAllRulesFromString(dmn, "EmptyDecision"));
    }

    @Test
    void testAmbiguousEvaluation() {
        DmnEvaluator evaluator = new DmnEvaluator();
        String dmn = readResource("ambiguous-decision.dmn");
        DmnEvaluator.DmnDecisionEvaluator dec = evaluator.loadRuleFromString(dmn, "AmbiguousDecision");
        // Provide variables that do not match any rule (e.g., value = "bar")
        org.camunda.bpm.engine.variable.VariableMap vars = Variables.createVariables().putValue("value", "bar");
        assertThrows(DmnEvaluator.DmnAmbiguousEvaluationException.class, () -> dec.evaluate(vars));
    }

    @Test
    void testGenericEvaluationError() {
        DmnEvaluator evaluator = new DmnEvaluator();
        // Simulate error by passing null as variables
        String dmn = readResource("ambiguous-decision.dmn");
        DmnEvaluator.DmnDecisionEvaluator dec = evaluator.loadRuleFromString(dmn, "AmbiguousDecision");
        assertThrows(DmnEvaluator.DmnEvaluationException.class, () -> dec.evaluate(null));
    }

    private String readResource(String name) {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(name)) {
            if (is == null) throw new RuntimeException("Resource not found: " + name);
            byte[] bytes = new byte[is.available()];
            int read = is.read(bytes);
            if (read <= 0) return "";
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
} 