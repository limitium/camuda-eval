package limitium.art.camunda.evaluator.diff;

/** One cell of a rule, aligned to the merged column list. {@code left}/{@code right} are null where the column is absent on that side. */
public class CellDiff {
    public final String columnId;
    public final ChangeType change;
    public final String left;
    public final String right;

    public CellDiff(String columnId, ChangeType change, String left, String right) {
        this.columnId = columnId;
        this.change = change;
        this.left = left;
        this.right = right;
    }
}
