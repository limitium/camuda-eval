package limitium.art.camunda.evaluator.diff;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Connections follow names: a table that checks a value another table in the domain gives uses that
 * table. The DMN requirements are derived from that — never edited, never merged — so after a merge
 * they are derived again from the merged tables. The first table giving a name is the one used.
 */
public final class Requirements {

    private Requirements() {
    }

    public static String sync(String xml) {
        Document doc = DmnMerger.dom(xml);
        sync(doc);
        return DmnMerger.serialize(doc);
    }

    static void sync(Document doc) {
        List<Element> decisions = DmnMerger.descendants(doc.getDocumentElement(), "decision");
        Map<String, String> giver = new LinkedHashMap<String, String>();
        for (Element decision : decisions) {
            for (String name : gives(decision)) {
                if (!giver.containsKey(name)) {
                    giver.put(name, decision.getAttribute("id"));
                }
            }
        }
        for (Element decision : decisions) {
            String id = decision.getAttribute("id");
            Set<String> needed = new LinkedHashSet<String>();
            for (String name : checks(decision)) {
                String from = giver.get(name);
                if (from != null && !from.equals(id)) {
                    needed.add(from);
                }
            }
            // Drop decision requirements; keep any on input data, which names do not decide.
            Element anchor = null;
            for (Element requirement : children(decision, "informationRequirement")) {
                if (!DmnMerger.descendants(requirement, "requiredDecision").isEmpty()) {
                    decision.removeChild(requirement);
                } else {
                    anchor = requirement;
                }
            }
            Node before = anchor != null ? anchor.getNextSibling() : firstRequirementPlace(decision);
            String ns = decision.getNamespaceURI();
            String prefix = decision.getPrefix() == null || decision.getPrefix().isEmpty() ? "" : decision.getPrefix() + ":";
            for (String from : needed) {
                Element requirement = doc.createElementNS(ns, prefix + "informationRequirement");
                requirement.setAttribute("id", "InformationRequirement_" + id + "_" + from);
                Element required = doc.createElementNS(ns, prefix + "requiredDecision");
                required.setAttribute("href", "#" + from);
                requirement.appendChild(required);
                decision.insertBefore(requirement, before);
            }
        }
    }

    /** Requirements come after description/question/allowedAnswers/variable and before the logic. */
    private static Node firstRequirementPlace(Element decision) {
        NodeList children = decision.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n instanceof Element) {
                String local = n.getLocalName();
                if (!"description".equals(local) && !"extensionElements".equals(local) && !"question".equals(local)
                        && !"allowedAnswers".equals(local) && !"variable".equals(local)) {
                    return n;
                }
            }
        }
        return null;
    }

    private static List<String> gives(Element decision) {
        List<String> names = new ArrayList<String>();
        List<Element> tables = DmnMerger.descendants(decision, "decisionTable");
        if (!tables.isEmpty()) {
            for (Element output : DmnMerger.descendants(tables.get(0), "output")) {
                if (!output.getAttribute("name").isEmpty()) {
                    names.add(output.getAttribute("name"));
                }
            }
        } else {
            for (Element variable : children(decision, "variable")) {
                names.add(variable.getAttribute("name"));
            }
        }
        return names;
    }

    private static List<String> checks(Element decision) {
        List<String> names = new ArrayList<String>();
        for (Element expression : DmnMerger.descendants(decision, "inputExpression")) {
            for (Element text : DmnMerger.descendants(expression, "text")) {
                names.add(text.getTextContent().trim());
            }
        }
        return names;
    }

    private static List<Element> children(Element parent, String localName) {
        List<Element> result = new ArrayList<Element>();
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n instanceof Element && localName.equals(n.getLocalName())) {
                result.add((Element) n);
            }
        }
        return result;
    }
}
