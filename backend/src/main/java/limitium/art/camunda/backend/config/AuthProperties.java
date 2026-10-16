package limitium.art.camunda.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@ConfigurationProperties(prefix = "dmn.auth")
@Component
public class AuthProperties {

    /** Set by the proxy that is the only route to this backend; callers cannot change it. */
    private String userHeader = "X-Forwarded-User";
    /**
     * Whether a request without the header is served. False in any real deployment: a request that
     * did not come through the proxy is a deployment fault, not an anonymous user, and serving it
     * would turn a misrouted deployment into untraceable writes.
     */
    private boolean allowAnonymous = false;
    /** Identity used when {@code allow-anonymous} is on, for local runs and tests. */
    private String anonymousUser = "local";

    public String getUserHeader() {
        return userHeader;
    }

    public void setUserHeader(String userHeader) {
        this.userHeader = userHeader;
    }

    public boolean isAllowAnonymous() {
        return allowAnonymous;
    }

    public void setAllowAnonymous(boolean allowAnonymous) {
        this.allowAnonymous = allowAnonymous;
    }

    public String getAnonymousUser() {
        return anonymousUser;
    }

    public void setAnonymousUser(String anonymousUser) {
        this.anonymousUser = anonymousUser;
    }
}
