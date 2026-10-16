package limitium.art.camunda.backend.git;

/** A working space as the API reports it. Everything here is derived from refs, nothing is stored. */
public class Workspace {
    public final String id;
    public final String kind;
    public final String owner;
    public final String name;
    /** The revision it was cut from: a tag for a hotfix, a branch for a feature. */
    public final String baseline;
    /** The line it lands on, from which the merge chain continues. */
    public final String targetLine;
    public final String head;
    public final String lastMessage;
    public final String lastModifiedBy;
    public final String lastModifiedAt;
    /** Uncommitted edits in this workspace's checkout. Publishing requires this to be false. */
    public final boolean dirty;
    public final int ahead;
    public final int behind;

    public Workspace(String id, String kind, String owner, String name, String baseline,
                     String targetLine, String head, String lastMessage, String lastModifiedBy,
                     String lastModifiedAt, boolean dirty, int ahead, int behind) {
        this.id = id;
        this.kind = kind;
        this.owner = owner;
        this.name = name;
        this.baseline = baseline;
        this.targetLine = targetLine;
        this.head = head;
        this.lastMessage = lastMessage;
        this.lastModifiedBy = lastModifiedBy;
        this.lastModifiedAt = lastModifiedAt;
        this.dirty = dirty;
        this.ahead = ahead;
        this.behind = behind;
    }
}
