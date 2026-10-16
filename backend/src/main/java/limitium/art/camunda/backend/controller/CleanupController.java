package limitium.art.camunda.backend.controller;

import jakarta.servlet.http.HttpServletRequest;
import limitium.art.camunda.backend.config.GitProperties;
import limitium.art.camunda.backend.git.BranchCleanup;
import limitium.art.camunda.backend.git.UserContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tidying old spaces and release lines. The plan is read first and shown; applying names each item
 * with the head that was shown, and is refused whole if anything moved since. See {@link BranchCleanup}
 * for what may be removed, what is archived and what is never touched.
 */
@RestController
@RequestMapping("/api/cleanup")
public class CleanupController {

    private final BranchCleanup cleanup;
    private final GitProperties properties;
    private final UserContext users;

    @Value("${dmn.role:author}")
    private String role;

    private final limitium.art.camunda.backend.git.RemoteSync sync;

    public CleanupController(BranchCleanup cleanup, GitProperties properties, UserContext users,
                             limitium.art.camunda.backend.git.RemoteSync sync) {
        this.sync = sync;
        this.cleanup = cleanup;
        this.properties = properties;
        this.users = users;
    }

    public static class ApplyRequest {
        /** id → the head the caller was shown for it. */
        public Map<String, String> items;
    }

    public static class RestoreRequest {
        public String ref;
    }

    @GetMapping
    public ResponseEntity<?> plan() {
        if (sync.enabled()) {
            sync.run(); // tidy against what every instance sees
        }
        if (!properties.isEnabled()) {
            return error(HttpStatus.NOT_FOUND, "this instance keeps no version history, so there is nothing to tidy");
        }
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("plan", cleanup.plan());
            body.put("archived", cleanup.archived());
            return ResponseEntity.ok(body);
        } catch (Exception e) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    @PostMapping("/apply")
    public ResponseEntity<?> apply(@RequestBody ApplyRequest request, HttpServletRequest http) {
        ResponseEntity<?> refused = refuse();
        if (refused != null) {
            return refused;
        }
        if (request == null || request.items == null || request.items.isEmpty()) {
            return error(HttpStatus.BAD_REQUEST, "choose what to tidy");
        }
        String by = users.require(http);
        try {
            List<String> done = cleanup.apply(request.items, by);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("done", done);
            body.put("plan", cleanup.plan());
            body.put("archived", cleanup.archived());
            return ResponseEntity.ok(body);
        } catch (BranchCleanup.PlanChanged e) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("code", "PLAN_CHANGED");
            body.put("error", e.getMessage());
            body.put("plan", e.plan);
            return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
        } catch (Exception e) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    @PostMapping("/restore")
    public ResponseEntity<?> restore(@RequestBody RestoreRequest request, HttpServletRequest http) {
        ResponseEntity<?> refused = refuse();
        if (refused != null) {
            return refused;
        }
        users.require(http);
        try {
            return ResponseEntity.ok(cleanup.restore(request.ref));
        } catch (IllegalArgumentException e) {
            return error(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (IllegalStateException e) {
            return error(HttpStatus.CONFLICT, e.getMessage());
        } catch (Exception e) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    /** A deploy-only instance changes nothing in the history. */
    private ResponseEntity<?> refuse() {
        if (!properties.isEnabled()) {
            return error(HttpStatus.NOT_FOUND, "this instance keeps no version history");
        }
        if ("deployer".equals(role)) {
            return error(HttpStatus.FORBIDDEN, "this instance only deploys");
        }
        return null;
    }

    private ResponseEntity<Map<String, Object>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Collections.singletonMap("error", message));
    }
}
