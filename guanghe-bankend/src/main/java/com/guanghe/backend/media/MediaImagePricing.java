package com.guanghe.backend.media;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/** The same explicit image-pricing rules are used by capability checks, quotes and confirmation. */
final class MediaImagePricing {
    private static final Pattern AMOUNT = Pattern.compile("(?:0|[1-9][0-9]{0,7})(?:\\.[0-9]{1,4})?");
    private static final Pattern UNIT_FEE = Pattern.compile(
            "((?:0|[1-9][0-9]{0,7})(?:\\.[0-9]{1,4})?)元[/／](张|篇)");

    private MediaImagePricing() { }

    record Rule(String mode, BigDecimal unitAmount, String source, String definition) {
        BigDecimal fee(int imageCount) {
            if (imageCount < 0 || imageCount > 10) throw new IllegalArgumentException("Invalid image count");
            if (imageCount == 0 || "INCLUDED".equals(mode)) return BigDecimal.ZERO.setScale(4);
            return ("PER_IMAGE".equals(mode) ? unitAmount.multiply(BigDecimal.valueOf(imageCount))
                    : unitAmount).setScale(4);
        }

        String description() {
            return switch (mode) {
                case "INCLUDED" -> "图片费用已包含在媒体报价中";
                case "PER_IMAGE" -> "图片按张计费，以本稿图片数量核算最终报价";
                case "PER_ARTICLE" -> "图片附加费按篇计费";
                default -> throw new IllegalStateException("Unsupported image pricing mode");
            };
        }
    }

    static Optional<Rule> resolve(MediaProperties properties, MediaRecords.Resource resource, ObjectMapper json) {
        JsonNode capabilities;
        try { capabilities = json.readTree(resource.capabilitiesJson()); }
        catch (Exception exception) { return Optional.empty(); }
        if (capabilities == null || !capabilities.isObject()) return Optional.empty();
        String policy = normalize(capabilities.path("imagePolicy").asText(""));
        // A configured rate never overrides an explicit supplier prohibition.
        if (deniesImages(policy)) return Optional.empty();
        String key = resource.upstreamModelId() + ":" + resource.upstreamResourceId();
        MediaProperties.ImagePricingRule configured = properties.getImagePricingRules().get(key);
        String rawFee = normalize(capabilities.path("imageFeeDescription").asText(""));
        if (rawFee.contains("无图") || rawFee.contains("不支持图片") || rawFee.contains("不可带图")
                || rawFee.contains("禁止图片")) return Optional.empty();
        if (configured != null) {
            if (!configured.valid()) return Optional.empty();
            String mode = configured.getMode() == null ? "" : configured.getMode().strip().toUpperCase(Locale.ROOT);
            if ("LEGACY_PER_IMAGE".equals(mode)) {
                // A naked number has no universal unit. Only an explicitly verified per-media legacy
                // rule permits interpreting that supplier field as a per-image amount.
                if (!AMOUNT.matcher(rawFee).matches()) return Optional.empty();
                return rule("PER_IMAGE", new BigDecimal(rawFee), "VERIFIED_LEGACY",
                        key + "@" + configured.getVersion() + ":" + rawFee);
            }
            if (!Set.of("INCLUDED", "PER_IMAGE", "PER_ARTICLE").contains(mode)) return Optional.empty();
            return rule(mode, configured.getAmount(), "VERIFIED_RULE", key + "@" + configured.getVersion());
        }
        if (Set.of("免费", "不收费").contains(rawFee)
                || rawFee.matches("0(?:\\.0{1,4})?(?:元)?")) {
            // The legacy resource feed often leaves imagePolicy empty. Its explicit image-fee
            // field is itself a capability signal; requiring another populated policy would
            // incorrectly hide those existing channels. Missing/ambiguous fees still fail closed.
            return rule("INCLUDED", BigDecimal.ZERO, "RESOURCE_METADATA", rawFee);
        }
        var matched = UNIT_FEE.matcher(rawFee);
        if (matched.matches()) {
            return rule("张".equals(matched.group(2)) ? "PER_IMAGE" : "PER_ARTICLE",
                    new BigDecimal(matched.group(1)), "RESOURCE_METADATA", rawFee);
        }
        // Missing, ambiguous and unquantified "收费" are not a zero-price promise.
        return Optional.empty();
    }

    private static Optional<Rule> rule(String mode, BigDecimal amount, String source, String definition) {
        if (amount == null || amount.signum() < 0 || amount.scale() > 4
                || amount.compareTo(new BigDecimal("99999999.9999")) > 0
                || "INCLUDED".equals(mode) && amount.signum() != 0) return Optional.empty();
        return Optional.of(new Rule(mode, amount.setScale(4), source, definition));
    }

    private static boolean deniesImages(String value) {
        return value.contains("不可") || value.contains("禁止") || value.contains("不支持")
                || value.contains("不带") || value.contains("无图") || value.contains("不能")
                || value.contains("不允许") || value.contains("拒绝")
                || Set.of("否", "无", "0", "FALSE", "NO", "UNSUPPORTED", "NOT_SUPPORTED")
                    .contains(value.toUpperCase(Locale.ROOT));
    }

    private static String normalize(String value) { return value.strip().replaceAll("\\s+", ""); }
}
