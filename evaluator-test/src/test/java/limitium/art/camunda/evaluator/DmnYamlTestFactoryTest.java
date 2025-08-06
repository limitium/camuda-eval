package limitium.art.camunda.evaluator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.camunda.bpm.engine.variable.Variables;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;

public class DnmYamlTestErrors {
    @Test
    void testMissingDmnFile() {
        DmnEvaluator evaluator = new DmnEvaluator();
        assertThrows(DmnEvaluator.DmnFileNotFoundException.class, () -> evaluator.loadAllDecisionKeys(Path.of("src/test/resources/no-such-file.dmn")));
    }

    @Test
    void testMissingDecision() {
        DmnEvaluator evaluator = new DmnEvaluator();
        assertThrows(DmnEvaluator.DmnDecisionNotFoundException.class, () -> evaluator.loadAllRules(Path.of("src/test/resources/movie-decision.dmn"), "NoSuchDecision"));
    }

    @Test
    void testMissingRule() {
        DmnEvaluator evaluator = new DmnEvaluator();
        // Use a real DMN file with a decision that exists but has no rules
        // We'll use a custom DMN file for this test: /empty-decision.dmn with decision id 'EmptyDecision'
        assertThrows(DmnEvaluator.DmnRuleNotFoundException.class, () -> evaluator.loadAllRules(Path.of("src/test/resources/empty-decision.dmn"), "EmptyDecision"));
    }

    @Test
    void testAmbiguousEvaluation() {
        DmnEvaluator evaluator = new DmnEvaluator();
        DmnEvaluator.DmnDecisionEvaluator dec = evaluator.loadRule(Path.of("src/test/resources/ambiguous-decision.dmn"), "AmbiguousDecision");
        // Provide variables that do not match any rule (e.g., value = "bar")
        var vars = Variables.createVariables().putValue("value", "bar");
        assertThrows(DmnEvaluator.DmnAmbiguousEvaluationException.class, () -> dec.evaluate(vars));
    }

    @Test
    void testGenericEvaluationError() {
        DmnEvaluator evaluator = new DmnEvaluator();
        // Simulate error by passing null as variables
        DmnEvaluator.DmnDecisionEvaluator dec = evaluator.loadRule(Path.of("src/test/resources/movie-decision.dmn"), "MovieDecision");
        assertThrows(DmnEvaluator.DmnEvaluationException.class, () -> dec.evaluate(null));
    }
} 