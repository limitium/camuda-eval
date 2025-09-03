package limitium.art.camunda.evaluator;

import org.junit.jupiter.api.Test;
import org.camunda.bpm.engine.variable.Variables;
import org.camunda.bpm.engine.variable.VariableMap;

import static org.junit.jupiter.api.Assertions.*;

public class DmnTypedEvaluateMethodsTest {

	@Test
	void evaluateBooleanAndNumberAndString() {
		DmnEvaluator evaluator = new DmnEvaluator();

		String dmn = readResource("typed-decisions.dmn");

		DmnEvaluator.DmnDecisionEvaluator boolDec = evaluator.loadRuleFromString(dmn, "BoolDecision");
		VariableMap vars = Variables.createVariables().putValue("flag", "yes");
		Boolean b = boolDec.evaluateToBoolean(vars);
		assertEquals(Boolean.TRUE, b);

		DmnEvaluator.DmnDecisionEvaluator numDec = evaluator.loadRuleFromString(dmn, "NumDecision");
		Number n = numDec.evaluateToNumber(Variables.createVariables().putValue("n", 0));
		assertEquals(0, new java.math.BigDecimal("123").compareTo(new java.math.BigDecimal(n.toString())));

		DmnEvaluator.DmnDecisionEvaluator strDec = evaluator.loadRuleFromString(dmn, "StrDecision");
		String s = strDec.evaluateToString(Variables.createVariables().putValue("x", "anything"));
		assertEquals("OK", s);
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


