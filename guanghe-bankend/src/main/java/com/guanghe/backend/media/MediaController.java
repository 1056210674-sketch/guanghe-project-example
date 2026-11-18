package com.guanghe.backend.media;

import com.guanghe.backend.auth.security.AuthenticatedAccount;
import com.guanghe.backend.catalog.PageResponse;
import com.guanghe.backend.common.api.ApiResponse;
import com.guanghe.backend.task.TaskDtos;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/media")
@ConditionalOnProperty(prefix = "zhixiaohe.media", name = "enabled", havingValue = "true")
public class MediaController {
    private final MediaSubmissionService service;
    public MediaController(MediaSubmissionService service) { this.service = service; }

    @GetMapping("/resources")
    public ApiResponse<PageResponse<MediaDtos.ResourceSummary>> resources(
            @AuthenticationPrincipal AuthenticatedAccount account,
            @RequestParam(required = false) @Size(max = 255) String search,
            @RequestParam(required = false) @Size(max = 32) String modelId,
            @RequestParam(required = false) @Size(max = 64) String type,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) @Min(0) Integer minSaleAmountCents,
            @RequestParam(required = false) @Min(0) Integer maxSaleAmountCents,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ApiResponse.success(service.resources(account, search, modelId, type, status,
                minSaleAmountCents, maxSaleAmountCents, page, size));
    }

    @GetMapping("/resources/{resourceId}")
    public ApiResponse<MediaDtos.ResourceSummary> resource(
            @AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable @Positive long resourceId) {
        return ApiResponse.success(service.resource(account, resourceId));
    }

    @GetMapping("/recommendations")
    public ApiResponse<MediaDtos.RecommendationSet> recommendations(
            @AuthenticationPrincipal AuthenticatedAccount account,
            @RequestParam @Positive long articleId,
            @RequestParam(defaultValue = "5") @Min(1) @Max(5) int limit) {
        return ApiResponse.success(service.recommendations(account, articleId, limit));
    }

    @PostMapping("/quotes")
    public ResponseEntity<ApiResponse<TaskDtos.DraftResponse>> quote(
            @AuthenticationPrincipal AuthenticatedAccount account,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody MediaDtos.QuoteRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(service.quote(account, idempotencyKey, request)));
    }

    @GetMapping("/submissions")
    public ApiResponse<PageResponse<MediaDtos.SubmissionSummary>> submissions(
            @AuthenticationPrincipal AuthenticatedAccount account,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) @Positive Long taskId,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ApiResponse.success(service.submissions(account, status, taskId, page, size));
    }

    @GetMapping("/submissions/{submissionId}")
    public ApiResponse<MediaDtos.SubmissionDetail> submission(
            @AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable @Positive long submissionId) {
        return ApiResponse.success(service.submission(account, submissionId));
    }
}
