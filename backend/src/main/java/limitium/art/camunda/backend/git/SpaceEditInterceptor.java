package limitium.art.camunda.backend.git;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import limitium.art.camunda.backend.config.GitProperties;
import org.eclipse.jgit.lib.Constants;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.file.Files;
import java.util.concurrent.locks.Lock;

/**
 * Holds a space shared for the whole of any request that reads or writes its files, so a save can
 * never interleave with that space's commit, deletion, tidy-up or restore — those take it exclusively.
 * Many saves in one space still run at once; the per-file lock in VersionedFiles orders saves of the
 * same file. A space that no longer exists is an honest 404, checked after the lock is held.
 */
@Component
public class SpaceEditInterceptor implements HandlerInterceptor {

    private static final String HELD = SpaceEditInterceptor.class.getName() + ".held";

    private final GitProperties properties;
    private final GitRuleStore store;

    public SpaceEditInterceptor(GitProperties properties, GitRuleStore store) {
        this.properties = properties;
        this.store = store;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String ws = request.getParameter("ws");
        if (!properties.isEnabled() || ws == null || ws.isBlank()) {
            return true; // flat mode, or the controller reports the missing ws itself
        }
        WorkspaceId id = WorkspaceId.parse(ws);
        if (id == null) {
            return true;
        }
        Lock lock = store.spaceLock(id).readLock();
        lock.lock();
        if (!Files.exists(store.checkoutOf(id).resolve(Constants.DOT_GIT))) {
            lock.unlock();
            response.setStatus(HttpStatus.NOT_FOUND.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"error\":\"no such working set: " + id.branch().replace("\"", "") + "\"}");
            return false;
        }
        request.setAttribute(HELD, lock);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        Object held = request.getAttribute(HELD);
        if (held instanceof Lock) {
            request.removeAttribute(HELD);
            ((Lock) held).unlock();
        }
    }
}
