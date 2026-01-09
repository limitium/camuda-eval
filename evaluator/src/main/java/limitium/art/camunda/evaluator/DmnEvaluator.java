package limitium.art.camunda.evaluator;

import org.camunda.bpm.dmn.engine.DmnDecision;
import org.camunda.bpm.dmn.engine.DmnDecisionResult;
import org.camunda.bpm.dmn.engine.DmnEngine;
import org.camunda.bpm.dmn.engine.delegate.*;
import org.camunda.bpm.engine.variable.VariableMap;
import java.util.Map;
import java.util.LinkedHashMap;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import org.camunda.bpm.model.dmn.Dmn;
import org.camunda.bpm.model.dmn.DmnModelInstance;
import org.camunda.bpm.model.dmn.instance.Decision;
import org.camunda.bpm.model.dmn.instance.DecisionTable;
import org.camunda.bpm.model.dmn.instance.Rule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.nio.file.Path;
import java.nio.file.Files;
import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public class DmnEvaluator {
    private final DnmEvaluationEvenCollector listener;
    private final DmnEngine dmnEngine;
    private boolean collectCoverage = false;
    private final List<CoverageEvent> coverageEvents = new ArrayList<>();

    static class DnmEvaluationEvenCollector implements DmnDecisionTableEvaluationListener {

        private List<DmnDecisionTableEvaluationEvent> events = new ArrayList<>();

        @Override
        public void notify(DmnDecisionTableEvaluationEvent event) {
            this.events.add(event);
        }


        public List<DmnDecisionTableEvaluationEvent> getEvents() {
            return events;
        }

        public void clearEvents() {
            this.events.clear();
        }
    }

    public static class DmnDecisionEvaluator {
        private final DmnDecision dmnDecision;
        private final DmnEvaluator dmnEvaluator;

        private static final Logger LOGGER = LoggerFactory.getLogger(DmnDecisionEvaluator.class);

        public DmnDecisionEvaluator(DmnDecision dmnDecision, DmnEvaluator dmnEvaluator) {
            this.dmnDecision = dmnDecision;
            this.dmnEvaluator = dmnEvaluator;
        }

        public DmnDecisionResult evaluate(VariableMap vars) {
            try {
                dmnEvaluator.listener.clearEvents();
                DmnDecisionResult dmnDecisionResultEntries = dmnEvaluator.dmnEngine.evaluateDecision(dmnDecision, vars);
                List<DmnDecisionTableEvaluationEvent> events = new ArrayList<>(dmnEvaluator.listener.getEvents());
                dmnEvaluator.listener.clearEvents();
                if (events.isEmpty()) {
                    throw new DmnAmbiguousEvaluationException("No evaluation events for decision: " + dmnDecision.getKey());
                }
                boolean anyRuleMatched = events.stream().anyMatch(evt -> !evt.getMatchingRules().isEmpty());
                if (!anyRuleMatched) {
                    throw new DmnAmbiguousEvaluationException("No rules matched for decision: " + dmnDecision.getKey());
                }
                if (dmnEvaluator.collectCoverage) {
                    for (DmnDecisionTableEvaluationEvent evt : events) {
                        for (DmnEvaluatedDecisionRule rule : evt.getMatchingRules()) {
                            Map<String, Object> paramCopy = new LinkedHashMap<>();
                            for (String key : vars.keySet()) {
                                paramCopy.put(key, vars.get(key));
                            }
                            dmnEvaluator.coverageEvents.add(new CoverageEvent(
                                    evt.getDecisionTable().getKey(),
                                    rule.getId(),
                                    paramCopy
                            ));
                        }
                    }
                }
                printHistory(events);
                return dmnDecisionResultEntries;
            } catch (DmnAmbiguousEvaluationException e) {
                throw e;
            } catch (Exception e) {
                throw new DmnEvaluationException("Error evaluating decision: " + dmnDecision.getKey(), e);
            }
        }

        // Convenience typed evaluators
        public String evaluateToString(VariableMap vars) {
            Object v = evaluate(vars).getSingleResult().getSingleEntry();
            return v == null ? null : String.valueOf(v);
        }

        public Boolean evaluateToBoolean(VariableMap vars) {
            Object v = evaluate(vars).getSingleResult().getSingleEntry();
            if (v == null) return null;
            if (v instanceof Boolean) return (Boolean) v;
            if (v instanceof String) return Boolean.valueOf((String) v);
            throw new IllegalStateException("Decision '" + dmnDecision.getKey() + "' did not return a boolean-compatible value: " + v.getClass().getName());
        }

        public Number evaluateToNumber(VariableMap vars) {
            Object v = evaluate(vars).getSingleResult().getSingleEntry();
            if (v == null) return null;
            if (v instanceof Number) return (Number) v;
            if (v instanceof String) {
                try {
                    return new java.math.BigDecimal((String) v);
                } catch (NumberFormatException e) {
                    throw new IllegalStateException("Decision '" + dmnDecision.getKey() + "' returned non-numeric string: " + v);
                }
            }
            throw new IllegalStateException("Decision '" + dmnDecision.getKey() + "' did not return a numeric value: " + v.getClass().getName());
        }

        public String evaluateToString(java.util.Map<String, Object> vars) {
            org.camunda.bpm.engine.variable.VariableMap map = org.camunda.bpm.engine.variable.Variables.createVariables();
            if (vars != null) vars.forEach(map::putValue);
            return evaluateToString(map);
        }

        public Boolean evaluateToBoolean(java.util.Map<String, Object> vars) {
            org.camunda.bpm.engine.variable.VariableMap map = org.camunda.bpm.engine.variable.Variables.createVariables();
            if (vars != null) vars.forEach(map::putValue);
            return evaluateToBoolean(map);
        }

        public Number evaluateToNumber(java.util.Map<String, Object> vars) {
            org.camunda.bpm.engine.variable.VariableMap map = org.camunda.bpm.engine.variable.Variables.createVariables();
            if (vars != null) vars.forEach(map::putValue);
            return evaluateToNumber(map);
        }

        private void printHistory(List<DmnDecisionTableEvaluationEvent> events) {
            String decisionKey = dmnDecision.getKey();
            
            // Build JSON format for machine processing - minimal overhead
            StringBuilder json = new StringBuilder(events.size() * 120 + 80);
            json.append("{\"decision\":\"").append(escapeJson(decisionKey)).append("\",\"events\":[");
            
            boolean firstEvent = true;
            for (DmnDecisionTableEvaluationEvent evt : events) {
                if (!firstEvent) json.append(',');
                firstEvent = false;
                
                json.append("{\"decisionTable\":\"").append(escapeJson(evt.getDecisionTable().getKey())).append("\",\"inputs\":{");
                boolean firstInput = true;
                for (DmnEvaluatedInput inp : evt.getInputs()) {
                    if (!firstInput) json.append(',');
                    firstInput = false;
                    json.append('"').append(escapeJson(inp.getName())).append("\":");
                    appendJsonValue(json, inp.getValue().getValue());
                }
                json.append("},\"matchedRules\":[");
                boolean firstRule = true;
                for (DmnEvaluatedDecisionRule rule : evt.getMatchingRules()) {
                    if (!firstRule) json.append(',');
                    firstRule = false;
                    json.append("{\"ruleId\":\"").append(escapeJson(rule.getId())).append("\",\"outputs\":{");
                    boolean firstOutput = true;
                    for (Map.Entry<String, DmnEvaluatedOutput> entry : rule.getOutputEntries().entrySet()) {
                        if (!firstOutput) json.append(',');
                        firstOutput = false;
                        json.append('"').append(escapeJson(entry.getKey())).append("\":");
                        appendJsonValue(json, entry.getValue().getValue().getValue());
                    }
                    json.append("}}");
                }
                json.append("]}");
            }
            json.append("]}");
            
            LOGGER.info(json.toString());
        }
        
        private void appendJsonValue(StringBuilder sb, Object value) {
            if (value == null) {
                sb.append("null");
            } else if (value instanceof String) {
                sb.append('"').append(escapeJson((String) value)).append('"');
            } else if (value instanceof Number || value instanceof Boolean) {
                sb.append(value);
            } else {
                sb.append('"').append(escapeJson(String.valueOf(value))).append('"');
            }
        }
        
        private String escapeJson(String str) {
            if (str == null) return "";
            StringBuilder sb = new StringBuilder(str.length() + 10);
            for (int i = 0; i < str.length(); i++) {
                char c = str.charAt(i);
                switch (c) {
                    case '"': sb.append("\\\""); break;
                    case '\\': sb.append("\\\\"); break;
                    case '\b': sb.append("\\b"); break;
                    case '\f': sb.append("\\f"); break;
                    case '\n': sb.append("\\n"); break;
                    case '\r': sb.append("\\r"); break;
                    case '\t': sb.append("\\t"); break;
                    default:
                        if (c < 0x20) {
                            sb.append(String.format("\\u%04x", (int) c));
                        } else {
                            sb.append(c);
                        }
                        break;
                }
            }
            return sb.toString();
        }
        
    }

    public DmnEvaluator() {
        listener = new DnmEvaluationEvenCollector();
        dmnEngine = org.camunda.bpm.dmn.engine.impl.DefaultDmnEngineConfiguration
                .createDefaultDmnEngineConfiguration()
                .customPostDecisionTableEvaluationListeners(Arrays.asList(listener))
                .buildEngine();
    }

    public DmnDecisionEvaluator loadRule(Path dmnFilePath, String ruleId) {
        try (InputStream is = Files.newInputStream(dmnFilePath)) {
            DmnDecision dmnDecision = dmnEngine.parseDecision(ruleId, is);
            return new DmnDecisionEvaluator(dmnDecision, this);
        } catch (IOException e) {
            throw new DmnFileNotFoundException(dmnFilePath.toString());
        }
    }

    public void setCollectCoverage(boolean collect) {
        this.collectCoverage = collect;
    }

    public List<CoverageEvent> getCoverageEvents() {
        return new ArrayList<>(coverageEvents);
    }

    public List<String> loadAllDecisionKeys(Path dmnFilePath) {
        try (InputStream is = Files.newInputStream(dmnFilePath)) {
            DmnModelInstance modelInstance = Dmn.readModelFromStream(is);
            List<String> decisionKeys = new ArrayList<>();
            for (Decision decision : modelInstance.getModelElementsByType(Decision.class)) {
                decisionKeys.add(decision.getId());
            }
            return decisionKeys;
        } catch (IOException e) {
            throw new DmnFileNotFoundException(dmnFilePath.toString());
        }
    }

    public List<RuleInfo> loadAllRules(Path dmnFilePath, String decisionKey) {
        try (InputStream is = Files.newInputStream(dmnFilePath)) {
            DmnModelInstance modelInstance = Dmn.readModelFromStream(is);
            Decision decision = modelInstance.getModelElementById(decisionKey);
            if (decision == null) throw new DmnDecisionNotFoundException(decisionKey, dmnFilePath.toString());
            Collection<DecisionTable> tables = decision.getChildElementsByType(DecisionTable.class);
            if (tables.isEmpty()) throw new DmnRuleNotFoundException("<any>", decisionKey, dmnFilePath.toString());
            DecisionTable table = tables.iterator().next();
            if (table.getRules().isEmpty()) throw new DmnRuleNotFoundException("<none>", decisionKey, dmnFilePath.toString());
            List<RuleInfo> rules = new ArrayList<>();
            for (Rule rule : table.getRules()) {
                List<String> inputEntries = new ArrayList<>();
                rule.getInputEntries().forEach(e -> inputEntries.add(e.getTextContent()));
                List<String> outputEntries = new ArrayList<>();
                rule.getOutputEntries().forEach(e -> outputEntries.add(e.getTextContent()));
                rules.add(new RuleInfo(rule.getId(), inputEntries, outputEntries));
            }
            return rules;
        } catch (IOException e) {
            throw new DmnFileNotFoundException(dmnFilePath.toString());
        }
    }

    // String-based DMN consumption APIs
    public DmnDecisionEvaluator loadRuleFromString(String dmnXmlContent, String ruleId) {
        ByteArrayInputStream is = new ByteArrayInputStream(dmnXmlContent.getBytes(StandardCharsets.UTF_8));
        DmnDecision dmnDecision = dmnEngine.parseDecision(ruleId, is);
        return new DmnDecisionEvaluator(dmnDecision, this);
    }

    public List<String> loadAllDecisionKeysFromString(String dmnXmlContent) {
        ByteArrayInputStream is = new ByteArrayInputStream(dmnXmlContent.getBytes(StandardCharsets.UTF_8));
        DmnModelInstance modelInstance = Dmn.readModelFromStream(is);
        List<String> decisionKeys = new ArrayList<>();
        for (Decision decision : modelInstance.getModelElementsByType(Decision.class)) {
            decisionKeys.add(decision.getId());
        }
        return decisionKeys;
    }

    public List<RuleInfo> loadAllRulesFromString(String dmnXmlContent, String decisionKey) {
        ByteArrayInputStream is = new ByteArrayInputStream(dmnXmlContent.getBytes(StandardCharsets.UTF_8));
        DmnModelInstance modelInstance = Dmn.readModelFromStream(is);
        Decision decision = modelInstance.getModelElementById(decisionKey);
        if (decision == null) throw new DmnDecisionNotFoundException(decisionKey, "<string>");
        Collection<DecisionTable> tables = decision.getChildElementsByType(DecisionTable.class);
        if (tables.isEmpty()) throw new DmnRuleNotFoundException("<any>", decisionKey, "<string>");
        DecisionTable table = tables.iterator().next();
        if (table.getRules().isEmpty()) throw new DmnRuleNotFoundException("<none>", decisionKey, "<string>");
        List<RuleInfo> rules = new ArrayList<>();
        for (Rule rule : table.getRules()) {
            List<String> inputEntries = new ArrayList<>();
            rule.getInputEntries().forEach(e -> inputEntries.add(e.getTextContent()));
            List<String> outputEntries = new ArrayList<>();
            rule.getOutputEntries().forEach(e -> outputEntries.add(e.getTextContent()));
            rules.add(new RuleInfo(rule.getId(), inputEntries, outputEntries));
        }
        return rules;
    }

    // Coverage event structure
    public static class CoverageEvent {
        public final String decisionKey;
        public final String ruleId;
        public final Map<String, Object> parameters;
        public CoverageEvent(String decisionKey, String ruleId, Map<String, Object> parameters) {
            this.decisionKey = decisionKey;
            this.ruleId = ruleId;
            this.parameters = parameters;
        }
    }

    // Rule info structure
    public static class RuleInfo {
        public final String ruleId;
        public final List<String> inputEntries;
        public final List<String> outputEntries;
        public RuleInfo(String ruleId, List<String> inputEntries, List<String> outputEntries) {
            this.ruleId = ruleId;
            this.inputEntries = inputEntries;
            this.outputEntries = outputEntries;
        }
    }

    // Custom exceptions
    public static class DmnFileNotFoundException extends RuntimeException {
        public DmnFileNotFoundException(String file) { super("DMN file not found: " + file); }
    }
    public static class DmnDecisionNotFoundException extends RuntimeException {
        public DmnDecisionNotFoundException(String decision, String file) { super("Decision '" + decision + "' not found in DMN file: " + file); }
    }
    public static class DmnRuleNotFoundException extends RuntimeException {
        public DmnRuleNotFoundException(String rule, String decision, String file) { super("Rule '" + rule + "' not found in decision '" + decision + "' in DMN file: " + file); }
    }
    public static class DmnAmbiguousEvaluationException extends RuntimeException {
        public DmnAmbiguousEvaluationException(String message) { super(message); }
    }
    public static class DmnEvaluationException extends RuntimeException {
        public DmnEvaluationException(String message, Throwable cause) { super(message, cause); }
    }
} 