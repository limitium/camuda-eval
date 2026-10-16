package limitium.art.camunda.backend.controller;

import jakarta.servlet.http.HttpServletRequest;
import limitium.art.camunda.backend.config.GitProperties;
import limitium.art.camunda.backend.git.GitRuleStore;
import limitium.art.camunda.backend.git.UserContext;
import limitium.art.camunda.backend.git.Workspace;
import limitium.art.camunda.backend.git.WorkspaceId;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Working spaces: a branch plus its own checkout, owned by the caller.
 *
 * <p>The baseline chosen at creation decides everything else — a shipped tag makes a hotfix on that
 * tag's line, an open line or {@code main} makes a feature — so the caller is never asked twice.
 */
@RestController
@RequestMapping("/api/workspaces")
public class WorkspacesController {

    private final GitRuleStore store;
    private final GitProperties properties;
    private final UserContext users;

    private final limitium.art.camunda.backend.git.RemoteSync sync;

    public WorkspacesController(GitRuleStore store, GitProperties properties, UserContext users,
                                limitium.art.camunda.backend.git.RemoteSync sync) {
        this.sync = sync;
        this.store = store;
        this.properties = properties;
        this.users = users;
    }

    public static class CreateRequest {
        public String name;
        /** What it starts from, at its newest commit: main, a release line, a version, or another space. */
        public String baseline;
        /** When the baseline is one commit: the branch it was picked on, which decides where the new space lands. */
        public String on;
    }

    public static class CommitRequest {
        public String message;
    }

    @GetMapping
    public ResponseEntity<?> list() {
        if (!properties.isEnabled()) {
            return ResponseEntity.ok(Collections.emptyList());
        }
        try {
            // Each with its saved state: the branch is the one place a space is written, so whether
            // this checkout matches it (saved), has unsaved changes, or is behind it is what matters.
            List<Map<String, Object>> list = new java.util.ArrayList<>();
            com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();
            for (Workspace w : store.workspaces()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> row = json.convertValue(w, Map.class);
                WorkspaceId id = WorkspaceId.parse(w.id);
                row.put("saved", id == null ? null : store.savedState(id));
                list.add(row);
            }
            return ResponseEntity.ok(list);
        } catch (Exception e) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    /**
     * What a new space can start from, each at its newest commit (fetched first): the newest work,
     * every release line, and every space of any instance.
     */
    @GetMapping("/bases")
    public ResponseEntity<?> bases() {
        if (!properties.isEnabled()) {
            return ResponseEntity.ok(Collections.emptyList());
        }
        try {
            if (sync.enabled()) {
                sync.run();
            }
            return ResponseEntity.ok(store.bases());
        } catch (Exception e) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    /** Continues a space here, from its saved version: it gets a checkout on this instance. */
    @PostMapping("/{kind}/{owner}/{name}/open")
    public ResponseEntity<?> open(@PathVariable String kind, @PathVariable String owner, @PathVariable String name) {
        WorkspaceId id = WorkspaceId.parse(kind + "/" + owner + "/" + name);
        if (id == null) {
            return error(HttpStatus.NOT_FOUND, "no such working set");
        }
        try {
            return ResponseEntity.ok(store.openWorkspace(id));
        } catch (GitRuleStore.NoSuchSpace e) {
            return error(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (Exception e) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    /** Spaces saved on another instance, with no checkout here yet: each can be opened here. */
    @GetMapping("/elsewhere")
    public ResponseEntity<?> elsewhere() {
        if (!properties.isEnabled()) {
            return ResponseEntity.ok(Collections.emptyList());
        }
        try {
            return ResponseEntity.ok(store.spacesElsewhere());
        } catch (Exception e) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    /** Everything a new working space can start from: main, each release line, each version. */
    @GetMapping("/baselines")
    public ResponseEntity<?> baselines() {
        if (!properties.isEnabled()) {
            return ResponseEntity.ok(Collections.emptyList());
        }
        try {
            return ResponseEntity.ok(store.baselines());
        } catch (Exception e) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    /** The shared history as a graph: every commit of main and the release lines, newest first. */
    @GetMapping("/history")
    public ResponseEntity<?> history(@org.springframework.web.bind.annotation.RequestParam(defaultValue = "600") int limit,
                                     @org.springframework.web.bind.annotation.RequestParam(defaultValue = "0") int days) {
        if (!properties.isEnabled()) {
            return ResponseEntity.ok(Collections.emptyList());
        }
        try {
            return ResponseEntity.ok(store.history(Math.max(1, Math.min(limit, 2000)), Math.max(0, days)));
        } catch (Exception e) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    /** Files changed since the working space's last saved version. */
    @GetMapping("/{kind}/{owner}/{name}/changes")
    public ResponseEntity<?> changes(@PathVariable String kind, @PathVariable String owner,
                                     @PathVariable String name) {
        WorkspaceId id = WorkspaceId.parse(kind + "/" + owner + "/" + name);
        if (id == null || !properties.isEnabled()) {
            return error(HttpStatus.NOT_FOUND, "no such working set");
        }
        try {
            List<Map<String, String>> files = new java.util.ArrayList<>();
            store.changes(id).forEach((path, change) -> {
                Map<String, String> file = new LinkedHashMap<>();
                file.put("path", path);
                file.put("change", change);
                files.add(file);
            });
            return ResponseEntity.ok(Collections.singletonMap("files", files));
        } catch (Exception e) {
            return error(HttpStatus.NOT_FOUND, "no such working set");
        }
    }

    /**
     * A file as it was at the working space's last saved version, so "what changes if I save this
     * version" can be shown as a rule-level diff. 404 when the file is new since then.
     */
    @GetMapping("/{kind}/{owner}/{name}/saved")
    public ResponseEntity<?> saved(@PathVariable String kind, @PathVariable String owner,
                                   @PathVariable String name,
                                   @org.springframework.web.bind.annotation.RequestParam String path) {
        WorkspaceId id = WorkspaceId.parse(kind + "/" + owner + "/" + name);
        if (id == null || !properties.isEnabled()) {
            return error(HttpStatus.NOT_FOUND, "no such working set");
        }
        if (!(path.startsWith("dmn/") || path.startsWith("yaml/")) || path.contains("..")) {
            return error(HttpStatus.BAD_REQUEST, "path must be a rule or test file");
        }
        try {
            byte[] content = store.readAt(id.branch(), path);
            if (content == null) {
                return error(HttpStatus.NOT_FOUND, "not in the last saved version");
            }
            return ResponseEntity.ok()
                    .contentType(org.springframework.http.MediaType.TEXT_PLAIN)
                    .body(new String(content, java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody CreateRequest request, HttpServletRequest http) {
        if (!properties.isEnabled()) {
            return error(HttpStatus.CONFLICT, "the git layer is disabled");
        }
        if (request == null || request.name == null || request.name.isBlank()) {
            return error(HttpStatus.BAD_REQUEST, "name is required");
        }
        if (request.baseline == null || request.baseline.isBlank()) {
            return error(HttpStatus.BAD_REQUEST, "baseline is required: pick what this builds on");
        }

        String owner = users.require(http);
        // Every git action starts from what every instance sees: the newest commit of what it builds on.
        if (sync.enabled()) {
            sync.run();
        }
        // A single commit picked on a branch builds like that branch, from that commit.
        String like = request.on != null && !request.on.isBlank() ? request.on : request.baseline;
        WorkspaceId from = WorkspaceId.parse(like);
        WorkspaceId.Kind kind = from != null ? from.kind : kindOf(request.baseline);
        WorkspaceId id;
        try {
            id = WorkspaceId.of(kind, owner, request.name);
        } catch (IllegalArgumentException e) {
            return error(HttpStatus.BAD_REQUEST, e.getMessage());
        }

        try {
            Workspace workspace;
            if (from != null) {
                // Built on another space: it lands where that one lands, and keeps its baseline.
                String[] fromBaseline = store.baselineOfSpace(from);
                workspace = store.createWorkspace(id, request.baseline, fromBaseline[0], fromBaseline[1]);
            } else {
                workspace = store.createWorkspace(id, request.baseline, request.baseline, targetLineOf(like));
            }
            return ResponseEntity.status(HttpStatus.CREATED).body(workspace);
        } catch (IllegalStateException e) {
            return error(HttpStatus.CONFLICT, e.getMessage());
        } catch (Exception e) {
            return error(HttpStatus.BAD_REQUEST, "could not create the working space: " + e.getMessage());
        }
    }

    @DeleteMapping("/{kind}/{owner}/{name}")
    public ResponseEntity<?> delete(@PathVariable String kind, @PathVariable String owner,
                                    @PathVariable String name) {
        WorkspaceId id = WorkspaceId.parse(kind + "/" + owner + "/" + name);
        if (id == null) {
            return error(HttpStatus.NOT_FOUND, "no such working set");
        }
        try {
            store.deleteWorkspace(id);
            return ResponseEntity.noContent().build();
        } catch (Exception e) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    /**
     * Commits and pushes. Publishing to an environment names this commit in its envelope, so the
     * push is not an optimisation — a sha nobody else can resolve is not provenance.
     */
    @PostMapping("/{kind}/{owner}/{name}/commit")
    public ResponseEntity<?> commit(@PathVariable String kind, @PathVariable String owner,
                                    @PathVariable String name, @RequestBody CommitRequest request,
                                    HttpServletRequest http) {
        WorkspaceId id = WorkspaceId.parse(kind + "/" + owner + "/" + name);
        if (id == null) {
            return error(HttpStatus.NOT_FOUND, "no such working set");
        }
        if (request == null || request.message == null || request.message.isBlank()) {
            return error(HttpStatus.BAD_REQUEST, "a commit message is required");
        }
        try {
            if (sync.enabled()) {
                sync.run(); // a newer version saved elsewhere is found before saving, not after
            }
            String sha = store.commit(id, request.message, users.require(http));
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("commit", sha);
            body.put("shortCommit", sha.substring(0, Math.min(7, sha.length())));
            // Saved for everyone on this instance; other instances see it once it reaches the remote.
            body.put("notSent", store.notSentReason(id));
            return ResponseEntity.ok(body);
        } catch (GitRuleStore.NoSuchSpace e) {
            return error(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (GitRuleStore.NotSaved e) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("code", e.code);
            body.put("error", e.getMessage());
            return ResponseEntity.status("BEHIND".equals(e.code) ? HttpStatus.CONFLICT : HttpStatus.SERVICE_UNAVAILABLE).body(body);
        } catch (GitRuleStore.NotShared e) {
            // Committed here, not visible to anyone else: say so, never "saved".
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("code", "NOT_SHARED");
            body.put("error", e.getMessage());
            body.put("commit", e.commit);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
        } catch (IllegalStateException e) {
            return error(HttpStatus.CONFLICT, e.getMessage());
        } catch (Exception e) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    /**
     * A shipped tag means fixing something already released, so it is a hotfix on that tag's line.
     * Anything else builds forward and is a feature.
     */
    private WorkspaceId.Kind kindOf(String baseline) {
        return baseline.startsWith("v") && baseline.length() > 1 && Character.isDigit(baseline.charAt(1))
                ? WorkspaceId.Kind.HOTFIX
                : WorkspaceId.Kind.FEATURE;
    }

    /** The line a working space lands on, from which the merge chain continues upward. */
    private String targetLineOf(String baseline) {
        if (baseline.startsWith("release/")) {
            return baseline;
        }
        if (kindOf(baseline) == WorkspaceId.Kind.HOTFIX) {
            String[] parts = baseline.substring(1).split("\\.");
            if (parts.length >= 2) {
                return "release/" + parts[0] + "." + parts[1];
            }
        }
        return properties.getMainBranch();
    }

    private ResponseEntity<Map<String, Object>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Collections.singletonMap("error", message));
    }
}
