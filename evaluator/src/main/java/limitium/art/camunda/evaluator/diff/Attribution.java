package limitium.art.camunda.evaluator.diff;

/**
 * The change that introduced one side of a conflict.
 *
 * <p>A conflict is far easier to resolve when you can see <em>why</em> the other value is there. One
 * local edit can collide with several incoming commits in different ways, so attribution belongs to
 * the individual conflict rather than to the merge as a whole.
 */
public class Attribution {
    public final String sha;
    public final String shortSha;
    public final String message;
    public final String author;
    /** ISO-8601 instant. */
    public final String when;

    public Attribution(String sha, String shortSha, String message, String author, String when) {
        this.sha = sha;
        this.shortSha = shortSha;
        this.message = message;
        this.author = author;
        this.when = when;
    }
}
