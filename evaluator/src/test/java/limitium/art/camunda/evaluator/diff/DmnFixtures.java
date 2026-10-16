package limitium.art.camunda.evaluator.diff;

import java.util.ArrayList;
import java.util.List;

/** Builds small single-decision DMN documents so a test reads as the table it describes. */
final class DmnFixtures {

    private DmnFixtures() {
    }

    static Builder table() {
        return new Builder();
    }

    static final class Builder {
        private String hitPolicy = "UNIQUE";
        private String aggregation;
        private final List<String[]> inputs = new ArrayList<String[]>();
        private final List<String[]> outputs = new ArrayList<String[]>();
        private final List<String[]> rules = new ArrayList<String[]>();

        Builder() {
            inputs.add(new String[]{"Input_1", "amount", "number"});
            outputs.add(new String[]{"Output_1", "result", "string"});
        }

        Builder hitPolicy(String value) {
            this.hitPolicy = value;
            return this;
        }

        Builder aggregation(String value) {
            this.aggregation = value;
            return this;
        }

        /** Retypes the first input column, for type-conflict cases. */
        Builder inputType(String typeRef) {
            inputs.get(0)[2] = typeRef;
            return this;
        }

        /** Adds an extra input column; entries for it go after the existing ones in {@link #rule}. */
        Builder input(String id, String expression, String typeRef) {
            inputs.add(new String[]{id, expression, typeRef});
            return this;
        }

        /** Replaces the single output, to vary its id while keeping the variable name. */
        Builder output(String id, String name, String typeRef) {
            outputs.clear();
            outputs.add(new String[]{id, name, typeRef});
            return this;
        }

        /** Entries are the input cells in column order, then the output cells. */
        Builder rule(String id, String... entries) {
            String[] row = new String[entries.length + 1];
            row[0] = id;
            System.arraycopy(entries, 0, row, 1, entries.length);
            rules.add(row);
            return this;
        }

        String xml() {
            return xmlWithDecision("Decision_1", "Decision");
        }

        String xmlWithDecision(String decisionId, String decisionName) {
            StringBuilder sb = new StringBuilder();
            sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
            sb.append("<definitions id=\"Defs\" name=\"Defs\" namespace=\"http://camunda.org/schema/1.0/dmn\"\n");
            sb.append("  xmlns=\"https://www.omg.org/spec/DMN/20191111/MODEL/\">\n");
            sb.append("  <decision id=\"").append(decisionId).append("\" name=\"").append(decisionName).append("\">\n");
            sb.append("    <decisionTable id=\"DecisionTable_1\" hitPolicy=\"").append(hitPolicy).append("\"");
            if (aggregation != null) {
                sb.append(" aggregation=\"").append(aggregation).append("\"");
            }
            sb.append(">\n");

            for (int i = 0; i < inputs.size(); i++) {
                String[] input = inputs.get(i);
                sb.append("      <input id=\"").append(input[0]).append("\">\n");
                sb.append("        <inputExpression id=\"InputExpression_").append(i)
                        .append("\" typeRef=\"").append(input[2]).append("\">\n");
                sb.append("          <text>").append(escape(input[1])).append("</text>\n");
                sb.append("        </inputExpression>\n");
                sb.append("      </input>\n");
            }
            for (String[] output : outputs) {
                sb.append("      <output id=\"").append(output[0]).append("\" name=\"").append(output[1])
                        .append("\" typeRef=\"").append(output[2]).append("\"/>\n");
            }
            for (String[] row : rules) {
                sb.append("      <rule id=\"").append(row[0]).append("\">\n");
                int cell = 1;
                for (int i = 0; i < inputs.size(); i++, cell++) {
                    String value = cell < row.length ? row[cell] : "";
                    sb.append("        <inputEntry id=\"InputEntry_").append(row[0]).append('_').append(i)
                            .append("\"><text>").append(escape(value)).append("</text></inputEntry>\n");
                }
                for (int i = 0; i < outputs.size(); i++, cell++) {
                    String value = cell < row.length ? row[cell] : "";
                    sb.append("        <outputEntry id=\"OutputEntry_").append(row[0]).append('_').append(i)
                            .append("\"><text>").append(escape(value)).append("</text></outputEntry>\n");
                }
                sb.append("      </rule>\n");
            }

            sb.append("    </decisionTable>\n");
            sb.append("  </decision>\n");
            sb.append("</definitions>\n");
            return sb.toString();
        }

        private static String escape(String value) {
            return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        }
    }
}
