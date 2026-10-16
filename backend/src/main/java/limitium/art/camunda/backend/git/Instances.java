package limitium.art.camunda.backend.git;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which servers each instance deploys to, declared through git — the only thing instances share.
 *
 * <p>Instances must deploy to servers no other instance deploys to: two instances writing one topic
 * would overwrite each other's rules without either knowing. Each instance writes its own list under
 * {@code refs/instances/<env>} (only it ever writes that ref), reads everyone else's, and refuses to
 * deploy while any of its servers is also another instance's.
 */
public class Instances {

    static final String PREFIX = "refs/instances/";
    private static final ObjectMapper JSON = new ObjectMapper();

    public static class Declared {
        public String env;
        public List<Map<String, Object>> servers = new ArrayList<>();
        public String declaredAt;
    }

    /** One of this instance's servers that another instance also deploys to. */
    public static class Overlap {
        public String server;
        public String otherInstance;
        public String otherServer;

        @Override
        public String toString() {
            return server + " is also " + otherInstance + "'s server " + otherServer;
        }
    }

    private final GitRuleStore store;

    public Instances(GitRuleStore store) {
        this.store = store;
    }

    /** Writes this instance's servers, if they changed; the next exchange sends them. */
    public synchronized void declare(String env, List<Map<String, Object>> servers) throws IOException {
        Declared mine = new Declared();
        mine.env = env;
        for (Map<String, Object> s : servers) {
            Map<String, Object> visible = new LinkedHashMap<>();
            for (String key : new String[]{"name", "type", "topic", "where"}) {
                visible.put(key, s.get(key)); // never credentials: describe() holds none
            }
            mine.servers.add(visible);
        }
        Declared current = read(env);
        if (current != null && JSON.writeValueAsString(current.servers).equals(JSON.writeValueAsString(mine.servers))) {
            return;
        }
        mine.declaredAt = Instant.now().toString();
        try (Git git = Git.open(store.mainCheckout().toFile()); ObjectInserter inserter = git.getRepository().newObjectInserter()) {
            ObjectId blob = inserter.insert(Constants.OBJ_BLOB, JSON.writeValueAsBytes(mine));
            inserter.flush();
            RefUpdate update = git.getRepository().updateRef(PREFIX + env);
            update.setNewObjectId(blob);
            update.setForceUpdate(true);
            update.update();
        }
    }

    public List<Declared> all() throws IOException {
        List<Declared> all = new ArrayList<>();
        try (Git git = Git.open(store.mainCheckout().toFile())) {
            for (Ref ref : git.getRepository().getRefDatabase().getRefsByPrefix(PREFIX)) {
                try {
                    all.add(JSON.readValue(git.getRepository().open(ref.getObjectId()).getBytes(), Declared.class));
                } catch (Exception e) {
                    // an unreadable declaration is not one
                }
            }
        }
        return all;
    }

    private Declared read(String env) throws IOException {
        for (Declared d : all()) {
            if (env.equals(d.env)) {
                return d;
            }
        }
        return null;
    }

    /** This instance's servers that another instance also declares. */
    public List<Overlap> overlaps(String env, List<Map<String, Object>> mine) throws IOException {
        List<Overlap> overlaps = new ArrayList<>();
        for (Declared other : all()) {
            if (env.equals(other.env)) {
                continue;
            }
            for (Map<String, Object> a : mine) {
                for (Map<String, Object> b : other.servers) {
                    if (same(a, b)) {
                        Overlap o = new Overlap();
                        o.server = String.valueOf(a.get("name"));
                        o.otherInstance = other.env;
                        o.otherServer = String.valueOf(b.get("name"));
                        overlaps.add(o);
                    }
                }
            }
        }
        return overlaps;
    }

    /** One topic on one cluster (any shared broker address), or one folder. */
    static boolean same(Map<String, Object> a, Map<String, Object> b) {
        if (!String.valueOf(a.get("type")).equals(String.valueOf(b.get("type")))
                || !String.valueOf(a.get("topic")).equals(String.valueOf(b.get("topic")))) {
            return false;
        }
        if ("file".equals(a.get("type"))) {
            return String.valueOf(a.get("where")).equals(String.valueOf(b.get("where")));
        }
        return !intersection(hosts(a.get("where")), hosts(b.get("where"))).isEmpty();
    }

    private static Set<String> hosts(Object where) {
        Set<String> hosts = new HashSet<>();
        for (String h : String.valueOf(where).split(",")) {
            String host = h.trim().toLowerCase().replaceFirst("^[a-z]+://", "");
            if (!host.isEmpty()) {
                hosts.add(host.replaceFirst("^127\\.0\\.0\\.1:", "localhost:"));
            }
        }
        return hosts;
    }

    private static Set<String> intersection(Set<String> a, Set<String> b) {
        Set<String> both = new HashSet<>(a);
        both.retainAll(b);
        return both;
    }
}
