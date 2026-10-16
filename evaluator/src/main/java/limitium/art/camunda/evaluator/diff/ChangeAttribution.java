package limitium.art.camunda.evaluator.diff;

import java.util.Collections;
import java.util.List;

/**
 * Looks up the commits that touched the element a conflict is about, per side.
 *
 * <p>A conflict can have several sources: the incoming side is a branch, and the same cell, rule or
 * column may have been changed by more than one commit on the way. All of them are returned, most
 * recent first, because "why is this value here" is sometimes answered by the second commit rather
 * than the last.
 *
 * <p>Implemented by the caller that owns history — the evaluator module stays free of any version
 * control dependency. {@link #NONE} is the default when no history is available, such as when
 * merging two documents handed in directly.
 */
public interface ChangeAttribution {

    ChangeAttribution NONE = new ChangeAttribution() {
        public List<Attribution> forConflict(String conflictId, Resolution side) {
            return Collections.emptyList();
        }
    };

    /**
     * @param conflictId the conflict's stable id, identifying the cell, rule, column or decision
     * @param side       which side's history to attribute
     * @return the commits that touched it on that side, most recent first; empty if unknown
     */
    List<Attribution> forConflict(String conflictId, Resolution side);
}
