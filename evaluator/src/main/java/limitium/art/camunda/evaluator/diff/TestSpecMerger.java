package limitium.art.camunda.evaluator.diff;

import limitium.art.camunda.evaluator.testspec.DecisionTests;
import limitium.art.camunda.evaluator.testspec.TestCase;
import limitium.art.camunda.evaluator.testspec.TestSpec;
import limitium.art.camunda.evaluator.testspec.TestSpecReader;
import limitium.art.camunda.evaluator.testspec.TestSpecWriter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Three-way merge of a domain's tests, case by case.
 *
 * <p>A set of cases is identified by its table and the values it supplies (its scope: alone, with
 * part of the chain, with all of it); a case by the values it supplies. Every case one side added,
 * removed or changed is taken; expectation columns added on either side are kept. Conflicts:
 * {@link MergeConflict.Kind#TEST_EXPECTATION}, {@link MergeConflict.Kind#TEST_DELETE_MODIFY},
 * {@link MergeConflict.Kind#TEST_ADD_ADD}, {@link MergeConflict.Kind#TEST_COLUMN_DELETE_IN_USE}, and
 * a scenario renamed both ways ({@link MergeConflict.Kind#TEXT}).
 *
 * <p>The result reuses {@link MergeResult}: its "xml" fields hold the merged YAML.
 */
public final class TestSpecMerger {

    private TestSpecMerger() {
    }

    public static MergeResult merge(String baseYaml, String oursYaml, String theirsYaml, Map<String, Resolution> resolutions) {
        Map<String, Resolution> chosen = resolutions == null ? Collections.<String, Resolution>emptyMap() : resolutions;
        TestSpec b;
        TestSpec o;
        TestSpec t;
        try {
            b = TestSpecReader.read(blankToEmpty(baseYaml));
            o = TestSpecReader.read(blankToEmpty(oursYaml));
            t = TestSpecReader.read(blankToEmpty(theirsYaml));
        } catch (RuntimeException e) {
            return whole(baseYaml, oursYaml, theirsYaml, chosen);
        }
        List<MergeConflict> conflicts = new ArrayList<MergeConflict>();
        Map<String, DecisionTests> bs = sets(b);
        Map<String, DecisionTests> os = sets(o);
        Map<String, DecisionTests> ts = sets(t);
        List<String> order = new ArrayList<String>(os.keySet());
        for (String k : ts.keySet()) {
            if (!order.contains(k)) {
                order.add(k);
            }
        }
        List<DecisionTests> merged = new ArrayList<DecisionTests>();
        for (String k : order) {
            DecisionTests bset = bs.get(k);
            DecisionTests oset = os.get(k);
            DecisionTests tset = ts.get(k);
            if (oset == null && tset == null) {
                continue;
            }
            DecisionTests shape = oset != null ? oset : (tset != null ? tset : bset);
            DecisionTests result = mergeSet(k,
                    bset != null ? bset : empty(shape),
                    oset != null ? oset : empty(bset != null ? bset : shape),
                    tset != null ? tset : empty(bset != null ? bset : shape),
                    chosen, conflicts);
            if (!result.cases.isEmpty() || (oset != null && tset != null)) {
                merged.add(result);
            }
        }
        String preview = TestSpecWriter.write(new TestSpec(merged));
        boolean clean = conflicts.isEmpty();
        return new MergeResult(clean, clean ? preview : null, conflicts, 0, 0, 0, preview);
    }

    private static DecisionTests mergeSet(String setKey, DecisionTests b, DecisionTests o, DecisionTests t,
                                          Map<String, Resolution> chosen, List<MergeConflict> conflicts) {
        Map<String, TestCase> bc = cases(b);
        Map<String, TestCase> oc = cases(o);
        Map<String, TestCase> tc = cases(t);

        // Expectation columns: three-way, with a removal refused while the other side fills it in.
        List<String> columns = new ArrayList<String>();
        for (String col : b.outputColumns) {
            boolean inO = o.outputColumns.contains(col);
            boolean inT = t.outputColumns.contains(col);
            if (inO && inT) {
                columns.add(col);
                continue;
            }
            if (!inO && !inT) {
                continue;
            }
            DecisionTests keeper = inO ? o : t;
            Map<String, TestCase> keeperCases = inO ? oc : tc;
            if (!fillsChanged(keeper, keeperCases, bc, b, col)) {
                continue; // removed on one side, nothing new relies on it
            }
            String cid = "tests|" + setKey + "|=>" + col;
            Resolution r = chosen.get(cid);
            boolean keep = inO ? r != Resolution.THEIRS : r == Resolution.THEIRS;
            if (r == null) {
                keep = inO;
                conflicts.add(new MergeConflict(cid, MergeConflict.Kind.TEST_COLUMN_DELETE_IN_USE, MergeConflict.Tier.SEMANTIC,
                        b.decision, null, col, "the expectation '" + col + "' in " + b.decision + "'s tests",
                        col, inO ? col : null, inT ? col : null,
                        inO ? MergeConflict.oursOrTheirs("keep checking it", null, "stop checking it", null)
                                : MergeConflict.oursOrTheirs("stop checking it", null, "keep checking it", null),
                        "one side stopped checking it; the other added or changed cases that do"));
            }
            if (keep) {
                columns.add(col);
            }
        }
        for (DecisionTests side : Arrays.asList(o, t)) {
            for (String col : side.outputColumns) {
                if (!b.outputColumns.contains(col) && !columns.contains(col)) {
                    columns.add(col);
                }
            }
        }

        Map<String, TestCase> result = new LinkedHashMap<String, TestCase>();
        List<String> keys = new ArrayList<String>(oc.keySet());
        for (String k : tc.keySet()) {
            if (!keys.contains(k)) {
                keys.add(k);
            }
        }
        for (String k : bc.keySet()) {
            if (!keys.contains(k)) {
                keys.add(k);
            }
        }
        for (String k : keys) {
            TestCase bv = bc.get(k);
            TestCase ov = oc.get(k);
            TestCase tv = tc.get(k);
            String cid = "tests|" + setKey + "|" + k;
            String label = b.decision + ": " + scenario(ov != null ? ov : (tv != null ? tv : bv));
            if (ov == null && tv == null) {
                continue;
            }
            if (bv == null && (ov == null || tv == null)) {
                result.put(k, ov != null ? ov : tv); // added on one side
                continue;
            }
            if (bv == null) {
                if (sameCase(ov, o, tv, t, columns)) {
                    result.put(k, ov);
                    continue;
                }
                Resolution r = chosen.get(cid);
                if (r == null) {
                    conflicts.add(new MergeConflict(cid, MergeConflict.Kind.TEST_ADD_ADD, MergeConflict.Tier.SEMANTIC,
                            b.decision, null, null, label + " — added on both sides, expecting different results",
                            null, expect(ov, o), expect(tv, t),
                            MergeConflict.oursOrTheirs(expect(ov, o), null, expect(tv, t), null),
                            "both sides added a case for these values"));
                }
                result.put(k, r == Resolution.THEIRS ? tv : ov);
                continue;
            }
            if (ov == null || tv == null) {
                boolean oursRemoved = ov == null;
                TestCase kept = oursRemoved ? tv : ov;
                DecisionTests keptSet = oursRemoved ? t : o;
                if (sameCase(bv, b, kept, keptSet, columns)) {
                    continue; // removed on one side, untouched on the other
                }
                Resolution r = chosen.get(cid);
                boolean keep = oursRemoved ? r == Resolution.THEIRS : r != Resolution.THEIRS;
                if (r == null) {
                    keep = !oursRemoved;
                    conflicts.add(new MergeConflict(cid, MergeConflict.Kind.TEST_DELETE_MODIFY, MergeConflict.Tier.SEMANTIC,
                            b.decision, null, null, label + " — removed on one side, changed on the other",
                            expect(bv, b), ov == null ? null : expect(ov, o), tv == null ? null : expect(tv, t),
                            oursRemoved ? MergeConflict.oursOrTheirs("keep it removed", null, "keep it: " + expect(tv, t), null)
                                    : MergeConflict.oursOrTheirs("keep it: " + expect(ov, o), null, "remove it", null),
                            "removed here, changed there"));
                }
                if (keep) {
                    result.put(k, kept);
                }
                continue;
            }
            // Present everywhere: expectations three-way, the scenario name as words.
            List<Object> expected = new ArrayList<Object>();
            boolean disagree = false;
            for (String col : columns) {
                Object b1 = value(bv, b, col);
                Object o1 = has(o, col) ? value(ov, o, col) : b1;
                Object t1 = has(t, col) ? value(tv, t, col) : b1;
                if (same(o1, t1) || same(t1, b1)) {
                    expected.add(o1);
                } else if (same(o1, b1)) {
                    expected.add(t1);
                } else {
                    disagree = true;
                    expected.add(o1);
                }
            }
            String scenario = threeText(bv.scenario, ov.scenario, tv.scenario);
            if (scenario == null) {
                Resolution r = chosen.get(cid + "#scenario");
                if (r == null) {
                    conflicts.add(new MergeConflict(cid + "#scenario", MergeConflict.Kind.TEXT, MergeConflict.Tier.COSMETIC,
                            b.decision, null, null, "name of the case " + label, bv.scenario, ov.scenario, tv.scenario,
                            MergeConflict.oursOrTheirs(ov.scenario, ov.scenario, tv.scenario, tv.scenario),
                            "both sides renamed this case; it does not affect what is checked"));
                }
                scenario = r == Resolution.THEIRS ? tv.scenario : ov.scenario;
            }
            if (disagree) {
                Resolution r = chosen.get(cid);
                if (r == Resolution.THEIRS) {
                    expected = valuesFor(tv, t, columns, bv, b);
                } else if (r == null) {
                    conflicts.add(new MergeConflict(cid, MergeConflict.Kind.TEST_EXPECTATION, MergeConflict.Tier.SEMANTIC,
                            b.decision, null, null, label, expect(bv, b), expect(ov, o), expect(tv, t),
                            MergeConflict.oursOrTheirs(expect(ov, o), null, expect(tv, t), null),
                            "both sides changed what this case expects"));
                    expected = valuesFor(ov, o, columns, bv, b);
                } else {
                    expected = valuesFor(ov, o, columns, bv, b);
                }
            }
            result.put(k, new TestCase(scenario, ov.inputs, expected));
        }

        // Re-shape every case to the merged columns.
        List<TestCase> shaped = new ArrayList<TestCase>();
        for (Map.Entry<String, TestCase> e : result.entrySet()) {
            TestCase c = e.getValue();
            DecisionTests from = oc.get(e.getKey()) == c ? o : (tc.get(e.getKey()) == c ? t : null);
            if (from != null) {
                shaped.add(new TestCase(c.scenario, c.inputs, valuesFor(c, from, columns, bc.get(e.getKey()), b)));
            } else {
                shaped.add(c);
            }
        }
        DecisionTests shape = o.inputColumns.isEmpty() && !t.inputColumns.isEmpty() ? t : o;
        return new DecisionTests(shape.decision, shape.inputColumns, columns, shaped);
    }

    /**
     * What one conflict is about, as it stands in a version of the tests — so the commit that changed
     * it can be found by comparing versions. Takes the conflict id this merger gave it.
     */
    public static String fingerprint(String yaml, String conflictId) {
        if (conflictId == null || !conflictId.startsWith("tests|") || "tests|file".equals(conflictId)) {
            return String.valueOf(yaml);
        }
        String rest = conflictId.substring("tests|".length());
        int bar = rest.indexOf('|');
        if (bar < 0) {
            return String.valueOf(yaml);
        }
        String setKey = rest.substring(0, bar);
        String what = rest.substring(bar + 1);
        TestSpec spec;
        try {
            spec = TestSpecReader.read(blankToEmpty(yaml));
        } catch (RuntimeException e) {
            return "unreadable";
        }
        DecisionTests set = sets(spec).get(setKey);
        if (set == null) {
            return "no such set";
        }
        if (what.startsWith("=>")) {
            String col = what.substring(2);
            if (!set.outputColumns.contains(col)) {
                return "no column";
            }
            StringBuilder sb = new StringBuilder();
            for (TestCase c : set.cases) {
                sb.append(text(value(c, set, col))).append(';');
            }
            return sb.toString();
        }
        String caseKey = what.endsWith("#scenario") ? what.substring(0, what.length() - "#scenario".length()) : what;
        TestCase c = cases(set).get(caseKey);
        if (c == null) {
            return "no case";
        }
        return what.endsWith("#scenario") ? String.valueOf(c.scenario) : expect(c, set);
    }

    private static List<Object> valuesFor(TestCase c, DecisionTests set, List<String> columns, TestCase base, DecisionTests baseSet) {
        List<Object> values = new ArrayList<Object>();
        for (String col : columns) {
            values.add(has(set, col) ? value(c, set, col) : (base != null && has(baseSet, col) ? value(base, baseSet, col) : null));
        }
        return values;
    }

    /** True when the side's added or changed cases put a value in that expectation column. */
    private static boolean fillsChanged(DecisionTests side, Map<String, TestCase> sideCases,
                                        Map<String, TestCase> baseCases, DecisionTests base, String col) {
        for (Map.Entry<String, TestCase> e : sideCases.entrySet()) {
            Object v = value(e.getValue(), side, col);
            if (v == null || String.valueOf(v).isEmpty()) {
                continue;
            }
            TestCase before = baseCases.get(e.getKey());
            if (before == null || !same(v, value(before, base, col))) {
                return true;
            }
        }
        return false;
    }

    private static boolean sameCase(TestCase a, DecisionTests as, TestCase c, DecisionTests cs, List<String> columns) {
        if (!String.valueOf(a.scenario).equals(String.valueOf(c.scenario))) {
            return false;
        }
        for (String col : columns) {
            if (has(as, col) && has(cs, col) && !same(value(a, as, col), value(c, cs, col))) {
                return false;
            }
        }
        return true;
    }

    private static String threeText(String b, String o, String t) {
        String bb = b == null ? "" : b;
        String oo = o == null ? "" : o;
        String tt = t == null ? "" : t;
        if (oo.equals(tt) || tt.equals(bb)) {
            return o;
        }
        if (oo.equals(bb)) {
            return t;
        }
        return null;
    }

    private static boolean has(DecisionTests set, String col) {
        return set.outputColumns.contains(col);
    }

    private static Object value(TestCase c, DecisionTests set, String col) {
        int i = set.outputColumns.indexOf(col);
        return i < 0 || c == null || i >= c.expected.size() ? null : c.expected.get(i);
    }

    private static boolean same(Object a, Object b) {
        return text(a).equals(text(b));
    }

    private static String text(Object v) {
        return v == null ? "" : String.valueOf(v).trim();
    }

    private static String scenario(TestCase c) {
        return c == null || c.scenario == null || c.scenario.isEmpty() ? "a case" : c.scenario;
    }

    private static String expect(TestCase c, DecisionTests set) {
        if (c == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < set.outputColumns.size(); i++) {
            sb.append(i == 0 ? "" : ", ").append(set.outputColumns.get(i)).append(' ')
                    .append(i < c.expected.size() ? text(c.expected.get(i)) : "");
        }
        return sb.toString();
    }

    /** A set's identity: its table and the values it supplies. */
    private static Map<String, DecisionTests> sets(TestSpec spec) {
        Map<String, DecisionTests> sets = new LinkedHashMap<String, DecisionTests>();
        Map<String, Integer> seen = new HashMap<String, Integer>();
        for (DecisionTests set : spec.decisions) {
            String k = set.decision + "(" + String.join(",", new TreeSet<String>(set.inputColumns)) + ")";
            int n = seen.containsKey(k) ? seen.get(k) + 1 : 1;
            seen.put(k, n);
            sets.put(n == 1 ? k : k + "#" + n, set);
        }
        return sets;
    }

    /** A case's identity within its set: the values it supplies, by column name. */
    private static Map<String, TestCase> cases(DecisionTests set) {
        Map<String, TestCase> cases = new LinkedHashMap<String, TestCase>();
        List<String> sorted = new ArrayList<String>(new TreeSet<String>(set.inputColumns));
        for (TestCase c : set.cases) {
            StringBuilder sb = new StringBuilder();
            for (String col : sorted) {
                int i = set.inputColumns.indexOf(col);
                sb.append(col).append('=').append(text(i < c.inputs.size() ? c.inputs.get(i) : null)).append(';');
            }
            String k = sb.toString();
            String unique = k;
            int n = 2;
            while (cases.containsKey(unique)) {
                unique = k + "#" + n++;
            }
            cases.put(unique, c);
        }
        return cases;
    }

    private static DecisionTests empty(DecisionTests like) {
        return new DecisionTests(like.decision, like.inputColumns, like.outputColumns, new ArrayList<TestCase>());
    }

    private static String blankToEmpty(String yaml) {
        return yaml == null || yaml.trim().isEmpty() ? "tests: []" : yaml;
    }

    /** A spec that does not parse on some side merges as a whole file. */
    private static MergeResult whole(String b, String o, String t, Map<String, Resolution> chosen) {
        String bb = b == null ? "" : b;
        String oo = o == null ? "" : o;
        String tt = t == null ? "" : t;
        if (oo.equals(tt) || tt.equals(bb)) {
            return new MergeResult(true, oo, new ArrayList<MergeConflict>(), 0, 0, 0, oo);
        }
        if (oo.equals(bb)) {
            return new MergeResult(true, tt, new ArrayList<MergeConflict>(), 0, 0, 0, tt);
        }
        String cid = "tests|file";
        Resolution r = chosen.get(cid);
        if (r != null) {
            String pick = r == Resolution.THEIRS ? tt : oo;
            return new MergeResult(true, pick, new ArrayList<MergeConflict>(), 0, 0, 0, pick);
        }
        List<MergeConflict> conflicts = new ArrayList<MergeConflict>();
        conflicts.add(new MergeConflict(cid, MergeConflict.Kind.TEST_EXPECTATION, MergeConflict.Tier.SEMANTIC,
                null, null, null, "the test file (it does not read as tests on one side, so it merges whole)",
                null, null, null, MergeConflict.oursOrTheirs("this side's file", null, "the other side's file", null),
                "both sides changed it and one version cannot be read case by case"));
        return new MergeResult(false, null, conflicts, 0, 0, 0, oo);
    }
}
