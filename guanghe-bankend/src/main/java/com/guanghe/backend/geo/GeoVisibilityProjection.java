package com.guanghe.backend.geo;

import com.fasterxml.jackson.databind.JsonNode;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;

/** Read-only, versioned lexical evidence projection. Never reads today's catalog or infers recommendations. */
final class GeoVisibilityProjection {
    static final String METHOD = "explicit-name-mention-v1";
    // These are generic category descriptions, not an identity for a merchant's product.
    private static final Set<String> GENERIC_NAMES = Set.of(
            "商品", "产品", "品牌", "保温杯", "不锈钢保温杯", "咖啡", "美式咖啡", "冰美式", "咖啡机",
            "手机", "服装", "食品", "饮料", "水杯", "咖啡杯", "杯子", "product", "brand", "coffee");

    private GeoVisibilityProjection() { }

    record Target(GeoTaskDtos.VisibilityTarget value, List<String> terms, String unavailableReason) { }
    private record Term(String label, Pattern pattern) { }

    static Boolean mentioned(Target target, String status, String answer) {
        if (target.unavailableReason() != null || !"SUCCEEDED".equals(status)
                || answer == null || answer.isBlank()) return null;
        return !matchingTerms(answer, target.terms().stream().map(GeoVisibilityProjection::matcher).toList()).isEmpty();
    }

    static GeoTaskDtos.VisibilityEvidence evidence(GeoTaskRecords.Run run, JsonNode snapshot,
                                                   List<GeoTaskRecords.Observation> observations) {
        Target target = target(run, snapshot);
        return new GeoTaskDtos.VisibilityEvidence(
                target.unavailableReason() == null ? "AVAILABLE" : "TARGET_UNRESOLVED", METHOD,
                observations.stream().map(value -> new GeoTaskDtos.VisibilityEvidenceUnit(
                        Long.toString(value.questionId()), value.platformCode(), value.sampleNo(),
                        mentioned(target, value.status(), value.answerText()))).toList());
    }

    static Target target(GeoTaskRecords.Run run, JsonNode snapshot) {
        return target(run.spaceId(), run.productId(), snapshot);
    }

    static Target target(long spaceId, Long productId, JsonNode snapshot) {
        JsonNode catalog = snapshot.path("catalogSnapshot");
        JsonNode space = catalog.path("space");
        if (!Long.toString(spaceId).equals(space.path("id").asText())) {
            return new Target(null, List.of(), "MISSING_TARGET_SNAPSHOT");
        }
        JsonNode identity;
        String kind;
        String id;
        if (productId != null) {
            identity = catalog.path("product");
            kind = "PRODUCT";
            id = Long.toString(productId);
            if (!id.equals(identity.path("id").asText())
                    || !Long.toString(spaceId).equals(identity.path("spaceId").asText())) {
                return new Target(null, List.of(), "MISSING_TARGET_SNAPSHOT");
            }
        } else {
            identity = space.path("brand");
            kind = "BRAND";
            id = Long.toString(spaceId);
        }
        String name = text(identity.path("name"));
        if (name == null) return new Target(null, List.of(), "MISSING_TARGET_SNAPSHOT");
        List<String> aliases = new ArrayList<>();
        if (identity.path("aliases").isArray()) {
            for (JsonNode alias : identity.path("aliases")) {
                String value = text(alias);
                if (value != null && aliases.size() < 50) aliases.add(value);
            }
        }
        Map<String, String> terms = new LinkedHashMap<>();
        String category = normalize(identity.path("category").asText(""));
        String brandName = normalize(space.path("brand").path("name").asText(""));
        List<String> candidates = new ArrayList<>(List.of(name));
        candidates.addAll(aliases);
        for (String term : candidates) {
            String normalized = normalize(term);
            if (normalized.codePointCount(0, normalized.length()) < 2
                    || GENERIC_NAMES.contains(normalized) || normalized.equals(category)
                    || ("PRODUCT".equals(kind) && normalized.equals(brandName))) continue;
            terms.putIfAbsent(normalized, term);
        }
        return new Target(new GeoTaskDtos.VisibilityTarget(kind, id, name, List.copyOf(aliases)),
                List.copyOf(terms.values()), terms.isEmpty() ? "AMBIGUOUS_TARGET_NAMES" : null);
    }

    static GeoTaskDtos.VisibilityResponse calculate(GeoTaskRecords.Run run, JsonNode snapshot,
                                                    List<GeoTaskDtos.ObservationResponse> observations) {
        Target target = target(run, snapshot);
        boolean assessable = target.unavailableReason() == null;
        List<Term> matchers = target.terms().stream().map(GeoVisibilityProjection::matcher).toList();
        int rounds = "DEEP".equals(snapshot.path("parameters").path("sampleMode").asText()) ? 3 : 1;
        List<String> questionIds = new ArrayList<>();
        for (JsonNode question : snapshot.path("authorizedContext").path("questions")) {
            String id = text(question.path("id"));
            if (id != null && !questionIds.contains(id)) questionIds.add(id);
        }
        List<GeoTaskDtos.VisibilityPlatform> platforms = new ArrayList<>();
        List<GeoTaskDtos.VisibilitySample> samples = new ArrayList<>();
        int totalValid = 0;
        Set<String> seenPlatforms = new LinkedHashSet<>();
        for (JsonNode platformNode : snapshot.path("parameters").path("platformCodes")) {
            String platform = text(platformNode);
            if (platform == null || !seenPlatforms.add(platform)) continue;
            Set<String> expected = new LinkedHashSet<>();
            for (String questionId : questionIds) {
                for (int round = 1; round <= rounds; round++) expected.add(questionId + ":" + platform + ":" + round);
            }
            Set<String> counted = new LinkedHashSet<>();
            int valid = 0;
            int failed = 0;
            int mentioned = 0;
            for (GeoTaskDtos.ObservationResponse observation : observations) {
                if (!platform.equals(observation.platformCode()) || !expected.contains(observation.unitKey())
                        || !observation.unitKey().equals(observation.questionId() + ":" + platform + ":" + observation.sampleNo())
                        || !counted.add(observation.unitKey())) continue;
                boolean success = "SUCCEEDED".equals(observation.status())
                        && observation.answerText() != null && !observation.answerText().isBlank();
                if (success) valid++;
                else if ("FAILED".equals(observation.status())) failed++;
                List<String> matched = success && assessable
                        ? matchingTerms(observation.answerText(), matchers) : List.of();
                Boolean match = success && assessable ? !matched.isEmpty() : null;
                if (Boolean.TRUE.equals(match)) mentioned++;
                samples.add(new GeoTaskDtos.VisibilitySample(observation.unitKey(), match, matched));
            }
            totalValid += valid;
            platforms.add(new GeoTaskDtos.VisibilityPlatform(platform, expected.size(), valid,
                    assessable && valid > 0 ? mentioned : null, failed,
                    Math.max(0, expected.size() - valid - failed),
                    assessable && valid > 0 ? Math.round(10000.0 * mentioned / valid) / 100.0 : null));
        }
        String status = !assessable ? "UNAVAILABLE_TARGET" : totalValid == 0 ? "NO_VALID_SAMPLES" : "AVAILABLE";
        String reason = !assessable ? target.unavailableReason() : totalValid == 0 ? "NO_VALID_ANSWERS" : null;
        return new GeoTaskDtos.VisibilityResponse(METHOD, status, reason, target.value(),
                List.copyOf(platforms), List.copyOf(samples));
    }

    private static List<String> matchingTerms(String answer, List<Term> terms) {
        String normalizedAnswer = normalize(visibleAnswerText(answer));
        List<String> matched = new ArrayList<>();
        for (Term term : terms) {
            if (term.pattern().matcher(normalizedAnswer).find()) matched.add(term.label());
        }
        return List.copyOf(matched);
    }

    static String visibleAnswerText(String answer) {
        // Drop Markdown destinations (including optional titles and nested URL parentheses),
        // retaining the visible link label. This projection never changes saved evidence.
        String text = answer.replaceAll("(?m)^ {0,3}\\[[^\\]\\r\\n]+\\]:[^\\r\\n]*(?:\\r?\\n|$)", " ");
        StringBuilder visible = new StringBuilder(text.length());
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            visible.append(character);
            if (character != ']' || index + 1 >= text.length() || text.charAt(index + 1) != '(') continue;
            int depth = 1;
            int end = index + 2;
            while (end < text.length() && depth > 0) {
                char next = text.charAt(end);
                if (next == '\\' && end + 1 < text.length()) { end += 2; continue; }
                if (next == '(') depth++;
                if (next == ')') depth--;
                end++;
            }
            if (depth == 0) index = end - 1;
        }
        String withoutReferences = visible.toString().replaceAll("\\]\\[[^\\]\\r\\n]*\\]", "]");
        String withoutUrls = withoutReferences.replaceAll("(?i)\\b(?:https?://|www\\.)[^\\s<>\\[\\]\\\"']+", " ");
        // Reuse the existing HTML parser to avoid counting link attributes or hidden markup.
        return Jsoup.parseBodyFragment(withoutUrls).text();
    }

    private static Term matcher(String term) {
        String normalized = normalize(term);
        String left = latinOrDigit(normalized.codePointAt(0)) ? "(?<![\\p{IsLatin}\\p{N}_])" : "";
        String right = latinOrDigit(normalized.codePointBefore(normalized.length())) ? "(?![\\p{IsLatin}\\p{N}_])" : "";
        return new Term(term, Pattern.compile(left + Pattern.quote(normalized) + right));
    }

    private static boolean latinOrDigit(int value) {
        return Character.UnicodeScript.of(value) == Character.UnicodeScript.LATIN || Character.isDigit(value);
    }

    private static String text(JsonNode value) {
        return value.isTextual() && !value.textValue().isBlank() && value.textValue().length() <= 150
                ? value.textValue().strip() : null;
    }

    static boolean identityName(String name) {
        String value = normalize(name);
        return value.codePointCount(0, value.length()) >= 2 && value.length() <= 150 && !GENERIC_NAMES.contains(value);
    }

    static boolean containsName(String answer, List<String> terms) {
        return !matchingTerms(answer, terms.stream().filter(GeoVisibilityProjection::identityName)
                .map(GeoVisibilityProjection::matcher).toList()).isEmpty();
    }

    static String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).strip();
    }
}
