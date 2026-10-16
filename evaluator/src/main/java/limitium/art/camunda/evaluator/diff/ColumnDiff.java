package limitium.art.camunda.evaluator.diff;

/** A column in the merged column list. Index is -1 on a side where the column does not exist. */
public class ColumnDiff {
    public final String id;
    public final ChangeType change;
    public final int leftIndex;
    public final int rightIndex;
    public final String leftLabel;
    public final String rightLabel;
    public final String leftExpression;
    public final String rightExpression;
    public final String leftTypeRef;
    public final String rightTypeRef;

    public ColumnDiff(String id, ChangeType change, int leftIndex, int rightIndex,
                      String leftLabel, String rightLabel,
                      String leftExpression, String rightExpression,
                      String leftTypeRef, String rightTypeRef) {
        this.id = id;
        this.change = change;
        this.leftIndex = leftIndex;
        this.rightIndex = rightIndex;
        this.leftLabel = leftLabel;
        this.rightLabel = rightLabel;
        this.leftExpression = leftExpression;
        this.rightExpression = rightExpression;
        this.leftTypeRef = leftTypeRef;
        this.rightTypeRef = rightTypeRef;
    }
}
