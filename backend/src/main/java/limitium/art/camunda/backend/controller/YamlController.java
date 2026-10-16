package limitium.art.camunda.backend.controller;

import limitium.art.camunda.backend.git.WorkspaceResolver;
import limitium.art.camunda.backend.service.VersionedFiles;
import limitium.art.camunda.evaluator.testspec.TestSpecReader;
import limitium.art.camunda.evaluator.testspec.TestSpecWriter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/yaml")
public class YamlController {

    /** The flat directories, used only when the git layer is off. */
    @Autowired
    private Path yamlDir;

    @Autowired
    private Path dmnDir;

    @Autowired
    private WorkspaceResolver workspaces;

    @Autowired
    private VersionedFiles files;

    private Path yaml(String ws) {
        return workspaces.yamlDir(ws, yamlDir);
    }

    private Path dmn(String ws) {
        return workspaces.dmnDir(ws, dmnDir);
    }

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> listYamlFiles(
            @RequestParam(value = "ws", required = false) String ws) {
        Path dir = yaml(ws);
        if (Files.notExists(dir)) {
            return ResponseEntity.ok(new ArrayList<>());
        }
        try (var stream = Files.list(dir)) {
            var list = stream
                    .filter(p -> p.toString().endsWith(".yaml"))
                    .map(this::fileInfo)
                    .collect(Collectors.toList());
            return ResponseEntity.ok(list);
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /** Returns the content with its ETag, which a later save must present as If-Match. */
    @GetMapping("/{name}")
    public ResponseEntity<byte[]> getYamlFile(@PathVariable String name,
                                              @RequestParam(value = "ws", required = false) String ws) {
        Path file = workspaces.file(yaml(ws), name);
        if (Files.notExists(file)) {
            return ResponseEntity.notFound().build();
        }
        try {
            byte[] content = Files.readAllBytes(file);
            return ResponseEntity.ok()
                    .contentType(MediaType.TEXT_PLAIN)
                    .eTag(VersionedFiles.etag(content))
                    .body(content);
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Saves the test spec in canonical form when it parses, and as written when it does not.
     *
     * <p>Canonical on every save keeps git diffs free of formatting churn, so two editors that disagree
     * about quoting cannot manufacture a conflict, and a reformat-only save leaves the ETag unchanged.
     * A spec that does not parse is saved as typed: its author is mid-edit, and errors are reported
     * inline rather than by refusing the save.
     *
     * <p>The response carries what was actually stored, so the editor can show that rather than drift
     * from it.
     */
    @PutMapping("/{name}")
    public ResponseEntity<Map<String, Object>> updateYamlFile(
            @PathVariable String name,
            @RequestParam(value = "ws", required = false) String ws,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestBody String content) {

        Path dst = workspaces.file(yaml(ws), name);
        if (!dst.toString().endsWith(".yaml")) {
            return ResponseEntity.badRequest().body(Collections.singletonMap("error", "Only .yaml supported"));
        }

        String fileName = dst.getFileName().toString();
        String base = fileName.substring(0, fileName.length() - 5);
        Path dmn = workspaces.file(dmn(ws), base + ".dmn");
        if (Files.notExists(dmn)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Collections.singletonMap("error", "Paired DMN not found: " + dmn.getFileName()));
        }
        if (Files.notExists(dst)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Collections.singletonMap("error", "YAML not initialized. Create DMN first."));
        }
        if (content == null || content.isEmpty()) {
            return ResponseEntity.badRequest().body(Collections.singletonMap("error", "Body required"));
        }

        String stored = canonical(content);
        try {
            VersionedFiles.WriteResult result = files.write(
                    dst, stored.getBytes(StandardCharsets.UTF_8), ifMatch, workspaces.requirePrecondition());
            Map<String, Object> body = new LinkedHashMap<>(result.body);
            if (result.ok()) {
                body.put("content", stored);
                body.put("canonicalized", !stored.equals(content));
            }
            return ResponseEntity.status(result.status).eTag(result.etag).body(body);
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Collections.singletonMap("error", "Failed to update file"));
        }
    }

    private static String canonical(String content) {
        try {
            return TestSpecWriter.write(TestSpecReader.read(content));
        } catch (RuntimeException e) {
            return content;
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
}
