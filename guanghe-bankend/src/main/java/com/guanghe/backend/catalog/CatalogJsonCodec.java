package com.guanghe.backend.catalog;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public final class CatalogJsonCodec {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() { };
    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() { };

    private final ObjectMapper objectMapper;

    public CatalogJsonCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to encode catalog JSON", exception);
        }
    }

    public <T> T read(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to decode catalog JSON", exception);
        }
    }

    public List<String> readStringList(String json) {
        try {
            return List.copyOf(objectMapper.readValue(json, STRING_LIST));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to decode catalog JSON array", exception);
        }
    }

    public Map<String, String> readStringMap(String json) {
        try {
            return Map.copyOf(objectMapper.readValue(json, STRING_MAP));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to decode catalog JSON object", exception);
        }
    }
}
