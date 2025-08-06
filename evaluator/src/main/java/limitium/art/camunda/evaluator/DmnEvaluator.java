package limitium.art.camunda.evaluator;

import org.camunda.bpm.dmn.engine.DmnDecision;
import org.camunda.bpm.dmn.engine.DmnDecisionResult;
import org.camunda.bpm.dmn.engine.DmnEngine;
import org.camunda.bpm.dmn.engine.delegate.*;
import org.camunda.bpm.engine.variable.VariableMap;
import org.camunda.bpm.engine.variable.value.TypedValue;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.DumperOptions;
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
import org.camunda.bpm.model.dmn.instance.Input;
import org.camunda.bpm.model.dmn.instance.Output;
import java.util.Collection;
import java.nio.file.Path;
import java.nio.file.Files;
import java.io.IOException;

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

        private static final java.util.logging.Logger LOGGER = java.util.logging.Logger.getLogger(DmnDecisionEvaluator.class.getName());

        public DmnDecisionEvaluator(DmnDecision dmnDecision, DmnEvaluator dmnEvaluator) {
            this.dmnDecision = dmnDecision;
            this.dmnEvaluator = dmnEvaluator;
        }

        public DmnDecisionResult evaluate(VariableMap vars) {
            try {
                dmnEvaluator.listener.clearEvents();
                DmnDecisionResult dmnDecisionResultEntries = dmnEvaluator.dmnEngine.evaluateDecision(dmnDecision, vars);
                List<DmnDecisionTableEvaluationEvent> events = List.copyOf(dmnEvaluator.listener.getEvents());
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

        private void printHistory(List<DmnDecisionTableEvaluationEvent> events) {
            // Prepare YAML output
            List<Map<String, Object>> history = new ArrayList<>();
            for (DmnDecisionTableEvaluationEvent evt : events) {
                Map<String, Object> eventMap = new LinkedHashMap<>();
                eventMap.put("decisionTable", evt.getDecisionTable().getKey());
                // Inputs
                Map<String, Object> inputs = new LinkedHashMap<>();
                for (DmnEvaluatedInput inp : evt.getInputs()) {
                    inputs.put(inp.getName(), inp.getValue().getValue());
                }
                eventMap.put("inputs", inputs);
                // Matched rules
                List<Map<String, Object>> matchedRules = new ArrayList<>();
                for (DmnEvaluatedDecisionRule rule : evt.getMatchingRules()) {
                    Map<String, Object> ruleMap = new LinkedHashMap<>();
                    ruleMap.put("ruleId", rule.getId());
                    Map<String, Object> outputs = new LinkedHashMap<>();
                    rule.getOutputEntries().forEach((k, v) -> outputs.put(k, v.getValue().getValue()));
                    ruleMap.put("outputs", outputs);
                    matchedRules.add(ruleMap);
                }
                eventMap.put("matchedRules", matchedRules);
                history.add(eventMap);
            }
            // YAML options for pretty output
            DumperOptions options = new DumperOptions();
            options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
            options.setPrettyFlow(true);
            Yaml yaml = new Yaml(options);
            String yamlStr = yaml.dump(history);
            LOGGER.info("\n=== DMN Evaluation History (YAML) for decision: '" + dmnDecision.getKey() + "' ===\n" + yamlStr + "=== End of History ===\n");
        }
    }

    public DmnEvaluator() {
        listener = new DnmEvaluationEvenCollector();
        dmnEngine = org.camunda.bpm.dmn.engine.impl.DefaultDmnEngineConfiguration
                .createDefaultDmnEngineConfiguration()
                .customPostDecisionTableEvaluationListeners(List.of(listener))
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