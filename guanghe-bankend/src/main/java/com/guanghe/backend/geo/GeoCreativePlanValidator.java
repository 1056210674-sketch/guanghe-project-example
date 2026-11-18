package com.guanghe.backend.geo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.guanghe.backend.common.api.BusinessException;
import com.guanghe.backend.common.api.ErrorCode;
import java.util.HashSet;
import java.util.Set;

/** Strict boundary between a model-authored plan, the browser and a paid task snapshot. */
public final class GeoCreativePlanValidator {
    private static final Set<String> UNSIGNED_FIELDS = Set.of("version", "taskType", "reportId",
            "recommendationId", "opportunity", "audience", "message", "evidenceKeys", "scene",
            "visualFocus", "shotBeats", "limitations");
    private static final Set<String> SIGNED_FIELDS = new HashSet<>(UNSIGNED_FIELDS);
    static { SIGNED_FIELDS.add("proof"); }

    private GeoCreativePlanValidator() { }

    public static ObjectNode require(JsonNode plan, String taskType, long reportId,
                                     String recommendationId, String scene, Set<String> availableEvidence) {
        return validate(plan, taskType, reportId, recommendationId, scene, availableEvidence, true);
    }

    public static ObjectNode requireUnsigned(JsonNode plan, String taskType, long reportId,
                                             String recommendationId, String scene,
                                             Set<String> availableEvidence) {
        return validate(plan, taskType, reportId, recommendationId, scene, availableEvidence, false);
    }

    private static ObjectNode validate(JsonNode plan, String taskType, long reportId,
                                       String recommendationId, String scene,
                                       Set<String> availableEvidence, boolean signed) {
        if (!(plan instanceof ObjectNode value)
                || !fields(value).equals(signed ? SIGNED_FIELDS : UNSIGNED_FIELDS)
                || signed && !value.path("proof").asText("").matches("^v1\\.[A-Za-z0-9_-]{43}$")
                || !"geo-visual-plan-v1".equals(value.path("version").textValue())
                || !taskType.equals(value.path("taskType").textValue())
                || !Long.toString(reportId).equals(value.path("reportId").textValue())
                || !sameOptional(recommendationId, value.get("recommendationId"))
                || !bounded(value.get("opportunity"), 300)
                || !bounded(value.get("audience"), 180)
                || !bounded(value.get("message"), 300)
                || !bounded(value.get("scene"), 800)
                || !bounded(value.get("visualFocus"), 300)
                || scene == null || !scene.equals(value.path("scene").textValue())) {
            throw invalid();
        }
        JsonNode evidence = value.path("evidenceKeys");
        if (!evidence.isArray() || evidence.isEmpty() || evidence.size() > 8) throw invalid();
        Set<String> unique = new HashSet<>();
        for (JsonNode key : evidence) {
            if (!bounded(key, 80) || !unique.add(key.textValue())
                    || !availableEvidence.contains(key.textValue())) throw invalid();
        }
        JsonNode beats = value.path("shotBeats");
        if (!beats.isArray() || ("IMAGE".equals(taskType) && !beats.isEmpty())
                || ("VIDEO".equals(taskType) && (beats.size() < 2 || beats.size() > 3))) throw invalid();
        for (JsonNode beat : beats) if (!bounded(beat, 240)) throw invalid();
        JsonNode limits = value.path("limitations");
        if (!limits.isArray() || limits.isEmpty() || limits.size() > 4) throw invalid();
        for (JsonNode limit : limits) if (!bounded(limit, 240)) throw invalid();
        return value.deepCopy();
    }

    private static Set<String> fields(ObjectNode value) {
        Set<String> names = new HashSet<>();
        value.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static boolean bounded(JsonNode value, int max) {
        return value != null && value.isTextual() && !value.textValue().isBlank()
                && value.textValue().length() <= max;
    }

    private static boolean sameOptional(String expected, JsonNode value) {
        return expected == null ? value != null && value.isNull()
                : value != null && value.isTextual() && expected.equals(value.textValue());
    }

    private static BusinessException invalid() {
        return new BusinessException(ErrorCode.VALIDATION_FAILED,
                "GEO 创作方案无效或已与当前报告不一致，请重新生成", java.util.Map.of());
    }
}
