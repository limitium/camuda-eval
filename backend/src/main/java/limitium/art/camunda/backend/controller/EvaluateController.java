package limitium.art.camunda.backend.controller;

import limitium.art.camunda.evaluator.DmnEvaluator;
import org.camunda.bpm.dmn.engine.DmnDecisionResult;
import org.camunda.bpm.engine.variable.VariableMap;
import org.camunda.bpm.engine.variable.Variables;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Evaluates one decision of the given content, saved or not, so a table can be tried while it is
 * being changed. Says which rules matched in every table the evaluation went through, so the grid
 * can point at them.
 */
@RestController
@RequestMapping("/api/evaluate")
public class EvaluateController {

    public static class EvaluateRequest {
        public String dmnXml;
        public String decision;
        public Map<String, Object> inputs;
    }

    private static final java.util.regex.Pattern OVERLAP = java.util.regex.Pattern.compile("DmnEvaluatedDecisionRuleImpl\\{id='([^']+)'");

    @PostMapping
    public ResponseEntity<?> evaluate(@RequestBody EvaluateRequest request) {
        if (request == null || request.dmnXml == null || request.decision == null) {
            return ResponseEntity.badRequest().body(Collections.singletonMap("error", "content and decision are required"));
        }
        DmnEvaluator evaluator = new DmnEvaluator();
        evaluator.setCollectCoverage(true);
        VariableMap vars = Variables.createVariables();
        if (request.inputs != null) {
            request.inputs.forEach(vars::putValue);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        try {
            // Values supplied for what a required table gives replace that table: the chain runs from there.
            String scoped = limitium.art.camunda.evaluator.testspec.ChainScope.cutXml(request.dmnXml, request.decision,
                    request.inputs == null ? Collections.<String>emptySet() : request.inputs.keySet());
            DmnDecisionResult result = evaluator.loadRuleFromString(scoped, request.decision).evaluate(vars);
            List<Map<String, Object>> rows = new ArrayList<>();
            result.getResultList().forEach(entry -> rows.add(new LinkedHashMap<>(entry)));
            body.put("results", rows);
            body.put("matched", matched(evaluator));
            return ResponseEntity.ok(body);
        } catch (RuntimeException e) {
            // No rule matching is an answer, not a failure; anything else is said as it is.
            String message = rootMessage(e);
            body.put("results", Collections.emptyList());
            body.put("matched", matched(evaluator));
            body.put("noMatch", message.startsWith("No rules matched"));
            // Several rules matching a table that allows one is a fact about the rules, so name them.
            java.util.regex.Matcher overlap = OVERLAP.matcher(message);
            if (message.contains("only allows a single rule to match")) {
                List<String> rules = new ArrayList<>();
                while (overlap.find()) {
                    rules.add(overlap.group(1));
                }
                body.put("overlap", rules);
                body.put("error", null);
            } else {
                body.put("error", message.startsWith("No rules matched") ? null : message);
            }
            return ResponseEntity.ok(body);
        }
    }

    private static List<Map<String, String>> matched(DmnEvaluator evaluator) {
        List<Map<String, String>> out = new ArrayList<>();
        for (DmnEvaluator.CoverageEvent event : evaluator.getCoverageEvents()) {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("decision", event.decisionKey);
            m.put("rule", event.ruleId);
            out.add(m);
        }
        return out;
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        String message = e.getMessage() != null && e.getMessage().startsWith("No rules matched") ? e.getMessage() : t.getMessage();
        return message == null ? e.getClass().getSimpleName() : message;
    }
}
