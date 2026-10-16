package limitium.art.camunda.backend.git;

import jakarta.servlet.http.HttpServletRequest;
import limitium.art.camunda.backend.config.AuthProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Who is calling, from the header the proxy sets.
 *
 * <p>The proxy is the only route to this backend and callers cannot set the header, so the identity
 * is real attribution rather than a self-reported label — which is what makes {@code owner} and
 * {@code publishedBy} worth recording at all.
 */
@Component
public class UserContext {

    private final AuthProperties properties;

    public UserContext(AuthProperties properties) {
        this.properties = properties;
    }

    public String require(HttpServletRequest request) {
        String user = request == null ? null : request.getHeader(properties.getUserHeader());
        if (user != null && !user.isBlank()) {
            return user.trim();
        }
        if (properties.isAllowAnonymous()) {
            return properties.getAnonymousUser();
        }
        // A request without the header did not come through the proxy. That is a deployment fault,
        // not an anonymous user, and serving it would make writes untraceable.
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                "missing " + properties.getUserHeader() + "; requests must arrive through the proxy");
    }
}
