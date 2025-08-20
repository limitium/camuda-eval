package limitium.art.camunda.evaluator.junit;

import limitium.art.camunda.evaluator.DmnEvaluator;
import org.camunda.bpm.engine.variable.Variables;
import org.junit.jupiter.api.DynamicTest;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.InputStream;
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

                        List<TestCase> cases = parseYamlCases(yamlPath);
                        for (TestCase tc : cases) {
                            String display = buildDisplayName(baseName, tc);
                            generated.add(DynamicTest.dynamicTest(display, () -> {
                                DmnEvaluator evaluator = new DmnEvaluator();
                                evaluator.setCollectCoverage(true);
                                DmnEvaluator.DmnDecisionEvaluator dec = evaluator.loadRule(dmnPath, tc.decisionKey);

                                org.camunda.bpm.engine.variable.VariableMap vars = Variables.createVariables();
                                tc.inputs.forEach(vars::putValue);

                                String actual = dec.evaluate(vars)
                                        .getSingleResult()
                                        .getSingleEntry();
                                assertEquals(tc.expected, actual);

                                synchronized (coverCollector) {
                                    coverCollector.addEvents(evaluator.getCoverageEvents());
                                }
                            }));
                        }
                    });
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> coverCollector.print(discoveredDmnFiles)));

        return generated.stream();
    }

    // ────────────────────────────────────────────────────────────────────────────────
    // YAML parsing helpers
    // ────────────────────────────────────────────────────────────────────────────────

    private static List<TestCase> parseYamlCases(Path yamlPath) {
        LoaderOptions lo = new LoaderOptions();
        Yaml yaml = new Yaml(new SafeConstructor(lo));
        try (InputStream in = Files.newInputStream(yamlPath)) {
            Map<?, ?> root = yaml.load(in);
            // Manually pull the node to avoid generics capture issues
            List<Map<String, Object>> rawCases;
            if (root == null) {
                rawCases = Collections.emptyList();
            } else {
                Object testsNode = root.get("tests");
                if (testsNode instanceof List<?>) {
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> tmp = (List<Map<String, Object>>) testsNode;
                    rawCases = tmp;
                } else {
                    rawCases = Collections.emptyList();
                }
            }
            List<TestCase> cases = new ArrayList<>(rawCases.size());
            for (Map<String, Object> map : rawCases) {
                cases.add(TestCase.from(map));
            }
            return cases;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse YAML file " + yamlPath, e);
        }
    }

    private static String buildDisplayName(String baseName, TestCase tc) {
        StringBuilder sb = new StringBuilder();
        if (tc.description != null && !tc.description.trim().isEmpty()) {
            sb.append(tc.description).append(" | ");
        }
        sb.append(baseName)
                .append(':').append(tc.decisionKey)
                .append(" → ")
                .append(tc.inputs)
                .append(" = ")
                .append(tc.expected);
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

    private static final class TestCase {
        final String decisionKey;
        final Map<String, Object> inputs;
        final String expected;
        final String description;

        TestCase(String decisionKey, Map<String, Object> inputs, String expected, String description) {
            this.decisionKey = decisionKey;
            this.inputs = inputs;
            this.expected = expected;
            this.description = description;
        }

        @SuppressWarnings("unchecked")
        static TestCase from(Map<String, Object> map) {
            String decision = Objects.requireNonNull((String) map.get("decision"), "decision key missing");
            Map<String, Object> in;
            Object inObj = map.get("in");
            if (inObj instanceof Map) {
                in = (Map<String, Object>) inObj;
            } else {
                in = Collections.emptyMap();
            }
            String out = Objects.requireNonNull((String) map.get("out"), "expected 'out' missing");
            String desc = (String) map.get("description");
            if (desc == null) desc = "";
            return new TestCase(decision, in, out, desc);
        }
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
