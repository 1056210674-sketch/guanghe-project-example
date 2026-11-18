package com.guanghe.backend.geo;

import com.guanghe.backend.auth.security.AuthenticatedAccount;
import com.guanghe.backend.catalog.PageResponse;
import com.guanghe.backend.common.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/geo/topics")
@ConditionalOnProperty(prefix = "zhixiaohe.geo", name = "enabled", havingValue = "true", matchIfMissing = true)
public class GeoController {
    private final GeoService service;
    private final GeoTrendService trends;

    public GeoController(GeoService service, GeoTrendService trends) {
        this.service = service;
        this.trends = trends;
    }

    @GetMapping("/{topicId}/visibility-trend")
    public ApiResponse<GeoTrendDtos.Response> visibilityTrend(
            @AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable @Positive long topicId,
            @RequestParam(defaultValue = "30") int days,
            @RequestParam(defaultValue = "DISCOVERY") String questionScope) {
        return ApiResponse.success(trends.trend(account, topicId, days, questionScope));
    }

    @GetMapping
    public ApiResponse<PageResponse<GeoDtos.TopicSummary>> list(
            @AuthenticationPrincipal AuthenticatedAccount account,
            @RequestParam(required = false) @Positive Long spaceId,
            @RequestParam(required = false) @Positive Long productId,
            @RequestParam(required = false) @Size(max = 200) String keyword,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        return ApiResponse.success(service.list(account, spaceId, productId, keyword, page, size));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<GeoDtos.TopicResponse>> create(
            @AuthenticationPrincipal AuthenticatedAccount account,
            @Valid @RequestBody GeoDtos.CreateTopicRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(service.create(account, request)));
    }

    @GetMapping("/{topicId}")
    public ApiResponse<GeoDtos.TopicResponse> get(
            @AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable @Positive long topicId
    ) {
        return ApiResponse.success(service.get(account, topicId));
    }

    @PatchMapping("/{topicId}")
    public ApiResponse<GeoDtos.TopicResponse> update(
            @AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable @Positive long topicId,
            @Valid @RequestBody GeoDtos.UpdateTopicRequest request
    ) {
        return ApiResponse.success(service.update(account, topicId, request));
    }

    @DeleteMapping("/{topicId}")
    public ApiResponse<Void> delete(
            @AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable @Positive long topicId
    ) {
        service.delete(account, topicId);
        return ApiResponse.success(null);
    }

    @PutMapping("/{topicId}/keywords")
    public ApiResponse<GeoDtos.TopicResponse> replaceKeywords(
            @AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable @Positive long topicId,
            @Valid @RequestBody GeoDtos.ReplaceKeywordsRequest request
    ) {
        return ApiResponse.success(service.replaceKeywords(account, topicId, request));
    }

    @PutMapping("/{topicId}/questions")
    public ApiResponse<GeoDtos.TopicResponse> replaceQuestions(
            @AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable @Positive long topicId,
            @Valid @RequestBody GeoDtos.ReplaceQuestionsRequest request
    ) {
        return ApiResponse.success(service.replaceQuestions(account, topicId, request));
    }

    @PutMapping("/{topicId}/competitors")
    public ApiResponse<GeoDtos.TopicResponse> replaceCompetitors(
            @AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable @Positive long topicId,
            @Valid @RequestBody GeoDtos.ReplaceCompetitorsRequest request
    ) {
        return ApiResponse.success(service.replaceCompetitors(account, topicId, request));
    }
}
