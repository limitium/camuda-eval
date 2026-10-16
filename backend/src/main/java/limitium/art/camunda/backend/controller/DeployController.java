package limitium.art.camunda.backend.controller;

import jakarta.servlet.http.HttpServletRequest;
import limitium.art.camunda.backend.config.GitProperties;
import limitium.art.camunda.backend.deploy.DeployProperties;
import limitium.art.camunda.backend.deploy.DeployService;
import limitium.art.camunda.backend.git.RemoteSync;
import limitium.art.camunda.backend.git.UserContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The applications this instance controls: what each runs and its history, read back from
 * its topic; deploying, redeploying, putting a release back, and publishing a draft.
 */
@RestController
@RequestMapping("/api/applications")
public class DeployController {

    private final DeployService deploys;
    private final GitProperties git;
    private final UserContext users;
    private final RemoteSync sync;
    private final String env;
    private final boolean allowDraftPublish;

    @Value("${dmn.role:author}")
    private String role;

    public DeployController(DeployService deploys, GitProperties git, UserContext users, DeployProperties deploy, RemoteSync sync) {
        this.deploys = deploys;
        this.git = git;
        this.users = users;
        this.sync = sync;
        this.env = deploy.getEnv();
        this.allowDraftPublish = deploy.isAllowDraftPublish();
    }

    public static class DeployRequest {
        public String release;
    }

    public static class DraftRequest {
        public String workspace;
        /** The caller has been told this replaces what the domain runs, for everyone. */
        public boolean confirm;
    }

    @GetMapping
    public ResponseEntity<?> list() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("env", env);
        body.put("allowDraftPublish", allowDraftPublish && !"deployer".equals(role));
        body.put("applications", deploys.overview());
        return ResponseEntity.ok(body);
    }

    @GetMapping("/{name}")
    public ResponseEntity<?> detail(@PathVariable String name) {
        return run(() -> deploys.detail(name));
    }

    @PostMapping("/{name}/deploy")
    public ResponseEntity<?> deploy(@PathVariable String name, @RequestBody DeployRequest request, HttpServletRequest http) {
        return run(() -> {
            if (sync.enabled()) {
                sync.run(); // a version made on another instance may have just arrived
            }
            return deploys.deployRelease(name, request == null ? null : request.release, users.require(http));
        });
    }

    @PostMapping("/{name}/redeploy")
    public ResponseEntity<?> redeploy(@PathVariable String name, HttpServletRequest http) {
        return run(() -> deploys.redeploy(name, users.require(http)));
    }

    @PostMapping("/{name}/restore")
    public ResponseEntity<?> restore(@PathVariable String name, HttpServletRequest http) {
        return run(() -> deploys.restore(name, users.require(http)));
    }

    @PostMapping("/{name}/draft")
    public ResponseEntity<?> draft(@PathVariable String name, @RequestBody DraftRequest request, HttpServletRequest http) {
        if ("deployer".equals(role)) {
            return error(HttpStatus.FORBIDDEN, "drafts are not published from a deploy-only instance");
        }
        if (request == null || !request.confirm) {
            return error(HttpStatus.BAD_REQUEST, "publishing a draft replaces what " + name + " runs for everyone; confirm it");
        }
        return run(() -> deploys.publishDraft(name, request.workspace, users.require(http)));
    }

    /** For a space: every application, and which of the files it reads the space changed. */
    @GetMapping("/draft-preview")
    public ResponseEntity<?> draftPreview(@RequestParam String workspace) {
        return run(() -> deploys.draftPreview(workspace));
    }

    private interface Action {
        Object run() throws Exception;
    }

    private ResponseEntity<?> run(Action action) {
        if (!git.isEnabled()) {
            return error(HttpStatus.NOT_FOUND, "this instance keeps no version history");
        }
        try {
            return ResponseEntity.ok(action.run());
        } catch (DeployService.Refused e) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("code", e.code);
            body.put("error", e.getMessage());
            if (e.details != null) {
                body.put("details", e.details);
            }
            return ResponseEntity.status("NO_SUCH_APPLICATION".equals(e.code) ? HttpStatus.NOT_FOUND : HttpStatus.CONFLICT).body(body);
        } catch (Exception e) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    private ResponseEntity<Map<String, Object>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Collections.singletonMap("error", message));
    }
}
