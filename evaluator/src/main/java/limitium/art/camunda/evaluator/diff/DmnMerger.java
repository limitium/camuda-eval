package limitium.art.camunda.evaluator.diff;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.ByteArrayInputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Three-way merge of one domain's DMN: every table, column, rule and cell, plus the connections
 * between tables, which follow names.
 *
 * <p>Everything one side changed and the other did not is taken — rules, cells, columns added,
 * removed, relabelled, retyped, renamed; tables added and removed. Only where both changed the same
 * thing differently is there a conflict, and each conflict carries its answers. The result is built
 * on ours (its diagram and anything this merge does not understand are kept) and answered
 * conflicts are applied; open ones read as "ours" in the preview and leave {@code mergedXml} null.
 *
 * <p>Identity: a decision by id; a column by id, then by what it checks or gives; a rule by id, then
 * by its conditions. A rule that can only be paired by similarity is never guessed at
 * ({@link MergeConflict.Kind#LOW_CONFIDENCE_MATCH}).
 */
public final class DmnMerger {

    private static final String WILDCARD = DmnDiffer.WILDCARD;

    private DmnMerger() {
    }

    public static MergeResult merge(String baseXml, String oursXml, String theirsXml) {
        return merge(baseXml, oursXml, theirsXml, Collections.<String, Resolution>emptyMap());
    }

    public static MergeResult merge(String baseXml, String oursXml, String theirsXml,
                                    Map<String, Resolution> resolutions) {
        return merge(baseXml, oursXml, theirsXml, resolutions, ChangeAttribution.NONE);
    }

    /**
     * @param attribution resolves which change produced each side of a conflict, so the person
     *                    choosing can read the commit message behind the other value
     */
    public static MergeResult merge(String baseXml, String oursXml, String theirsXml,
                                    Map<String, Resolution> resolutions,
                                    ChangeAttribution attribution) {
        Merge m = new Merge(baseXml, oursXml, theirsXml,
                resolutions == null ? Collections.<String, Resolution>emptyMap() : resolutions);
        m.run();
        ChangeAttribution attr = attribution == null ? ChangeAttribution.NONE : attribution;
        List<MergeConflict> conflicts = new ArrayList<MergeConflict>();
        for (MergeConflict c : m.conflicts) {
            conflicts.add(c.attributed(attr.forConflict(c.id, Resolution.OURS), attr.forConflict(c.id, Resolution.THEIRS)));
        }
        String preview = serialize(m.out);
        boolean clean = conflicts.isEmpty();
        return new MergeResult(clean, clean ? preview : null, conflicts,
                m.cellsTakenFromTheirs, m.rulesAdded, m.rulesRemoved, preview);
    }

    // =============================================================================================

    private static final class Merge {
        final Map<String, DecisionTableReader.Table> base;
        final Map<String, DecisionTableReader.Table> ours;
        final Map<String, DecisionTableReader.Table> theirs;
        final Document baseDoc;
        final Document theirsDoc;
        final Document out;
        final Map<String, Resolution> chosen;
        final List<MergeConflict> conflicts = new ArrayList<MergeConflict>();
        int cellsTakenFromTheirs;
        int rulesAdded;
        int rulesRemoved;

        Merge(String baseXml, String oursXml, String theirsXml, Map<String, Resolution> chosen) {
            this.base = DecisionTableReader.read(baseXml);
            this.ours = DecisionTableReader.read(oursXml);
            this.theirs = DecisionTableReader.read(theirsXml);
            this.baseDoc = dom(baseXml);
            this.theirsDoc = dom(theirsXml);
            this.out = dom(oursXml);
            this.chosen = chosen;
        }

        void run() {
            List<String> order = new ArrayList<String>(ours.keySet());
            for (String id : theirs.keySet()) {
                if (!order.contains(id)) {
                    order.add(id);
                }
            }
            for (String id : base.keySet()) {
                if (!order.contains(id)) {
                    order.add(id);
                }
            }
            for (String id : order) {
                decision(id, base.get(id), ours.get(id), theirs.get(id));
            }
            uniqueIds(out); // before anything reads the merged document back
            connections();
            Requirements.sync(out);
            uniqueIds(out);
            dropDanglingDiagram(out);
        }

        // --- decisions ---------------------------------------------------------------------------

        void decision(String id, DecisionTableReader.Table b, DecisionTableReader.Table o, DecisionTableReader.Table t) {
            if (b == null) {
                if (o != null && t != null) {
                    if (!sameDecision(o, t)) {
                        String cid = key(id, null, "#added");
                        Resolution r = chosen.get(cid);
                        if (r == Resolution.THEIRS) {
                            replaceDecision(id);
                        } else if (r == null) {
                            conflicts.add(new MergeConflict(cid, MergeConflict.Kind.DECISION_ADD_ADD, MergeConflict.Tier.SEMANTIC,
                                    id, null, null, "table '" + name(t) + "' was added on both sides, differently",
                                    null, summary(o), summary(t),
                                    MergeConflict.oursOrTheirs("keep this side's " + name(o), null, "take the other side's " + name(t), null),
                                    "both sides added a table with this id; only one can be kept"));
                        }
                    }
                } else if (t != null) {
                    appendDecision(id);
                }
                return;
            }
            if (o == null && t == null) {
                return;
            }
            if (o == null) {
                if (sameDecision(b, t)) {
                    return; // ours removed it, theirs left it alone
                }
                String cid = key(id, null, null);
                Resolution r = chosen.get(cid);
                if (r == Resolution.THEIRS) {
                    appendDecision(id);
                } else if (r == null) {
                    conflicts.add(new MergeConflict(cid, MergeConflict.Kind.DECISION_DELETE_MODIFY, MergeConflict.Tier.SEMANTIC,
                            id, null, null, "table '" + name(b) + "' was removed on one side and changed on the other",
                            summary(b), null, summary(t),
                            MergeConflict.oursOrTheirs("keep it removed", null, "keep it, with the other side's changes", null),
                            "removed here, changed there"));
                }
                return;
            }
            if (t == null) {
                if (sameDecision(b, o)) {
                    removeDecision(id);
                    return;
                }
                String cid = key(id, null, null);
                Resolution r = chosen.get(cid);
                if (r == Resolution.THEIRS) {
                    removeDecision(id);
                } else if (r == null) {
                    conflicts.add(new MergeConflict(cid, MergeConflict.Kind.DECISION_DELETE_MODIFY, MergeConflict.Tier.SEMANTIC,
                            id, null, null, "table '" + name(b) + "' was changed on one side and removed on the other",
                            summary(b), summary(o), null,
                            MergeConflict.oursOrTheirs("keep it, with this side's changes", null, "remove it", null),
                            "changed here, removed there"));
                }
                return;
            }
            text(id, null, null, "#name", "name of table '" + name(b) + "'", b.decisionName, o.decisionName, t.decisionName,
                    new Apply() {
                        public void to(String value) {
                            Element d = byId(out, id);
                            if (d != null && value != null) {
                                d.setAttribute("name", value);
                            }
                        }
                    });
            if (!b.tabular || !o.tabular || !t.tabular) {
                expression(id, b, o, t);
                return;
            }
            new TableMerge(this, id, b, o, t).run();
        }

        /** A decision that is not a table merges whole: whichever side changed it, or a conflict. */
        void expression(String id, DecisionTableReader.Table b, DecisionTableReader.Table o, DecisionTableReader.Table t) {
            String bt = canonical(byId(baseDoc, id));
            String ot = canonical(byId(out, id));
            String tt = canonical(byId(theirsDoc, id));
            if (ot.equals(tt) || tt.equals(bt)) {
                return;
            }
            if (ot.equals(bt)) {
                replaceDecision(id);
                return;
            }
            String cid = key(id, null, "#expression");
            Resolution r = chosen.get(cid);
            if (r == Resolution.THEIRS) {
                replaceDecision(id);
            } else if (r == null) {
                conflicts.add(new MergeConflict(cid, MergeConflict.Kind.EXPRESSION, MergeConflict.Tier.SEMANTIC,
                        id, null, null, "the expression of '" + name(b) + "' was changed on both sides",
                        null, null, null,
                        MergeConflict.oursOrTheirs("keep this side's", null, "take the other side's", null),
                        "both sides changed this expression"));
            }
        }

        void appendDecision(String id) {
            Element source = byId(theirsDoc, id);
            if (source == null) {
                return;
            }
            Node copy = out.importNode(source, true);
            Element definitions = out.getDocumentElement();
            Node diagram = firstChildNamed(definitions, "DMNDI");
            definitions.insertBefore(copy, diagram);
        }

        void replaceDecision(String id) {
            Element mine = byId(out, id);
            Element source = byId(theirsDoc, id);
            if (mine == null || source == null) {
                return;
            }
            mine.getParentNode().replaceChild(out.importNode(source, true), mine);
        }

        void removeDecision(String id) {
            Element mine = byId(out, id);
            if (mine != null) {
                mine.getParentNode().removeChild(mine);
            }
        }

        // --- words -------------------------------------------------------------------------------

        void text(String decisionId, String ruleId, String columnId, String suffix, String what,
                  String b, String o, String t, Apply apply) {
            if (equalText(o, t) || equalText(t, b)) {
                return;
            }
            if (equalText(o, b)) {
                apply.to(t);
                return;
            }
            String cid = key(decisionId, ruleId, (columnId == null ? "" : columnId) + suffix);
            Resolution r = chosen.get(cid);
            if (r == Resolution.THEIRS) {
                apply.to(t);
            } else if (r == null) {
                conflicts.add(new MergeConflict(cid, MergeConflict.Kind.TEXT, MergeConflict.Tier.COSMETIC,
                        decisionId, ruleId, columnId, what, b, o, t,
                        MergeConflict.oursOrTheirs(String.valueOf(o), o, String.valueOf(t), t),
                        "both sides changed this text; it does not affect evaluation"));
            }
        }

        // --- connections ---------------------------------------------------------------------------

        /**
         * Connections follow names, so a clean merge can re-wire tables without any cell disagreeing.
         * Compares who feeds whom after the merge with who fed whom on each side.
         */
        void connections() {
            Map<String, DecisionTableReader.Table> merged = DecisionTableReader.read(serialize(out));
            Map<String, List<String>> givers = givers(merged);
            Map<String, List<String>> oursGivers = givers(ours);
            Map<String, List<String>> theirsGivers = givers(theirs);

            for (Map.Entry<String, List<String>> e : givers.entrySet()) {
                String name = e.getKey();
                List<String> ids = e.getValue();
                if (ids.size() < 2 || count(oursGivers, name) > 1 || count(theirsGivers, name) > 1) {
                    continue; // no clash, or one that was already there before the merge
                }
                String first = ids.get(0);
                String second = ids.get(1);
                String cid = key("", null, "clash:" + name);
                String secondName = uniqueName(merged, name + clean(name(merged.get(second))));
                String firstName = uniqueName(merged, name + clean(name(merged.get(first))));
                Resolution r = chosen.get(cid);
                if (r == Resolution.OURS) {
                    renameOutput(second, name, secondName);
                } else if (r == Resolution.THEIRS) {
                    renameOutput(first, name, firstName);
                } else {
                    conflicts.add(new MergeConflict(cid, MergeConflict.Kind.RESULT_CLASH, MergeConflict.Tier.SEMANTIC,
                            first, null, null, "two tables now give a result called '" + name + "'",
                            null, name(merged.get(first)), name(merged.get(second)),
                            Arrays.asList(
                                    Choice.ours(name(merged.get(first)) + " keeps '" + name + "'; " + name(merged.get(second)) + "'s becomes '" + secondName + "'", secondName),
                                    Choice.theirs(name(merged.get(second)) + " keeps '" + name + "'; " + name(merged.get(first)) + "'s becomes '" + firstName + "'", firstName)),
                            "tables checking '" + name + "' would silently use whichever table comes first"));
                }
            }

            for (DecisionTableReader.Table table : merged.values()) {
                if (!table.tabular) {
                    continue;
                }
                for (DecisionTableReader.Column input : table.inputs) {
                    String name = input.expression == null ? null : input.expression.trim();
                    if (name == null || givers.containsKey(name)) {
                        continue;
                    }
                    String fedOnOurs = fedBy(ours, oursGivers, table.decisionId, name);
                    String fedOnTheirs = fedBy(theirs, theirsGivers, table.decisionId, name);
                    String feeder = fedOnOurs != null ? fedOnOurs : fedOnTheirs;
                    if (feeder == null) {
                        continue; // asked of the caller on both sides already
                    }
                    String renamedTo = renamedOutput(feeder, name, merged);
                    String cid = key(table.decisionId, null, "connection:" + name);
                    Resolution r = chosen.get(cid);
                    if (r == Resolution.THEIRS && renamedTo != null) {
                        renameInput(table.decisionId, input.id, renamedTo);
                    } else if (r == null) {
                        String feederName = name(merged.containsKey(feeder) ? merged.get(feeder)
                                : (ours.containsKey(feeder) ? ours.get(feeder) : theirs.get(feeder)));
                        List<Choice> choices = new ArrayList<Choice>();
                        choices.add(Choice.ours("leave it: " + name(table) + " asks the caller for '" + name + "'", name));
                        if (renamedTo != null) {
                            choices.add(Choice.theirs("follow the rename: " + name(table) + " checks " + feederName + "'s '" + renamedTo + "'", renamedTo));
                        }
                        conflicts.add(new MergeConflict(cid, MergeConflict.Kind.CONNECTION_LOST, MergeConflict.Tier.SEMANTIC,
                                table.decisionId, null, input.id,
                                name(table) + " checks '" + name + "', which no table gives any more",
                                null, feederName, renamedTo,
                                choices,
                                renamedTo != null
                                        ? feederName + " now gives '" + renamedTo + "' instead"
                                        : feederName + " no longer gives it"));
                    }
                }
            }
        }

        /** The table that fed a value to this table on one side, or null. */
        String fedBy(Map<String, DecisionTableReader.Table> side, Map<String, List<String>> sideGivers, String consumer, String name) {
            DecisionTableReader.Table table = side.get(consumer);
            List<String> ids = sideGivers.get(name);
            if (ids == null || ids.isEmpty()) {
                return null;
            }
            if (table == null) {
                // The consumer is new on the other side; it would have been fed by whichever side gave it.
                return ids.get(0);
            }
            for (DecisionTableReader.Column input : table.inputs) {
                if (input.expression != null && input.expression.trim().equals(name)) {
                    return ids.get(0);
                }
            }
            return null;
        }

        /** What a table's output once called {@code name} is called after the merge (same column id), or null. */
        String renamedOutput(String decisionId, String name, Map<String, DecisionTableReader.Table> merged) {
            String columnId = null;
            for (Map<String, DecisionTableReader.Table> side : Arrays.asList(ours, theirs, base)) {
                DecisionTableReader.Table t = side.get(decisionId);
                if (t == null) {
                    continue;
                }
                for (DecisionTableReader.Column c : t.outputs) {
                    if (name.equals(c.expression)) {
                        columnId = c.id;
                    }
                }
            }
            DecisionTableReader.Table now = merged.get(decisionId);
            if (columnId == null || now == null) {
                return null;
            }
            for (DecisionTableReader.Column c : now.outputs) {
                if (columnId.equals(c.id) && c.expression != null && !name.equals(c.expression)) {
                    return c.expression;
                }
            }
            return null;
        }

        void renameOutput(String decisionId, String from, String to) {
            Element decision = byId(out, decisionId);
            if (decision == null) {
                return;
            }
            for (Element output : descendants(decision, "output")) {
                if (from.equals(output.getAttribute("name"))) {
                    output.setAttribute("name", to);
                }
            }
        }

        void renameInput(String decisionId, String inputId, String to) {
            Element input = byId(out, inputId);
            if (input == null) {
                return;
            }
            for (Element text : descendants(input, "text")) {
                text.setTextContent(to);
            }
        }
    }

    // =============================================================================================

    /** One table that exists on all three sides. */
    private static final class TableMerge {
        final Merge m;
        final String id;
        final DecisionTableReader.Table b;
        final DecisionTableReader.Table o;
        final DecisionTableReader.Table t;
        final List<Col> inputs;
        final List<Col> outputs;

        TableMerge(Merge m, String id, DecisionTableReader.Table b, DecisionTableReader.Table o, DecisionTableReader.Table t) {
            this.m = m;
            this.id = id;
            this.b = b;
            this.o = o;
            this.t = t;
            this.inputs = new ArrayList<Col>();
            this.outputs = new ArrayList<Col>();
        }

        void run() {
            hitPolicy();
            // Rules are matched first: whether a removed column is still in use depends on which
            // rules each side added or changed.
            RuleMatch oursMatch = matchRules(b, o, "ours");
            RuleMatch theirsMatch = matchRules(b, t, "theirs");
            columns(false, inputs, oursMatch, theirsMatch);
            columns(true, outputs, oursMatch, theirsMatch);
            List<Row> rows = rules(oursMatch, theirsMatch);
            write(rows);
        }

        // --- hit policy -------------------------------------------------------------------------

        void hitPolicy() {
            String result;
            if (equalText(o.hitPolicy, t.hitPolicy) || equalText(t.hitPolicy, b.hitPolicy)) {
                return;
            } else if (equalText(o.hitPolicy, b.hitPolicy)) {
                result = t.hitPolicy;
            } else {
                String cid = key(id, null, "#hitPolicy");
                Resolution r = m.chosen.get(cid);
                if (r == Resolution.THEIRS) {
                    result = t.hitPolicy;
                } else {
                    if (r == null) {
                        m.conflicts.add(new MergeConflict(cid, MergeConflict.Kind.HIT_POLICY, MergeConflict.Tier.SEMANTIC,
                                id, null, null, "hit policy of '" + name(b) + "'", b.hitPolicy, o.hitPolicy, t.hitPolicy,
                                MergeConflict.oursOrTheirs(o.hitPolicy, o.hitPolicy, t.hitPolicy, t.hitPolicy),
                                "both sides changed how matching rules combine"));
                    }
                    return;
                }
            }
            Element table = decisionTable();
            String[] parts = result.split(" ");
            table.setAttribute("hitPolicy", parts[0]);
            if (parts.length > 1) {
                table.setAttribute("aggregation", parts[1]);
            } else {
                table.removeAttribute("aggregation");
            }
        }

        // --- columns ------------------------------------------------------------------------------

        void columns(boolean output, List<Col> into, RuleMatch oursMatch, RuleMatch theirsMatch) {
            List<DecisionTableReader.Column> bc = output ? b.outputs : b.inputs;
            List<DecisionTableReader.Column> oc = output ? o.outputs : o.inputs;
            List<DecisionTableReader.Column> tc = output ? t.outputs : t.inputs;
            int[] oFor = matchColumns(bc, oc);
            int[] tFor = matchColumns(bc, tc);
            Set<Integer> oUsed = new HashSet<Integer>();
            Set<Integer> tUsed = new HashSet<Integer>();
            Map<Integer, Col> byOurs = new LinkedHashMap<Integer, Col>();
            List<Col> theirsOnly = new ArrayList<Col>();
            Map<Col, Integer> theirsPos = new HashMap<Col, Integer>();

            for (int i = 0; i < bc.size(); i++) {
                DecisionTableReader.Column bcol = bc.get(i);
                Col col = new Col(output, i, oFor[i], tFor[i]);
                col.base = bcol;
                col.ours = oFor[i] >= 0 ? oc.get(oFor[i]) : null;
                col.theirs = tFor[i] >= 0 ? tc.get(tFor[i]) : null;
                if (oFor[i] >= 0) {
                    oUsed.add(oFor[i]);
                }
                if (tFor[i] >= 0) {
                    tUsed.add(tFor[i]);
                }
                if (!resolveColumn(col, oursMatch, theirsMatch)) {
                    continue; // removed
                }
                if (col.oi >= 0) {
                    byOurs.put(col.oi, col);
                } else {
                    theirsOnly.add(col);
                    theirsPos.put(col, col.ti);
                }
            }
            // Added on either side; the same column added on both (same check or name) is one column.
            for (int j = 0; j < oc.size(); j++) {
                if (oUsed.contains(j)) {
                    continue;
                }
                DecisionTableReader.Column ocol = oc.get(j);
                int match = -1;
                for (int k = 0; k < tc.size(); k++) {
                    if (!tUsed.contains(k) && same(columnKey(ocol), columnKey(tc.get(k)))) {
                        match = k;
                        break;
                    }
                }
                Col col = new Col(output, -1, j, match);
                col.ours = ocol;
                col.theirs = match >= 0 ? tc.get(match) : null;
                if (match >= 0) {
                    tUsed.add(match);
                    resolveAddedOnBoth(col);
                } else {
                    col.key = columnKey(ocol);
                    col.label = ocol.label;
                    col.typeRef = ocol.typeRef;
                }
                byOurs.put(j, col);
            }
            for (int k = 0; k < tc.size(); k++) {
                if (tUsed.contains(k)) {
                    continue;
                }
                DecisionTableReader.Column tcol = tc.get(k);
                Col col = new Col(output, -1, -1, k);
                col.theirs = tcol;
                col.key = columnKey(tcol);
                col.label = tcol.label;
                col.typeRef = tcol.typeRef;
                theirsOnly.add(col);
                theirsPos.put(col, k);
            }

            // Ours' order, with theirs-only columns placed after the column they followed on their side.
            List<Col> ordered = new ArrayList<Col>();
            for (int j = 0; j < oc.size(); j++) {
                if (byOurs.containsKey(j)) {
                    ordered.add(byOurs.get(j));
                }
            }
            for (Col col : theirsOnly) {
                int pos = theirsPos.get(col);
                int insertAt = 0;
                for (int k = pos - 1; k >= 0; k--) {
                    int found = indexOfTheirs(ordered, k);
                    if (found >= 0) {
                        insertAt = found + 1;
                        break;
                    }
                }
                // After this side's own additions at the same spot, so each side's new columns stay together.
                while (insertAt < ordered.size() && ordered.get(insertAt).base == null && ordered.get(insertAt).ti < 0) {
                    insertAt++;
                }
                ordered.add(insertAt, col);
            }
            into.addAll(ordered);
        }

        int indexOfTheirs(List<Col> cols, int theirsIndex) {
            for (int i = 0; i < cols.size(); i++) {
                if (cols.get(i).ti == theirsIndex) {
                    return i;
                }
            }
            return -1;
        }

        /** A base column: three-way on what it checks or gives, its type and its label. False when removed. */
        boolean resolveColumn(Col col, RuleMatch oursMatch, RuleMatch theirsMatch) {
            DecisionTableReader.Column bc = col.base;
            String what = (col.output ? "result" : "condition") + " '" + label(bc) + "' of '" + name(b) + "'";
            if (col.ours == null && col.theirs == null) {
                return false;
            }
            if (col.ours == null || col.theirs == null) {
                boolean oursRemoved = col.ours == null;
                DecisionTableReader.Column kept = oursRemoved ? col.theirs : col.ours;
                RuleMatch keptMatch = oursRemoved ? theirsMatch : oursMatch;
                DecisionTableReader.Table keptTable = oursRemoved ? t : o;
                int keptIndex = oursRemoved ? col.ti : col.oi;
                boolean changed = !sameColumn(bc, kept);
                boolean inUse = !changed && usesColumn(keptTable, keptMatch, col.output, col.bi, keptIndex);
                if (!changed && !inUse) {
                    return false; // removed on one side, untouched on the other
                }
                String cid = key(id, null, bc.id + (changed ? "#deleteModify" : "#deleteInUse"));
                Resolution r = m.chosen.get(cid);
                boolean keep = oursRemoved ? r == Resolution.THEIRS : r != Resolution.THEIRS;
                if (r == null) {
                    keep = !oursRemoved; // preview: ours
                    MergeConflict.Kind kind = changed ? MergeConflict.Kind.COLUMN_DELETE_MODIFY : MergeConflict.Kind.COLUMN_DELETE_IN_USE;
                    String detail = changed
                            ? "one side removed it, the other changed it"
                            : "one side removed it, the other added or changed rules that use it — removing it silently drops those "
                            + (col.output ? "results" : "conditions");
                    m.conflicts.add(new MergeConflict(cid, kind, MergeConflict.Tier.SEMANTIC, id, null, bc.id, what,
                            describe(bc), oursRemoved ? null : describe(col.ours), oursRemoved ? describe(col.theirs) : null,
                            oursRemoved
                                    ? MergeConflict.oursOrTheirs("keep it removed", null, "keep the column", null)
                                    : MergeConflict.oursOrTheirs("keep the column", null, "remove it", null),
                            detail));
                }
                if (!keep) {
                    return false;
                }
                col.key = columnKey(kept);
                col.label = kept.label;
                col.typeRef = kept.typeRef;
                return true;
            }
            col.key = three(col, "#key", MergeConflict.Kind.COLUMN_RENAME, MergeConflict.Tier.SEMANTIC,
                    (col.output ? "name of result '" : "what condition '") + label(bc) + (col.output ? "' gives" : "' checks"),
                    columnKey(bc), columnKey(col.ours), columnKey(col.theirs),
                    col.output ? "both sides renamed this result; tables using it follow the name" : "both sides changed what this condition checks");
            col.typeRef = three(col, "#type", MergeConflict.Kind.COLUMN_TYPE, MergeConflict.Tier.SEMANTIC,
                    "type of " + what, bc.typeRef, col.ours.typeRef, col.theirs.typeRef, "both sides changed the type");
            col.label = three(col, "#label", MergeConflict.Kind.TEXT, MergeConflict.Tier.COSMETIC,
                    "heading of " + what, bc.label, col.ours.label, col.theirs.label, "both sides changed this text; it does not affect evaluation");
            return true;
        }

        /** The same column added on both sides: only its type (or heading) can disagree. */
        void resolveAddedOnBoth(Col col) {
            col.key = columnKey(col.ours);
            String what = (col.output ? "new result '" : "new condition '") + col.key + "' of '" + name(o) + "'";
            col.typeRef = three(col, "#type", MergeConflict.Kind.COLUMN_TYPE, MergeConflict.Tier.SEMANTIC,
                    "type of " + what, null, col.ours.typeRef, col.theirs.typeRef, "both sides added it, with different types");
            col.label = three(col, "#label", MergeConflict.Kind.TEXT, MergeConflict.Tier.COSMETIC,
                    "heading of " + what, null, col.ours.label, col.theirs.label, "both sides changed this text; it does not affect evaluation");
        }

        String three(Col col, String suffix, MergeConflict.Kind kind, MergeConflict.Tier tier, String what,
                     String bv, String ov, String tv, String detail) {
            if (equalText(ov, tv) || equalText(tv, bv)) {
                return ov;
            }
            if (equalText(ov, bv)) {
                return tv;
            }
            String cid = key(id, null, col.ident() + suffix);
            Resolution r = m.chosen.get(cid);
            if (r == Resolution.THEIRS) {
                return tv;
            }
            if (r == null) {
                m.conflicts.add(new MergeConflict(cid, kind, tier, id, null, col.ident(), what, bv, ov, tv,
                        MergeConflict.oursOrTheirs(String.valueOf(ov), ov, String.valueOf(tv), tv), detail));
            }
            return ov;
        }

        /** True when the side's added or changed rules put a real value in that column. */
        boolean usesColumn(DecisionTableReader.Table side, RuleMatch match, boolean output, int baseIndex, int sideIndex) {
            for (int r = 0; r < side.rules.size(); r++) {
                DecisionTableReader.Rule rule = side.rules.get(r);
                String value = at(output ? rule.outputEntries : rule.inputEntries, sideIndex);
                if (WILDCARD.equals(DmnDiffer.normalize(value))) {
                    continue;
                }
                Integer baseRule = match.sideToBase.get(r);
                if (baseRule == null) {
                    return true; // a new rule using it
                }
                DecisionTableReader.Rule before = b.rules.get(baseRule);
                if (!same(value, at(output ? before.outputEntries : before.inputEntries, baseIndex))) {
                    return true; // a changed rule using it
                }
            }
            return false;
        }

        // --- rules --------------------------------------------------------------------------------

        RuleMatch matchRules(DecisionTableReader.Table base, DecisionTableReader.Table side, String which) {
            RuleMatch match = new RuleMatch();
            int[] colFor = matchColumns(base.inputs, side.inputs);
            Set<Integer> taken = new HashSet<Integer>();
            // By id: kept across every edit made in this editor.
            Map<String, Integer> sideIds = new HashMap<String, Integer>();
            for (int s = 0; s < side.rules.size(); s++) {
                sideIds.put(side.rules.get(s).id, s);
            }
            for (int r = 0; r < base.rules.size(); r++) {
                Integer s = sideIds.get(base.rules.get(r).id);
                if (s != null && !taken.contains(s)) {
                    match.put(r, s);
                    taken.add(s);
                }
            }
            // By conditions: ids regenerated, conditions the same.
            for (int r = 0; r < base.rules.size(); r++) {
                if (match.baseToSide.containsKey(r)) {
                    continue;
                }
                String k = conditions(base.rules.get(r), base, colFor, true);
                for (int s = 0; s < side.rules.size(); s++) {
                    if (!taken.contains(s) && k.equals(conditions(side.rules.get(s), side, colFor, false))) {
                        match.put(r, s);
                        taken.add(s);
                        break;
                    }
                }
            }
            // Similar only: never guessed. A person says whether they are the same rule.
            for (int r = 0; r < base.rules.size(); r++) {
                if (match.baseToSide.containsKey(r)) {
                    continue;
                }
                for (int s = 0; s < side.rules.size(); s++) {
                    if (taken.contains(s) || !similar(base.rules.get(r), side.rules.get(s))) {
                        continue;
                    }
                    String cid = key(id, base.rules.get(r).id, "#match-" + which);
                    Resolution answer = m.chosen.get(cid);
                    if (answer == Resolution.OURS) {
                        match.put(r, s);
                        taken.add(s);
                    } else if (answer == null) {
                        m.conflicts.add(new MergeConflict(cid, MergeConflict.Kind.LOW_CONFIDENCE_MATCH, MergeConflict.Tier.SEMANTIC,
                                id, base.rules.get(r).id, null,
                                "is rule " + describeRule(base.rules.get(r)) + " the same as " + describeRule(side.rules.get(s)) + "?",
                                describeRule(base.rules.get(r)), describeRule(side.rules.get(s)), null,
                                Arrays.asList(Choice.ours("the same rule, changed", null), Choice.theirs("different rules: one removed, one added", null)),
                                "its id and conditions both changed, so it can only be paired by similarity"));
                        match.put(r, s); // preview pairs them
                        taken.add(s);
                    }
                    break;
                }
            }
            return match;
        }

        List<Row> rules(RuleMatch om, RuleMatch tm) {
            List<Col> all = new ArrayList<Col>(inputs);
            all.addAll(outputs);
            Map<Integer, Row> fromBase = new HashMap<Integer, Row>();

            for (int r = 0; r < b.rules.size(); r++) {
                DecisionTableReader.Rule br = b.rules.get(r);
                Integer oi = om.baseToSide.get(r);
                Integer ti = tm.baseToSide.get(r);
                DecisionTableReader.Rule or = oi == null ? null : o.rules.get(oi);
                DecisionTableReader.Rule tr = ti == null ? null : t.rules.get(ti);
                if (or == null && tr == null) {
                    continue;
                }
                if (or == null || tr == null) {
                    boolean oursRemoved = or == null;
                    DecisionTableReader.Rule kept = oursRemoved ? tr : or;
                    if (sameRule(br, kept, all, oursRemoved)) {
                        rulesRemovedOrKept(oursRemoved);
                        continue; // removed on one side, untouched on the other
                    }
                    String cid = key(id, br.id, null);
                    Resolution res = m.chosen.get(cid);
                    boolean keep = oursRemoved ? res == Resolution.THEIRS : res != Resolution.THEIRS;
                    if (res == null) {
                        keep = !oursRemoved;
                        m.conflicts.add(new MergeConflict(cid, MergeConflict.Kind.RULE_DELETE_MODIFY, MergeConflict.Tier.SEMANTIC,
                                id, br.id, null, "rule " + describeRule(br) + " of '" + name(b) + "'",
                                describeRule(br), or == null ? null : describeRule(or), tr == null ? null : describeRule(tr),
                                oursRemoved
                                        ? MergeConflict.oursOrTheirs("keep it removed", null, "keep it, with the other side's edits", null)
                                        : MergeConflict.oursOrTheirs("keep it, with this side's edits", null, "remove it", null),
                                "removed on one side, edited on the other"));
                    }
                    if (!keep) {
                        continue;
                    }
                    Row row = new Row(kept.id);
                    row.description = kept.description;
                    for (Col col : all) {
                        String v = oursRemoved ? cell(t, tr, col, false) : cell(o, or, col, true);
                        row.cells.put(col, v == null ? cell(b, br, col, null) : v);
                    }
                    fromBase.put(r, row);
                    continue;
                }
                Row row = new Row(or.id);
                for (Col col : all) {
                    String bv = cell(b, br, col, null);
                    String ov = cell(o, or, col, true);
                    String tv = cell(t, tr, col, false);
                    String o2 = ov == null ? bv : ov;
                    String t2 = tv == null ? bv : tv;
                    String value;
                    if (same(o2, t2) || same(t2, bv)) {
                        value = o2;
                    } else if (same(o2, bv)) {
                        value = t2;
                        m.cellsTakenFromTheirs++;
                    } else {
                        String cid = key(id, br.id, col.ident());
                        Resolution res = m.chosen.get(cid);
                        value = res == Resolution.THEIRS ? t2 : o2;
                        if (res == null) {
                            m.conflicts.add(new MergeConflict(cid, MergeConflict.Kind.CELL, MergeConflict.Tier.SEMANTIC,
                                    id, br.id, col.ident(),
                                    "'" + label(col) + "' in rule " + (r + 1) + " of " + name(b) + whenText(br),
                                    bv, o2, t2, MergeConflict.oursOrTheirs(String.valueOf(o2), o2, String.valueOf(t2), t2),
                                    "both sides changed this cell"));
                        }
                    }
                    row.cells.put(col, value);
                }
                final Row target = row;
                row.description = or.description;
                m.text(id, br.id, null, "#description", "description of rule " + describeRule(br),
                        br.description, or.description, tr.description, new Apply() {
                            public void to(String value) {
                                target.description = value;
                            }
                        });
                fromBase.put(r, row);
            }

            // Rules added on each side; the same conditions added on both are one rule, or a conflict.
            List<Row> oursAdded = new ArrayList<Row>();
            Map<Integer, Row> oursAddedAt = new HashMap<Integer, Row>();
            for (int s = 0; s < o.rules.size(); s++) {
                if (om.sideToBase.containsKey(s)) {
                    continue;
                }
                Row row = added(o, o.rules.get(s), all, true);
                oursAdded.add(row);
                oursAddedAt.put(s, row);
            }
            Map<Integer, Row> theirsAddedAt = new HashMap<Integer, Row>();
            for (int s = 0; s < t.rules.size(); s++) {
                if (tm.sideToBase.containsKey(s)) {
                    continue;
                }
                Row theirsRow = added(t, t.rules.get(s), all, false);
                Row twin = null;
                for (Row ours : oursAdded) {
                    if (conditionsOf(ours).equals(conditionsOf(theirsRow))) {
                        twin = ours;
                        break;
                    }
                }
                if (twin == null) {
                    theirsAddedAt.put(s, theirsRow);
                    m.rulesAdded++;
                    continue;
                }
                if (resultsOf(twin).equals(resultsOf(theirsRow))) {
                    continue; // the same rule added on both sides
                }
                String cid = key(id, "added:" + conditionsOf(theirsRow), null);
                Resolution res = m.chosen.get(cid);
                if (res == Resolution.THEIRS) {
                    twin.cells.putAll(theirsRow.cells);
                } else if (res == Resolution.BOTH) {
                    theirsAddedAt.put(s, theirsRow);
                } else if (res == null) {
                    m.conflicts.add(new MergeConflict(cid, MergeConflict.Kind.RULE_ADD_ADD, MergeConflict.Tier.SEMANTIC,
                            id, twin.id, null, "a new rule for " + conditionsOf(theirsRow) + " in '" + name(b) + "'",
                            null, resultsOf(twin), resultsOf(theirsRow),
                            Arrays.asList(Choice.ours(resultsOf(twin), null),
                                    Choice.theirs(resultsOf(theirsRow), null),
                                    Choice.both("keep both rules")),
                            "both sides added a rule with these conditions, giving different results"));
                }
            }

            return order(om, tm, fromBase, oursAddedAt, theirsAddedAt);
        }

        void rulesRemovedOrKept(boolean oursRemoved) {
            m.rulesRemoved++;
        }

        /** Rows in order: whichever side reordered the base rules decides; additions after their neighbour. */
        List<Row> order(RuleMatch om, RuleMatch tm, Map<Integer, Row> fromBase,
                        Map<Integer, Row> oursAdded, Map<Integer, Row> theirsAdded) {
            boolean oursMoved = reordered(om);
            boolean theirsMoved = reordered(tm);
            boolean useTheirs = theirsMoved && !oursMoved;
            if (oursMoved && theirsMoved && !sameOrder(om, tm)) {
                String cid = key(id, null, "#order");
                Resolution res = m.chosen.get(cid);
                useTheirs = res == Resolution.THEIRS;
                if (res == null) {
                    m.conflicts.add(new MergeConflict(cid, MergeConflict.Kind.RULE_ORDER, MergeConflict.Tier.SEMANTIC,
                            id, null, null, "order of the rules in '" + name(b) + "'", null, null, null,
                            MergeConflict.oursOrTheirs("this side's order", null, "the other side's order", null),
                            "both sides moved rules; order can change which rule answers"));
                }
            }
            DecisionTableReader.Table lead = useTheirs ? t : o;
            RuleMatch leadMatch = useTheirs ? tm : om;
            Map<Integer, Row> leadAdded = useTheirs ? theirsAdded : oursAdded;
            DecisionTableReader.Table other = useTheirs ? o : t;
            RuleMatch otherMatch = useTheirs ? om : tm;
            Map<Integer, Row> otherAdded = useTheirs ? oursAdded : theirsAdded;

            List<Row> rows = new ArrayList<Row>();
            Map<Integer, Row> leadPlaced = new HashMap<Integer, Row>();
            for (int s = 0; s < lead.rules.size(); s++) {
                Integer baseIndex = leadMatch.sideToBase.get(s);
                Row row = baseIndex != null ? fromBase.get(baseIndex) : leadAdded.get(s);
                if (row != null && !rows.contains(row)) {
                    rows.add(row);
                    leadPlaced.put(s, row);
                }
            }
            // Base rules the lead side removed but which are kept (a resolved delete/modify).
            for (Map.Entry<Integer, Row> e : fromBase.entrySet()) {
                if (!rows.contains(e.getValue())) {
                    rows.add(Math.min(e.getKey(), rows.size()), e.getValue());
                }
            }
            for (int s = 0; s < other.rules.size(); s++) {
                Row row = otherAdded.get(s);
                if (row == null || rows.contains(row)) {
                    continue;
                }
                int insertAt = 0;
                for (int k = s - 1; k >= 0; k--) {
                    Integer baseIndex = otherMatch.sideToBase.get(k);
                    Row anchor = baseIndex != null ? fromBase.get(baseIndex) : otherAdded.get(k);
                    int found = anchor == null ? -1 : rows.indexOf(anchor);
                    if (found >= 0) {
                        insertAt = found + 1;
                        break;
                    }
                }
                while (insertAt < rows.size() && leadAdded.containsValue(rows.get(insertAt))) {
                    insertAt++;
                }
                rows.add(insertAt, row);
            }
            return rows;
        }

        boolean reordered(RuleMatch match) {
            List<Integer> seen = new ArrayList<Integer>(match.sideToBaseOrdered());
            for (int i = 1; i < seen.size(); i++) {
                if (seen.get(i) < seen.get(i - 1)) {
                    return true;
                }
            }
            return false;
        }

        boolean sameOrder(RuleMatch a, RuleMatch c) {
            List<Integer> x = new ArrayList<Integer>(a.sideToBaseOrdered());
            List<Integer> y = new ArrayList<Integer>(c.sideToBaseOrdered());
            x.retainAll(y);
            y.retainAll(x);
            return x.equals(y);
        }

        Row added(DecisionTableReader.Table side, DecisionTableReader.Rule rule, List<Col> all, boolean ours) {
            Row row = new Row(rule.id);
            row.description = rule.description;
            for (Col col : all) {
                String v = cell(side, rule, col, ours);
                row.cells.put(col, v == null ? (col.output ? "null" : WILDCARD) : v);
            }
            return row;
        }

        /** A rule's conditions in words: "(amount: < 100, region: any)". */
        String whenText(DecisionTableReader.Rule rule) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < b.inputs.size(); i++) {
                String v = DmnDiffer.normalize(at(rule.inputEntries, i));
                sb.append(sb.length() == 0 ? " (" : ", ").append(label(b.inputs.get(i))).append(": ")
                        .append(WILDCARD.equals(v) ? "any" : v);
            }
            return sb.length() == 0 ? "" : sb.append(')').toString();
        }

        String conditionsOf(Row row) {
            StringBuilder sb = new StringBuilder();
            for (Col col : inputs) {
                sb.append(sb.length() == 0 ? "" : ", ").append(DmnDiffer.normalize(row.cells.get(col)));
            }
            return sb.toString();
        }

        String resultsOf(Row row) {
            StringBuilder sb = new StringBuilder();
            for (Col col : outputs) {
                sb.append(sb.length() == 0 ? "" : ", ").append(DmnDiffer.normalize(row.cells.get(col)));
            }
            return sb.toString();
        }

        /** A rule's value in a merged column on one side; null when that side has no such column. */
        String cell(DecisionTableReader.Table side, DecisionTableReader.Rule rule, Col col, Boolean ours) {
            int index = ours == null ? col.bi : (ours ? col.oi : col.ti);
            if (index < 0 || rule == null) {
                return null;
            }
            return at(col.output ? rule.outputEntries : rule.inputEntries, index);
        }

        boolean sameRule(DecisionTableReader.Rule base, DecisionTableReader.Rule kept, List<Col> all, boolean keptIsTheirs) {
            for (Col col : all) {
                String bv = cell(b, base, col, null);
                String kv = cell(keptIsTheirs ? t : o, kept, col, !keptIsTheirs);
                if (kv != null && !same(bv, kv)) {
                    return false;
                }
            }
            return equalText(base.description, kept.description);
        }

        // --- writing ------------------------------------------------------------------------------

        Element decisionTable() {
            Element decision = byId(m.out, id);
            List<Element> tables = descendants(decision, "decisionTable");
            return tables.get(0);
        }

        void write(List<Row> rows) {
            Element table = decisionTable();
            String ns = table.getNamespaceURI();
            List<Node> old = new ArrayList<Node>();
            NodeList children = table.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                Node n = children.item(i);
                if (n instanceof Element) {
                    String local = n.getLocalName();
                    if ("input".equals(local) || "output".equals(local) || "rule".equals(local)) {
                        old.add(n);
                    }
                }
            }
            Map<String, Element> keptColumns = new HashMap<String, Element>();
            for (Node n : old) {
                keptColumns.put(((Element) n).getAttribute("id"), (Element) n);
                table.removeChild(n);
            }
            for (Col col : inputs) {
                table.appendChild(columnElement(col, keptColumns));
            }
            for (Col col : outputs) {
                table.appendChild(columnElement(col, keptColumns));
            }
            Set<String> usedIds = new HashSet<String>();
            for (Row row : rows) {
                Element rule = m.out.createElementNS(ns, prefixed(table, "rule"));
                String ruleId = row.id == null || !usedIds.add(row.id) ? "Rule_" + Integer.toHexString(System.identityHashCode(row)) : row.id;
                usedIds.add(ruleId);
                rule.setAttribute("id", ruleId);
                if (row.description != null && !row.description.isEmpty()) {
                    Element description = m.out.createElementNS(ns, prefixed(table, "description"));
                    description.setTextContent(row.description);
                    rule.appendChild(description);
                }
                for (Col col : inputs) {
                    rule.appendChild(entry(table, ns, "inputEntry", ruleId + "_" + col.ident(), row.cells.get(col), WILDCARD));
                }
                for (Col col : outputs) {
                    rule.appendChild(entry(table, ns, "outputEntry", ruleId + "_" + col.ident(), row.cells.get(col), "null"));
                }
                table.appendChild(rule);
            }
        }

        Element entry(Element table, String ns, String kind, String entryId, String value, String empty) {
            Element entry = m.out.createElementNS(ns, prefixed(table, kind));
            entry.setAttribute("id", entryId.replaceAll("[^A-Za-z0-9_.-]", "_"));
            Element text = m.out.createElementNS(ns, prefixed(table, "text"));
            text.setTextContent(value == null ? empty : value);
            entry.appendChild(text);
            return entry;
        }

        Element columnElement(Col col, Map<String, Element> keptColumns) {
            Element source = col.ours != null ? keptColumns.get(col.ours.id) : null;
            Element element;
            if (source != null) {
                element = source;
            } else {
                Element theirsElement = byId(m.theirsDoc, col.theirs.id);
                element = (Element) m.out.importNode(theirsElement, true);
            }
            if (col.label == null || col.label.isEmpty()) {
                element.removeAttribute("label");
            } else {
                element.setAttribute("label", col.label);
            }
            if (col.output) {
                if (col.key != null) {
                    element.setAttribute("name", col.key);
                }
                setOrRemove(element, "typeRef", col.typeRef);
            } else {
                List<Element> expressions = descendants(element, "inputExpression");
                if (!expressions.isEmpty()) {
                    setOrRemove(expressions.get(0), "typeRef", col.typeRef);
                    List<Element> texts = descendants(expressions.get(0), "text");
                    if (!texts.isEmpty() && col.key != null) {
                        texts.get(0).setTextContent(col.key);
                    }
                }
            }
            return element;
        }
    }

    // --- small model -----------------------------------------------------------------------------

    private interface Apply {
        void to(String value);
    }

    private static final class Col {
        final boolean output;
        final int bi;
        final int oi;
        final int ti;
        DecisionTableReader.Column base;
        DecisionTableReader.Column ours;
        DecisionTableReader.Column theirs;
        String key;
        String label;
        String typeRef;

        Col(boolean output, int bi, int oi, int ti) {
            this.output = output;
            this.bi = bi;
            this.oi = oi;
            this.ti = ti;
        }

        /** Stable across recomputation: the base column's id, else the added column's. */
        String ident() {
            if (base != null) {
                return base.id;
            }
            return ours != null ? ours.id : theirs.id;
        }
    }

    private static final class Row {
        final String id;
        String description;
        final Map<Col, String> cells = new LinkedHashMap<Col, String>();

        Row(String id) {
            this.id = id;
        }
    }

    private static final class RuleMatch {
        final Map<Integer, Integer> baseToSide = new HashMap<Integer, Integer>();
        final Map<Integer, Integer> sideToBase = new HashMap<Integer, Integer>();

        void put(int base, int side) {
            baseToSide.put(base, side);
            sideToBase.put(side, base);
        }

        List<Integer> sideToBaseOrdered() {
            List<Integer> sides = new ArrayList<Integer>(sideToBase.keySet());
            Collections.sort(sides);
            List<Integer> result = new ArrayList<Integer>();
            for (Integer s : sides) {
                result.add(sideToBase.get(s));
            }
            return result;
        }
    }

    // --- helpers ---------------------------------------------------------------------------------

    /** For each base column, the index of the same column on the side (by id, then by key), or -1. */
    private static int[] matchColumns(List<DecisionTableReader.Column> base, List<DecisionTableReader.Column> side) {
        int[] result = new int[base.size()];
        Arrays.fill(result, -1);
        Set<Integer> taken = new HashSet<Integer>();
        for (int i = 0; i < base.size(); i++) {
            for (int j = 0; j < side.size(); j++) {
                if (!taken.contains(j) && base.get(i).id != null && base.get(i).id.equals(side.get(j).id)) {
                    result[i] = j;
                    taken.add(j);
                    break;
                }
            }
        }
        for (int i = 0; i < base.size(); i++) {
            if (result[i] >= 0) {
                continue;
            }
            for (int j = 0; j < side.size(); j++) {
                if (!taken.contains(j) && same(columnKey(base.get(i)), columnKey(side.get(j)))) {
                    result[i] = j;
                    taken.add(j);
                    break;
                }
            }
        }
        return result;
    }

    private static String conditions(DecisionTableReader.Rule rule, DecisionTableReader.Table table, int[] colFor, boolean isBase) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < colFor.length; i++) {
            if (colFor[i] < 0) {
                continue;
            }
            int index = isBase ? i : colFor[i];
            sb.append(DmnDiffer.normalize(at(rule.inputEntries, index))).append('|');
        }
        return sb.toString();
    }

    private static boolean similar(DecisionTableReader.Rule a, DecisionTableReader.Rule b) {
        List<String> left = new ArrayList<String>(a.inputEntries);
        left.addAll(a.outputEntries);
        List<String> right = new ArrayList<String>(b.inputEntries);
        right.addAll(b.outputEntries);
        int size = Math.max(left.size(), right.size());
        if (size == 0) {
            return false;
        }
        int equal = 0;
        for (int i = 0; i < Math.min(left.size(), right.size()); i++) {
            if (same(left.get(i), right.get(i))) {
                equal++;
            }
        }
        return equal * 2 >= size;
    }

    private static boolean sameDecision(DecisionTableReader.Table a, DecisionTableReader.Table b) {
        return summary(a).equals(summary(b)) && equalText(a.decisionName, b.decisionName);
    }

    /** Everything that matters about a table, as one comparable string. */
    private static String summary(DecisionTableReader.Table t) {
        StringBuilder sb = new StringBuilder();
        sb.append(t.hitPolicy).append(" in[");
        for (DecisionTableReader.Column c : t.inputs) {
            sb.append(c.expression).append(':').append(c.typeRef).append(':').append(c.label).append(',');
        }
        sb.append("] out[");
        for (DecisionTableReader.Column c : t.outputs) {
            sb.append(c.expression).append(':').append(c.typeRef).append(':').append(c.label).append(',');
        }
        sb.append("] rules ").append(t.rules.size()).append(':');
        for (DecisionTableReader.Rule r : t.rules) {
            sb.append(describeRule(r)).append("->");
            for (String v : r.outputEntries) {
                sb.append(DmnDiffer.normalize(v)).append(',');
            }
            sb.append(r.description).append(';');
        }
        return sb.toString();
    }

    private static boolean sameColumn(DecisionTableReader.Column a, DecisionTableReader.Column b) {
        return same(columnKey(a), columnKey(b)) && equalText(a.typeRef, b.typeRef) && equalText(a.label, b.label);
    }

    private static String columnKey(DecisionTableReader.Column c) {
        return c == null || c.expression == null ? null : c.expression.trim();
    }

    private static String describe(DecisionTableReader.Column c) {
        return c == null ? null : columnKey(c) + (c.typeRef == null ? "" : " (" + c.typeRef + ")");
    }

    private static String describeRule(DecisionTableReader.Rule rule) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < rule.inputEntries.size(); i++) {
            sb.append(i == 0 ? "" : ", ").append(DmnDiffer.normalize(rule.inputEntries.get(i)));
        }
        return sb.append(']').toString();
    }

    private static String label(DecisionTableReader.Column c) {
        return c.label != null && !c.label.isEmpty() ? c.label : String.valueOf(c.expression);
    }

    private static String label(Col c) {
        return c.label != null && !c.label.isEmpty() ? c.label : String.valueOf(c.key);
    }

    private static String name(DecisionTableReader.Table t) {
        return t == null ? "?" : (t.decisionName != null ? t.decisionName : t.decisionId);
    }

    private static String clean(String s) {
        return s.replaceAll("[^A-Za-z0-9]", "");
    }

    private static String uniqueName(Map<String, DecisionTableReader.Table> tables, String wanted) {
        Set<String> names = new HashSet<String>();
        for (DecisionTableReader.Table t : tables.values()) {
            for (DecisionTableReader.Column c : t.outputs) {
                names.add(c.expression);
            }
        }
        String candidate = wanted;
        int n = 2;
        while (names.contains(candidate)) {
            candidate = wanted + n++;
        }
        return candidate;
    }

    /** Result name to the tables giving it, in document order. */
    private static Map<String, List<String>> givers(Map<String, DecisionTableReader.Table> tables) {
        Map<String, List<String>> givers = new LinkedHashMap<String, List<String>>();
        for (DecisionTableReader.Table t : tables.values()) {
            for (DecisionTableReader.Column c : t.outputs) {
                if (c.expression == null) {
                    continue;
                }
                List<String> ids = givers.get(c.expression);
                if (ids == null) {
                    ids = new ArrayList<String>();
                    givers.put(c.expression, ids);
                }
                if (!ids.contains(t.decisionId)) {
                    ids.add(t.decisionId);
                }
            }
        }
        return givers;
    }

    private static int count(Map<String, List<String>> givers, String name) {
        List<String> ids = givers.get(name);
        return ids == null ? 0 : ids.size();
    }

    private static boolean same(String a, String b) {
        return DmnDiffer.normalize(a).equals(DmnDiffer.normalize(b));
    }

    private static boolean equalText(String a, String b) {
        String x = a == null ? "" : a.trim();
        String y = b == null ? "" : b.trim();
        return x.equals(y);
    }

    private static String at(List<String> list, int index) {
        return index >= 0 && index < list.size() ? list.get(index) : null;
    }

    private static String key(String decisionId, String ruleId, String columnId) {
        return decisionId + "|" + (ruleId == null ? "" : ruleId) + "|" + (columnId == null ? "" : columnId);
    }

    private static void setOrRemove(Element element, String attribute, String value) {
        if (value == null || value.isEmpty()) {
            element.removeAttribute(attribute);
        } else {
            element.setAttribute(attribute, value);
        }
    }

    private static String prefixed(Element context, String local) {
        String prefix = context.getPrefix();
        return prefix == null || prefix.isEmpty() ? local : prefix + ":" + local;
    }

    // --- DOM -------------------------------------------------------------------------------------

    static Document dom(String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            return factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalArgumentException("not a readable DMN document: " + e.getMessage(), e);
        }
    }

    static String serialize(Document doc) {
        try {
            Transformer transformer = TransformerFactory.newInstance().newTransformer();
            transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            StringWriter out = new StringWriter();
            transformer.transform(new DOMSource(doc), new StreamResult(out));
            return out.toString();
        } catch (Exception e) {
            throw new IllegalStateException("could not write the merged document: " + e.getMessage(), e);
        }
    }

    private static String canonical(Element element) {
        if (element == null) {
            return "";
        }
        try {
            Transformer transformer = TransformerFactory.newInstance().newTransformer();
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
            StringWriter out = new StringWriter();
            transformer.transform(new DOMSource(element), new StreamResult(out));
            return out.toString().replaceAll(">\\s+<", "><").trim();
        } catch (Exception e) {
            return "";
        }
    }

    static Element byId(Document doc, String id) {
        if (id == null) {
            return null;
        }
        return find(doc.getDocumentElement(), id);
    }

    private static Element find(Element element, String id) {
        if (id.equals(element.getAttribute("id"))) {
            return element;
        }
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element) {
                Element found = find((Element) children.item(i), id);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    static List<Element> descendants(Element element, String localName) {
        List<Element> result = new ArrayList<Element>();
        NodeList all = element.getElementsByTagNameNS("*", localName);
        for (int i = 0; i < all.getLength(); i++) {
            result.add((Element) all.item(i));
        }
        return result;
    }

    private static Node firstChildNamed(Element parent, String localName) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n instanceof Element && localName.equals(n.getLocalName())) {
                return n;
            }
        }
        return null;
    }

    /**
     * Elements both sides created independently can share an id (two new columns' expressions, say).
     * Decisions are keyed by id and never duplicated; anything else that repeats gets a fresh one.
     */
    private static void uniqueIds(Document doc) {
        Set<String> seen = new HashSet<String>();
        NodeList all = doc.getElementsByTagNameNS("*", "*");
        for (int i = 0; i < all.getLength(); i++) {
            Element element = (Element) all.item(i);
            String id = element.getAttribute("id");
            if (id.isEmpty() || seen.add(id)) {
                continue;
            }
            String fresh = id;
            int n = 2;
            while (seen.contains(fresh)) {
                fresh = id + "_" + n++;
            }
            element.setAttribute("id", fresh);
            seen.add(fresh);
        }
    }

    /** Diagram shapes and edges pointing at elements that no longer exist are dropped, never left dangling. */
    private static void dropDanglingDiagram(Document doc) {
        Set<String> ids = new LinkedHashSet<String>();
        NodeList all = doc.getElementsByTagNameNS("*", "*");
        for (int i = 0; i < all.getLength(); i++) {
            String id = ((Element) all.item(i)).getAttribute("id");
            if (!id.isEmpty()) {
                ids.add(id);
            }
        }
        for (String kind : new String[]{"DMNShape", "DMNEdge"}) {
            for (Element shape : descendants(doc.getDocumentElement(), kind)) {
                String ref = shape.getAttribute("dmnElementRef");
                String local = ref.contains(":") ? ref.substring(ref.indexOf(':') + 1) : ref;
                if (!local.isEmpty() && !ids.contains(local)) {
                    shape.getParentNode().removeChild(shape);
                }
            }
        }
    }
}
