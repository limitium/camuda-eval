package limitium.art.camunda.backend.controller;

import jakarta.servlet.http.HttpServletRequest;
import limitium.art.camunda.backend.config.GitProperties;
import limitium.art.camunda.backend.git.GitRuleStore;
import limitium.art.camunda.backend.git.Landing;
import limitium.art.camunda.backend.git.Releases;
import limitium.art.camunda.backend.git.UserContext;
import limitium.art.camunda.backend.git.WorkspaceId;
import limitium.art.camunda.evaluator.diff.Resolution;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Release lines, versions, and landing. Authoring instances only: a deploy-only instance never
 * moves a line or makes a version.
 */
@RestController
@RequestMapping("/api/lifecycle")
public class LifecycleController {

    private final Releases releases;
    private final Landing landing;
    private final GitProperties git;
    private final UserContext users;

    @Value("${dmn.role:author}")
    private String role;

    public LifecycleController(Releases releases, Landing landing, GitProperties git, UserContext users) {
        this.releases = releases;
        this.landing = landing;
        this.git = git;
        this.users = users;
    }

    public static class ReleaseRequest {
        public String line;
        public String notes;
        /** Run the tests and say what would be made; change nothing. */
        public boolean check;
        /** The commit to make the version at; the line's newest when absent. */
        public String at;
    }

    public static class OpenLineRequest {
        /** The commit of main it opens at; main's newest when absent. */
        public String at;
        /** "M.m"; the next free number when absent. */
        public String number;
    }

    public static class LandRequest {
        /** Answers by conflict id: OURS (keep the line's), THEIRS (take what comes in), BOTH. */
        public Map<String, String> resolutions;
        /** Each line's commit as last shown; landing is refused (MOVED) if any has moved. */
        public Map<String, String> tips;
        public boolean apply;
    }

    public static class PullRequest {
        /** A line, a version, or another space (of any instance). */
        public String from;
        public Map<String, String> resolutions;
        /** The space's commit as last shown; refused (MOVED) if it was saved again meanwhile. */
        public String head;
        public boolean apply;
    }

    /** Brings another branch into a space, through the same answers as landing. */
    @PostMapping("/pull/{kind}/{owner}/{name}")
    public ResponseEntity<?> pull(@PathVariable String kind, @PathVariable String owner, @PathVariable String name,
                                  @RequestBody PullRequest request, HttpServletRequest http) {
        ResponseEntity<?> refused = authorOnly();
        if (refused != null) {
            return refused;
        }
        WorkspaceId id = WorkspaceId.parse(kind + "/" + owner + "/" + name);
        if (id == null || request == null || request.from == null) {
            return error(HttpStatus.BAD_REQUEST, "say which space and what to bring in");
        }
        try {
            return ResponseEntity.ok(landing.pull(id, request.from, answers(request.resolutions),
                    request.head, request.apply, users.require(http)));
        } catch (GitRuleStore.NoSuchSpace e) {
            return error(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (Landing.Refused e) {
            return conflict(e.code, e.getMessage());
        } catch (Exception e) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    public static class RefreshRequest {
        public Map<String, String> resolutions;
        public boolean apply;
    }

    /** Brings this checkout up to the space's newest saved version, keeping unsaved changes (merged). */
    @PostMapping("/refresh/{kind}/{owner}/{name}")
    public ResponseEntity<?> refresh(@PathVariable String kind, @PathVariable String owner, @PathVariable String name,
                                     @RequestBody(required = false) RefreshRequest request, HttpServletRequest http) {
        ResponseEntity<?> refused = authorOnly();
        if (refused != null) {
            return refused;
        }
        WorkspaceId id = WorkspaceId.parse(kind + "/" + owner + "/" + name);
        if (id == null) {
            return error(HttpStatus.NOT_FOUND, "no such working set");
        }
        RefreshRequest r = request == null ? new RefreshRequest() : request;
        try {
            return ResponseEntity.ok(landing.refresh(id, answers(r.resolutions), r.apply, users.require(http)));
        } catch (GitRuleStore.NoSuchSpace e) {
            return error(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (Exception e) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    private static Map<String, Resolution> answers(Map<String, String> raw) {
        Map<String, Resolution> answers = new LinkedHashMap<>();
        if (raw != null) {
            for (Map.Entry<String, String> e : raw.entrySet()) {
                try {
                    answers.put(e.getKey(), Resolution.valueOf(e.getValue()));
                } catch (IllegalArgumentException ignored) {
                    // an answer this version does not know is simply not an answer
                }
            }
        }
        return answers;
    }

    private ResponseEntity<?> conflict(String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("error", message);
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    @GetMapping("/lines")
    public ResponseEntity<?> lines() {
        if (!git.isEnabled()) {
            return error(HttpStatus.NOT_FOUND, "this instance keeps no version history");
        }
        try {
            return ResponseEntity.ok(releases.lines());
        } catch (Exception e) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    @PostMapping("/release")
    public ResponseEntity<?> release(@RequestBody ReleaseRequest request, HttpServletRequest http) {
        ResponseEntity<?> refused = authorOnly();
        if (refused != null) {
            return refused;
        }
        try {
            return ResponseEntity.ok(releases.release(request.line, request.at, request.notes, users.require(http), request.check));
        } catch (Releases.Refused e) {
            return refusedBody(e);
        } catch (Exception e) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    public static class CarryRequest {
        public String line;
        public Map<String, String> resolutions;
        public Map<String, String> tips;
        public boolean apply;
    }

    /** Carries a release line forward into every newer line and main, like a landing (with apply, lands it). */
    @PostMapping("/carry")
    public ResponseEntity<?> carry(@RequestBody CarryRequest request, HttpServletRequest http) {
        ResponseEntity<?> refused = authorOnly();
        if (refused != null) {
            return refused;
        }
        Map<String, Resolution> answers = new LinkedHashMap<>();
        if (request.resolutions != null) {
            for (Map.Entry<String, String> e : request.resolutions.entrySet()) {
                try {
                    answers.put(e.getKey(), Resolution.valueOf(e.getValue()));
                } catch (IllegalArgumentException ignored) {
                    // an answer this version does not know is simply not an answer
                }
            }
        }
        try {
            return ResponseEntity.ok(landing.carryForward(request.line, answers, request.tips, request.apply, users.require(http)));
        } catch (Landing.Refused e) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("code", e.code);
            body.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
        } catch (Exception e) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    /** Opens a release line at a commit of main. */
    @PostMapping("/lines/open")
    public ResponseEntity<?> openLine(@RequestBody OpenLineRequest request, HttpServletRequest http) {
        ResponseEntity<?> refused = authorOnly();
        if (refused != null) {
            return refused;
        }
        try {
            return ResponseEntity.ok(releases.openLine(request.at, request.number, users.require(http)));
        } catch (Releases.Refused e) {
            return refusedBody(e);
        } catch (Exception e) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    private ResponseEntity<?> refusedBody(Releases.Refused e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", e.code);
        body.put("error", e.getMessage());
        if (e.details != null) {
            body.put("details", e.details);
        }
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    /** Computes the whole chain (and lands it, with {@code apply}); 200 either way, the status says which. */
    @PostMapping("/land/{kind}/{owner}/{name}")
    public ResponseEntity<?> land(@PathVariable String kind, @PathVariable String owner, @PathVariable String name,
                                  @RequestBody(required = false) LandRequest request, HttpServletRequest http) {
        ResponseEntity<?> refused = authorOnly();
        if (refused != null) {
            return refused;
        }
        WorkspaceId id = WorkspaceId.parse(kind + "/" + owner + "/" + name);
        if (id == null) {
            return error(HttpStatus.NOT_FOUND, "no such working set");
        }
        LandRequest r = request == null ? new LandRequest() : request;
        Map<String, Resolution> answers = new LinkedHashMap<>();
        if (r.resolutions != null) {
            for (Map.Entry<String, String> e : r.resolutions.entrySet()) {
                try {
                    answers.put(e.getKey(), Resolution.valueOf(e.getValue()));
                } catch (IllegalArgumentException ignored) {
                    // an answer this version does not know is simply not an answer
                }
            }
        }
        try {
            return ResponseEntity.ok(landing.land(id, answers, r.tips, r.apply, users.require(http)));
        } catch (GitRuleStore.NoSuchSpace e) {
            return error(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (Landing.Refused e) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("code", e.code);
            body.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
        } catch (Exception e) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    private ResponseEntity<?> authorOnly() {
        if (!git.isEnabled()) {
            return error(HttpStatus.NOT_FOUND, "this instance keeps no version history");
        }
        if ("deployer".equals(role)) {
            return error(HttpStatus.FORBIDDEN, "this instance deploys only; lines and versions are made on an authoring instance");
        }
        return null;
    }

    private ResponseEntity<Map<String, Object>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Collections.singletonMap("error", message));
    }
}
