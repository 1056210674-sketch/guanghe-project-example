package com.guanghe.backend.knowledge;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.*;
import java.time.OffsetDateTime;
import java.util.List;

public final class MemoryDtos {
    private MemoryDtos() { }
    public record Entry(String id,String scope,String projectId,String kind,String key,String label,String value,
            int version,boolean deleted,String sourceType,String sourceMessageId,String sourceConversationId,OffsetDateTime updatedAt) { }
    public record Context(int schemaVersion,String projectId,List<Entry> entries,String revision,OffsetDateTime historyCutoffAt) {
        public Context { entries=List.copyOf(entries); }
    }
    public record Create(@NotBlank @Pattern(regexp="PROJECT|ACCOUNT") String scope,
            @Pattern(regexp="^[1-9][0-9]{0,18}$") String projectId,
            @NotBlank @Pattern(regexp="FACT|PREFERENCE") String kind,
            @NotBlank @Pattern(regexp="^[a-z][a-z0-9_.-]{0,63}$") String key,
            @NotBlank @Size(max=100) String label,@NotBlank @Size(max=2000) String value) {
        @JsonAnySetter public void unknown(String name,JsonNode value) { throw new IllegalArgumentException("Unknown memory field"); }
    }
    public record Update(@NotNull @Min(1) Integer version,@NotBlank @Size(max=100) String label,
            @NotBlank @Size(max=2000) String value,Boolean restore) {
        public Update(Integer version,String label,String value) { this(version,label,value,false); }
        @JsonAnySetter public void unknown(String name,JsonNode value) { throw new IllegalArgumentException("Unknown memory field"); }
    }
    public record Upsert(@Pattern(regexp="PROJECT|ACCOUNT") String scope,
            @NotBlank @Pattern(regexp="FACT|PREFERENCE") String kind,
            @NotBlank @Pattern(regexp="^[a-z][a-z0-9_.-]{0,63}$") String key,
            @NotBlank @Size(max=100) String label,@NotBlank @Size(max=2000) String value,
            @NotNull @Min(0) Integer expectedVersion,@NotBlank @Size(max=4000) String sourceQuote,
            @Pattern(regexp="REPLACE|APPEND") String mode) {
        public Upsert(String scope,String kind,String key,String label,String value,Integer expectedVersion,String sourceQuote) {
            this(scope,kind,key,label,value,expectedVersion,sourceQuote,null);
        }
        @JsonAnySetter public void unknown(String name,JsonNode value) { throw new IllegalArgumentException("Unknown memory field"); }
    }
    public record Forget(@Pattern(regexp="PROJECT|ACCOUNT") String scope,
            @NotBlank @Pattern(regexp="^[a-z][a-z0-9_.-]{0,63}$") String key,
            @NotNull @Min(1) Integer expectedVersion,@NotBlank @Size(max=4000) String sourceQuote) {
        @JsonAnySetter public void unknown(String name,JsonNode value) { throw new IllegalArgumentException("Unknown memory field"); }
    }
    public record Mutation(Entry entry,Context memory) { }
    public record Stored(long id,long userId,Long projectId,String scope,String kind,String key,String label,String value,
            int version,String sourceType,Long sourceMessageId,Long sourceConversationId,OffsetDateTime createdAt,
            OffsetDateTime updatedAt,OffsetDateTime deletedAt,OffsetDateTime historyCutoffAt) { }
}
