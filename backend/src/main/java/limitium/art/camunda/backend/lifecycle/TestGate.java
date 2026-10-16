package limitium.art.camunda.backend.lifecycle;

import limitium.art.camunda.backend.git.GitRuleStore;
import limitium.art.camunda.backend.model.TestResult;
import limitium.art.camunda.backend.model.TestStatistics;
import limitium.art.camunda.backend.service.TestExecutionService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every domain's tests at one commit. The one gate behind landing (every step of the chain), making
 * a release, and deploying: the same check, so "green" means the same thing everywhere.
 *
 * <p>A commit never changes, so neither does its verdict — but it is computed afresh each time,
 * because "the tests were green" must never rest on a record someone could have written by hand.
 */
public class TestGate {

    /** The tests of every domain at one commit. */
    public static class Report {
        public String commit;
        public boolean passed = true;
        public int total;
        public int failed;
        public List<DomainTests> domains = new ArrayList<>();

        /** A compact record for a version's annotation. */
        public Map<String, Object> summary() {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("commit", commit);
            s.put("passed", passed);
            s.put("total", total);
            s.put("failed", failed);
            Map<String, Object> per = new LinkedHashMap<>();
            for (DomainTests d : domains) {
                per.put(d.domain, d.noTests ? "no tests" : d.passed + "/" + d.total);
            }
            s.put("domains", per);
            return s;
        }
    }

    public static class DomainTests {
        public String domain;
        public int total;
        public int passed;
        public boolean noTests;
        /** The spec could not run at all (does not match the table, does not parse). */
        public String error;
        public List<Map<String, String>> failures = new ArrayList<>();
    }

    public static class NoSuchCommit extends RuntimeException {
        public NoSuchCommit(String revision) {
            super("there is no commit " + revision);
        }
    }

    private final GitRuleStore store;
    private final TestExecutionService tests;

    public TestGate(GitRuleStore store, TestExecutionService tests) {
        this.store = store;
        this.tests = tests;
    }

    /** Results already worked out, per commit: a commit never changes, so neither do its tests. */
    private final java.util.Map<String, Report> shown = java.util.Collections.synchronizedMap(
            new java.util.LinkedHashMap<String, Report>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<String, Report> eldest) {
                    return size() > 500;
                }
            });

    /**
     * For showing a result (a screen asking "are this version's tests green?"): answered from memory
     * when this commit was run before. Never used to let something through — landing, releasing and
     * deploying always {@link #run} afresh.
     */
    public Report runForDisplay(String revision) throws IOException {
        String commit = store.resolve(revision);
        if (commit == null) {
            throw new NoSuchCommit(revision);
        }
        Report known = shown.get(commit);
        if (known != null) {
            return known;
        }
        Report fresh = run(commit);
        shown.put(commit, fresh);
        return fresh;
    }

    public Report run(String revision) throws IOException {
        String commit = store.resolve(revision);
        if (commit == null) {
            throw new NoSuchCommit(revision);
        }
        Report report = new Report();
        report.commit = commit;
        for (String domain : domainsAt(commit)) {
            DomainTests d = new DomainTests();
            d.domain = domain;
            byte[] dmn = store.readAt(commit, "dmn/" + domain + ".dmn");
            byte[] yaml = store.readAt(commit, "yaml/" + domain + ".yaml");
            String spec = yaml == null ? "" : new String(yaml, StandardCharsets.UTF_8);
            if (spec.isBlank()) {
                d.noTests = true;
            } else {
                try {
                    TestStatistics stats = tests.runYamlTests(new String(dmn, StandardCharsets.UTF_8), spec, null);
                    d.total = stats.totalTests;
                    d.passed = stats.passedTests;
                    d.noTests = stats.totalTests == 0;
                    for (TestResult r : stats.results) {
                        if (!r.passed) {
                            Map<String, String> f = new LinkedHashMap<>();
                            f.put("name", r.testName);
                            f.put("error", r.errorMessage);
                            d.failures.add(f);
                        }
                    }
                } catch (RuntimeException e) {
                    d.error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                }
            }
            report.total += d.total;
            report.failed += d.failures.size() + (d.error == null ? 0 : 1);
            if (d.error != null || !d.failures.isEmpty()) {
                report.passed = false;
            }
            report.domains.add(d);
        }
        return report;
    }

    public List<String> domainsAt(String commit) throws IOException {
        List<String> domains = new ArrayList<>();
        for (String file : store.filesAt(commit, "dmn")) {
            if (file.endsWith(".dmn")) {
                domains.add(file.substring(0, file.length() - 4));
            }
        }
        return domains;
    }
}
