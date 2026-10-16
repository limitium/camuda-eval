package limitium.art.camunda.evaluator.diff;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * One thing the merge could not decide on its own.
 *
 * <p>These documents are structured, so the set of things that can genuinely conflict is closed and
 * small. Each conflict names exactly what disagrees — a cell, a row, a column, a hit policy — and
 * carries the answers it accepts, so resolving is choosing rather than editing.
 *
 * <p>Granularity is as small as the change that caused it: resolving one conflict never discards
 * unrelated work on either side.
 */
public class MergeConflict {

    public enum Kind {
        // --- a domain (one DMN file and its tests) ---
        /** One side removed the whole domain, the other changed it. */
        DOMAIN_DELETE_MODIFY,
        /** Both sides created a domain of the same name, differently. The name is its key: one must win. */
        DOMAIN_ADD_ADD,

        // --- a table ---
        /** One deleted a decision the other edited. */
        DECISION_DELETE_MODIFY,
        /** Both added the same decision (same id), differently. */
        DECISION_ADD_ADD,
        /** Both changed a decision that is an expression rather than a table. */
        EXPRESSION,
        /** Both changed the hit policy or aggregation differently. */
        HIT_POLICY,

        // --- a column ---
        /** Both changed what a column checks (input) or what it gives (output name), differently. */
        COLUMN_RENAME,
        /** Both changed a column's type differently. */
        COLUMN_TYPE,
        /** One removed a column the other changed. */
        COLUMN_DELETE_MODIFY,
        /**
         * One removed a column while the other's new or changed rules put a real value in it. The
         * dangerous one: the merged rows look intact but have silently lost a condition or result.
         */
        COLUMN_DELETE_IN_USE,

        // --- a rule ---
        /** Both changed the same cell differently. */
        CELL,
        /** One deleted a rule the other edited. */
        RULE_DELETE_MODIFY,
        /** Both added a rule with the same conditions but different results. */
        RULE_ADD_ADD,
        /** Both reordered the rules, differently. Order is always treated as significant. */
        RULE_ORDER,

        // --- connections, which follow names: checked on the merged result ---
        /**
         * A table checks a value that, after the merge, no table gives any more — though on its own
         * side one did (the other side renamed or removed it). The table silently becomes one that
         * asks the caller for that value.
         */
        CONNECTION_LOST,
        /**
         * After the merge two tables give a result of the same name, which neither side had alone.
         * Tables checking that name would silently follow whichever comes first.
         */
        RESULT_CLASH,

        // --- tests ---
        /** Both changed the same test case's expectations differently. */
        TEST_EXPECTATION,
        /** One removed a test case the other edited. */
        TEST_DELETE_MODIFY,
        /** Both added a test case with the same inputs but different expectations. */
        TEST_ADD_ADD,
        /** One removed an expectation column the other's new or changed cases fill in. */
        TEST_COLUMN_DELETE_IN_USE,

        // --- words only ---
        /** Names, labels, descriptions, scenario names: both changed the text differently. */
        TEXT,

        /**
         * A rule could only be paired by similarity, not by id or conditions. Fine for a diff, refused
         * here: an unattended merge must not guess which rule is which, so a person says.
         */
        LOW_CONFIDENCE_MATCH
    }

    /** Whether this affects evaluation, or only how the table reads. */
    public enum Tier {
        SEMANTIC,
        /** Names, labels and descriptions: real disagreements, but resolvable in bulk. */
        COSMETIC
    }

    /** Stable identifier, used as the key when a caller supplies resolutions. */
    public final String id;
    public final Kind kind;
    public final Tier tier;
    public final String decisionId;
    public final String ruleId;
    public final String columnId;
    /** A human sentence naming what disagrees, e.g. 'Discount for [100..300), "EU"'. */
    public final String label;
    public final String base;
    public final String ours;
    public final String theirs;
    public final List<Choice> choices;
    public final String detail;
    /** Commits that produced our value, most recent first; empty when history is unavailable. */
    public final List<Attribution> oursChanges;
    /**
     * Commits that produced their value, most recent first. Usually the ones worth reading before
     * choosing, and there can be several: the same cell may have been changed more than once on the
     * incoming branch.
     */
    public final List<Attribution> theirsChanges;

    public MergeConflict(String id, Kind kind, Tier tier, String decisionId, String ruleId,
                         String columnId, String label, String base, String ours, String theirs,
                         List<Choice> choices, String detail) {
        this(id, kind, tier, decisionId, ruleId, columnId, label, base, ours, theirs, choices,
                detail, Collections.<Attribution>emptyList(), Collections.<Attribution>emptyList());
    }

    public MergeConflict(String id, Kind kind, Tier tier, String decisionId, String ruleId,
                         String columnId, String label, String base, String ours, String theirs,
                         List<Choice> choices, String detail,
                         List<Attribution> oursChanges, List<Attribution> theirsChanges) {
        this.id = id;
        this.kind = kind;
        this.tier = tier;
        this.decisionId = decisionId;
        this.ruleId = ruleId;
        this.columnId = columnId;
        this.label = label;
        this.base = base;
        this.ours = ours;
        this.theirs = theirs;
        this.choices = Collections.unmodifiableList(choices);
        this.detail = detail;
        this.oursChanges = oursChanges == null
                ? Collections.<Attribution>emptyList() : Collections.unmodifiableList(oursChanges);
        this.theirsChanges = theirsChanges == null
                ? Collections.<Attribution>emptyList() : Collections.unmodifiableList(theirsChanges);
    }

    /** Returns a copy carrying the commits that produced each side. */
    MergeConflict attributed(List<Attribution> ours, List<Attribution> theirs) {
        boolean noneOurs = ours == null || ours.isEmpty();
        boolean noneTheirs = theirs == null || theirs.isEmpty();
        if (noneOurs && noneTheirs) {
            return this;
        }
        return new MergeConflict(id, kind, tier, decisionId, ruleId, columnId, label,
                base, this.ours, this.theirs, choices, detail, ours, theirs);
    }

    static List<Choice> oursOrTheirs(String oursLabel, String oursValue,
                                     String theirsLabel, String theirsValue) {
        return Arrays.asList(Choice.ours(oursLabel, oursValue), Choice.theirs(theirsLabel, theirsValue));
    }
}
