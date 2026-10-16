package limitium.art.camunda.backend.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.List;
import java.util.regex.Pattern;

/**
 * A deploy-only instance (production) changes nothing: no tables, tests, work spaces, versions,
 * release lines or clean-ups. It looks at what runs and at any version, runs tests and tries rules
 * (computing, never storing), and deploys released versions. Every other write is refused here,
 * before any controller, so a new endpoint is closed on production until it is added below.
 */
@Component
public class DeployOnlyGuard implements HandlerInterceptor {

    /** The writes production allows: deploying tagged versions, and computing without storing. */
    private static final List<Pattern> ALLOWED = List.of(
            Pattern.compile("/api/applications/[^/]+/(deploy|redeploy|restore)"),
            Pattern.compile("/api/commit/tests"),
            Pattern.compile("/api/runs"),
            Pattern.compile("/api/evaluate"),
            Pattern.compile("/api/diff(/merge-preview)?"),
            Pattern.compile("/api/dmn/validate"),
            Pattern.compile("/api/sync"));

    private final boolean deployOnly;

    public DeployOnlyGuard(@Value("${dmn.role:author}") String role) {
        this.deployOnly = "deployer".equals(role);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (!deployOnly) {
            return true;
        }
        String method = request.getMethod();
        if ("GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method)) {
            return true;
        }
        String path = request.getRequestURI().substring(request.getContextPath().length());
        for (Pattern p : ALLOWED) {
            if (p.matcher(path).matches()) {
                return true;
            }
        }
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json");
        response.getWriter().write("{\"code\":\"DEPLOY_ONLY\",\"error\":\"this instance deploys released versions only; nothing is changed here\"}");
        return false;
    }
}
