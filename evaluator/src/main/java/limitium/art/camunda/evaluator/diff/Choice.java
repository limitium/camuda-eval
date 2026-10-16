package limitium.art.camunda.evaluator.diff;

/**
 * One answer a conflict accepts, carried with the conflict itself so a UI can render buttons
 * generically rather than switching on the conflict kind.
 */
public class Choice {
    public final Resolution key;
    /** What the button says, phrased for this conflict — "keep deleted", not "ours". */
    public final String label;
    /** The resulting value where there is one to preview; null for structural choices. */
    public final String value;

    public Choice(Resolution key, String label, String value) {
        this.key = key;
        this.label = label;
        this.value = value;
    }

    static Choice ours(String label, String value) {
        return new Choice(Resolution.OURS, label, value);
    }

    static Choice theirs(String label, String value) {
        return new Choice(Resolution.THEIRS, label, value);
    }

    static Choice both(String label) {
        return new Choice(Resolution.BOTH, label, null);
    }
}
