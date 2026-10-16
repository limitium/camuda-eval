package limitium.art.camunda.evaluator.testspec;

import limitium.art.camunda.evaluator.diff.DecisionTableReader;
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
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What a test of a decision actually runs, decided by what it supplies.
 *
 * <p>Evaluating a decision normally runs every decision it requires. A test may instead supply the
 * value a required decision would give: that decision, and anything only it needed, is then not run —
 * the chain is cut there. So the same table can be tested alone (supply everything it checks), with
 * the step before it, or from the very start of its chain, and the columns of the test say which.
 */
public final class ChainScope {

    /** The decision the test is about. */
    public final String end;
    /** Decisions that run, starting tables first, ending with {@link #end}. */
    public final List<String> runs;
    /** Required decisions replaced by supplied values. */
    public final Set<String> cut;
    /** Values to supply for this scope: what the running tables check and none of them gives. */
    public final List<String> needed;
    /** Results the test can expect, by output name, to the decision that gives each. */
    public final Map<String, String> outputs;
    /** Every value any table in the full chain checks: the columns a test of this decision may have. */
    public final Set<String> anyInput;

    private ChainScope(String end, List<String> runs, Set<String> cut, List<String> needed,
                       Map<String, String> outputs, Set<String> anyInput) {
        this.end = end;
        this.runs = runs;
        this.cut = cut;
        this.needed = needed;
        this.outputs = outputs;
        this.anyInput = anyInput;
    }

    public static ChainScope of(String dmnXml, String end, Collection<String> supplied) {
        return of(DecisionTableReader.read(dmnXml), end, supplied);
    }

    public static ChainScope of(Map<String, DecisionTableReader.Table> tables, String end, Collection<String> supplied) {
        Set<String> given = new HashSet<String>(supplied);
        List<String> runs = new ArrayList<String>();
        Set<String> cut = new LinkedHashSet<String>();
        visit(end, tables, given, runs, cut, new HashSet<String>());

        Set<String> produced = new HashSet<String>();
        Map<String, String> outputs = new LinkedHashMap<String, String>();
        Set<String> inputs = new LinkedHashSet<String>();
        for (String id : runs) {
            DecisionTableReader.Table table = tables.get(id);
            for (DecisionTableReader.Column column : table.outputs) {
                String name = name(column);
                produced.add(name);
                if (!outputs.containsKey(name)) {
                    outputs.put(name, id);
                }
            }
            for (DecisionTableReader.Column column : table.inputs) {
                if (column.expression != null) {
                    inputs.add(column.expression);
                }
            }
        }
        List<String> needed = new ArrayList<String>();
        for (String input : inputs) {
            if (!produced.contains(input)) {
                needed.add(input);
            }
        }

        Set<String> anyInput = new LinkedHashSet<String>();
        collectInputs(end, tables, anyInput, new HashSet<String>());
        return new ChainScope(end, runs, cut, needed, outputs, anyInput);
    }

    /** Post-order: what a decision needs runs before it, unless the test supplies all it gives. */
    private static void visit(String id, Map<String, DecisionTableReader.Table> tables, Set<String> given,
                              List<String> runs, Set<String> cut, Set<String> seen) {
        if (!seen.add(id) || !tables.containsKey(id)) {
            return;
        }
        for (String upstream : tables.get(id).requiredDecisions) {
            DecisionTableReader.Table required = tables.get(upstream);
            if (required != null && suppliesAll(required, given)) {
                cut.add(upstream);
            } else {
                visit(upstream, tables, given, runs, cut, seen);
            }
        }
        runs.add(id);
    }

    private static boolean suppliesAll(DecisionTableReader.Table table, Set<String> given) {
        if (table.outputs.isEmpty()) {
            return false;
        }
        for (DecisionTableReader.Column column : table.outputs) {
            if (!given.contains(name(column))) {
                return false;
            }
        }
        return true;
    }

    private static void collectInputs(String id, Map<String, DecisionTableReader.Table> tables,
                                      Set<String> inputs, Set<String> seen) {
        DecisionTableReader.Table table = tables.get(id);
        if (table == null || !seen.add(id)) {
            return;
        }
        for (DecisionTableReader.Column column : table.inputs) {
            if (column.expression != null) {
                inputs.add(column.expression);
            }
        }
        for (String upstream : table.requiredDecisions) {
            collectInputs(upstream, tables, inputs, seen);
        }
    }

    private static String name(DecisionTableReader.Column column) {
        return column.expression != null ? column.expression : column.id;
    }

    /**
     * The same file with the chain cut where the test supplies values: the running decisions no longer
     * require the cut ones, so the engine takes the supplied value instead of computing it.
     */
    public static String cutXml(String dmnXml, String end, Collection<String> supplied) {
        ChainScope scope = of(dmnXml, end, supplied);
        if (scope.cut.isEmpty()) {
            return dmnXml;
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            Document doc = factory.newDocumentBuilder()
                    .parse(new ByteArrayInputStream(dmnXml.getBytes(StandardCharsets.UTF_8)));
            NodeList decisions = doc.getElementsByTagNameNS("*", "decision");
            for (int i = 0; i < decisions.getLength(); i++) {
                Element decision = (Element) decisions.item(i);
                if (!scope.runs.contains(decision.getAttribute("id"))) {
                    continue;
                }
                List<Node> drop = new ArrayList<Node>();
                NodeList children = decision.getChildNodes();
                for (int c = 0; c < children.getLength(); c++) {
                    Node child = children.item(c);
                    if (child instanceof Element && "informationRequirement".equals(child.getLocalName())) {
                        NodeList targets = ((Element) child).getElementsByTagNameNS("*", "requiredDecision");
                        if (targets.getLength() > 0) {
                            String href = ((Element) targets.item(0)).getAttribute("href");
                            if (scope.cut.contains(href.startsWith("#") ? href.substring(1) : href)) {
                                drop.add(child);
                            }
                        }
                    }
                }
                for (Node node : drop) {
                    decision.removeChild(node);
                }
            }
            Transformer transformer = TransformerFactory.newInstance().newTransformer();
            transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            StringWriter out = new StringWriter();
            transformer.transform(new DOMSource(doc), new StreamResult(out));
            return out.toString();
        } catch (Exception e) {
            throw new IllegalArgumentException("could not cut the chain: " + e.getMessage(), e);
        }
    }
}
