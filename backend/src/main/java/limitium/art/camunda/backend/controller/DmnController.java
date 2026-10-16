package limitium.art.camunda.backend.controller;

import limitium.art.camunda.backend.git.WorkspaceResolver;
import limitium.art.camunda.backend.service.VersionedFiles;
import limitium.art.camunda.evaluator.testspec.TestSpecHints;
import limitium.art.camunda.evaluator.testspec.TestSpecValidator;
import limitium.art.camunda.evaluator.testspec.TestSpec;
import limitium.art.camunda.evaluator.testspec.TestSpecWriter;
import org.camunda.bpm.model.dmn.Dmn;
import org.camunda.bpm.model.dmn.DmnModelInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/dmn")
public class DmnController {

    /** The flat directories, used only when the git layer is off. */
    @Autowired
    private Path dmnDir;

    @Autowired
    private Path yamlDir;

    @Autowired
    private Path runsDir;

    @Autowired
    private WorkspaceResolver workspaces;

    @Autowired
    private VersionedFiles files;

    private Path dmn(String ws) {
        return workspaces.dmnDir(ws, dmnDir);
    }

    private Path yaml(String ws) {
        return workspaces.yamlDir(ws, yamlDir);
    }

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> listDmnFiles(
            @RequestParam(value = "ws", required = false) String ws) {
        Path dir = dmn(ws);
        if (Files.notExists(dir)) {
            return ResponseEntity.ok(new ArrayList<>());
        }
        try (var stream = Files.list(dir)) {
            var list = stream
                    .filter(p -> p.toString().endsWith(".dmn"))
                    .map(this::fileInfo)
                    .collect(Collectors.toList());
            return ResponseEntity.ok(list);
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /** Returns the content with its ETag, which a later save must present as If-Match. */
    @GetMapping("/{name}")
    public ResponseEntity<byte[]> getDmnFile(@PathVariable String name,
                                             @RequestParam(value = "ws", required = false) String ws) {
        Path file = workspaces.file(dmn(ws), name);
        if (Files.notExists(file)) {
            return ResponseEntity.notFound().build();
        }
        try {
            byte[] content = Files.readAllBytes(file);
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType("application/xml"))
                    .eTag(VersionedFiles.etag(content))
                    .body(content);
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> uploadDmnFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "name", required = false) String name,
            @RequestParam(value = "ws", required = false) String ws) {
        
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Collections.singletonMap("error", "file is required"));
        }
        
        String fileName = Optional.ofNullable(name).orElse(file.getOriginalFilename());
        if (!fileName.endsWith(".dmn")) {
            fileName = fileName + ".dmn";
        }
        
        // Validate DMN content before saving
        try {
            String dmnContent = new String(file.getBytes());
            Map<String, Object> validationResult = validateDmnContentInternal(dmnContent);
            if (!(Boolean) validationResult.get("valid")) {
                return ResponseEntity.badRequest()
                        .body(Collections.singletonMap("error", 
                                "Invalid DMN file: " + validationResult.get("error")));
            }
        } catch (IOException e) {
            return ResponseEntity.badRequest()
                    .body(Collections.singletonMap("error", "Failed to read file content"));
        }
        
        Path dst = workspaces.file(dmn(ws), fileName);
        try {
            byte[] bytes = file.getBytes();
            if (workspaces.requirePrecondition()) {
                // Upload creates. Replacing an existing file this way would skip the precondition every
                // other write carries, so where editing is shared it is refused and the caller edits
                // instead. Created atomically: of two uploads racing, exactly one wins.
                if (!files.create(dst, bytes)) {
                    return ResponseEntity.status(HttpStatus.CONFLICT).body(Collections.singletonMap("error",
                            fileName + " already exists in this working space; edit it rather than upload over it"));
                }
            } else {
                VersionedFiles.writeAtomically(dst, bytes);
            }

            // The paired test spec, generated from the table, unless one is already there.
            String base = fileName.substring(0, fileName.length() - 4);
            Path yamlFile = workspaces.file(yaml(ws), base + ".yaml");
            files.create(yamlFile, testSpecSkeleton(new String(bytes, java.nio.charset.StandardCharsets.UTF_8))
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));

            return ResponseEntity.ok(fileInfo(dst));
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Collections.singletonMap("error", "Failed to save file"));
        }
    }

    @PutMapping("/{name}")
    public ResponseEntity<Map<String, Object>> updateDmnFile(
            @PathVariable String name,
            @RequestParam(value = "ws", required = false) String ws,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestBody byte[] content) {

        if (content.length == 0) {
            return ResponseEntity.badRequest().body(Collections.singletonMap("error", "Body required"));
        }

        Map<String, Object> validationResult = validateDmnContentInternal(new String(content));
        if (!(Boolean) validationResult.get("valid")) {
            return ResponseEntity.badRequest()
                    .body(Collections.singletonMap("error",
                            "Invalid DMN content: " + validationResult.get("error")));
        }

        Path dst = workspaces.file(dmn(ws), name);
        try {
            VersionedFiles.WriteResult result =
                    files.write(dst, content, ifMatch, workspaces.requirePrecondition());
            return ResponseEntity.status(result.status).eTag(result.etag).body(result.body);
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Collections.singletonMap("error", "Failed to update file"));
        }
    }

    @PostMapping("/validate")
    public ResponseEntity<Map<String, Object>> validateDmnContent(
            @RequestBody String dmnContent) {
        
        Map<String, Object> validationResult = validateDmnContentInternal(dmnContent);
        return ResponseEntity.ok(validationResult);
    }

    /**
     * Removes a file and its tests, if the caller's {@code If-Match} names the version on disk — removing
     * a version you have not seen discards a colleague's work as surely as overwriting it. Test-run
     * history is kept: it is shared by every space with a file of this name.
     */
    @DeleteMapping("/{name}")
    public ResponseEntity<?> deleteDmnFile(@PathVariable String name,
                                           @RequestParam(value = "ws", required = false) String ws,
                                           @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        Path file = workspaces.file(dmn(ws), name);
        try {
            VersionedFiles.WriteResult result = files.delete(file, ifMatch, workspaces.requirePrecondition());
            if (!result.ok()) {
                return ResponseEntity.status(result.status).body(result.body);
            }
            String fileName = file.getFileName().toString();
            if (fileName.endsWith(".dmn")) {
                String base = fileName.substring(0, fileName.length() - 4);
                Path yaml = workspaces.file(yaml(ws), base + ".yaml");
                files.delete(yaml, null, false);
            }
            return ResponseEntity.noContent().build();
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * A starter test spec already shaped for the uploaded file: a block per decision with the
     * variables a test actually supplies, and no rows. Getting the column names right by hand is the
     * tedious part, and for a chained decision it is not even obvious — the names are the upstream
     * inputs, not the ones the decision declares.
     */
    private String testSpecSkeleton(String dmnContent) {
        try {
            TestSpec skeleton = TestSpecHints.skeleton(dmnContent);
            return skeleton.decisions.isEmpty() ? "tests: []\n" : TestSpecWriter.write(skeleton);
        } catch (RuntimeException e) {
            return "tests: []\n";
        }
    }

    /**
     * Problems in the test spec paired with this DMN. The spec is the table's usage, so a rename in
     * the table leaves it parsing perfectly while testing something that no longer exists; only
     * comparing the two finds it. Errors block a merge or a tag, warnings do not.
     */
    @GetMapping("/{name}/test-problems")
    public ResponseEntity<?> testProblems(@PathVariable String name,
                                          @RequestParam(value = "ws", required = false) String ws) {
        Path dmn = workspaces.file(dmn(ws), name);
        if (Files.notExists(dmn)) {
            return ResponseEntity.notFound().build();
        }
        String base = name.endsWith(".dmn") ? name.substring(0, name.length() - 4) : name;
        Path yaml = workspaces.file(yaml(ws), base + ".yaml");
        try {
            String spec = Files.exists(yaml) ? Files.readString(yaml) : "tests: []\n";
            List<TestSpecValidator.Problem> problems =
                    TestSpecValidator.validate(Files.readString(dmn), spec);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("problems", problems);
            body.put("hasErrors", TestSpecValidator.hasErrors(problems));
            return ResponseEntity.ok(body);
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * What the test editor can offer: decisions in this file, the variables each one needs supplied,
     * and the outputs an expectation column may target.
     */
    @GetMapping("/{name}/test-hints")
    public ResponseEntity<?> testHints(@PathVariable String name,
                                       @RequestParam(value = "ws", required = false) String ws) {
        Path file = workspaces.file(dmn(ws), name);
        if (Files.notExists(file)) {
            return ResponseEntity.notFound().build();
        }
        try {
            List<TestSpecHints.DecisionHint> hints = TestSpecHints.of(Files.readString(file));
            return ResponseEntity.ok(Collections.singletonMap("decisions", hints));
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest()
                    .body(Collections.singletonMap("error", "could not read decisions: " + e.getMessage()));
        }
    }

    private Map<String, Object> fileInfo(Path p) {
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
    
    private Map<String, Object> validateDmnContentInternal(String dmnContent) {
        Map<String, Object> result = new HashMap<>();
        
        try {
            // Check if content is empty
            if (dmnContent == null || dmnContent.trim().isEmpty()) {
                result.put("valid", false);
                result.put("error", "DMN content is empty");
                return result;
            }
            
            // Try to parse as XML first
            try {
                ByteArrayInputStream inputStream = new ByteArrayInputStream(dmnContent.getBytes());
                DmnModelInstance modelInstance = Dmn.readModelFromStream(inputStream);
                
                if (modelInstance == null) {
                    result.put("valid", false);
                    result.put("error", "Failed to parse DMN model - no model instance created");
                    return result;
                }
                
                // Check if it's a valid DMN model
                if (modelInstance.getDefinitions() == null) {
                    result.put("valid", false);
                    result.put("error", "Invalid DMN structure - missing definitions element");
                    return result;
                }
                
                // Check for at least one decision
                if (modelInstance.getModelElementsByType(org.camunda.bpm.model.dmn.instance.Decision.class).isEmpty()) {
                    result.put("valid", false);
                    result.put("error", "DMN model must contain at least one decision");
                    return result;
                }
                
                result.put("valid", true);
                result.put("error", null);
                return result;
                
            } catch (Exception e) {
                result.put("valid", false);
                result.put("error", "XML parsing error: " + e.getMessage());
                return result;
            }
            
        } catch (Exception e) {
            result.put("valid", false);
            result.put("error", "Unexpected error during validation: " + e.getMessage());
            return result;
        }
    }
}
