package com.guanghe.backend.geo;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.guanghe.backend.auth.security.AuthenticatedAccount;
import com.guanghe.backend.catalog.CatalogAccess;
import com.guanghe.backend.catalog.product.ProductIdentityAssetService;
import com.guanghe.backend.common.api.BusinessException;
import com.guanghe.backend.common.api.ErrorCode;
import com.guanghe.backend.conversation.context.AuthorizedCatalogContextFactory;
import com.guanghe.backend.conversation.context.InternalRunContextDtos;
import com.guanghe.backend.integration.internal.client.PythonCallResult;
import com.guanghe.backend.integration.internal.client.PythonInternalClient;
import com.guanghe.backend.task.PaidTaskProperties;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Service;

/** Builds only a bounded, authorized evidence packet before asking the text model for a visual plan. */
@Service
@ConditionalOnExpression("'${zhixiaohe.paid-task.enabled:false}' == 'true'"
        + " and '${zhixiaohe.asset.enabled:false}' == 'true'")
public class GeoCreativePlanService {
    private static final long MIN_USER_INTERVAL_NANOS = java.time.Duration.ofSeconds(30).toNanos();
    private static final int MAX_LOCAL_BUCKETS = 10_000;
    private final GeoTaskMapper reports;
    private final AuthorizedCatalogContextFactory catalog;
    private final ProductIdentityAssetService identityAssets;
    private final PythonInternalClient python;
    private final ObjectMapper json;
    private final GeoCreativePlanProof proof;
    /** Small per-JVM double-click/rapid-repeat guard; provider concurrency is enforced by Python. */
    private final ConcurrentHashMap<Long, Long> nextAllowedByUser = new ConcurrentHashMap<>();

    public GeoCreativePlanService(GeoTaskMapper reports, AuthorizedCatalogContextFactory catalog,
                                  ProductIdentityAssetService identityAssets,
                                  ObjectProvider<PythonInternalClient> pythonProvider,
                                  PaidTaskProperties taskProperties, ObjectMapper json) {
        this.reports = reports;
        this.catalog = catalog;
        this.identityAssets = identityAssets;
        this.python = pythonProvider.getIfAvailable();
        this.json = json;
        this.proof = new GeoCreativePlanProof(taskProperties, json);
    }

    /** No transaction spans the model call. The plan is generated only by an explicit browser POST. */
    public GeoCreativePlanDtos.Response plan(AuthenticatedAccount account, long reportId,
                                             GeoCreativePlanDtos.Request request) {
        long userId = CatalogAccess.requireUser(account);
        long productId = id(request.productId());
        if (!Set.of("IMAGE", "VIDEO").contains(request.taskType())
                || request.userDirection() != null && request.userDirection().length() > 500) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        GeoTaskRecords.Report report = reports.findReport(reportId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        GeoTaskRecords.Run run = reports.findRun(report.runId(), userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        if (!Set.of("SUCCEEDED", "PARTIAL").contains(report.status())
                || !Set.of("SUCCEEDED", "PARTIAL").contains(run.status())
                || !"geo-synthesis-v1".equals(report.methodVersion())
                || report.sampleSize() < 1 || report.sampleSize() > 210 || run.succeededUnits() < 1
                || run.productId() == null || run.productId() != productId
                || report.archivedAt() != null || report.summary() == null || report.summary().isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "请选择当前商品真实完成且含有效证据的 GEO 报告", java.util.Map.of());
        }
        InternalRunContextDtos.Product product = catalog.create(userId, run.spaceId(), productId).product();
        int identityAssetCount = identityAssets.requireReadyIdentityAssets(userId, productId, run.spaceId()).size();
        JsonNode synthesis = read(report.synthesisJson());
        JsonNode recommendation = recommendation(synthesis, reportId, request.recommendationId());
        List<GeoCreativePlanDtos.Evidence> evidence = selectEvidence(
                reports.findSuccessfulEvidence(run.id(), userId, 210), recommendation,
                report.summary(), request.userDirection());
        if (evidence.isEmpty()) throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                "报告缺少成功的观测样本，暂不能生成创作方案", java.util.Map.of());

        ObjectNode packet = json.createObjectNode();
        packet.put("taskType", request.taskType());
        ObjectNode reportPacket = packet.putObject("report");
        reportPacket.put("reportId", Long.toString(report.id()));
        reportPacket.put("methodVersion", report.methodVersion());
        reportPacket.put("status", report.status());
        reportPacket.put("sampleSize", report.sampleSize());
        reportPacket.put("summary", cut(report.summary(), 1000));
        if (recommendation == null) reportPacket.putNull("recommendation");
        else {
            ObjectNode selected = reportPacket.putObject("recommendation");
            selected.put("id", request.recommendationId());
            for (String part : List.of("priority", "title", "rationale")) {
                String text = recommendation.path(part).asText("");
                if (text.isBlank()) throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                        "报告中的该建议缺少必要内容，请选择其他建议", java.util.Map.of());
                selected.put(part, cut(text, "priority".equals(part) ? 32 :
                        "title".equals(part) ? 300 : 500));
            }
        }
        for (String field : List.of("coverageGaps", "findings", "contentBlueprint", "missingSamples")) {
            ArrayNode out = reportPacket.putArray(field);
            JsonNode source = synthesis.path(field);
            if (source.isArray()) for (JsonNode item : source) {
                if (out.size() >= 2) break;
                if (item.isTextual() && !item.textValue().isBlank()) out.add(cut(item.textValue(), 160));
            }
        }
        packet.set("evidence", json.valueToTree(evidence));
        ObjectNode facts = packet.putObject("productFacts");
        if (product.name() == null || product.name().isBlank()) throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        facts.put("name", cut(product.name(), 150));
        nullable(facts, "category", product.category(), 100);
        nullable(facts, "modelSpec", product.modelSpec(), 150);
        boundedList(facts.putArray("sellingPoints"), product.sellingPoints(), 3, 120);
        ObjectNode parameters = facts.putObject("parameters");
        product.parameters().entrySet().stream()
                .filter(entry -> entry.getKey() != null && !entry.getKey().isBlank()
                        && entry.getValue() != null && !entry.getValue().isBlank())
                .sorted(java.util.Map.Entry.comparingByKey()).limit(5)
                .forEach(entry -> parameters.put(cut(entry.getKey(), 80), cut(entry.getValue(), 160)));
        boundedList(facts.putArray("audience"), product.audience(), 3, 120);
        boundedList(facts.putArray("usageScenarios"), product.usageScenarios(), 3, 120);
        boundedList(packet.putArray("prohibitedClaims"), product.prohibitedClaims(), 4, 120);
        if (request.userDirection() == null || request.userDirection().isBlank()) packet.putNull("userDirection");
        else packet.put("userDirection", request.userDirection().trim());
        if (packet.toString().length() > 14_000) throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                "当前报告和商品信息过长，请缩短自定义要求后重试", java.util.Map.of());

        if (python == null) throw new BusinessException(ErrorCode.PROVIDER_NOT_CONFIGURED);
        throttle(userId);
        PythonCallResult<JsonNode> result = python.planGeoVisual(packet);
        if (!result.available() || result.httpStatus() != 200 || result.payload() == null
                || !result.payload().isObject()) {
            throw new BusinessException(ErrorCode.PROVIDER_UNAVAILABLE,
                    "GEO 创作方案暂不可用，请稍后重试", java.util.Map.of());
        }
        ObjectNode plan = ((ObjectNode) result.payload()).deepCopy();
        plan.put("reportId", Long.toString(reportId));
        if (request.recommendationId() == null) plan.putNull("recommendationId");
        else plan.put("recommendationId", request.recommendationId());
        Set<String> offered = new HashSet<>();
        evidence.forEach(item -> offered.add(item.key()));
        ObjectNode checked;
        try {
            checked = GeoCreativePlanValidator.requireUnsigned(plan, request.taskType(), reportId,
                    request.recommendationId(), plan.path("scene").textValue(), offered);
        } catch (BusinessException invalidModelPlan) {
            throw new BusinessException(ErrorCode.PROVIDER_UNAVAILABLE,
                    "GEO 创作方案暂不可用，请稍后重试", java.util.Map.of());
        }
        // Runtime counts and reference availability are business facts, not model copy.
        // Replace model-authored caveats before signing so they cannot contradict the
        // owned run or claim that a required, verified reference image is absent.
        ArrayNode limitations = checked.putArray("limitations");
        limitations.add("本报告计划 " + run.expectedUnits() + " 条，成功 " + run.succeededUnits()
                + " 条，失败 " + run.failedUnits() + " 条，缺失 " + run.missingUnits()
                + " 条。失败和缺失不按“未提及”计算。");
        limitations.add("本次规划取用 " + evidence.size() + " 条成功回答节选，方案引用其中 "
                + checked.path("evidenceKeys").size()
                + " 条；节选不是完整回答或新增监测，不代表全平台覆盖。");
        limitations.add("商品已有 " + identityAssetCount
                + " 张可用身份参考图。本次方案基于商品文字资料与回答节选，未进行图片内容识别；成品外观以最终选定的参考图为准。");
        limitations.add("创意不代表平台推荐、排名或市场效果；不得新增未经商品资料证实的性能、认证、销量或功效，须遵守商品全部禁用表述。测试或演示资料仅用于测试展示。");
        proof.sign(checked, userId, run.spaceId(), productId, product);
        return new GeoCreativePlanDtos.Response(Long.toString(reportId), request.recommendationId(),
                checked, evidence);
    }

    /** Rank all bounded run observations before selecting a small, varied packet for the planner. */
    private List<GeoCreativePlanDtos.Evidence> selectEvidence(List<GeoTaskRecords.Observation> observations,
                                                                JsonNode recommendation, String summary,
                                                                String userDirection) {
        LinkedHashMap<String, GeoCreativePlanDtos.Evidence> distinct = new LinkedHashMap<>();
        for (GeoTaskRecords.Observation observation : observations) {
            if (observation.unitKey() == null || observation.unitKey().isBlank()
                    || observation.unitKey().length() > 80
                    || observation.questionTextSnapshot() == null || observation.questionTextSnapshot().isBlank()
                    || observation.answerText() == null || observation.answerText().isBlank()
                    || observation.platformCode() == null || observation.platformCode().isBlank()) continue;
            distinct.putIfAbsent(observation.unitKey(), new GeoCreativePlanDtos.Evidence(
                    observation.unitKey(), cut(observation.questionTextSnapshot(), 160),
                    cut(observation.platformCode(), 32), cut(observation.answerText(), 300)));
        }
        String selectedText = recommendation == null ? "" : recommendation.path("title").asText("")
                + " " + recommendation.path("rationale").asText("");
        Set<String> selectedTerms = terms(selectedText);
        Set<String> summaryTerms = terms(summary);
        Set<String> directionTerms = terms(userDirection);
        ArrayList<GeoCreativePlanDtos.Evidence> available = new ArrayList<>(distinct.values());
        ArrayList<GeoCreativePlanDtos.Evidence> chosen = new ArrayList<>();
        while (chosen.size() < 8 && !available.isEmpty()) {
            GeoCreativePlanDtos.Evidence best = null;
            int bestScore = Integer.MIN_VALUE;
            for (GeoCreativePlanDtos.Evidence candidate : available) {
                Set<String> answerTerms = terms(candidate.questionText() + " " + candidate.answerExcerpt());
                int score = 20 * overlap(selectedTerms, answerTerms)
                        + 4 * overlap(summaryTerms, answerTerms)
                        + 8 * overlap(directionTerms, answerTerms);
                for (GeoCreativePlanDtos.Evidence already : chosen) {
                    if (already.platformCode().equals(candidate.platformCode())) score -= 5;
                    if (already.questionText().equals(candidate.questionText())) score -= 12;
                }
                if (score > bestScore) { best = candidate; bestScore = score; }
            }
            chosen.add(best);
            available.remove(best);
        }
        return List.copyOf(chosen);
    }

    private static Set<String> terms(String text) {
        Set<String> terms = new HashSet<>();
        if (text == null) return terms;
        String normalized = text.toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]+", " ");
        for (String word : normalized.split(" +")) {
            if (word.isBlank()) continue;
            if (word.length() >= 2) {
                for (int i = 0; i < word.length() - 1; i++) terms.add(word.substring(i, i + 2));
            } else terms.add(word);
        }
        return terms;
    }

    private static int overlap(Set<String> left, Set<String> right) {
        int count = 0;
        for (String term : left) if (right.contains(term)) count++;
        return count;
    }

    private JsonNode recommendation(JsonNode synthesis, long reportId, String requested) {
        if (requested == null) return null;
        String prefix = reportId + ":recommendation:";
        if (!requested.startsWith(prefix)) throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        try {
            int index = Integer.parseInt(requested.substring(prefix.length())) - 1;
            JsonNode list = synthesis.path("recommendations");
            if (list.isArray() && index >= 0 && index < list.size() && list.path(index).isObject()) {
                return list.path(index);
            }
        } catch (NumberFormatException ignored) { }
        throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
    }

    private void throttle(long userId) {
        long now = System.nanoTime();
        if (nextAllowedByUser.size() >= MAX_LOCAL_BUCKETS) {
            nextAllowedByUser.entrySet().removeIf(entry -> entry.getValue() - now <= 0);
            if (nextAllowedByUser.size() >= MAX_LOCAL_BUCKETS && !nextAllowedByUser.containsKey(userId)) {
                throw new BusinessException(ErrorCode.RATE_LIMITED);
            }
        }
        nextAllowedByUser.compute(userId, (ignored, deadline) -> {
            if (deadline != null && deadline - now > 0) throw new BusinessException(ErrorCode.RATE_LIMITED);
            return now + MIN_USER_INTERVAL_NANOS;
        });
    }

    private JsonNode read(String value) {
        try { return json.readTree(value == null ? "{}" : value); }
        catch (JsonProcessingException exception) { throw new BusinessException(ErrorCode.PROVIDER_UNAVAILABLE); }
    }

    private static long id(String value) {
        try {
            long parsed = Long.parseLong(value);
            if (parsed > 0) return parsed;
        } catch (RuntimeException ignored) { }
        throw new BusinessException(ErrorCode.VALIDATION_FAILED);
    }

    private static String cut(String value, int maximum) {
        if (value == null) return "";
        return value.substring(0, Math.min(value.length(), maximum));
    }

    private static void nullable(ObjectNode target, String key, String value, int maximum) {
        if (value == null) target.putNull(key);
        else target.put(key, cut(value, maximum));
    }

    private static void boundedList(ArrayNode target, List<String> values, int maximumCount, int maximumLength) {
        if (values == null) return;
        values.stream().filter(value -> value != null && !value.isBlank()).limit(maximumCount)
                .forEach(value -> target.add(cut(value, maximumLength)));
    }
}
