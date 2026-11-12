package limitium.art.camunda.backend.service;

import limitium.art.camunda.backend.model.*;
import limitium.art.camunda.evaluator.DmnEvaluator;
import org.camunda.bpm.engine.variable.Variables;
import org.springframework.stereotype.Service;
import limitium.art.camunda.evaluator.testspec.DecisionTests;
import limitium.art.camunda.evaluator.testspec.TestCase;
import limitium.art.camunda.evaluator.testspec.TestSpec;
import limitium.art.camunda.evaluator.testspec.TestSpecReader;
import limitium.art.camunda.evaluator.testspec.TestSpecValidator;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;

/**
 * Runs the YAML test cases paired with a DMN table and reports pass/fail plus rule coverage.
 *
 * <p>The content-based {@link #runYamlTests} is the real implementation; the {@link Path} variant
 * just reads the files and delegates. Working from strings is what lets a merge candidate that
 * exists only in memory be tested without materialising it into a working tree.
 */
@Service
public class TestExecutionService {

    private static final Consumer<String> NO_LOG = line -> { };

    /** Runs the cases in {@code yamlContent} against {@code dmnXml}. Progress is streamed to {@code log}. */
    public TestStatistics runYamlTests(String dmnXml, String yamlContent, Consumer<String> log) {
        Consumer<String> out = log != null ? log : NO_LOG;

        // A spec that no longer matches the table would otherwise fail deep inside evaluation with
        // a message about a missing decision key. Say what is actually wrong, before running anything.
        List<TestSpecValidator.Problem> problems = TestSpecValidator.validate(dmnXml, yamlContent);
        for (TestSpecValidator.Problem problem : problems) {
            out.accept("  " + problem.severity + " " + problem.message);
        }
        if (TestSpecValidator.hasErrors(problems)) {
            StringBuilder why = new StringBuilder("the test cases do not match the tables: ");
            boolean first = true;
            for (TestSpecValidator.Problem problem : problems) {
                if (problem.severity == TestSpecValidator.Problem.Severity.ERROR) {
                    why.append(first ? "" : "; ").append(problem.message);
                    first = false;
                }
            }
            throw new IllegalStateException(why.toString());
        }

        TestSpec spec = TestSpecReader.read(yamlContent);

        DmnEvaluator evaluator = new DmnEvaluator();
        evaluator.setCollectCoverage(true);

        int totalTests = spec.caseCount();
        out.accept("Found " + totalTests + " test cases across " + spec.decisions.size() + " decisions.");

        List<TestResult> results = new ArrayList<>();
        int index = 0;
        Map<String, Integer> setsSeen = new java.util.HashMap<>();
        for (DecisionTests decision : spec.decisions) {
            // A table may have several test sets (alone, with part of its chain, with all of it);
            // numbering them keeps every case's name unique.
            long sets = spec.decisions.stream().filter(d -> d.decision.equals(decision.decision)).count();
            int set = setsSeen.merge(decision.decision, 1, Integer::sum);
            String label = sets > 1 ? decision.decision + " #" + set : decision.decision;
            for (TestCase testCase : decision.cases) {
                index++;
                String testName = testCase.scenario == null || testCase.scenario.trim().isEmpty()
                        ? label
                        : label + " / " + testCase.scenario;
                out.accept("Running test " + index + "/" + totalTests + ": " + testName);

                long startTime = System.currentTimeMillis();
                boolean passed = false;
                String errorMessage = null;
                try {
                    evaluate(evaluator, dmnXml, decision, testCase);
                    passed = true;
                    out.accept("  \u2713 PASSED");
                } catch (Throwable t) {
                    errorMessage = formatExceptionWithCauses(t);
                    out.accept("  \u2717 FAILED");
                    out.accept("    " + errorMessage);
                }
                results.add(new TestResult(testName, passed,
                        errorMessage, System.currentTimeMillis() - startTime));
            }
        }

        CoverageStatistics coverageStats = extractCoverageStatistics(dmnXml, evaluator);

        int passedTests = (int) results.stream().filter(r -> r.passed).count();
        int failedTests = totalTests - passedTests;
        int coveragePercent = totalTests > 0 ? (passedTests * 100) / totalTests : 0;

        return new TestStatistics(totalTests, passedTests, failedTests, coveragePercent, results, coverageStats);
    }

    /**
     * Evaluates one row and asserts every expected output, each against the table that gives it.
     *
     * <p>Results are read the way DMN gives them: no rule matched gives nothing, one rule its value, and
     * several (COLLECT, RULE ORDER, OUTPUT ORDER) every value in order — which a list cell, [A, B], expects.
     */
    private void evaluate(DmnEvaluator evaluator, String dmnXml,
                          DecisionTests decision, TestCase testCase) {
        // What the block supplies decides what runs: a supplied value replaces the table that would
        // give it, so the same table can be tested alone or with any length of its chain.
        String scoped = limitium.art.camunda.evaluator.testspec.ChainScope.cutXml(dmnXml, decision.decision, decision.inputColumns);
        var dec = evaluator.loadRuleFromString(scoped, decision.decision);
        var vars = Variables.createVariables();
        testCase.inputsAsMap(decision.inputColumns).forEach(vars::putValue);
        var result = dec.evaluate(vars);

        // A test of a chain may also expect what tables on the way give: each such expectation is
        // checked against the table that gives it, evaluated with the same values.
        Map<String, String> chainOutputs = new java.util.LinkedHashMap<>(
                limitium.art.camunda.evaluator.testspec.ChainScope.of(dmnXml, decision.decision, decision.inputColumns).outputs);
        chainOutputs.values().removeIf(decision.decision::equals);
        Map<String, org.camunda.bpm.dmn.engine.DmnDecisionResult> given = new java.util.HashMap<>();
        Map<String, String> names = new java.util.HashMap<>();
        given.put(decision.decision, result);
        names.put(decision.decision, dec.name());
        for (int i = 0; i < decision.outputColumns.size(); i++) {
            String output = decision.outputColumns.get(i);
            String giver = chainOutputs.getOrDefault(output, decision.decision);
            if (!given.containsKey(giver)) {
                var upstream = evaluator.loadRuleFromString(scoped, giver);
                names.put(giver, upstream.name());
                given.put(giver, upstream.evaluate(vars));
            }
            check(testCase.expected.get(i), outputOf(given.get(giver), output), output, names.get(giver));
        }
    }

    /** No rule of the table matched, so it gave nothing. */
    private static final Object NO_MATCH = new Object();

    /** What a table gave for one output: nothing, one value, or every matched rule's value in order. */
    private static Object outputOf(org.camunda.bpm.dmn.engine.DmnDecisionResult result, String output) {
        List<Object> values = new ArrayList<>();
        for (Map<String, Object> entries : result.getResultList()) {
            // A one-output table answers under its own name, whatever the column calls it.
            values.add(entries.containsKey(output) || entries.size() != 1 ? entries.get(output) : entries.values().iterator().next());
        }
        if (values.isEmpty()) {
            return NO_MATCH;
        }
        return values.size() == 1 ? values.get(0) : values;
    }

    /** One expectation against what the table gave, failing in words: what was expected, what came. */
    private void check(Object expected, Object actual, String output, String table) {
        if (actual == NO_MATCH) {
            if (expected == null) {
                return;
            }
            throw new AssertionError(output + ": expected " + shown(expected) + ", but no rule of " + table + " matched");
        }
        if (expected instanceof List<?> && !(actual instanceof List<?>)) {
            actual = Collections.singletonList(actual);
        }
        if (!(expected instanceof List<?>) && actual instanceof List<?> many) {
            throw new AssertionError(output + ": expected " + shown(expected) + ", but " + many.size() + " rules of " + table
                    + " matched, giving " + shown(actual) + " — expect them all as a list, like [A, B]");
        }
        try {
            assertEqualsNormalized(expected, actual);
        } catch (AssertionError e) {
            throw new AssertionError(output + ": expected " + shown(expected) + ", " + table + " gave " + shown(actual));
        }
    }

    private static String shown(Object value) {
        if (value == null) {
            return "nothing";
        }
        if (value instanceof List<?> list) {
            List<String> parts = new ArrayList<>();
            list.forEach(v -> parts.add(v == null ? "nothing" : String.valueOf(v)));
            return "[" + String.join(", ", parts) + "]";
        }
        return String.valueOf(value);
    }

    private void assertEqualsNormalized(Object expected, Object actual) {
        if (expected instanceof List<?> eList && actual instanceof List<?> aList) {
            if (eList.size() != aList.size()) {
                throw new AssertionError("List sizes differ: expected=" + eList.size() + ", actual=" + aList.size());
            }
            for (int i = 0; i < eList.size(); i++) {
                assertEqualsNormalized(eList.get(i), aList.get(i));
            }
            return;
        }
        if (expected instanceof Number && actual instanceof Number) {
            BigDecimal e = new BigDecimal(expected.toString());
            BigDecimal a = new BigDecimal(actual.toString());
            if (e.compareTo(a) != 0) {
                throw new AssertionError("Numeric values differ: expected=" + e + ", actual=" + a);
            }
            return;
        }
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError("Values differ: expected=" + expected + ", actual=" + actual);
        }
    }

    /** Builds a concise cause chain, so a parse error reports its root rather than a bare wrapper. */
    public static String formatExceptionWithCauses(Throwable throwable) {
        if (throwable == null) return "Unknown error";
        StringBuilder sb = new StringBuilder();
        Throwable current = throwable;
        for (int depth = 0; current != null && depth < 6; depth++) {
            if (depth > 0) sb.append(" -> ");
            sb.append(current.getClass().getSimpleName());
            String message = current.getMessage();
            if (message != null && !message.isBlank()) {
                sb.append(": ").append(message);
            }
            current = current.getCause();
        }
        return sb.toString();
    }

    private CoverageStatistics extractCoverageStatistics(String dmnXml, DmnEvaluator evaluator) {
        List<DmnEvaluator.CoverageEvent> coverageEvents = evaluator.getCoverageEvents();
        List<String> decisions = evaluator.loadAllDecisionKeysFromString(dmnXml);

        Map<String, DecisionCoverage> decisionCoverages = new LinkedHashMap<>();
        int totalRules = 0;
        int coveredRules = 0;

        for (String decision : decisions) {
            List<DmnEvaluator.RuleInfo> rules;
            try {
                rules = evaluator.loadAllRulesFromString(dmnXml, decision);
            } catch (RuntimeException e) {
                // A literal-expression decision or an empty decision table has no rules to cover.
                // loadAllRulesFromString throws for both, so skip rather than failing the whole run.
                continue;
            }
            totalRules += rules.size();

            Set<String> covered = new HashSet<>();
            for (DmnEvaluator.CoverageEvent evt : coverageEvents) {
                if (evt.decisionKey.equals(decision)) {
                    covered.add(evt.ruleId);
                }
            }
            coveredRules += covered.size();

            List<String> uncovered = new ArrayList<>();
            for (DmnEvaluator.RuleInfo rule : rules) {
                if (!covered.contains(rule.ruleId)) {
                    uncovered.add(rule.ruleId);
                }
            }

            double coverage = rules.isEmpty() ? 1.0 : ((double) covered.size() / rules.size());
            decisionCoverages.put(decision, new DecisionCoverage(rules.size(), covered.size(), coverage, uncovered));
        }

        double overallCoverage = totalRules == 0 ? 1.0 : ((double) coveredRules / totalRules);
        return new CoverageStatistics(totalRules, coveredRules, overallCoverage, decisionCoverages);
    }
}
