package com.guanghe.backend.media;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guanghe.backend.common.api.BusinessException;
import com.guanghe.backend.common.api.ErrorCode;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** Validated public article links from the upstream receipt, never its attachment field. */
final class MediaPublishedLinks {
    private static final int MAX_LINKS = 20;
    private static final int MAX_URL_LENGTH = 2048;
    private static final int MAX_JSON_LENGTH = 16_384;

    private MediaPublishedLinks() { }

    /**
     * A signed upstream publication receipt is authoritative for status even when its free-text
     * return_info has no usable article URL. Never expose that free text as a browser link, and
     * never make point settlement depend on the provider formatting a clickable URL.
     */
    static List<String> publicLinksFromReceipt(String returnInfo) {
        if (returnInfo == null || returnInfo.isBlank() || returnInfo.length() > MAX_JSON_LENGTH) {
            return List.of();
        }
        String[] parts = returnInfo.split(";", -1);
        if (parts.length > MAX_LINKS) return List.of();
        LinkedHashSet<String> links = new LinkedHashSet<>();
        for (String part : parts) {
            try {
                links.add(validatedUrl(part.strip()));
            } catch (BusinessException ignored) {
                // The upstream may return prose or a non-public attachment, not an article URL.
            }
        }
        return List.copyOf(links);
    }

    static String toJson(ObjectMapper json, List<String> links) {
        try {
            String value = json.writeValueAsString(links);
            if (value.length() > MAX_JSON_LENGTH) throw invalid();
            return value;
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }

    static List<String> fromStored(ObjectMapper json, String value, String firstLink) {
        if (value == null || value.isBlank()) {
            return firstLink == null ? List.of() : List.of(validatedStoredUrl(firstLink));
        }
        if (value.length() > MAX_JSON_LENGTH) throw new IllegalStateException("Invalid stored media links");
        try {
            JsonNode array = json.readTree(value);
            if (!array.isArray() || array.size() > MAX_LINKS) {
                throw new IllegalStateException("Invalid stored media links");
            }
            List<String> links = new ArrayList<>();
            array.forEach(node -> {
                if (!node.isTextual()) throw new IllegalStateException("Invalid stored media link");
                links.add(validatedStoredUrl(node.textValue()));
            });
            if (links.isEmpty()) return firstLink == null ? List.of() : List.of(validatedStoredUrl(firstLink));
            if (firstLink == null || !links.getFirst().equals(validatedStoredUrl(firstLink))) {
                throw new IllegalStateException("Stored media links do not match the primary link");
            }
            return List.copyOf(links);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String validatedStoredUrl(String value) {
        try {
            return validatedUrl(value);
        } catch (BusinessException exception) {
            throw new IllegalStateException("Invalid stored media link", exception);
        }
    }

    private static String validatedUrl(String value) {
        if (value == null || value.isBlank() || value.length() > MAX_URL_LENGTH) throw invalid();
        try {
            URI uri = URI.create(value);
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null) throw invalid();
            String normalized = uri.toASCIIString();
            if (normalized.length() > MAX_URL_LENGTH) throw invalid();
            return normalized;
        } catch (IllegalArgumentException exception) {
            throw invalid();
        }
    }

    private static BusinessException invalid() {
        return new BusinessException(ErrorCode.VALIDATION_FAILED);
    }
}
