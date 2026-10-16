package limitium.art.camunda.evaluator.diff;

import java.util.Collections;
import java.util.List;

public class MergeResult {
    public final boolean clean;
    /** The merged document. Present whenever every conflict was resolved, null otherwise. */
    public final String mergedXml;
    public final List<MergeConflict> conflicts;
    public final int cellsTakenFromTheirs;
    public final int rulesAdded;
    public final int rulesRemoved;
    /**
     * The merged document with every open conflict answered "ours" for now: what the person resolving
     * looks at. Never landed — only {@link #mergedXml} is, and only when nothing is open.
     */
    public final String previewXml;

    public MergeResult(boolean clean, String mergedXml, List<MergeConflict> conflicts,
                       int cellsTakenFromTheirs, int rulesAdded, int rulesRemoved) {
        this(clean, mergedXml, conflicts, cellsTakenFromTheirs, rulesAdded, rulesRemoved, mergedXml);
    }

    public MergeResult(boolean clean, String mergedXml, List<MergeConflict> conflicts,
                       int cellsTakenFromTheirs, int rulesAdded, int rulesRemoved, String previewXml) {
        this.previewXml = previewXml;
        this.clean = clean;
        this.mergedXml = mergedXml;
        this.conflicts = Collections.unmodifiableList(conflicts);
        this.cellsTakenFromTheirs = cellsTakenFromTheirs;
        this.rulesAdded = rulesAdded;
        this.rulesRemoved = rulesRemoved;
    }
}
