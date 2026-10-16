package limitium.art.camunda.evaluator.diff;

public enum ChangeType {
    ADDED,
    REMOVED,
    CHANGED,
    /** Content is identical but the rule moved position, which is semantic under FIRST and PRIORITY. */
    MOVED,
    UNCHANGED
}
