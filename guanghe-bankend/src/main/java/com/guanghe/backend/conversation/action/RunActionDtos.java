package com.guanghe.backend.conversation.action;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertFalse;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.net.URI;
import java.time.OffsetDateTime;

public final class RunActionDtos {
    private RunActionDtos() { }

    public record Request(@NotBlank @Pattern(regexp="^[A-Za-z0-9_.:-]{1,128}$") String toolCallId,
            @NotBlank @Pattern(regexp="^(context\\.read|brand\\.save|geo\\.monitor|(article|image|video)\\.generate|memory\\.(read|upsert|forget))$") String action,
            @NotNull JsonNode arguments) {
        @JsonAnySetter public void unknown(String name, JsonNode value) { throw new IllegalArgumentException("Unknown action field"); }
    }
    public record Response(String action, String status, JsonNode data) { }
    public record ImageGenerate(
            @NotBlank @Pattern(regexp="^(TEXT_ONLY|CURRENT_ATTACHMENTS|CATALOG_PRODUCT|PREVIOUS_IMAGE_RESULT|PREVIOUS_USER_ATTACHMENT)$") String sourceMode,
            @NotBlank @Size(max=1000) String scene,
            @Pattern(regexp="^(1:1|3:4|4:3|9:16|16:9)$") String aspectRatio,
            @jakarta.validation.constraints.Min(1) @jakarta.validation.constraints.Max(4) Integer count,
            @Size(min=1,max=256) String style,@Size(min=1,max=256) String lighting,
            @Size(min=1,max=1000) String negativePrompt,
            @Valid CreativeContinuationReview continuationReview) {
        public ImageGenerate(String sourceMode,String scene,String aspectRatio,Integer count,String style,
                String lighting,String negativePrompt) {
            this(sourceMode,scene,aspectRatio,count,style,lighting,negativePrompt,null);
        }
        @JsonAnySetter public void unknown(String name,JsonNode value) { throw new IllegalArgumentException("Unknown image field"); }
    }
    public record VideoGenerate(
            @NotBlank @Pattern(regexp="^(TEXT_ONLY|CURRENT_ATTACHMENTS|CATALOG_PRODUCT|PRODUCT_MATERIALS|PREVIOUS_IMAGE_RESULT|PREVIOUS_USER_ATTACHMENT)$") String sourceMode,
            @NotBlank @Size(max=1000) String scene,
            @jakarta.validation.constraints.Min(2) @jakarta.validation.constraints.Max(30) Integer durationSeconds,
            @Pattern(regexp="^(16:9|4:3|1:1|3:4|9:16)$") String aspectRatio,
            @Pattern(regexp="^(480P|720P|1080P)$") String resolution,Boolean audio,
            @Size(min=1,max=256) String style,@Size(min=1,max=256) String cameraMotion,
            @Valid CreativeContinuationReview continuationReview) {
        public VideoGenerate(String sourceMode,String scene,Integer durationSeconds,String aspectRatio,
                String resolution,Boolean audio,String style,String cameraMotion) {
            this(sourceMode,scene,durationSeconds,aspectRatio,resolution,audio,style,cameraMotion,null);
        }
        @JsonAnySetter public void unknown(String name,JsonNode value) { throw new IllegalArgumentException("Unknown video field"); }
    }
    /** Harness verdict bound to freshly reconstructed Java-owned dialogue evidence. */
    public record CreativeContinuationReview(
            @NotBlank @Pattern(regexp="^[1-9][0-9]{0,18}$") String currentMessageId,
            @NotBlank @Pattern(regexp="^[a-f0-9]{64}$") String historyFingerprint,
            @NotBlank @Pattern(regexp="^(IMAGE|VIDEO)$") String taskType,
            @NotBlank @Pattern(regexp="^CREATE_NEW$") String decision,
            @NotBlank @Pattern(regexp="^(TEXT_ONLY|PREVIOUS_IMAGE_RESULT|CURRENT_ATTACHMENTS)$") String sourceMode,
            @Pattern(regexp="^[1-9][0-9]{0,18}$") String sourceImageAssetId) {
        public CreativeContinuationReview(String currentMessageId,String historyFingerprint,String taskType,String decision) {
            this(currentMessageId,historyFingerprint,taskType,decision,"TEXT_ONLY",null);
        }
        public CreativeContinuationReview(String currentMessageId,String historyFingerprint,String taskType,String decision,String sourceImageAssetId) {
            this(currentMessageId,historyFingerprint,taskType,decision,
                    sourceImageAssetId==null?"TEXT_ONLY":"PREVIOUS_IMAGE_RESULT",sourceImageAssetId);
        }
        @JsonAnySetter public void unknown(String name,JsonNode value) { throw new IllegalArgumentException("Unknown creative continuation review field"); }
    }
    public record ArticleGenerate(@Pattern(regexp="^[A-Z][A-Z0-9_]{0,63}$") String tone,
            @jakarta.validation.constraints.Min(100) @jakarta.validation.constraints.Max(20000) Integer targetLength,
            @Valid ArticleResearch research, @Valid ArticleContinuationReview continuationReview) {
        @JsonAnySetter public void unknown(String name, JsonNode value) { throw new IllegalArgumentException("Unknown article field"); }
    }
    /** Harness-owned semantic verdict; IDs must match the candidate rebuilt by Java, not a model-selected history. */
    public record ArticleContinuationReview(
            @NotBlank @Pattern(regexp="^[1-9][0-9]{0,18}$") String currentMessageId,
            @NotBlank @Pattern(regexp="^[1-9][0-9]{0,18}$") String sourceMessageId,
            @NotBlank @Pattern(regexp="^[1-9][0-9]{0,18}$") String offerMessageId,
            @NotBlank @Pattern(regexp="^NEW_DRAFT$") String decision) {
        @JsonAnySetter public void unknown(String name, JsonNode value) { throw new IllegalArgumentException("Unknown continuation review field"); }
    }
    /** Runtime-observed search excerpts, not model-written facts or a user instruction. */
    public record ArticleResearch(
            @NotBlank @Pattern(regexp="^WEB_SEARCH_EXCERPTS$") String sourceType,
            @NotNull @AssertFalse Boolean fullPagesRead,
            @NotNull @Size(min=1,max=8) List<@NotNull @Valid ArticleResearchSource> sources) {
        public ArticleResearch {
            sources = sources == null ? null : List.copyOf(sources);
        }
        @JsonAnySetter public void unknown(String name, JsonNode value) { throw new IllegalArgumentException("Unknown research field"); }
    }
    public record ArticleResearchSource(
            @Size(max=500) String title,
            @NotBlank @Size(max=2048) String url,
            @Size(max=600) String snippet,
            @NotNull @com.fasterxml.jackson.annotation.JsonFormat(shape=com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING)
            OffsetDateTime observedAt) {
        public ArticleResearchSource {
            if (url != null) {
                URI uri = URI.create(url);
                if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                        || uri.getHost() == null || uri.getHost().isBlank() || uri.getRawUserInfo() != null) {
                    throw new IllegalArgumentException("Research source URL must be an HTTP(S) URL without credentials");
                }
            }
        }
        @JsonAnySetter public void unknown(String name, JsonNode value) { throw new IllegalArgumentException("Unknown research source field"); }
    }
    public record BrandSave(@NotBlank @Size(max=100) String name,
            @Size(max=20) List<@NotBlank @Size(max=100) String> aliases,
            @Size(max=16000) String information) {
        @JsonAnySetter public void unknown(String name, JsonNode value) { throw new IllegalArgumentException("Unknown brand field"); }
    }
    public record GeoMonitor(@Size(min=1,max=100) String topic,
            @NotBlank @Size(max=100) String region,
            @NotNull @Size(min=3,max=10) List<@NotBlank @Size(max=1000) String> questions,
            @Size(min=1,max=7) List<@NotBlank @Pattern(regexp="^(DOUBAO|DEEPSEEK|QWEN|WENXIN|KIMI|YUANBAO|ZHIPU)$") String> platformCodes) {
        @JsonAnySetter public void unknown(String name, JsonNode value) { throw new IllegalArgumentException("Unknown monitor field"); }
    }
}
