package limitium.art.camunda.backend.deploy;

/**
 * One domain as published: its content and where it came from. The key is {@link #name}, the
 * domain's file name; a missing value (tombstone) means the domain does not run.
 */
public class Envelope {
    public String name;
    public String dmnXml;
    public String commit;
    /** The released version, or null for a draft. */
    public String release;
    public String line;
    /** The space a draft came from, or null for a release. */
    public String workspace;
    public String owner;
    public String baseline;
    public String publishedAt;
    public String publishedBy;
    /** The instance that published it. */
    public String env;

    /** The same envelope without its content, for listing what runs. */
    public Envelope summary() {
        Envelope e = new Envelope();
        e.name = name;
        e.commit = commit;
        e.release = release;
        e.line = line;
        e.workspace = workspace;
        e.owner = owner;
        e.baseline = baseline;
        e.publishedAt = publishedAt;
        e.publishedBy = publishedBy;
        e.env = env;
        return e;
    }
}
