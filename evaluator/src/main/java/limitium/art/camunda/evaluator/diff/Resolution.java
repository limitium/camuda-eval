package limitium.art.camunda.evaluator.diff;

/**
 * Which side a caller chose for a conflict.
 *
 * <p>{@code OURS} and {@code THEIRS} mean "take that side's state", which covers deletions too: where
 * we deleted a rule and they edited it, {@code OURS} keeps it deleted and {@code THEIRS} restores it
 * with their edits. {@code BOTH} exists only where keeping both is meaningful, such as two columns
 * added at the same position.
 */
public enum Resolution {
    OURS,
    THEIRS,
    BOTH
}
