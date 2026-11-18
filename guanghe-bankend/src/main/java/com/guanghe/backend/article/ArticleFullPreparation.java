package com.guanghe.backend.article;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.guanghe.backend.common.api.BusinessException;
import com.guanghe.backend.common.api.ErrorCode;
import com.guanghe.backend.task.PaidTaskProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * The fixed ARTICLE_FULL_V1 preparation contract. Deliberately not a Spring execution service or a
 * Browser/Internal API DTO. Admission, persistence, automatic adoption and deferred materialization
 * must be implemented before this value can authorize any task. Nothing here performs side effects.
 */
public final class ArticleFullPreparation {
    public static final String TEMPLATE_CODE = "ARTICLE_FULL_V1";
    private static final List<String> STAGES = List.of("TITLE", "OUTLINE", "BODY");
    private static final int MAX_PREPARATION_BYTES = 262_144;

    private ArticleFullPreparation() { }

    /** Only ArticleService's owner-checked path creates a brief; no mutable JSON escapes. */
    static Brief freezeBrief(ObjectMapper json, ObjectNode snapshot) {
        String canonical = canonical(json, snapshot);
        return new Brief(canonical, sha256(canonical));
    }

    public static Snapshot prepare(ObjectMapper json, PaidTaskProperties properties, Brief brief,
                                   OffsetDateTime preparedAt) {
        if (brief == null || preparedAt == null) throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        PaidTaskProperties.ArticleStageBudget budget = properties.articleStageBudget();
        Duration ttl = properties.getConfirmationTtl();
        if (ttl == null || ttl.isZero() || ttl.isNegative()) throw unavailable();
        List<Stage> stages = new ArrayList<>();
        int totalPoints = 0;
        try {
            for (String mode : STAGES) {
                PaidTaskProperties.ArticleStageQuote quote = properties.articleStageQuote(mode);
                stages.add(new Stage(stages.size() + 1, quote.mode(), quote.pricingVersionId(),
                        quote.quotedPoints(), budget));
                totalPoints = Math.addExact(totalPoints, quote.quotedPoints());
            }
            // This is a bounded preparation lifetime, not a per-resume renewal. Admission must persist
            // the deadline; later stages must be clipped to it and cannot create a fresh allowance.
            OffsetDateTime start = preparedAt.withOffsetSameInstant(ZoneOffset.UTC);
            OffsetDateTime deadline = start.plus(budget.executionDeadline().multipliedBy(STAGES.size()));
            OffsetDateTime expiry = start.plus(ttl);
            if (expiry.isAfter(deadline)) expiry = deadline;
            ObjectNode snapshot = json.createObjectNode();
            snapshot.put("templateCode", TEMPLATE_CODE);
            snapshot.put("schemaVersion", 1);
            snapshot.put("articleCount", 1);
            snapshot.put("maxReplans", 0);
            snapshot.put("briefHash", brief.sha256());
            snapshot.set("brief", json.readTree(brief.canonicalJson()));
            snapshot.put("totalQuotedPoints", totalPoints);
            snapshot.put("maxModelCalls", Math.multiplyExact(budget.maxModelCalls(), STAGES.size()));
            snapshot.put("preparedAt", start.toInstant().toString());
            snapshot.put("quoteExpiresAt", expiry.toInstant().toString());
            snapshot.put("deadlineAt", deadline.toInstant().toString());
            ArrayNode rows = snapshot.putArray("stages");
            for (Stage stage : stages) {
                ObjectNode row = rows.addObject();
                row.put("ordinal", stage.ordinal());
                row.put("mode", stage.mode());
                row.put("pricingVersionId", Long.toString(stage.pricingVersionId()));
                row.put("quotedPoints", stage.quotedPoints());
                ObjectNode limits = row.putObject("budget");
                limits.put("maxModelCalls", budget.maxModelCalls());
                limits.put("maxRepairRetries", budget.maxRepairRetries());
                // maxInputTokens remains a per-request ceiling, not a new cumulative token contract.
                limits.put("maxInputTokens", budget.maxInputTokens());
                limits.put("maxOutputTokens", budget.maxOutputTokens());
                limits.put("executionDeadline", budget.executionDeadline().toString());
            }
            String canonical = canonical(json, snapshot);
            if (canonical.getBytes(StandardCharsets.UTF_8).length > MAX_PREPARATION_BYTES) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED);
            }
            return new Snapshot(canonical, sha256(canonical), brief.sha256(), List.copyOf(stages),
                    totalPoints, Math.multiplyExact(budget.maxModelCalls(), STAGES.size()), start, expiry, deadline);
        } catch (ArithmeticException | DateTimeException exception) {
            throw unavailable();
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Frozen ARTICLE brief is invalid", exception);
        }
    }

    public static final class Brief {
        private final String canonicalJson;
        private final String sha256;

        private Brief(String canonicalJson, String sha256) {
            this.canonicalJson = canonicalJson;
            this.sha256 = sha256;
        }

        public String canonicalJson() { return canonicalJson; }
        public String sha256() { return sha256; }
        @Override public String toString() { return "ArticleFullBrief[sha256=" + sha256 + "]"; }
    }

    public record Stage(int ordinal, String mode, long pricingVersionId, int quotedPoints,
                        PaidTaskProperties.ArticleStageBudget budget) { }

    public record Snapshot(String canonicalJson, String sha256, String briefHash, List<Stage> stages,
                           int totalQuotedPoints, int maxModelCalls, OffsetDateTime preparedAt,
                           OffsetDateTime quoteExpiresAt, OffsetDateTime deadlineAt) {
        public Snapshot { stages = List.copyOf(stages); }
        @Override public String toString() { return "ArticleFullPreparation[sha256=" + sha256 + "]"; }
    }

    private static String canonical(ObjectMapper json, JsonNode value) {
        try { return json.writeValueAsString(sorted(json, value)); }
        catch (JsonProcessingException exception) { throw new IllegalStateException(exception); }
    }

    private static JsonNode sorted(ObjectMapper json, JsonNode value) {
        if (value.isObject()) {
            ObjectNode result = json.createObjectNode();
            List<String> fields = new ArrayList<>();
            value.fieldNames().forEachRemaining(fields::add);
            fields.stream().sorted().forEach(field -> result.set(field, sorted(json, value.get(field))));
            return result;
        }
        if (value.isArray()) {
            ArrayNode result = json.createArrayNode();
            value.forEach(item -> result.add(sorted(json, item)));
            return result;
        }
        return value.deepCopy();
    }

    private static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }

    private static BusinessException unavailable() {
        return new BusinessException(ErrorCode.PROVIDER_NOT_CONFIGURED);
    }
}
