package limitium.art.camunda.evaluator.testspec;

import java.util.ArrayList;
import java.util.List;

/**
 * Emits the canonical form of a test spec, with columns padded so the rows line up.
 *
 * <p>Alignment by hand is tedious and decays; emitting it on every save means the file reads like a
 * table without anyone maintaining it. It also makes git diffs minimal — two people whose editors
 * disagree about quoting cannot manufacture a conflict — at the cost of any comments in the file.
 */
public final class TestSpecWriter {

    private static final String COLUMNS_PREFIX = "    columns: ";
    /**
     * Rows are indented so their opening bracket lands in the same column as the header's. YAML
     * allows any deeper indent for a sequence item, and lining them up is the entire point of a
     * columnar format: header and data should read as one table.
     */
    private static final String ROW_PREFIX = rowPrefix();

    private static String rowPrefix() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < COLUMNS_PREFIX.length() - 2; i++) {
            sb.append(' ');
        }
        return sb.append("- ").toString();
    }

    private TestSpecWriter() {
    }

    public static String write(TestSpec spec) {
        // A bare "tests:" parses to null, not to an empty list, and the editor's schema rejects it.
        // Canonical output must be something every consumer of the file accepts.
        if (spec.decisions.isEmpty()) {
            return "tests: []\n";
        }
        StringBuilder out = new StringBuilder();
        out.append("tests:\n");
        for (int d = 0; d < spec.decisions.size(); d++) {
            if (d > 0) {
                out.append('\n');
            }
            writeDecision(out, spec.decisions.get(d));
        }
        return out.toString();
    }

    private static void writeDecision(StringBuilder out, DecisionTests decision) {
        // Expectation headers are always quoted: "=>" is syntax in this format, and leaving it bare
        // invites an editor or a person to treat it as decoration and drop it.
        List<String> headers = new ArrayList<String>();
        headers.add(scalar(TestSpecReader.SCENARIO));
        for (String input : decision.inputColumns) {
            headers.add(scalar(input));
        }
        for (String output : decision.outputColumns) {
            headers.add(quote(TestSpecReader.EXPECT_PREFIX + " " + output));
        }

        List<List<String>> rows = new ArrayList<List<String>>();
        for (TestCase testCase : decision.cases) {
            List<String> cells = new ArrayList<String>();
            cells.add(scalar(testCase.scenario));
            for (int i = 0; i < decision.inputColumns.size(); i++) {
                cells.add(scalar(at(testCase.inputs, i)));
            }
            for (int i = 0; i < decision.outputColumns.size(); i++) {
                cells.add(scalar(at(testCase.expected, i)));
            }
            rows.add(cells);
        }

        int[] widths = new int[headers.size()];
        for (int c = 0; c < headers.size(); c++) {
            widths[c] = headers.get(c).length();
        }
        for (List<String> row : rows) {
            for (int c = 0; c < row.size() && c < widths.length; c++) {
                widths[c] = Math.max(widths[c], row.get(c).length());
            }
        }

        out.append("  - decision: ").append(scalar(decision.decision)).append('\n');
        out.append(COLUMNS_PREFIX).append(flow(headers, widths)).append('\n');
        if (rows.isEmpty()) {
            out.append("    rows: []\n");
            return;
        }
        out.append("    rows:\n");
        for (List<String> row : rows) {
            out.append(ROW_PREFIX).append(flow(row, widths)).append('\n');
        }
    }

    /**
     * A flow sequence whose cells start at the same offset on every row. Padding goes after the
     * separator, so commas sit against their value rather than floating in whitespace.
     */
    private static String flow(List<String> cells, int[] widths) {
        StringBuilder sb = new StringBuilder("[");
        for (int c = 0; c < cells.size(); c++) {
            String cell = cells.get(c);
            sb.append(cell);
            if (c == cells.size() - 1) {
                break;
            }
            sb.append(',');
            int width = c < widths.length ? widths[c] : cell.length();
            for (int pad = cell.length(); pad < width; pad++) {
                sb.append(' ');
            }
            sb.append(' ');
        }
        return sb.append(']').toString();
    }

    /** Renders one value, quoting only where YAML would otherwise read it as something else. */
    static String scalar(Object value) {
        if (value == null) {
            return "~";
        }
        if (value instanceof Boolean || value instanceof Number) {
            return value.toString();
        }
        if (value instanceof List) {
            List<?> list = (List<?>) value;
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(scalar(list.get(i)));
            }
            return sb.append(']').toString();
        }
        String text = value.toString();
        return needsQuoting(text) ? quote(text) : text;
    }

    private static boolean needsQuoting(String text) {
        if (text.isEmpty()) {
            return true;
        }
        if (!text.trim().equals(text)) {
            return true;
        }
        // Anything that would parse as another type, or that flow syntax would cut in half.
        if (text.indexOf(',') >= 0 || text.indexOf('[') >= 0 || text.indexOf(']') >= 0
                || text.indexOf('{') >= 0 || text.indexOf('}') >= 0 || text.indexOf('#') >= 0
                || text.indexOf(':') >= 0 || text.indexOf('"') >= 0 || text.indexOf('\'') >= 0
                || text.indexOf('\n') >= 0) {
            return true;
        }
        char first = text.charAt(0);
        if (first == '-' || first == '?' || first == '&' || first == '*' || first == '!'
                || first == '>' || first == '|' || first == '%' || first == '@' || first == '`') {
            return true;
        }
        if (looksLikeNumber(text) || looksLikeBoolean(text) || "~".equals(text) || "null".equals(text)) {
            return true;
        }
        return false;
    }

    private static boolean looksLikeNumber(String text) {
        try {
            Double.parseDouble(text);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static boolean looksLikeBoolean(String text) {
        String lower = text.toLowerCase();
        return "true".equals(lower) || "false".equals(lower)
                || "yes".equals(lower) || "no".equals(lower)
                || "on".equals(lower) || "off".equals(lower);
    }

    private static String quote(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static Object at(List<Object> values, int index) {
        return index < values.size() ? values.get(index) : null;
    }
}
