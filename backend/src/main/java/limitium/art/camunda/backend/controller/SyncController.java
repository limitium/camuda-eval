package limitium.art.camunda.backend.controller;

import limitium.art.camunda.backend.config.GitProperties;
import limitium.art.camunda.backend.git.GitRuleStore;
import limitium.art.camunda.backend.git.RemoteSync;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/** How this instance stands with the remote repository: the last exchange, and one on demand. */
@RestController
@RequestMapping("/api/sync")
public class SyncController {

    private final RemoteSync sync;
    private final GitRuleStore store;
    private final GitProperties properties;

    public SyncController(RemoteSync sync, GitRuleStore store, GitProperties properties) {
        this.sync = sync;
        this.store = store;
        this.properties = properties;
    }

    @GetMapping
    public ResponseEntity<?> status() {
        return ResponseEntity.ok(body(sync.last()));
    }

    @PostMapping
    public ResponseEntity<?> now() {
        return ResponseEntity.ok(body(sync.enabled() ? sync.run() : null));
    }

    private Map<String, Object> body(RemoteSync.Report report) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("enabled", sync.enabled());
        body.put("remote", sync.enabled() ? withoutCredentials(properties.getRemoteUrl()) : null);
        body.put("everySeconds", properties.getSyncSeconds());
        body.put("last", report);
        body.put("notSent", properties.isEnabled() ? store.notSentAll() : Map.of());
        return body;
    }

    /** https://user:token@host/repo.git is shown as https://host/repo.git. */
    static String withoutCredentials(String url) {
        return url == null ? null : url.replaceFirst("^([a-zA-Z][a-zA-Z0-9+.-]*://)[^/@]*@", "$1");
    }
}
