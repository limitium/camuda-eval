package limitium.art.camunda.evaluator.junit;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import limitium.art.camunda.evaluator.DmnEvaluator;
import limitium.art.camunda.evaluator.testspec.DecisionTests;
import limitium.art.camunda.evaluator.testspec.TestCase;
import limitium.art.camunda.evaluator.testspec.TestSpec;
import limitium.art.camunda.evaluator.testspec.TestSpecReader;
import org.camunda.bpm.dmn.engine.DmnDecisionResult;
import org.camunda.bpm.engine.variable.value.TypedValue;
import org.camunda.bpm.engine.variable.Variables;
import org.junit.jupiter.api.DynamicTest;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Factory utility that turns a directory structure containing DMN decision tables and YAML test specifications
 * into a {@link Stream Stream&lt;DynamicTest&gt;} that JUnit 5 can execute.
 * <p>
 * The produced stream is entirely deterministic and can therefore be reused in any project that adds this
 * library as a test‑scoped dependency. No paths are hard‑coded – everything is parameterised – so you are free
 * to organise your resources as you please.
 * <p>
 * <h2>Directory layout</h2>
 * For every <code>my-decision.dmn</code> file there must be a matching <code>my-decision.yaml</code>
 * located in the <em>same</em> directory (or in a parallel directory if you provide different roots). The YAML
 * test file must have the following (flexible) schema:
 * <pre>{@code
 * tests:
 *   - description: "Happy path"
 *     decision: "decisionKey"   # mandatory
 *     in:                       # map of input variables
 *       age: 42
 *       country: "PT"
 *     out: "OK"                # expected single-entry result
 *   - decision: "decisionKey"
 *     in: {...}
 *     out: "NOK"
 * }</pre>
 * <p>
 * <h2>Usage</h2>
 * <pre>{@code
 * @TestFactory
 * Stream<DynamicTest> dmnTests() throws Exception {
 *     return DmnYamlTestFactory.from(Paths.get("src/test/resources/dmn"),
 *                                    Paths.get("src/test/resources/yaml"));
 * }
 * }</pre>
 * If both DMN and YAML files live under the same root you can simply call {@link #from(Path)}.
 * <p>
 * <h2>Coverage report</h2>
 * Every executed rule emits a coverage event. When the JVM shuts down a YAML coverage report is printed to
 * <code>STDOUT</code>. This behaviour is opt‑in – just keep the factory instance around; no additional
 * configuration is needed.
 */
public final class DmnYamlTestFactory {

    /**
     * System‑property key that can override the root folder for both DMN and YAML resources.
     * Useful for CI pipelines (e.g. <code>-Ddmn.test.root=./resources</code>).
     */
    public static final String ROOT_PROPERTY = "dmn.test.root";

    private DmnYamlTestFactory() {
        // utility – no instances
    }

    /**
     * Builds a dynamic test stream where DMN and YAML files live under the same root.
     */
    public static Stream<DynamicTest> from(Path root) throws Exception {
        return from(root, root);
    }

    /**
     * Builds a dynamic test stream using separate roots for DMN and YAML files.
     */
    public static Stream<DynamicTest> from(Path dmnRoot, Path yamlRoot) throws Exception {
        Objects.requireNonNull(dmnRoot, "dmnRoot must not be null");
        Objects.requireNonNull(yamlRoot, "yamlRoot must not be null");

        // Resolve via system property fallback so clients need not pass explicit paths
        if (Files.notExists(dmnRoot)) {
            Path fallback = Paths.get(System.getProperty(ROOT_PROPERTY, rootFallback()));
            if (Files.exists(fallback)) {
                dmnRoot = fallback;
                yamlRoot = fallback;
            }
        }

        if (Files.notExists(dmnRoot) || Files.notExists(yamlRoot)) {
            // No resources present – JUnit will mark test factory as containing zero tests
            return Stream.empty();
        }

        CoverCollector coverCollector = new CoverCollector();
        List<Path> discoveredDmnFiles = new ArrayList<>();

        List<DynamicTest> generated = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(dmnRoot)) {
            Path finalYamlRoot = yamlRoot;
            walk.filter(p -> p.toString().endsWith(".dmn"))
                    .forEach(dmnPath -> {
                        discoveredDmnFiles.add(dmnPath);
                        String baseName = stripExtension(dmnPath.getFileName().toString());
                        Path yamlPath = finalYamlRoot.resolve(baseName + ".yaml");
                        if (!Files.exists(yamlPath)) {
                            return; // silently ignore unmatched DMN file
                        }

                        TestSpec spec = readSpec(yamlPath);
                        for (final DecisionTests decision : spec.decisions) {
                            for (final TestCase tc : decision.cases) {
                                String display = buildDisplayName(baseName, decision, tc);
                                generated.add(DynamicTest.dynamicTest(display, () -> {
                                    DmnEvaluator evaluator = new DmnEvaluator();
                                    evaluator.setCollectCoverage(true);
                                    DmnEvaluator.DmnDecisionEvaluator dec =
                                            evaluator.loadRule(dmnPath, decision.decision);

                                    org.camunda.bpm.engine.variable.VariableMap vars = Variables.createVariables();
                                    tc.inputsAsMap(decision.inputColumns).forEach(vars::putValue);

                                    DmnDecisionResult result = dec.evaluate(vars);
                                    assertExpectations(decision, tc, result);

                                    synchronized (coverCollector) {
                                        coverCollector.addEvents(evaluator.getCoverageEvents());
                                    }
                                }));
                            }
                        }
                    });
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> coverCollector.print(discoveredDmnFiles)));

        return generated.stream();
    }

    // ────────────────────────────────────────────────────────────────────────────────
    // Test spec helpers
    // ────────────────────────────────────────────────────────────────────────────────

    private static TestSpec readSpec(Path yamlPath) {
        try {
            byte[] bytes = Files.readAllBytes(yamlPath);
            return TestSpecReader.read(new String(bytes, StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to read test spec " + yamlPath + ": "
                    + e.getMessage(), e);
        }
    }

    /**
     * One expectation column is compared against the decision's single entry, which is how a
     * one-output table behaves; several are compared against the result map by output name.
     */
    private static void assertExpectations(DecisionTests decision, TestCase tc,
                                           DmnDecisionResult result) {
        if (decision.outputColumns.size() == 1) {
            Object expected = tc.expected.get(0);
            Object actual;
            if (expected instanceof List<?>) {
                List<Object> collected = new ArrayList<>();
                for (Object rr : result.getResultList()) {
                    collected.add(extractSingleEntry(rr));
                }
                actual = collected;
            } else {
                actual = result.getSingleResult().getSingleEntry();
            }
            assertEqualsNormalized(expected, actual);
            return;
        }
        Map<String, Object> actual = result.getSingleResult();
        for (int i = 0; i < decision.outputColumns.size(); i++) {
            String output = decision.outputColumns.get(i);
            try {
                assertEqualsNormalized(tc.expected.get(i), actual.get(output));
            } catch (AssertionError e) {
                throw new AssertionError(output + ": " + e.getMessage());
            }
        }
    }

    private static String buildDisplayName(String baseName, DecisionTests decision, TestCase tc) {
        StringBuilder sb = new StringBuilder();
        if (tc.scenario != null && !tc.scenario.trim().isEmpty()) {
            sb.append(tc.scenario).append(" | ");
        }
        sb.append(baseName).append(':').append(decision.decision)
                .append(" \u2192 ").append(tc.inputsAsMap(decision.inputColumns))
                .append(" = ").append(tc.expected.size() == 1
                        ? String.valueOf(tc.expected.get(0)) : String.valueOf(tc.expected));
        return sb.toString();
    }

    private static String stripExtension(String filename) {
        int idx = filename.lastIndexOf('.');
        return idx == -1 ? filename : filename.substring(0, idx);
    }

    private static String rootFallback() {
        return "src/test/resources";
    }

    // ────────────────────────────────────────────────────────────────────────────────
    // Internal value objects
    // ────────────────────────────────────────────────────────────────────────────────


    private static void assertEqualsNormalized(Object expected, Object actual) {
        // List comparison with element-wise numeric normalization
        if (expected instanceof List<?> && actual instanceof List<?>) {
            List<?> eList = (List<?>) expected;
            List<?> aList = (List<?>) actual;
            assertEquals(eList.size(), aList.size(), "List sizes differ: expected=" + eList.size() + ", actual=" + aList.size());
            for (int i = 0; i < eList.size(); i++) {
                assertEqualsNormalized(eList.get(i), aList.get(i));
            }
            return;
        }
        if (expected instanceof Number && actual instanceof Number) {
            java.math.BigDecimal e = new java.math.BigDecimal(expected.toString());
            java.math.BigDecimal a = new java.math.BigDecimal(actual.toString());
            assertEquals(0, e.compareTo(a), "Numeric values differ: expected=" + e + ", actual=" + a);
            return;
        }
        assertEquals(expected, actual);
    }

    private static Object extractSingleEntry(Object entry) {
        if (entry == null) return null;
        // Prefer Camunda API if available
        try {
            java.lang.reflect.Method m = entry.getClass().getMethod("getSingleEntry");
            return m.invoke(entry);
        } catch (Exception ignore) {
            // Fallback to map assumption
        }
        if (entry instanceof Map<?, ?>) {
            Map<?, ?> map = (Map<?, ?>) entry;
            if (map.isEmpty()) return null;
            Object value = map.values().iterator().next();
            if (value instanceof TypedValue) {
                return ((TypedValue) value).getValue();
            }
            return value;
        }
        return entry;
    }

    // ────────────────────────────────────────────────────────────────────────────────
    // Coverage collection & reporting
    // ────────────────────────────────────────────────────────────────────────────────

    private static final class CoverCollector {
        private final List<DmnEvaluator.CoverageEvent> events = new ArrayList<>();

        void addEvents(List<DmnEvaluator.CoverageEvent> evts) {
            events.addAll(evts);
        }

        void print(List<Path> dmnFiles) {
            try {
                Map<String, Object> report = new LinkedHashMap<>();
                for (Path dmnFile : dmnFiles) {
                    DmnEvaluator eval = new DmnEvaluator();
                    List<String> decisions = eval.loadAllDecisionKeys(dmnFile);

                    Map<String, Object> dmnReport = new LinkedHashMap<>();
                    int totalRules = 0;
                    int coveredRules = 0;

                    for (String decision : decisions) {
                        List<DmnEvaluator.RuleInfo> rules = eval.loadAllRules(dmnFile, decision);
                        totalRules += rules.size();

                        Set<String> covered = new HashSet<>();
                        for (DmnEvaluator.CoverageEvent evt : events) {
                            if (evt.decisionKey.equals(decision)) {
                                covered.add(evt.ruleId);
                            }
                        }
                        coveredRules += covered.size();

                        Map<String, Object> decisionReport = new LinkedHashMap<>();
                        decisionReport.put("totalRules", rules.size());
                        decisionReport.put("coveredRules", covered.size());
                        decisionReport.put("coverage", rules.isEmpty() ? 1.0 : ((double) covered.size() / rules.size()));

                        List<String> uncovered = new ArrayList<>();
                        for (DmnEvaluator.RuleInfo rule : rules) {
                            if (!covered.contains(rule.ruleId)) {
                                uncovered.add(rule.ruleId);
                            }
                        }
                        decisionReport.put("uncoveredRules", uncovered);
                        dmnReport.put(decision, decisionReport);
                    }

                    Map<String, Object> summary = new LinkedHashMap<>();
                    summary.put("totalRules", totalRules);
                    summary.put("coveredRules", coveredRules);
                    summary.put("coverage", totalRules == 0 ? 1.0 : ((double) coveredRules / totalRules));
                    dmnReport.put("_summary", summary);

                    report.put(dmnFile.toString(), dmnReport);
                }

                DumperOptions opts = new DumperOptions();
                opts.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
                opts.setPrettyFlow(true);
                String dump = new Yaml(opts).dump(report);

                System.out.println("\n=== DMN Test Coverage Report (YAML) ===\n" + dump + "=== End of Coverage Report ===\n");
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }
}
