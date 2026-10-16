package limitium.art.camunda.evaluator.testspec;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Reads the columnar test format.
 *
 * <p>Errors name the decision and the row, because a test file that fails to parse with a vague
 * message is worse than no validation: people stop trusting the runner rather than fix the file.
 */
public final class TestSpecReader {

    /** The first column of every block, naming what the row tests. */
    public static final String SCENARIO = "scenario";
    /** Marks a column as an expected output rather than an input. */
    public static final String EXPECT_PREFIX = "=>";

    private TestSpecReader() {
    }

    public static TestSpec read(String yamlContent) {
        Object root = load(yamlContent);
        if (root == null) {
            return new TestSpec(new ArrayList<DecisionTests>());
        }
        if (!(root instanceof Map)) {
            throw new TestSpecException("the file must be a mapping with a 'tests' key");
        }
        Object testsNode = ((Map<?, ?>) root).get("tests");
        if (testsNode == null) {
            return new TestSpec(new ArrayList<DecisionTests>());
        }
        if (!(testsNode instanceof List)) {
            throw new TestSpecException("'tests' must be a list of decision blocks");
        }

        List<DecisionTests> decisions = new ArrayList<DecisionTests>();
        for (Object blockNode : (List<?>) testsNode) {
            if (!(blockNode instanceof Map)) {
                throw new TestSpecException("each entry under 'tests' must be a decision block");
            }
            decisions.add(readBlock((Map<?, ?>) blockNode));
        }
        return new TestSpec(decisions);
    }

    private static DecisionTests readBlock(Map<?, ?> block) {
        Object decisionNode = block.get("decision");
        if (decisionNode == null || String.valueOf(decisionNode).trim().isEmpty()) {
            throw new TestSpecException("a decision block is missing its 'decision' key");
        }
        String decision = String.valueOf(decisionNode);

        Object columnsNode = block.get("columns");
        if (!(columnsNode instanceof List)) {
            throw new TestSpecException("decision '" + decision + "' is missing its 'columns' list");
        }
        List<?> rawColumns = (List<?>) columnsNode;
        if (rawColumns.isEmpty() || !SCENARIO.equals(String.valueOf(rawColumns.get(0)).trim())) {
            throw new TestSpecException("decision '" + decision
                    + "': the first column must be '" + SCENARIO + "'");
        }

        List<String> inputColumns = new ArrayList<String>();
        List<String> outputColumns = new ArrayList<String>();
        // Position within the row of each input and each output, so a row is read once.
        List<Integer> inputAt = new ArrayList<Integer>();
        List<Integer> outputAt = new ArrayList<Integer>();

        for (int c = 1; c < rawColumns.size(); c++) {
            String name = String.valueOf(rawColumns.get(c)).trim();
            if (name.startsWith(EXPECT_PREFIX)) {
                String output = name.substring(EXPECT_PREFIX.length()).trim();
                if (output.isEmpty()) {
                    throw new TestSpecException("decision '" + decision
                            + "': column " + (c + 1) + " marks an expectation but names no output");
                }
                outputColumns.add(output);
                outputAt.add(Integer.valueOf(c));
            } else {
                if (name.isEmpty()) {
                    throw new TestSpecException("decision '" + decision
                            + "': column " + (c + 1) + " has no name");
                }
                inputColumns.add(name);
                inputAt.add(Integer.valueOf(c));
            }
        }
        if (outputColumns.isEmpty()) {
            throw new TestSpecException("decision '" + decision + "': no expected output column. "
                    + "Mark one with '" + EXPECT_PREFIX + " <outputName>'");
        }

        List<TestCase> cases = new ArrayList<TestCase>();
        Object rowsNode = block.get("rows");
        if (rowsNode != null) {
            if (!(rowsNode instanceof List)) {
                throw new TestSpecException("decision '" + decision + "': 'rows' must be a list");
            }
            List<?> rows = (List<?>) rowsNode;
            for (int r = 0; r < rows.size(); r++) {
                cases.add(readRow(decision, r, rows.get(r), rawColumns.size(), inputAt, outputAt));
            }
        }
        return new DecisionTests(decision, inputColumns, outputColumns, cases);
    }

    private static TestCase readRow(String decision, int index, Object rowNode, int columnCount,
                                    List<Integer> inputAt, List<Integer> outputAt) {
        if (!(rowNode instanceof List)) {
            throw new TestSpecException("decision '" + decision + "', row " + (index + 1)
                    + ": expected a list of cells matching the columns");
        }
        List<?> row = (List<?>) rowNode;
        if (row.size() != columnCount) {
            throw new TestSpecException("decision '" + decision + "', row " + (index + 1)
                    + ": has " + row.size() + " cells but there are " + columnCount + " columns");
        }

        String scenario = row.get(0) == null ? "" : String.valueOf(row.get(0));
        List<Object> inputs = new ArrayList<Object>();
        for (int i = 0; i < inputAt.size(); i++) {
            inputs.add(row.get(inputAt.get(i).intValue()));
        }
        List<Object> expected = new ArrayList<Object>();
        for (int i = 0; i < outputAt.size(); i++) {
            expected.add(row.get(outputAt.get(i).intValue()));
        }
        return new TestCase(scenario, inputs, expected);
    }

    private static Object load(String yamlContent) {
        Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
        try {
            InputStream in = new ByteArrayInputStream(yamlContent.getBytes("UTF-8"));
            return yaml.load(in);
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException("UTF-8 unavailable", e);
        } catch (RuntimeException e) {
            throw new TestSpecException("could not parse YAML: " + e.getMessage(), e);
        }
    }

    /** Raised for a file that parses as YAML but is not a valid test spec. */
    public static class TestSpecException extends RuntimeException {
        public TestSpecException(String message) {
            super(message);
        }

        public TestSpecException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    static List<Object> emptyList() {
        return Collections.emptyList();
    }
}
