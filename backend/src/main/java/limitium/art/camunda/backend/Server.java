package limitium.art.camunda.backend;

import io.javalin.Javalin;
import io.javalin.http.UploadedFile;
import io.javalin.config.JavalinConfig;
import limitium.art.camunda.evaluator.DmnEvaluator;
import org.camunda.bpm.dmn.engine.DmnDecisionResult;
import org.camunda.bpm.engine.variable.Variables;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;
import java.util.Comparator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Server {
    private static final Logger logger = LoggerFactory.getLogger(Server.class);
    private static Path DATA_ROOT = Paths.get("../data");
    private static Path DMN_DIR = DATA_ROOT.resolve("dmn");
    private static Path YAML_DIR = DATA_ROOT.resolve("yaml");
    private static Path RUNS_DIR = DATA_ROOT.resolve("runs");

    private static final ExecutorService EXEC = Executors.newCachedThreadPool();
    
    // Test result class to hold statistics
    public static class TestResult {
        public final String testName;
        public final boolean passed;
        public final String errorMessage;
        public final long executionTimeMs;
        
        public TestResult(String testName, boolean passed, String errorMessage, long executionTimeMs) {
            this.testName = testName;
            this.passed = passed;
            this.errorMessage = errorMessage;
            this.executionTimeMs = executionTimeMs;
        }
    }
    
    // Test statistics class
    public static class TestStatistics {
        public final int totalTests;
        public final int passedTests;
        public final int failedTests;
        public final int coveragePercent;
        public final List<TestResult> results;
        public final CoverageStatistics coverageStats;
        
        public TestStatistics(int totalTests, int passedTests, int failedTests, int coveragePercent, List<TestResult> results, CoverageStatistics coverageStats) {
            this.totalTests = totalTests;
            this.passedTests = passedTests;
            this.failedTests = failedTests;
            this.coveragePercent = coveragePercent;
            this.results = results;
            this.coverageStats = coverageStats;
        }
    }
    
    public static class CoverageStatistics {
        public final int totalRules;
        public final int coveredRules;
        public final double coveragePercent;
        public final Map<String, DecisionCoverage> decisions;
        
        public CoverageStatistics(int totalRules, int coveredRules, double coveragePercent, Map<String, DecisionCoverage> decisions) {
            this.totalRules = totalRules;
            this.coveredRules = coveredRules;
            this.coveragePercent = coveragePercent;
            this.decisions = decisions;
        }
    }
    
    public static class DecisionCoverage {
        public final int totalRules;
        public final int coveredRules;
        public final double coverage;
        public final List<String> uncoveredRules;
        
        public DecisionCoverage(int totalRules, int coveredRules, double coverage, List<String> uncoveredRules) {
            this.totalRules = totalRules;
            this.coveredRules = coveredRules;
            this.coverage = coverage;
            this.uncoveredRules = uncoveredRules;
        }
    }

    public static void main(String[] args) throws Exception {
        // Resolve from system properties if provided
        var rootProp = System.getProperty("dmn.data.root");
        if (rootProp != null && !rootProp.isEmpty()) {
            setDataRoot(Paths.get(rootProp));
        }
        int port = Integer.getInteger("dmn.port", 7070);
        var app = startServer(DATA_ROOT, port);
        app.events(ev -> ev.serverStopped(() -> EXEC.shutdownNow()));
    }

    public static Javalin startServer(Path dataRoot, int port) throws Exception {
        setDataRoot(dataRoot);
        Files.createDirectories(DMN_DIR);
        Files.createDirectories(YAML_DIR);
        Files.createDirectories(RUNS_DIR);

        var app = Javalin.create(Server::configure);

        // DMN CRUD
        app.get("/dmn", ctx -> {
            try (var stream = Files.list(DMN_DIR)) {
                var list = stream
                        .filter(p -> p.toString().endsWith(".dmn"))
                        .map(Server::fileInfo)
                        .collect(Collectors.toList());
                ctx.json(list);
            }
        });
        app.get("/dmn/{name}", ctx -> {
            Path file = DMN_DIR.resolve(ctx.pathParam("name"));
            if (Files.notExists(file)) { ctx.status(404); return; }
            ctx.contentType("application/xml").result(Files.newInputStream(file));
        });
        app.post("/dmn", ctx -> {
            UploadedFile file = ctx.uploadedFiles("file").stream().findFirst().orElse(null);
            if (file == null) { ctx.status(400).result("file is required"); return; }
            String name = Optional.ofNullable(ctx.queryParam("name")).orElse(file.filename());
            if (!name.endsWith(".dmn")) name = name + ".dmn";
            Path dst = DMN_DIR.resolve(name);
            try (InputStream in = file.content()) {
                Files.copy(in, dst, StandardCopyOption.REPLACE_EXISTING);
            }
            // Ensure paired YAML exists
            String base = name.substring(0, name.length() - 4);
            Path yaml = YAML_DIR.resolve(base + ".yaml");
            if (Files.notExists(yaml)) {
                Files.createDirectories(YAML_DIR);
                Files.write(yaml, "tests:\n".getBytes(), StandardOpenOption.CREATE_NEW);
            }
            ctx.json(fileInfo(dst));
        });
        app.put("/dmn/{name}", ctx -> {
            Path dst = DMN_DIR.resolve(ctx.pathParam("name"));
            if (ctx.body().isEmpty()) { ctx.status(400).result("Body required"); return; }
            Files.write(dst, ctx.bodyAsBytes(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            ctx.json(fileInfo(dst));
        });
        app.delete("/dmn/{name}", ctx -> {
            String fileName = ctx.pathParam("name");
            logger.info("DELETE /dmn/{} - Starting delete operation", fileName);
            logger.info("DELETE /dmn/{} - Current working directory: {}", fileName, System.getProperty("user.dir"));
            logger.info("DELETE /dmn/{} - DATA_ROOT: {}", fileName, DATA_ROOT);
            logger.info("DELETE /dmn/{} - DMN_DIR: {}", fileName, DMN_DIR);
            Path file = DMN_DIR.resolve(fileName);
            logger.info("DELETE /dmn/{} - DMN file path: {}", fileName, file);
            logger.info("DELETE /dmn/{} - DMN file absolute path: {}", fileName, file.toAbsolutePath());
            if (Files.exists(file)) {
                logger.info("DELETE /dmn/{} - DMN file exists, deleting", fileName);
                Files.delete(file);
                logger.info("DELETE /dmn/{} - DMN file deleted successfully", fileName);
                // Delete paired YAML
                String name = file.getFileName().toString();
                if (name.endsWith(".dmn")) {
                    String base = name.substring(0, name.length() - 4);
                    Path yaml = YAML_DIR.resolve(base + ".yaml");
                    logger.info("DELETE /dmn/{} - YAML file path: {}", fileName, yaml);
                    Files.deleteIfExists(yaml);
                    logger.info("DELETE /dmn/{} - YAML file deleted successfully", fileName);
                    
                    // Delete run folder for this file
                    Path runFolder = Paths.get("/tmp", "camunda-runs", base);
                    logger.info("DELETE /dmn/{} - Run folder path: {}", fileName, runFolder);
                    if (Files.exists(runFolder)) {
                        logger.info("DELETE /dmn/{} - Run folder exists, deleting recursively", fileName);
                        try (var stream = Files.walk(runFolder)) {
                            stream.sorted(Comparator.reverseOrder())
                                  .forEach(path -> {
                                      try {
                                          Files.deleteIfExists(path);
                                          logger.info("DELETE /dmn/{} - Deleted: {}", fileName, path);
                                      } catch (IOException e) {
                                          logger.warn("Failed to delete run file: {}", path, e);
                                      }
                                  });
                        } catch (IOException e) {
                            logger.warn("Failed to delete run folder: {}", runFolder, e);
                        }
                        logger.info("DELETE /dmn/{} - Run folder deleted successfully", fileName);
                    } else {
                        logger.info("DELETE /dmn/{} - Run folder does not exist", fileName);
                    }
                }
            } else {
                logger.info("DELETE /dmn/{} - DMN file does not exist", fileName);
            }
            logger.info("DELETE /dmn/{} - Delete operation completed", fileName);
            ctx.status(204);
        });

        // YAML CRUD
        app.get("/yaml", ctx -> {
            try (var stream = Files.list(YAML_DIR)) {
                var list = stream
                        .filter(p -> p.toString().endsWith(".yaml"))
                        .map(Server::fileInfo)
                        .collect(Collectors.toList());
                ctx.json(list);
            }
        });
        app.get("/yaml/{name}", ctx -> {
            Path file = YAML_DIR.resolve(ctx.pathParam("name"));
            if (Files.notExists(file)) { ctx.status(404); return; }
            ctx.contentType("text/plain").result(Files.newInputStream(file));
        });
        app.put("/yaml/{name}", ctx -> {
            Path dst = YAML_DIR.resolve(ctx.pathParam("name"));
            if (!dst.toString().endsWith(".yaml")) { ctx.status(400).result("Only .yaml supported"); return; }
            // Ensure paired DMN exists
            String name = dst.getFileName().toString();
            String base = name.substring(0, name.length() - 5);
            Path dmn = DMN_DIR.resolve(base + ".dmn");
            if (Files.notExists(dmn)) { ctx.status(404).result("Paired DMN not found: " + dmn.getFileName()); return; }
            // YAML must already exist (initialized by DMN creation)
            if (Files.notExists(dst)) { ctx.status(404).result("YAML not initialized. Create DMN first."); return; }
            if (ctx.body().isEmpty()) { ctx.status(400).result("Body required"); return; }
            Files.write(dst, ctx.bodyAsBytes(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            ctx.json(fileInfo(dst));
        });

        // Trigger test run - accepts file name and content
        app.post("/runs", ctx -> {
            String fileName = ctx.formParam("fileName");
            logger.info("POST /runs - fileName: {}", fileName);
            
            if (fileName == null) { 
                logger.error("POST /runs - ERROR: fileName is required");
                ctx.status(400).result("fileName is required"); 
                return; 
            }
            
            // Get DMN and YAML content from form data
            var dmnUpload = ctx.uploadedFiles("dmnContent").stream().findFirst().orElse(null);
            var yamlUpload = ctx.uploadedFiles("yamlContent").stream().findFirst().orElse(null);
            
            logger.info("POST /runs - dmnUpload: {}", dmnUpload != null ? "present" : "null");
            logger.info("POST /runs - yamlUpload: {}", yamlUpload != null ? "present" : "null");
            
            if (dmnUpload == null || yamlUpload == null) {
                System.out.println("POST /runs - ERROR: dmnContent and yamlContent are required");
                ctx.status(400).result("dmnContent and yamlContent are required"); 
                return;
            }

            // Generate run ID
            var runId = UUID.randomUUID().toString();
            System.out.println("POST /runs - Generated runId: " + runId);
            
            // Create run directory under /tmp/camunda-runs/filename/runId
            var tmpBaseDir = Paths.get("/tmp", "camunda-runs", fileName);
            var runDir = tmpBaseDir.resolve(runId);
            System.out.println("POST /runs - Creating directory: " + runDir);
            Files.createDirectories(runDir);
            System.out.println("POST /runs - Directory created successfully");
            
            // Save DMN and YAML files to run directory
            var dmnPath = runDir.resolve(fileName + ".dmn");
            var yamlPath = runDir.resolve(fileName + ".yaml");
            
            System.out.println("POST /runs - Saving DMN to: " + dmnPath);
            try (InputStream dmnIn = dmnUpload.content()) { 
                Files.copy(dmnIn, dmnPath, StandardCopyOption.REPLACE_EXISTING); 
                System.out.println("POST /runs - DMN saved successfully");
            }
            
            System.out.println("POST /runs - Saving YAML to: " + yamlPath);
            try (InputStream yamlIn = yamlUpload.content()) { 
                Files.copy(yamlIn, yamlPath, StandardCopyOption.REPLACE_EXISTING); 
                System.out.println("POST /runs - YAML saved successfully");
            }
            
            // Create status and log files
            var statusFile = runDir.resolve("status.json");
            var logFile = runDir.resolve("log.txt");
            var resultFile = runDir.resolve("result.txt");
            
            System.out.println("POST /runs - Creating status file: " + statusFile);
            var status = new LinkedHashMap<String, Object>();
            status.put("id", runId);
            status.put("fileName", fileName);
            status.put("startedAt", Instant.now().toString());
            status.put("state", "running");
            writeJson(statusFile, status);
            System.out.println("POST /runs - Status file created successfully");

            // Run tests asynchronously
            System.out.println("POST /runs - Submitting async task");
            EXEC.submit(() -> {
                logger.info("Async task started for runId: {}", runId);
                try {
                    logger.info("Running tests for runId: {}", runId);
                    TestStatistics stats = runYamlTestsAgainstDmn(dmnPath, yamlPath);
                    logger.info("Tests completed for runId: {}, total: {}, passed: {}, failed: {}", 
                               runId, stats.totalTests, stats.passedTests, stats.failedTests);
                    
                    // Generate log content from statistics
                    StringBuilder logBuilder = new StringBuilder();
                    for (TestResult result : stats.results) {
                        if (result.passed) {
                            logBuilder.append("OK: ").append(result.testName);
                        } else {
                            logBuilder.append("FAIL: ").append(result.testName)
                                     .append(" -> ").append(result.errorMessage);
                        }
                        logBuilder.append('\n');
                    }
                    String log = logBuilder.toString();
                    Files.write(logFile, log.getBytes());
                    logger.info("Log file written successfully for runId: {}", runId);
                    
                        // Create result file as YAML with detailed coverage statistics
                        var result = new StringBuilder();
                        result.append("runId: ").append(runId).append("\n");
                        result.append("fileName: ").append(fileName).append("\n");
                        result.append("startedAt: ").append(status.get("startedAt")).append("\n");
                        result.append("finishedAt: ").append(Instant.now().toString()).append("\n");
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
                            String decisionKey = entry.getKey();
                            DecisionCoverage decisionCoverage = entry.getValue();
                            result.append("    ").append(decisionKey).append(":\n");
                            result.append("      totalRules: ").append(decisionCoverage.totalRules).append("\n");
                            result.append("      coveredRules: ").append(decisionCoverage.coveredRules).append("\n");
                            result.append("      coverage: ").append(String.format("%.2f", decisionCoverage.coverage * 100)).append("\n");
                            if (!decisionCoverage.uncoveredRules.isEmpty()) {
                                result.append("      uncoveredRules:\n");
                                for (String ruleId : decisionCoverage.uncoveredRules) {
                                    result.append("        - ").append(ruleId).append("\n");
                                }
                            }
                        }
                        result.append("log: |\n");
                        // Indent log content for YAML
                        String[] logLines = log.split("\n");
                        for (String line : logLines) {
                            result.append("  ").append(line).append("\n");
                        }
                        Files.write(resultFile, result.toString().getBytes());
                    logger.info("Result file written successfully for runId: {}", runId);
                    
                    status.put("state", "finished");
                    status.put("finishedAt", Instant.now().toString());
                    status.put("result", stats.failedTests > 0 ? "failed" : "passed");
                    
                        // Add coverage information to status
                        var coverage = new LinkedHashMap<String, Object>();
                        coverage.put("totalTests", stats.totalTests);
                        coverage.put("passedTests", stats.passedTests);
                        coverage.put("failedTests", stats.failedTests);
                        coverage.put("testCoveragePercent", stats.coveragePercent);
                        coverage.put("dmnTotalRules", stats.coverageStats.totalRules);
                        coverage.put("dmnCoveredRules", stats.coverageStats.coveredRules);
                        coverage.put("dmnCoveragePercent", Math.round(stats.coverageStats.coveragePercent * 100));
                        status.put("coverage", coverage);
                    
                    logger.info("Updating status to finished for runId: {}", runId);
                    logger.info("Final status for runId: {} -> state: {}, result: {}, failedTests: {}", 
                               runId, status.get("state"), status.get("result"), stats.failedTests);
                } catch (Exception e) {
                    logger.error("ERROR in async task for runId: {} - {}", runId, e.toString(), e);
                    try { 
                        Files.write(logFile, ("ERROR: " + e.toString()).getBytes()); 
                        Files.write(resultFile, ("ERROR: " + e.toString()).getBytes());
                    } catch (IOException ignored) {}
                    status.put("state", "failed");
                    status.put("error", e.toString());
                    
                        // Add coverage information for failed runs too
                        var coverage = new LinkedHashMap<String, Object>();
                        coverage.put("totalTests", 0);
                        coverage.put("passedTests", 0);
                        coverage.put("failedTests", 1);
                        coverage.put("testCoveragePercent", 0);
                        coverage.put("dmnTotalRules", 0);
                        coverage.put("dmnCoveredRules", 0);
                        coverage.put("dmnCoveragePercent", 0);
                        status.put("coverage", coverage);
                    status.put("result", "failed");
                } finally {
                    writeJson(statusFile, status);
                    logger.info("Final status written for runId: {}", runId);
                }
            });

            logger.info("POST /runs - Returning status for runId: {}", runId);
            ctx.json(status);
        });

        // Get run status
        app.get("/runs/{id}/status", ctx -> {
            String runId = ctx.pathParam("id");
            System.out.println("GET /runs/" + runId + "/status - Looking for status file");
            // Try to find the run in any /tmp/filename/runId directory
            Path statusFile = findRunStatusFile(runId);
            System.out.println("GET /runs/" + runId + "/status - Found status file: " + statusFile);
            if (statusFile == null || Files.notExists(statusFile)) { 
                System.out.println("GET /runs/" + runId + "/status - ERROR: Status file not found");
                ctx.status(404).result("Run not found"); 
                return; 
            }
            System.out.println("GET /runs/" + runId + "/status - Returning status file content");
            ctx.contentType("application/json").result(Files.newInputStream(statusFile));
        });

        // Get run log
        app.get("/runs/{id}/log", ctx -> {
            String runId = ctx.pathParam("id");
            System.out.println("GET /runs/" + runId + "/log - Looking for log file");
            Path logFile = findRunLogFile(runId);
            System.out.println("GET /runs/" + runId + "/log - Found log file: " + logFile);
            if (logFile == null || Files.notExists(logFile)) { 
                System.out.println("GET /runs/" + runId + "/log - ERROR: Log file not found");
                ctx.status(404).result("Log not found"); 
                return; 
            }
            System.out.println("GET /runs/" + runId + "/log - Returning log file content");
            ctx.contentType("text/plain").result(Files.newInputStream(logFile));
        });

        // Get run result
        app.get("/runs/{id}/result", ctx -> {
            String runId = ctx.pathParam("id");
            System.out.println("GET /runs/" + runId + "/result - Looking for result file");
            Path resultFile = findRunResultFile(runId);
            System.out.println("GET /runs/" + runId + "/result - Found result file: " + resultFile);
            if (resultFile == null || Files.notExists(resultFile)) { 
                System.out.println("GET /runs/" + runId + "/result - ERROR: Result file not found");
                ctx.status(404).result("Result not found"); 
                return; 
            }
            System.out.println("GET /runs/" + runId + "/result - Returning result file content");
            ctx.contentType("text/plain").result(Files.newInputStream(resultFile));
        });

        // Get run DMN file
        app.get("/runs/{id}/dmn", ctx -> {
            String runId = ctx.pathParam("id");
            System.out.println("GET /runs/" + runId + "/dmn - Looking for DMN file");
            Path dmnFile = findRunDmnFile(runId);
            System.out.println("GET /runs/" + runId + "/dmn - Found DMN file: " + dmnFile);
            if (dmnFile == null || Files.notExists(dmnFile)) { 
                System.out.println("GET /runs/" + runId + "/dmn - ERROR: DMN file not found");
                ctx.status(404).result("DMN file not found"); 
                return; 
            }
            System.out.println("GET /runs/" + runId + "/dmn - Returning DMN file content");
            ctx.contentType("application/xml").result(Files.newInputStream(dmnFile));
        });

        // Get run YAML file
        app.get("/runs/{id}/yaml", ctx -> {
            String runId = ctx.pathParam("id");
            System.out.println("GET /runs/" + runId + "/yaml - Looking for YAML file");
            Path yamlFile = findRunYamlFile(runId);
            System.out.println("GET /runs/" + runId + "/yaml - Found YAML file: " + yamlFile);
            if (yamlFile == null || Files.notExists(yamlFile)) { 
                System.out.println("GET /runs/" + runId + "/yaml - ERROR: YAML file not found");
                ctx.status(404).result("YAML file not found"); 
                return; 
            }
            System.out.println("GET /runs/" + runId + "/yaml - Returning YAML file content");
            ctx.contentType("text/plain").result(Files.newInputStream(yamlFile));
        });

        // List runs for a specific file
        app.get("/runs/file/{fileName}", ctx -> {
            String fileName = ctx.pathParam("fileName");
            System.out.println("GET /runs/file/" + fileName + " - Listing runs for file");
            try {
                Path fileDir = Paths.get("/tmp", "camunda-runs", fileName);
                if (!Files.exists(fileDir)) {
                    System.out.println("GET /runs/file/" + fileName + " - File directory does not exist");
                    ctx.json(new ArrayList<>());
                    return;
                }
                
                List<Map<String, Object>> runs = new ArrayList<>();
                try (var stream = Files.list(fileDir)) {
                    stream.filter(Files::isDirectory)
                        .forEach(runDir -> {
                            try {
                                Path statusFile = runDir.resolve("status.json");
                                if (Files.exists(statusFile)) {
                                    var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                                    var status = mapper.readValue(statusFile.toFile(), Map.class);
                                    runs.add(status);
                                }
                            } catch (Exception e) {
                                System.out.println("Error reading status for run: " + runDir + " - " + e.getMessage());
                            }
                        });
                }
                
                // Sort by startedAt descending
                runs.sort((a, b) -> {
                    String startedA = (String) a.get("startedAt");
                    String startedB = (String) b.get("startedAt");
                    return startedB.compareTo(startedA);
                });
                
                System.out.println("GET /runs/file/" + fileName + " - Found " + runs.size() + " runs");
                ctx.json(runs);
            } catch (Exception e) {
                System.out.println("GET /runs/file/" + fileName + " - ERROR: " + e.getMessage());
                ctx.status(500).result("Error listing runs: " + e.getMessage());
            }
        });

        // Serve static assets manually
        app.get("/assets/{filename}", ctx -> {
            String path = ctx.path();
            System.out.println("Serving static asset: " + path);
            try {
                // Try container location first (when running in container with Jib)
                Path containerFile = Paths.get("/app/static" + path);
                if (Files.exists(containerFile)) {
                    String contentType = getContentType(path);
                    ctx.contentType(contentType).result(Files.newInputStream(containerFile));
                } else {
                    // Try the resources directory (for local development)
                    Path resourceFile = Paths.get("src/main/resources/static" + path);
                    if (Files.exists(resourceFile)) {
                        String contentType = getContentType(path);
                        ctx.contentType(contentType).result(Files.newInputStream(resourceFile));
                    } else {
                        ctx.status(404).result("File not found: " + path);
                    }
                }
            } catch (Exception e) {
                ctx.status(500).result("Error serving file: " + e.getMessage());
            }
        });

        // Serve frontend SPA - catch-all route for client-side routing (but not for static assets)
        app.get("/*", ctx -> {
            // Check if the request is for a static file that exists
            String path = ctx.path();
            System.out.println("Catch-all route hit for: " + path);
            if (path.startsWith("/api/") || path.startsWith("/dmn") || path.startsWith("/yaml") || path.startsWith("/runs") || 
                path.startsWith("/assets/") || path.endsWith(".js") || path.endsWith(".css") || path.endsWith(".png") || 
                path.endsWith(".jpg") || path.endsWith(".svg") || path.endsWith(".ico") || path.endsWith(".woff") || 
                path.endsWith(".woff2") || path.endsWith(".ttf") || path.endsWith(".eot")) {
                // Let API routes and static assets handle these
                System.out.println("Returning early for: " + path);
                return;
            }
            
            // For all other routes, serve index.html for SPA routing
            try {
                // Try container location first (when running in container with Jib)
                Path containerIndexFile = Paths.get("/app/static/index.html");
                if (Files.exists(containerIndexFile)) {
                    ctx.contentType("text/html").result(Files.newInputStream(containerIndexFile));
                } else {
                    // Try the build directory (for local development)
                    Path buildIndexFile = Paths.get("build/resources/static/index.html");
                    if (Files.exists(buildIndexFile)) {
                        ctx.contentType("text/html").result(Files.newInputStream(buildIndexFile));
                    } else {
                        ctx.status(404).result("Frontend not found");
                    }
                }
            } catch (Exception e) {
                ctx.status(500).result("Error serving frontend: " + e.getMessage());
            }
        });

        app.start(port);
        return app;
    }

    private static void setDataRoot(Path dataRoot) {
        DATA_ROOT = dataRoot;
        DMN_DIR = DATA_ROOT.resolve("dmn");
        YAML_DIR = DATA_ROOT.resolve("yaml");
        RUNS_DIR = DATA_ROOT.resolve("runs");
    }

    private static Map<String, Object> fileInfo(Path p) {
        try {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", p.getFileName().toString());
            m.put("size", Files.size(p));
            m.put("modified", Files.getLastModifiedTime(p).toInstant().toString());
            return m;
        } catch (IOException e) {
            return Collections.singletonMap("name", p.getFileName().toString());
        }
    }

    private static void writeJson(Path file, Map<String, Object> data) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
            Files.createDirectories(file.getParent());
            om.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), data);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    // Helper methods to find run files in /tmp/filename/runId structure
    private static Path findRunStatusFile(String runId) {
        System.out.println("findRunStatusFile - Looking for runId: " + runId);
        try {
            Path camundaRunsDir = Paths.get("/tmp", "camunda-runs");
            System.out.println("findRunStatusFile - Checking /tmp/camunda-runs directory: " + camundaRunsDir);
            if (!Files.exists(camundaRunsDir)) {
                System.out.println("findRunStatusFile - /tmp/camunda-runs directory does not exist");
                return null;
            }
            
            try (var stream = Files.list(camundaRunsDir)) {
                var result = stream.filter(Files::isDirectory)
                    .peek(dir -> System.out.println("findRunStatusFile - Checking directory: " + dir))
                    .map(fileNameDir -> {
                        Path statusPath = fileNameDir.resolve(runId).resolve("status.json");
                        System.out.println("findRunStatusFile - Checking status path: " + statusPath);
                        return statusPath;
                    })
                    .filter(Files::exists)
                    .findFirst()
                    .orElse(null);
                System.out.println("findRunStatusFile - Result: " + result);
                return result;
            }
        } catch (IOException e) {
            System.out.println("findRunStatusFile - IOException: " + e.getMessage());
        }
        return null;
    }

    private static Path findRunLogFile(String runId) {
        System.out.println("findRunLogFile - Looking for runId: " + runId);
        try {
            Path camundaRunsDir = Paths.get("/tmp", "camunda-runs");
            if (!Files.exists(camundaRunsDir)) {
                System.out.println("findRunLogFile - /tmp/camunda-runs directory does not exist");
                return null;
            }
            
            try (var stream = Files.list(camundaRunsDir)) {
                var result = stream.filter(Files::isDirectory)
                    .peek(dir -> System.out.println("findRunLogFile - Checking directory: " + dir))
                    .map(fileNameDir -> {
                        Path logPath = fileNameDir.resolve(runId).resolve("log.txt");
                        System.out.println("findRunLogFile - Checking log path: " + logPath);
                        return logPath;
                    })
                    .filter(Files::exists)
                    .findFirst()
                    .orElse(null);
                System.out.println("findRunLogFile - Result: " + result);
                return result;
            }
        } catch (IOException e) {
            System.out.println("findRunLogFile - IOException: " + e.getMessage());
        }
        return null;
    }

    private static Path findRunResultFile(String runId) {
        System.out.println("findRunResultFile - Looking for runId: " + runId);
        try {
            Path camundaRunsDir = Paths.get("/tmp", "camunda-runs");
            if (!Files.exists(camundaRunsDir)) {
                System.out.println("findRunResultFile - /tmp/camunda-runs directory does not exist");
                return null;
            }
            
            try (var stream = Files.list(camundaRunsDir)) {
                var result = stream.filter(Files::isDirectory)
                    .peek(dir -> System.out.println("findRunResultFile - Checking directory: " + dir))
                    .map(fileNameDir -> {
                        Path resultPath = fileNameDir.resolve(runId).resolve("result.txt");
                        System.out.println("findRunResultFile - Checking result path: " + resultPath);
                        return resultPath;
                    })
                    .filter(Files::exists)
                    .findFirst()
                    .orElse(null);
                System.out.println("findRunResultFile - Result: " + result);
                return result;
            }
        } catch (IOException e) {
            System.out.println("findRunResultFile - IOException: " + e.getMessage());
        }
        return null;
    }

    private static Path findRunDmnFile(String runId) {
        System.out.println("findRunDmnFile - Looking for runId: " + runId);
        try {
            Path camundaRunsDir = Paths.get("/tmp", "camunda-runs");
            if (!Files.exists(camundaRunsDir)) {
                System.out.println("findRunDmnFile - /tmp/camunda-runs directory does not exist");
                return null;
            }
            
            try (var stream = Files.list(camundaRunsDir)) {
                var result = stream.filter(Files::isDirectory)
                    .peek(dir -> System.out.println("findRunDmnFile - Checking directory: " + dir))
                    .flatMap(fileNameDir -> {
                        try {
                            return Files.list(fileNameDir.resolve(runId))
                                .filter(Files::isRegularFile)
                                .filter(path -> path.getFileName().toString().endsWith(".dmn"));
                        } catch (IOException e) {
                            return java.util.stream.Stream.empty();
                        }
                    })
                    .peek(path -> System.out.println("findRunDmnFile - Checking DMN path: " + path))
                    .filter(Files::exists)
                    .findFirst()
                    .orElse(null);
                System.out.println("findRunDmnFile - Result: " + result);
                return result;
            }
        } catch (IOException e) {
            System.out.println("findRunDmnFile - IOException: " + e.getMessage());
        }
        return null;
    }

    private static Path findRunYamlFile(String runId) {
        System.out.println("findRunYamlFile - Looking for runId: " + runId);
        try {
            Path camundaRunsDir = Paths.get("/tmp", "camunda-runs");
            if (!Files.exists(camundaRunsDir)) {
                System.out.println("findRunYamlFile - /tmp/camunda-runs directory does not exist");
                return null;
            }
            
            try (var stream = Files.list(camundaRunsDir)) {
                var result = stream.filter(Files::isDirectory)
                    .peek(dir -> System.out.println("findRunYamlFile - Checking directory: " + dir))
                    .flatMap(fileNameDir -> {
                        try {
                            return Files.list(fileNameDir.resolve(runId))
                                .filter(Files::isRegularFile)
                                .filter(path -> path.getFileName().toString().endsWith(".yaml"));
                        } catch (IOException e) {
                            return java.util.stream.Stream.empty();
                        }
                    })
                    .peek(path -> System.out.println("findRunYamlFile - Checking YAML path: " + path))
                    .filter(Files::exists)
                    .findFirst()
                    .orElse(null);
                System.out.println("findRunYamlFile - Result: " + result);
                return result;
            }
        } catch (IOException e) {
            System.out.println("findRunYamlFile - IOException: " + e.getMessage());
        }
        return null;
    }

    private static void configure(JavalinConfig cfg) {
        cfg.plugins.enableCors(cors -> cors.add(it -> it.anyHost()));
        
        // Serve static files from the static directory (checking multiple locations)
        try {
            // Try container location first (when running in container with Jib)
            Path containerStaticDir = Paths.get("/app/static");
            if (Files.exists(containerStaticDir)) {
                System.out.println("Configuring static files from: /app/static");
                cfg.staticFiles.add(staticFiles -> {
                    staticFiles.hostedPath = "/";
                    staticFiles.directory = "/app/static";
                    staticFiles.location = io.javalin.http.staticfiles.Location.EXTERNAL;
                });
            } else {
                // Try the build directory (for local development)
                Path buildStaticDir = Paths.get("build/resources/static");
                if (Files.exists(buildStaticDir)) {
                    System.out.println("Configuring static files from: " + buildStaticDir.toAbsolutePath());
                    cfg.staticFiles.add(staticFiles -> {
                        staticFiles.hostedPath = "/";
                        staticFiles.directory = "/static";
                        staticFiles.location = io.javalin.http.staticfiles.Location.CLASSPATH;
                    });
                } else {
                    System.out.println("No static files directory found");
                }
            }
        } catch (Exception e) {
            System.out.println("Error configuring static files: " + e.getMessage());
        }
    }

    // Extract coverage statistics from DMN evaluator
    private static CoverageStatistics extractCoverageStatistics(Path dmnPath, DmnEvaluator evaluator) throws Exception {
        List<DmnEvaluator.CoverageEvent> coverageEvents = evaluator.getCoverageEvents();
        List<String> decisions = evaluator.loadAllDecisionKeys(dmnPath);
        
        Map<String, DecisionCoverage> decisionCoverages = new LinkedHashMap<>();
        int totalRules = 0;
        int coveredRules = 0;
        
        for (String decision : decisions) {
            List<DmnEvaluator.RuleInfo> rules = evaluator.loadAllRules(dmnPath, decision);
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
            decisionCoverages.put(decision, new DecisionCoverage(
                rules.size(), 
                covered.size(), 
                coverage, 
                uncovered
            ));
        }
        
        double overallCoverage = totalRules == 0 ? 1.0 : ((double) coveredRules / totalRules);
        return new CoverageStatistics(totalRules, coveredRules, overallCoverage, decisionCoverages);
    }

    // Run YAML tests against DMN and return statistics
    @SuppressWarnings("unchecked")
    private static TestStatistics runYamlTestsAgainstDmn(Path dmnPath, Path yamlPath) throws Exception {
        List<Map<String, Object>> cases = parseYamlCases(yamlPath);
        List<TestResult> results = new ArrayList<>();
        
        // Create evaluator and enable coverage collection
        var evaluator = new DmnEvaluator();
        evaluator.setCollectCoverage(true);
        
        for (var tc : cases) {
            var decision = Objects.requireNonNull((String) tc.get("decision"), "decision missing");
            Map<String, Object> in;
            var inObj = tc.get("in");
            if (inObj instanceof Map<?, ?> inMap) {
                @SuppressWarnings("unchecked")
                var cast = (Map<String, Object>) inMap;
                in = cast;
            } else {
                in = Collections.emptyMap();
            }
            var expected = Objects.requireNonNull(tc.get("out"), "out missing");
            var desc = String.valueOf(tc.getOrDefault("description", ""));
            var testName = !desc.isEmpty() ? desc : decision;

            long startTime = System.currentTimeMillis();
            boolean passed = false;
            String errorMessage = null;
            
            try {
                var dec = evaluator.loadRule(dmnPath, decision);
                var vars = Variables.createVariables();
                in.forEach(vars::putValue);
                var result = dec.evaluate(vars);

                Object actual;
                if (expected instanceof List<?>) {
                    var out = new ArrayList<>();
                    for (var rr : result.getResultList()) {
                        out.add(extractSingleEntry(rr));
                    }
                    actual = out;
                } else {
                    actual = result.getSingleResult().getSingleEntry();
                }

                assertEqualsNormalized(expected, actual);
                passed = true;
            } catch (Throwable t) {
                errorMessage = t.getMessage();
                passed = false;
            }
            
            long executionTime = System.currentTimeMillis() - startTime;
            results.add(new TestResult(testName, passed, errorMessage, executionTime));
        }
        
        // Extract coverage statistics
        CoverageStatistics coverageStats = extractCoverageStatistics(dmnPath, evaluator);
        
        int totalTests = results.size();
        int passedTests = (int) results.stream().filter(r -> r.passed).count();
        int failedTests = totalTests - passedTests;
        int coveragePercent = totalTests > 0 ? (passedTests * 100) / totalTests : 0;
        
        return new TestStatistics(totalTests, passedTests, failedTests, coveragePercent, results, coverageStats);
    }

    private static List<Map<String, Object>> parseYamlCases(Path yamlPath) {
        try (var in = Files.newInputStream(yamlPath)) {
            var lo = new LoaderOptions();
            var yaml = new Yaml(new SafeConstructor(lo));
            var root = yaml.load(in);
            if (!(root instanceof Map<?, ?> mapRoot)) return Collections.emptyList();
            var testsNode = mapRoot.get("tests");
            if (testsNode instanceof List<?> tmp) {
                @SuppressWarnings("unchecked")
                var cast = (List<Map<String, Object>>) tmp;
                return cast;
            }
            return Collections.emptyList();
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse YAML: " + yamlPath, e);
        }
    }

    private static void assertEqualsNormalized(Object expected, Object actual) {
        if (expected instanceof List<?> && actual instanceof List<?>) {
            List<?> eList = (List<?>) expected;
            List<?> aList = (List<?>) actual;
            if (eList.size() != aList.size()) {
                throw new AssertionError("List sizes differ: expected=" + eList.size() + ", actual=" + aList.size());
            }
            for (int i = 0; i < eList.size(); i++) {
                assertEqualsNormalized(eList.get(i), aList.get(i));
            }
            return;
        }
        if (expected instanceof Number && actual instanceof Number) {
            java.math.BigDecimal e = new java.math.BigDecimal(expected.toString());
            java.math.BigDecimal a = new java.math.BigDecimal(actual.toString());
            if (e.compareTo(a) != 0) {
                throw new AssertionError("Numeric values differ: expected=" + e + ", actual=" + a);
            }
            return;
        }
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError("Values differ: expected=" + expected + ", actual=" + actual);
        }
    }

    private static Object extractSingleEntry(Object entry) {
        if (entry == null) return null;
        try {
            java.lang.reflect.Method m = entry.getClass().getMethod("getSingleEntry");
            return m.invoke(entry);
        } catch (Exception ignore) {
            // Fall through
        }
        if (entry instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) entry;
            if (map.isEmpty()) return null;
            return map.values().iterator().next();
        }
        return entry;
    }

    private static String getContentType(String path) {
        if (path.endsWith(".js")) return "application/javascript";
        if (path.endsWith(".css")) return "text/css";
        if (path.endsWith(".html")) return "text/html";
        if (path.endsWith(".png")) return "image/png";
        if (path.endsWith(".jpg") || path.endsWith(".jpeg")) return "image/jpeg";
        if (path.endsWith(".svg")) return "image/svg+xml";
        if (path.endsWith(".ico")) return "image/x-icon";
        if (path.endsWith(".woff")) return "font/woff";
        if (path.endsWith(".woff2")) return "font/woff2";
        if (path.endsWith(".ttf")) return "font/ttf";
        if (path.endsWith(".eot")) return "application/vnd.ms-fontobject";
        return "text/plain";
    }
}


