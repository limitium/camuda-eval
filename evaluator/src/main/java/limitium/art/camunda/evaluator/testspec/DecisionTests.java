package limitium.art.camunda.evaluator.testspec;

import java.util.Collections;
import java.util.List;

/**
 * The cases for one decision. Columns are held per decision rather than per file, so a table with
 * two inputs and one with seven do not distort each other into a wide sparse grid.
 */
public class DecisionTests {
    public final String decision;
    /** Input column names, matching the decision table's input expressions. */
    public final List<String> inputColumns;
    /** Expected-output column names, matching the decision table's output variable names. */
    public final List<String> outputColumns;
    public final List<TestCase> cases;

    public DecisionTests(String decision, List<String> inputColumns, List<String> outputColumns,
                         List<TestCase> cases) {
        this.decision = decision;
        this.inputColumns = Collections.unmodifiableList(inputColumns);
        this.outputColumns = Collections.unmodifiableList(outputColumns);
        this.cases = Collections.unmodifiableList(cases);
    }
}
