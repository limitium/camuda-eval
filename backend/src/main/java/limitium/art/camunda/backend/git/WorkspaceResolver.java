package limitium.art.camunda.backend.git;

import limitium.art.camunda.backend.config.GitProperties;
import org.eclipse.jgit.lib.Constants;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Turns {@code ?ws=<id>} into the checkout a request should read and write.
 *
 * <p>There is no fallback. Defaulting a missing or stale working space to {@code main} would hand
 * back a real document and let someone edit the integration line believing it was their own work — an
 * honest 404 costs one round trip, a silent fallback costs an afternoon.
 */
@Component
public class WorkspaceResolver {

    private final GitProperties properties;
    private final GitRuleStore store;

    public WorkspaceResolver(GitProperties properties, GitRuleStore store) {
        this.properties = properties;
        this.store = store;
    }

    public boolean gitEnabled() {
        return properties.isEnabled();
    }

    /**
     * Writes must carry a precondition only when working spaces exist. Without git there is one
     * shared directory and no concurrent editing model to protect.
     */
    public boolean requirePrecondition() {
        return properties.isEnabled();
    }

    /** The working space's DMN directory, or the flat one when git is off. */
    public Path dmnDir(String workspace, Path flatDmnDir) {
        return properties.isEnabled() ? checkout(workspace).resolve("dmn") : flatDmnDir;
    }

    /** The working space's test spec directory, or the flat one when git is off. */
    public Path yamlDir(String workspace, Path flatYamlDir) {
        return properties.isEnabled() ? checkout(workspace).resolve("yaml") : flatYamlDir;
    }

    /**
     * Resolves a file name inside a directory, refusing anything that would leave it. The name comes
     * straight from the URL, so without this {@code ../../x} is a request for an arbitrary file.
     */
    public Path file(Path dir, String name) {
        if (name == null || name.isBlank() || name.startsWith(".")
                || name.contains("/") || name.contains("\\") || name.contains("\u0000")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid file name: " + name);
        }
        Path base = dir.toAbsolutePath().normalize();
        Path resolved = base.resolve(name).normalize();
        if (!resolved.getParent().equals(base)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid file name: " + name);
        }
        return resolved;
    }

    private Path checkout(String workspace) {
        if (workspace == null || workspace.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "ws is required: name the working space this request applies to");
        }
        WorkspaceId id = WorkspaceId.parse(workspace);
        if (id == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no such working set: " + workspace);
        }
        Path checkout = store.checkoutOf(id);
        if (!Files.exists(checkout.resolve(Constants.DOT_GIT))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no such working set: " + workspace);
        }
        return checkout;
    }

    public WorkspaceId parse(String workspace) {
        WorkspaceId id = WorkspaceId.parse(workspace);
        if (id == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no such working set: " + workspace);
        }
        return id;
    }
}
