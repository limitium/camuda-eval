package limitium.art.camunda.backend.controller;

import limitium.art.camunda.backend.config.GitProperties;
import limitium.art.camunda.backend.deploy.DeployService;
import limitium.art.camunda.backend.git.GitRuleStore;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Any commit, opened: its domains with their tables and tests exactly as they were, and their tests
 * run. The same way in for "what was deployed" — a deploy names its commit.
 *
 * <p>Read only, and needs no lock: a commit never changes.
 */
@RestController
@RequestMapping("/api/commit")
public class CommitsController {

    private final GitRuleStore store;
    private final GitProperties git;
    private final DeployService deploys;

    public CommitsController(GitRuleStore store, GitProperties git, DeployService deploys) {
        this.store = store;
        this.git = git;
        this.deploys = deploys;
    }

    @GetMapping
    public ResponseEntity<?> commit(@RequestParam String rev) {
        if (!git.isEnabled()) {
            return error(HttpStatus.NOT_FOUND, "this instance keeps no version history");
        }
        try {
            GitRuleStore.CommitInfo info = store.commitInfo(rev);
            return info == null ? error(HttpStatus.NOT_FOUND, "there is no commit " + rev) : ResponseEntity.ok(info);
        } catch (Exception e) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    /** One domain at a commit: its table and its tests. */
    @GetMapping("/domain")
    public ResponseEntity<?> domain(@RequestParam String rev, @RequestParam String name) {
        if (!git.isEnabled()) {
            return error(HttpStatus.NOT_FOUND, "this instance keeps no version history");
        }
        try {
            String sha = store.resolve(rev);
            if (sha == null) {
                return error(HttpStatus.NOT_FOUND, "there is no commit " + rev);
            }
            byte[] dmn = store.readAt(sha, "dmn/" + name + ".dmn");
            if (dmn == null) {
                return error(HttpStatus.NOT_FOUND, "there is no domain " + name + " at " + sha.substring(0, 7));
            }
            byte[] yaml = store.readAt(sha, "yaml/" + name + ".yaml");
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("commit", sha);
            body.put("name", name);
            body.put("dmn", new String(dmn, StandardCharsets.UTF_8));
            body.put("yaml", yaml == null ? "" : new String(yaml, StandardCharsets.UTF_8));
            return ResponseEntity.ok(body);
        } catch (Exception e) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    /** Every domain's tests at a commit — exactly what a deploy of it runs first. */
    @PostMapping("/tests")
    public ResponseEntity<?> tests(@RequestParam String rev) {
        if (!git.isEnabled()) {
            return error(HttpStatus.NOT_FOUND, "this instance keeps no version history");
        }
        try {
            return ResponseEntity.ok(deploys.testsForDisplay(rev));
        } catch (DeployService.Refused e) {
            return error(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (Exception e) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    private ResponseEntity<Map<String, Object>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Collections.singletonMap("error", message));
    }
}
