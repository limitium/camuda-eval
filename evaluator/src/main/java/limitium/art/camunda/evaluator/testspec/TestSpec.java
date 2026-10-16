package limitium.art.camunda.evaluator.testspec;

import java.util.Collections;
import java.util.List;

/**
 * The test cases paired with one DMN file, one block per decision.
 *
 * <p>The thing under test is a table, so the tests are written as one. Each row is a case, the
 * columns line up with the decision table's own inputs and outputs, and a leading {@code scenario}
 * column says what the row is for:
 *
 * <pre>
 * tests:
 *   - decision: Pricing
 *     columns: [scenario,       amount, region, "=&gt; discount"]
 *     rows:
 *       - [EU small order,      50,     EU,     "0%"]
 *       - [EU bulk,             600,    EU,     "10%"]
 * </pre>
 *
 * <p>Cells are <em>values</em>, not FEEL expressions: {@code 50} is the number fifty and {@code EU}
 * is the string EU. Quoting is ordinary YAML quoting, needed only where the value would otherwise be
 * ambiguous.
 */
public class TestSpec {
    public final List<DecisionTests> decisions;

    public TestSpec(List<DecisionTests> decisions) {
        this.decisions = Collections.unmodifiableList(decisions);
    }

    public int caseCount() {
        int total = 0;
        for (DecisionTests decision : decisions) {
            total += decision.cases.size();
        }
        return total;
    }
}
