package com.guanghe.backend.conversation.context;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.databind.JsonNode;
import com.guanghe.backend.knowledge.MemoryDtos;
import jakarta.validation.constraints.*;
import java.util.List;

public final class ConversationCompactionDtos {
    private ConversationCompactionDtos() { }
    public record SourceRequest(@NotNull @Min(4000) @Max(24000) Integer maxSourceChars, @NotNull Boolean force) {
        @JsonAnySetter public void unknown(String key, JsonNode value) { throw new IllegalArgumentException("Unknown compaction field"); }
    }
    public record CommitRequest(@NotNull @Min(0) Integer expectedVersion, @NotNull @Min(1) Long throughSequence,
            @NotBlank @Pattern(regexp="[a-f0-9]{64}") String sourceHash,
            @NotBlank @Size(max=6000) String summary, @NotNull @Min(4000) @Max(24000) Integer maxSourceChars,
            @NotNull Boolean force) {
        @JsonAnySetter public void unknown(String key, JsonNode value) { throw new IllegalArgumentException("Unknown compaction field"); }
    }
    public record Source(int version, long throughSequence, String summary, String sourceHash,
            long sourceThroughSequence, boolean needsCompaction, boolean hasMore,
            List<InternalRunContextDtos.MemoryMessage> messages,
            List<InternalRunContextDtos.MemoryMessage> recentMessages, MemoryDtos.Context memory) { }
}
