package limitium.art.camunda.evaluator.testspec;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One row: what is being tested, the inputs, and what each output is expected to be. */
public class TestCase {
    /** Free text naming the case; used as the test name in failure output. */
    public final String scenario;
    /** Values aligned to {@link DecisionTests#inputColumns}. */
    public final List<Object> inputs;
    /** Values aligned to {@link DecisionTests#outputColumns}. */
    public final List<Object> expected;

    public TestCase(String scenario, List<Object> inputs, List<Object> expected) {
        this.scenario = scenario;
        this.inputs = Collections.unmodifiableList(inputs);
        this.expected = Collections.unmodifiableList(expected);
    }

    /** The inputs as the evaluator wants them. Empty cells are omitted rather than passed as null. */
    public Map<String, Object> inputsAsMap(List<String> inputColumns) {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        for (int i = 0; i < inputColumns.size() && i < inputs.size(); i++) {
            Object value = inputs.get(i);
            if (value != null) {
                map.put(inputColumns.get(i), value);
            }
        }
        return map;
    }
}
