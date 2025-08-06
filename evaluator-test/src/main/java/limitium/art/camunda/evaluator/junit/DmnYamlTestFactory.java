package limitium.art.camunda.evaluator;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
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

public class DnmYamlTest {

    @TestFactory
    Stream<DynamicTest> dynamicTestsFromYaml() throws Exception {
        // Define paths for DMN and YAML resources
        Path dmnRoot = Paths.get("src/test/resources");
        Path yamlRoot = Paths.get("src/test/resources");

        if (!Files.exists(dmnRoot) || !Files.exists(yamlRoot)) {
            return Stream.empty();
        }

        List<DynamicTest> tests = new ArrayList<>();
        Set<Path> dmnFiles = new HashSet<>();
        try (Stream<Path> paths = Files.walk(dmnRoot)) {
            paths.filter(p -> p.toString().endsWith(".dmn"))
                    .forEach(dmnPath -> {
                        String fileName = dmnPath.getFileName().toString();
                        String baseName = fileName.replaceAll("\\.dmn$", "");
                        dmnFiles.add(dmnPath);
                        Path yamlPath = yamlRoot.resolve(baseName + ".yaml");

                        if (Files.exists(yamlPath)) {
                            try (InputStream in = Files.newInputStream(yamlPath)) {
                                Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
                                Map<?,?> root = yaml.load(in);
                                @SuppressWarnings("unchecked")
                                List<Map<String, Object>> testList = (List<Map<String, Object>>) root.get("tests");
                                if (testList == null) return;

                                for (Map<String, Object> testCase : testList) {
                                    String decisionKey = (String) testCase.get("decision");
                                    @SuppressWarnings("unchecked")
                                    Map<String, Object> inputs = (Map<String, Object>) testCase.get("in");
                                    String expected = (String) testCase.get("out");
                                    String description = (String) testCase.getOrDefault("description", "");

                                    String displayName = (description.isEmpty() ? "" : (description + " | ")) + baseName + ":" + decisionKey + " → " + inputs + " = " + expected;

                                    tests.add(DynamicTest.dynamicTest(displayName, () -> {
                                        DmnEvaluator evaluator = new DmnEvaluator();
                                        evaluator.setCollectCoverage(true);
                                        DmnEvaluator.DmnDecisionEvaluator dec = evaluator.loadRule(
                                                dmnPath, decisionKey
                                        );
                                        var variables = org.camunda.bpm.engine.variable.Variables.createVariables();
                                        inputs.forEach(variables::putValue);
                                        var result = dec.evaluate(variables);
                                        String actual = result.getSingleResult().getSingleEntry();
                                        assertEquals(expected, actual);
                                        // After each test, merge coverage events
                                        synchronized (DnmYamlTest.class) {
                                            CoverageCollector.INSTANCE.addEvents(evaluator.getCoverageEvents());
                                        }
                                    }));
                                }
                            } catch (Exception e) {
                                throw new RuntimeException("Failed loading tests from " + yamlPath, e);
                            }
                        }
                    });
        }

        // After all tests, print coverage report
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            CoverageCollector.INSTANCE.logCoverageReport(dmnFiles);
        }));

        return tests.stream();
    }

    // --- CoverageCollector singleton for thread-safe event collection and reporting ---
    static class CoverageCollector {
        static final CoverageCollector INSTANCE = new CoverageCollector();
        private final List<limitium.art.camunda.evaluator.DmnEvaluator.CoverageEvent> allEvents = new ArrayList<>();
        public synchronized void addEvents(List<limitium.art.camunda.evaluator.DmnEvaluator.CoverageEvent> events) {
            allEvents.addAll(events);
        }
        public void logCoverageReport(Set<Path> dmnFiles) {
            try {
                Map<String, Object> report = new LinkedHashMap<>();
                for (Path dmnFile : dmnFiles) {
                    DmnEvaluator evaluator = new DmnEvaluator();
                    List<String> decisions = evaluator.loadAllDecisionKeys(dmnFile);
                    Map<String, Object> dmnReport = new LinkedHashMap<>();
                    int totalRules = 0;
                    int coveredRules = 0;
                    for (String decision : decisions) {
                        List<DmnEvaluator.RuleInfo> rules = evaluator.loadAllRules(dmnFile, decision);
                        totalRules += rules.size();
                        Set<String> covered = new HashSet<>();
                        for (limitium.art.camunda.evaluator.DmnEvaluator.CoverageEvent evt : allEvents) {
                            if (evt.decisionKey.equals(decision)) {
                                covered.add(evt.ruleId);
                            }
                        }
                        coveredRules += covered.size();
                        Map<String, Object> decisionReport = new LinkedHashMap<>();
                        decisionReport.put("totalRules", rules.size());
                        decisionReport.put("coveredRules", covered.size());
                        decisionReport.put("coverage", rules.size() == 0 ? 1.0 : ((double) covered.size() / rules.size()));
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
                DumperOptions options = new DumperOptions();
                options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
                options.setPrettyFlow(true);
                Yaml yaml = new Yaml(options);
                String yamlStr = yaml.dump(report);
                System.out.println("\n=== DMN Test Coverage Report (YAML) ===\n" + yamlStr + "=== End of Coverage Report ===\n");
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }
} 