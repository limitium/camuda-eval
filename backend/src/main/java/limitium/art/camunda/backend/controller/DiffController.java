package limitium.art.camunda.backend.controller;

import limitium.art.camunda.evaluator.diff.DmnDiff;
import limitium.art.camunda.evaluator.diff.DmnDiffer;
import limitium.art.camunda.evaluator.diff.DmnMerger;
import limitium.art.camunda.evaluator.diff.MergeResult;
import limitium.art.camunda.evaluator.diff.Resolution;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Semantic diff and three-way merge over DMN content.
 *
 * <p>These take XML directly rather than revisions, so they work before any git or Kafka wiring
 * exists and stay usable as the plain "compare these two documents" API.
 */
@RestController
@RequestMapping("/api/diff")
public class DiffController {

    public static class DiffRequest {
        public String leftXml;
        public String rightXml;
        public String leftLabel;
        public String rightLabel;
    }

    public static class MergeRequest {
        public String baseXml;
        public String oursXml;
        public String theirsXml;
        /** Conflict id to "ours" or "theirs"; conflicts left out stay unresolved. */
        public Map<String, String> resolutions;
    }

    @PostMapping
    public ResponseEntity<?> diff(@RequestBody DiffRequest request) {
        if (request == null || request.leftXml == null || request.rightXml == null) {
            return badRequest("leftXml and rightXml are required");
        }
        try {
            DmnDiff diff = DmnDiffer.diff(request.leftXml, request.rightXml,
                    request.leftLabel != null ? request.leftLabel : "left",
                    request.rightLabel != null ? request.rightLabel : "right");
            return ResponseEntity.ok(diff);
        } catch (RuntimeException e) {
            return badRequest("could not diff: " + e.getMessage());
        }
    }

    @PostMapping("/merge-preview")
    public ResponseEntity<?> mergePreview(@RequestBody MergeRequest request) {
        if (request == null || request.baseXml == null || request.oursXml == null || request.theirsXml == null) {
            return badRequest("baseXml, oursXml and theirsXml are required");
        }
        Map<String, Resolution> resolutions;
        try {
            resolutions = parseResolutions(request.resolutions);
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
        try {
            MergeResult result = DmnMerger.merge(request.baseXml, request.oursXml, request.theirsXml, resolutions);
            return ResponseEntity.ok(result);
        } catch (RuntimeException e) {
            return badRequest("could not merge: " + e.getMessage());
        }
    }

    private static Map<String, Resolution> parseResolutions(Map<String, String> raw) {
        if (raw == null || raw.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, Resolution> resolutions = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : raw.entrySet()) {
            String value = entry.getValue();
            if (value == null) {
                continue;
            }
            try {
                resolutions.put(entry.getKey(), Resolution.valueOf(value.trim().toUpperCase()));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                        "resolution for '" + entry.getKey() + "' must be 'ours' or 'theirs', got '" + value + "'");
            }
        }
        return resolutions;
    }

    private static ResponseEntity<Map<String, Object>> badRequest(String message) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Collections.singletonMap("error", message));
    }
}
