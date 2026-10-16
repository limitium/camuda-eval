package limitium.art.camunda.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Where the rule repository lives and how to reach it.
 *
 * <p>With {@code remote-url} blank the repository is initialised locally and never pushed, which is
 * what makes the whole lifecycle runnable in a test or on a laptop with no server.
 */
@Component
@ConfigurationProperties(prefix = "dmn.git")
public class GitProperties {

    private boolean enabled = false;
    private String remoteUrl = "";
    /** Holds one checkout per working space, plus one for main. */
    private String workRoot = "";
    private String mainBranch = "main";
    private String username = "";
    private String token = "";
    private String sshKeyPath = "";
    private String authorName = "camunda-eval";
    private String authorEmail = "camunda-eval@localhost";
    /** A space untouched for this long, with work not landed anywhere, is archived by a tidy-up. */
    private int staleAfterDays = 30;
    /**
     * A space with nothing of its own is offered for removal only after this long without activity,
     * so a space someone has just opened never vanishes before they start.
     */
    private int idleGraceDays = 7;
    /** How often this instance exchanges versions with the remote; 0 turns the timer off. */
    private int syncSeconds = 30;
    /** How long one conversation with the remote may take. */
    private int remoteTimeoutSeconds = 30;

    public int getSyncSeconds() {
        return syncSeconds;
    }

    public void setSyncSeconds(int syncSeconds) {
        this.syncSeconds = syncSeconds;
    }

    public int getRemoteTimeoutSeconds() {
        return remoteTimeoutSeconds;
    }

    public void setRemoteTimeoutSeconds(int remoteTimeoutSeconds) {
        this.remoteTimeoutSeconds = remoteTimeoutSeconds;
    }

    /** The newest release lines always kept open, whatever their state. */
    private int keepReleaseLines = 3;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getRemoteUrl() {
        return remoteUrl;
    }

    public void setRemoteUrl(String remoteUrl) {
        this.remoteUrl = remoteUrl;
    }

    public boolean hasRemote() {
        return remoteUrl != null && !remoteUrl.isBlank();
    }

    public String getWorkRoot() {
        return workRoot;
    }

    public void setWorkRoot(String workRoot) {
        this.workRoot = workRoot;
    }

    public Path workRootPath(Path dataRoot) {
        return workRoot == null || workRoot.isBlank() ? dataRoot.resolve("ws") : Paths.get(workRoot);
    }

    public String getMainBranch() {
        return mainBranch;
    }

    public void setMainBranch(String mainBranch) {
        this.mainBranch = mainBranch;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public String getSshKeyPath() {
        return sshKeyPath;
    }

    public void setSshKeyPath(String sshKeyPath) {
        this.sshKeyPath = sshKeyPath;
    }

    public String getAuthorName() {
        return authorName;
    }

    public void setAuthorName(String authorName) {
        this.authorName = authorName;
    }

    public String getAuthorEmail() {
        return authorEmail;
    }

    public void setAuthorEmail(String authorEmail) {
        this.authorEmail = authorEmail;
    }

    public int getStaleAfterDays() {
        return staleAfterDays;
    }

    public void setStaleAfterDays(int staleAfterDays) {
        this.staleAfterDays = staleAfterDays;
    }

    public int getKeepReleaseLines() {
        return keepReleaseLines;
    }

    public void setKeepReleaseLines(int keepReleaseLines) {
        this.keepReleaseLines = keepReleaseLines;
    }

    public int getIdleGraceDays() {
        return idleGraceDays;
    }

    public void setIdleGraceDays(int idleGraceDays) {
        this.idleGraceDays = idleGraceDays;
    }
}
