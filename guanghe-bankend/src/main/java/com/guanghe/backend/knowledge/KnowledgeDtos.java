package com.guanghe.backend.knowledge;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;

public final class KnowledgeDtos {
    private KnowledgeDtos() { }

    public record CreateDocumentRequest(@NotBlank @Size(max = 255) String name,
            @NotBlank @Size(max = 100000) String text, @Size(max = 2048) String sourceUrl) {
        @JsonAnySetter public void rejectUnknown(String name, JsonNode value) {
            throw new IllegalArgumentException("Unknown knowledge field");
        }
    }
    public record UpdateDocumentRequest(@NotNull @Min(1) Integer version,
            @NotBlank @Size(max = 255) String name, @NotBlank @Size(max = 100000) String text,
            @Size(max = 2048) String sourceUrl) {
        @JsonAnySetter public void rejectUnknown(String name, JsonNode value) {
            throw new IllegalArgumentException("Unknown knowledge field");
        }
    }
    public record Paragraph(int index, Integer page, String text) { }
    public record DocumentSummary(String id, String projectId, String name, String sourceType,
            String status, int version, String sha256, String contentType, long sizeBytes,
            int paragraphCount, Integer pageCount, String sourceUrl, String failureCode,
            OffsetDateTime createdAt, OffsetDateTime updatedAt) { }
    public record DocumentDetail(String id, String projectId, String name, String sourceType,
            String status, int version, String sha256, String contentType, long sizeBytes,
            int paragraphCount, Integer pageCount, String sourceUrl, String failureCode,
            OffsetDateTime createdAt, OffsetDateTime updatedAt, List<Paragraph> paragraphs,
            boolean readLimitApplied) {
        public DocumentDetail { paragraphs = List.copyOf(paragraphs); }
    }
    public record CreatePreferenceRequest(@Pattern(regexp = "^[1-9][0-9]{0,18}$") String projectId,
            @NotBlank @Size(max = 1000) String content) {
        @JsonAnySetter public void rejectUnknown(String name, JsonNode value) {
            throw new IllegalArgumentException("Unknown preference field");
        }
    }
    public record UpdatePreferenceRequest(@NotNull @Min(1) Integer version,
            @NotBlank @Size(max = 1000) String content) {
        @JsonAnySetter public void rejectUnknown(String name, JsonNode value) {
            throw new IllegalArgumentException("Unknown preference field");
        }
    }
    public record PreferenceView(String id, String projectId, String scope, String content,
            String sourceType, String sourceMessageId, int version,
            OffsetDateTime createdAt, OffsetDateTime updatedAt) { }
    public record BusinessHistory(String projectId, long writtenArticleCount,
            long publishedArticleCount, long publishedPlacementCount, OffsetDateTime asOf) { }
    public record ContextDocument(String id, int version, String name, String sha256,
            String sourceType, String sourceUrl, List<Paragraph> paragraphs) {
        public ContextDocument { paragraphs = List.copyOf(paragraphs); }
    }
    /** Untrusted user-provided sources, never execution instructions or authorization. */
    public record KnowledgeContext(List<ContextDocument> documents, List<PreferenceView> preferences,
            BusinessHistory businessHistory, boolean truncated, int sourceCount, int returnedCharacters,
            MemoryDtos.Context memory) {
        public KnowledgeContext(List<ContextDocument> documents,List<PreferenceView> preferences,
                BusinessHistory businessHistory,boolean truncated,int sourceCount,int returnedCharacters) {
            this(documents,preferences,businessHistory,truncated,sourceCount,returnedCharacters,null);
        }
        public KnowledgeContext {
            documents = List.copyOf(documents);
            preferences = List.copyOf(preferences);
        }
        /** Resource-read access does not implicitly grant scoped memory or personal preferences. */
        public KnowledgeContext withoutMemory() {
            return new KnowledgeContext(documents, List.of(), businessHistory, truncated, sourceCount,
                    documents.stream().flatMap(document -> document.paragraphs().stream())
                            .mapToInt(paragraph -> paragraph.text().length()).sum(), null);
        }
    }
}
