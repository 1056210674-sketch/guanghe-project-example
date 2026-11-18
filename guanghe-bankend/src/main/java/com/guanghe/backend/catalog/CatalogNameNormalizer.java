package com.guanghe.backend.catalog;

import com.guanghe.backend.common.api.BusinessException;
import com.guanghe.backend.common.api.ErrorCode;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public final class CatalogNameNormalizer {

    private static final int MAX_ALIASES = 50;

    public AliasValue requiredName(String raw, int maxLength, String field) {
        if (raw == null) {
            throw invalid(field);
        }
        String value = raw.strip();
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
                .strip()
                .toLowerCase(Locale.ROOT);
        if (value.isEmpty() || codePoints(value) > maxLength
                || normalized.isEmpty() || codePoints(normalized) > maxLength) {
            throw invalid(field);
        }
        return new AliasValue(value, normalized);
    }

    public String nullableText(String raw, int maxLength, String field) {
        if (raw == null) {
            return null;
        }
        String value = raw.strip();
        if (value.isEmpty() || codePoints(value) > maxLength) {
            throw invalid(field);
        }
        return value;
    }

    public List<AliasValue> aliases(List<String> rawAliases, int maxLength, String field) {
        List<String> source = rawAliases == null ? List.of() : rawAliases;
        if (source.size() > MAX_ALIASES) {
            throw invalid(field);
        }
        Map<String, AliasValue> unique = new LinkedHashMap<>();
        for (String raw : source) {
            AliasValue alias = requiredName(raw, maxLength, field);
            if (unique.putIfAbsent(alias.normalized(), alias) != null) {
                throw invalid(field);
            }
        }
        return unique.values().stream()
                .sorted(Comparator.comparing(AliasValue::normalized))
                .toList();
    }

    public List<String> textList(
            List<String> rawValues,
            int maxItems,
            int maxLength,
            String field
    ) {
        List<String> source = rawValues == null ? List.of() : rawValues;
        if (source.size() > maxItems) {
            throw invalid(field);
        }
        List<String> result = new ArrayList<>(source.size());
        for (String raw : source) {
            if (raw == null) {
                throw invalid(field);
            }
            String value = raw.strip();
            if (value.isEmpty() || codePoints(value) > maxLength || result.contains(value)) {
                throw invalid(field);
            }
            result.add(value);
        }
        return List.copyOf(result);
    }

    public Map<String, String> parameters(Map<String, String> rawParameters, String field) {
        Map<String, String> source = rawParameters == null ? Map.of() : rawParameters;
        if (source.size() > 100) {
            throw invalid(field);
        }
        Map<String, String> result = new LinkedHashMap<>();
        source.forEach((rawKey, rawValue) -> {
            if (rawKey == null || rawValue == null) {
                throw invalid(field);
            }
            String key = rawKey.strip();
            String value = rawValue.strip();
            if (key.isEmpty() || codePoints(key) > 100 || codePoints(value) > 1000
                    || result.putIfAbsent(key, value) != null) {
                throw invalid(field);
            }
        });
        return Map.copyOf(result);
    }

    public BusinessException invalid(String field) {
        return new BusinessException(
                ErrorCode.VALIDATION_FAILED,
                ErrorCode.VALIDATION_FAILED.defaultMessage(),
                Map.of("field", field)
        );
    }

    private int codePoints(String value) {
        return value.codePointCount(0, value.length());
    }
}
