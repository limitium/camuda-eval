package limitium.art.camunda.backend.git;

/**
 * A working space is a branch, and its id <em>is</em> the branch name, so owner and kind are read off
 * the ref rather than stored in a sidecar that could disagree with it.
 *
 * <pre>
 * feature/alice/pricing-q4   kind=FEATURE  owner=alice  name=pricing-q4
 * hotfix/bob/rounding        kind=HOTFIX   owner=bob    name=rounding
 * </pre>
 */
public final class WorkspaceId {

    public enum Kind {
        FEATURE("feature"),
        HOTFIX("hotfix");

        public final String prefix;

        Kind(String prefix) {
            this.prefix = prefix;
        }

        static Kind fromPrefix(String prefix) {
            for (Kind kind : values()) {
                if (kind.prefix.equals(prefix)) {
                    return kind;
                }
            }
            return null;
        }
    }

    public final Kind kind;
    public final String owner;
    public final String name;

    private WorkspaceId(Kind kind, String owner, String name) {
        this.kind = kind;
        this.owner = owner;
        this.name = name;
    }

    public static WorkspaceId of(Kind kind, String owner, String name) {
        String safeName = slug(name);
        if (safeName.isEmpty()) {
            throw new IllegalArgumentException("working space name must contain a letter or digit");
        }
        return new WorkspaceId(kind, slug(owner), safeName);
    }

    /** Parses a branch name back into an id, or returns null if it is not a working space branch. */
    public static WorkspaceId parse(String branch) {
        if (branch == null) {
            return null;
        }
        String[] parts = branch.split("/", 3);
        if (parts.length != 3) {
            return null;
        }
        Kind kind = Kind.fromPrefix(parts[0]);
        if (kind == null || parts[1].isEmpty() || parts[2].isEmpty()) {
            return null;
        }
        return new WorkspaceId(kind, parts[1], parts[2]);
    }

    public String branch() {
        return kind.prefix + "/" + owner + "/" + name;
    }

    /**
     * A directory name for this workspace's checkout. Flattened because a checkout per working space
     * nested three deep would collide with the repository layout itself.
     */
    public String checkoutDir() {
        return kind.prefix + "__" + owner + "__" + name;
    }

    /** Keeps a branch name legal and a path traversal impossible in one step. */
    private static String slug(String value) {
        if (value == null) {
            return "";
        }
        String cleaned = value.trim().toLowerCase()
                .replaceAll("[^a-z0-9._-]+", "-")
                .replaceAll("-{2,}", "-")
                .replaceAll("^[-.]+|[-.]+$", "");
        return cleaned;
    }

    @Override
    public String toString() {
        return branch();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof WorkspaceId && branch().equals(((WorkspaceId) other).branch());
    }

    @Override
    public int hashCode() {
        return branch().hashCode();
    }
}
