package limitium.art.camunda.backend.controller;

import limitium.art.camunda.backend.config.GitProperties;
import limitium.art.camunda.backend.git.ElementHistory;
import limitium.art.camunda.backend.git.GitRuleStore;
import limitium.art.camunda.backend.git.WorkspaceId;
import org.eclipse.jgit.lib.Constants;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Files;
import java.util.Collections;
import java.util.Map;

/**
 * The history of a domain, a table, a rule, a column or one cell — whichever is named — read back
 * from a work space (what it saved but did not commit, then its commits) or from any commit.
 *
 * <pre>
 * GET /api/history?domain=fee&amp;ws=feature/alice/x                                   the domain
 * GET /api/history?domain=fee&amp;rev=v1.1.0&amp;decision=FeeCode                          a table
 * GET /api/history?domain=fee&amp;ws=…&amp;decision=FeeCode&amp;rule=Rule_2                   a rule
 * GET /api/history?domain=fee&amp;ws=…&amp;decision=FeeCode&amp;column=Output_1               a column
 * GET /api/history?domain=fee&amp;ws=…&amp;decision=FeeCode&amp;rule=Rule_2&amp;column=Output_1   one cell
 * </pre>
 *
 * <p>Read only, so a deploy-only instance answers it too.
 */
@RestController
@RequestMapping("/api/history")
public class HistoryController {

    private final ElementHistory history;
    private final GitRuleStore store;
    private final GitProperties git;

    public HistoryController(ElementHistory history, GitRuleStore store, GitProperties git) {
        this.history = history;
        this.store = store;
        this.git = git;
    }

    @GetMapping
    public ResponseEntity<?> history(@RequestParam String domain,
                                     @RequestParam(required = false) String ws,
                                     @RequestParam(required = false) String rev,
                                     @RequestParam(required = false) String decision,
                                     @RequestParam(required = false) String rule,
                                     @RequestParam(required = false) String column) {
        if (!git.isEnabled()) {
            return error(HttpStatus.NOT_FOUND, "this instance keeps no git history");
        }
        if (domain.isBlank() || domain.startsWith(".") || domain.contains("/") || domain.contains("\\") || domain.contains("\u0000")) {
            return error(HttpStatus.BAD_REQUEST, "invalid domain name: " + domain);
        }
        if ((ws == null) == (rev == null)) {
            return error(HttpStatus.BAD_REQUEST, "name either a work space (ws) or a commit (rev)");
        }
        if ((rule != null || column != null) && (decision == null || decision.isBlank())) {
            return error(HttpStatus.BAD_REQUEST, "a rule or a column is named inside a table: decision is required");
        }
        ElementHistory.Subject subject = new ElementHistory.Subject(domain, decision, rule, column);
        try {
            if (ws != null) {
                WorkspaceId id = WorkspaceId.parse(ws);
                if (id == null || !Files.exists(store.checkoutOf(id).resolve(Constants.DOT_GIT))) {
                    return error(HttpStatus.NOT_FOUND, "there is no work space " + ws + " here");
                }
                return ResponseEntity.ok(history.ofSpace(id, subject));
            }
            ElementHistory.Result result = history.at(rev, subject);
            return result == null ? error(HttpStatus.NOT_FOUND, "there is no commit " + rev) : ResponseEntity.ok(result);
        } catch (Exception e) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    private ResponseEntity<Map<String, Object>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Collections.singletonMap("error", message));
    }
}
