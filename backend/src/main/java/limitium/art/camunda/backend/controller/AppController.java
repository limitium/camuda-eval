package limitium.art.camunda.backend.controller;

import jakarta.servlet.http.HttpServletRequest;
import limitium.art.camunda.backend.config.AuthProperties;
import limitium.art.camunda.backend.config.GitProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What this instance is: which environment it serves, whether it authors at all, and who is asking.
 * The interface shapes itself from this rather than guessing, so a deployer instance never renders
 * an authoring control to begin with.
 */
@RestController
@RequestMapping("/api/app")
public class AppController {

    private final GitProperties git;
    private final AuthProperties auth;

    @Value("${dmn.role:author}")
    private String role;

    @Value("${dmn.deploy.env:dev}")
    private String env;

    @Value("${dmn.deploy.allow-draft-publish:false}")
    private boolean allowDraftPublish;

    public AppController(GitProperties git, AuthProperties auth) {
        this.git = git;
        this.auth = auth;
    }

    @GetMapping
    public Map<String, Object> describe(HttpServletRequest request) {
        String user = request.getHeader(auth.getUserHeader());
        if ((user == null || user.isBlank()) && auth.isAllowAnonymous()) {
            user = auth.getAnonymousUser();
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("user", user == null || user.isBlank() ? null : user.trim());
        body.put("env", env);
        body.put("role", role);
        body.put("gitEnabled", git.isEnabled());
        body.put("mainBranch", git.getMainBranch());
        body.put("allowDraftPublish", allowDraftPublish);
        return body;
    }
}
