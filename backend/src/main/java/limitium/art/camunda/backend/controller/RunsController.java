package limitium.art.camunda.backend.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import limitium.art.camunda.backend.model.*;
import limitium.art.camunda.backend.service.TestExecutionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

@RestController
@RequestMapping("/api/runs")
public class RunsController {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private ExecutorService executorService;

    @Autowired
    private TestExecutionService testExecutionService;

    /**
     * Runs live under the mounted data root, not /tmp, so history survives a restart and a run's
     * DMN snapshot stays available as a diff source.
     */
    @Autowired
    private Path runsDir;

    @PostMapping
    public ResponseEntity<Map<String, Object>> createRun(
            @RequestParam("fileName") String fileName,
            @RequestParam("dmnContent") MultipartFile dmnContent,
            @RequestParam("yamlContent") MultipartFile yamlContent) {

        if (fileName == null) {
            return ResponseEntity.badRequest().body(Collections.singletonMap("error", "fileName is required"));
        }
        if (dmnContent == null || yamlContent == null) {
            return ResponseEntity.badRequest().body(Collections.singletonMap("error", "dmnContent and yamlContent are required"));
        }

        String runId = UUID.randomUUID().toString();
        Path runDir = runsDir.resolve(fileName).resolve(runId);

        try {
            Files.createDirectories(runDir);

            String dmnXml = new String(dmnContent.getBytes(), StandardCharsets.UTF_8);
            String yamlText = new String(yamlContent.getBytes(), StandardCharsets.UTF_8);

            Path dmnPath = runDir.resolve(fileName + ".dmn");
            Path yamlPath = runDir.resolve(fileName + ".yaml");
            Files.writeString(dmnPath, dmnXml);
            Files.writeString(yamlPath, yamlText);

            Path statusFile = runDir.resolve("status.json");
            Path logFile = runDir.resolve("log.txt");
            Path resultFile = runDir.resolve("result.txt");

            Files.writeString(logFile, "Test execution starting...\n");

            Map<String, Object> status = new LinkedHashMap<>();
            status.put("id", runId);
            status.put("fileName", fileName);
            status.put("startedAt", Instant.now().toString());
            status.put("state", "running");
            // Content hashes let a release prove that a passing run matches the content being tagged.
            status.put("dmnSha256", sha256(dmnXml));
            status.put("yamlSha256", sha256(yamlText));
            writeJson(statusFile, status);

            // The response gets its own copy: the run below keeps changing `status` on another thread.
            Map<String, Object> started = new LinkedHashMap<>(status);
            executorService.submit(() -> {
                Consumer<String> log = line -> appendLine(logFile, line);
                try {
                    log.accept("Loading DMN and YAML files...");

                    TestStatistics stats = testExecutionService.runYamlTests(dmnXml, yamlText, log);

                    StringBuilder summary = new StringBuilder("\nTest execution completed.\nResults:\n");
                    for (TestResult result : stats.results) {
                        summary.append(result.passed ? "OK: " : "FAIL: ").append(result.testName);
                        if (!result.passed) {
                            summary.append(" -> ").append(result.errorMessage);
                        }
                        summary.append('\n');
                    }
                    Files.writeString(logFile, summary.toString(), StandardOpenOption.APPEND);

                    Files.writeString(resultFile, renderResult(runId, fileName, status, stats, logFile));

                    status.put("state", "finished");
                    status.put("finishedAt", Instant.now().toString());
                    status.put("result", stats.failedTests > 0 ? "failed" : "passed");
                    status.put("coverage", coverageSummary(stats));
                    // Structured per case, so the editor can mark the failing row rather than
                    // asking someone to read a log.
                    List<Map<String, Object>> cases = new ArrayList<>();
                    for (TestResult result : stats.results) {
                        Map<String, Object> one = new LinkedHashMap<>();
                        one.put("name", result.testName);
                        one.put("passed", result.passed);
                        one.put("error", result.passed ? null : result.errorMessage);
                        cases.add(one);
                    }
                    status.put("tests", cases);
                } catch (Exception e) {
                    String detailed = TestExecutionService.formatExceptionWithCauses(e);
                    try {
                        Files.writeString(logFile, "Test execution failed: " + detailed + "\n", StandardOpenOption.APPEND);
                        Files.writeString(resultFile, "ERROR: " + detailed + "\n");
                    } catch (IOException ignored) {
                        // nothing useful to do if the log itself cannot be written
                    }
                    status.put("state", "failed");
                    status.put("error", detailed);
                    status.put("result", "failed");
                    status.put("coverage", emptyCoverage());
                } finally {
                    writeJson(statusFile, status);
                }
            });

            return ResponseEntity.ok(started);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Collections.singletonMap("error", "too many test runs are waiting; try again in a moment"));
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Collections.singletonMap("error", "Failed to create run: " + e.getMessage()));
        }
    }

    @GetMapping("/{id}/status")
    public ResponseEntity<Resource> getRunStatus(@PathVariable String id) {
        return serve(findRunFile(id, "status.json"), MediaType.APPLICATION_JSON);
    }

    @GetMapping("/{id}/log")
    public ResponseEntity<Resource> getRunLog(@PathVariable String id) {
        return serve(findRunFile(id, "log.txt"), MediaType.TEXT_PLAIN);
    }

    @GetMapping("/{id}/result")
    public ResponseEntity<Resource> getRunResult(@PathVariable String id) {
        return serve(findRunFile(id, "result.txt"), MediaType.TEXT_PLAIN);
    }

    @GetMapping("/{id}/dmn")
    public ResponseEntity<Resource> getRunDmn(@PathVariable String id) {
        return serve(findRunFileBySuffix(id, ".dmn"), MediaType.parseMediaType("application/xml"));
    }

    @GetMapping("/{id}/yaml")
    public ResponseEntity<Resource> getRunYaml(@PathVariable String id) {
        return serve(findRunFileBySuffix(id, ".yaml"), MediaType.TEXT_PLAIN);
    }

    @GetMapping("/file/{fileName}")
    public ResponseEntity<List<Map<String, Object>>> listRunsForFile(@PathVariable String fileName) {
        Path fileDir = runsDir.resolve(fileName);
        if (Files.notExists(fileDir)) {
            return ResponseEntity.ok(new ArrayList<>());
        }
        try (var stream = Files.list(fileDir)) {
            List<Map<String, Object>> runs = new ArrayList<>();
            stream.filter(Files::isDirectory).forEach(runDir -> {
                Path statusFile = runDir.resolve("status.json");
                if (Files.exists(statusFile)) {
                    try {
                        runs.add(MAPPER.readValue(statusFile.toFile(), Map.class));
                    } catch (Exception ignored) {
                        // a half-written status file shouldn't hide the rest of the history
                    }
                }
            });
            runs.sort(Comparator.comparing(
                    (Map<String, Object> r) -> String.valueOf(r.get("startedAt"))).reversed());
            return ResponseEntity.ok(runs);
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    // --- run lookup -------------------------------------------------------------------------

    /** Run ids are unique, but they live under a per-file directory, so a lookup scans one level. */
    private Path findRunDir(String runId) {
        if (Files.notExists(runsDir)) return null;
        try (var stream = Files.list(runsDir)) {
            return stream.filter(Files::isDirectory)
                    .map(fileDir -> fileDir.resolve(runId))
                    .filter(Files::isDirectory)
                    .findFirst()
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    private Path findRunFile(String runId, String fileName) {
        Path runDir = findRunDir(runId);
        if (runDir == null) return null;
        Path file = runDir.resolve(fileName);
        return Files.exists(file) ? file : null;
    }

    private Path findRunFileBySuffix(String runId, String suffix) {
        Path runDir = findRunDir(runId);
        if (runDir == null) return null;
        try (var stream = Files.list(runDir)) {
            return stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(suffix))
                    .findFirst()
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    private ResponseEntity<Resource> serve(Path file, MediaType contentType) {
        if (file == null || Files.notExists(file)) {
            return ResponseEntity.notFound().build();
        }
        try {
            return ResponseEntity.ok()
                    .contentType(contentType)
                    .body(new InputStreamResource(Files.newInputStream(file)));
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    // --- output rendering -------------------------------------------------------------------

    private Map<String, Object> coverageSummary(TestStatistics stats) {
        Map<String, Object> coverage = new LinkedHashMap<>();
        coverage.put("totalTests", stats.totalTests);
        coverage.put("passedTests", stats.passedTests);
        coverage.put("failedTests", stats.failedTests);
        coverage.put("testCoveragePercent", stats.coveragePercent);
        coverage.put("dmnTotalRules", stats.coverageStats.totalRules);
        coverage.put("dmnCoveredRules", stats.coverageStats.coveredRules);
        coverage.put("dmnCoveragePercent", Math.round(stats.coverageStats.coveragePercent * 100));
        return coverage;
    }

    private Map<String, Object> emptyCoverage() {
        Map<String, Object> coverage = new LinkedHashMap<>();
        coverage.put("totalTests", 0);
        coverage.put("passedTests", 0);
        coverage.put("failedTests", 1);
        coverage.put("testCoveragePercent", 0);
        coverage.put("dmnTotalRules", 0);
        coverage.put("dmnCoveredRules", 0);
        coverage.put("dmnCoveragePercent", 0);
        return coverage;
    }

    private String renderResult(String runId, String fileName, Map<String, Object> status,
                                TestStatistics stats, Path logFile) {
        StringBuilder result = new StringBuilder();
        result.append("runId: ").append(runId).append("\n");
        result.append("fileName: ").append(fileName).append("\n");
        result.append("startedAt: ").append(status.get("startedAt")).append("\n");
        result.append("finishedAt: ").append(Instant.now()).append("\n");
        result.append("status: ").append(stats.failedTests > 0 ? "failed" : "passed").append("\n");
        result.append("testResults:\n");
        result.append("  totalTests: ").append(stats.totalTests).append("\n");
        result.append("  passedTests: ").append(stats.passedTests).append("\n");
        result.append("  failedTests: ").append(stats.failedTests).append("\n");
        result.append("  testCoveragePercent: ").append(stats.coveragePercent).append("\n");
        result.append("  results:\n");
        for (TestResult testResult : stats.results) {
            result.append("    - testName: \"").append(testResult.testName).append("\"\n");
            result.append("      status: ").append(testResult.passed ? "passed" : "failed").append("\n");
            result.append("      executionTime: ").append(testResult.executionTimeMs).append("ms\n");
            if (!testResult.passed && testResult.errorMessage != null) {
                result.append("      error: \"").append(testResult.errorMessage).append("\"\n");
            }
        }
        result.append("dmnCoverage:\n");
        result.append("  totalRules: ").append(stats.coverageStats.totalRules).append("\n");
        result.append("  coveredRules: ").append(stats.coverageStats.coveredRules).append("\n");
        result.append("  coveragePercent: ").append(String.format("%.2f", stats.coverageStats.coveragePercent * 100)).append("\n");
        result.append("  decisions:\n");
        for (Map.Entry<String, DecisionCoverage> entry : stats.coverageStats.decisions.entrySet()) {
            DecisionCoverage dc = entry.getValue();
            result.append("    ").append(entry.getKey()).append(":\n");
            result.append("      totalRules: ").append(dc.totalRules).append("\n");
            result.append("      coveredRules: ").append(dc.coveredRules).append("\n");
            result.append("      coverage: ").append(String.format("%.2f", dc.coverage * 100)).append("\n");
            if (!dc.uncoveredRules.isEmpty()) {
                result.append("      uncoveredRules:\n");
                for (String ruleId : dc.uncoveredRules) {
                    result.append("        - ").append(ruleId).append("\n");
                }
            }
        }
        result.append("log: |\n");
        try {
            for (String line : Files.readString(logFile).split("\n")) {
                result.append("  ").append(line).append("\n");
            }
        } catch (IOException e) {
            result.append("  Error reading log file\n");
        }
        return result.toString();
    }

    // --- io helpers -------------------------------------------------------------------------

    /** Appends one line. The previous implementation truncated on every write, so only the last line survived. */
    private void appendLine(Path logFile, String line) {
        try {
            Files.writeString(logFile, line + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // progress logging must never break the run
        }
    }

    private void writeJson(Path file, Map<String, Object> data) {
        try {
            // Polled while the run writes it: swapped in whole, so a poll never reads half a status.
            limitium.art.camunda.backend.service.VersionedFiles.writeAtomically(file,
                    MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(new LinkedHashMap<>(data)));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private String sha256(String content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
