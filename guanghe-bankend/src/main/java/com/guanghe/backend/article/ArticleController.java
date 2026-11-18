package com.guanghe.backend.article;

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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/articles")
@ConditionalOnProperty(prefix = "zhixiaohe.article", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ArticleController {
    private final ArticleService service;

    public ArticleController(ArticleService service) { this.service = service; }

    @GetMapping
    public ApiResponse<PageResponse<ArticleDtos.Summary>> list(
            @AuthenticationPrincipal AuthenticatedAccount account,
            @RequestParam(required = false) @Positive Long spaceId,
            @RequestParam(required = false) @Positive Long productId,
            @RequestParam(required = false) ArticleDtos.Status status,
            @RequestParam(required = false) @Size(max = 200) String keyword,
            @RequestParam(required = false) Boolean savedToAssets,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ApiResponse.success(service.list(account, spaceId, productId, status, keyword, page, size, savedToAssets));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<ArticleDtos.Response>> create(
            @AuthenticationPrincipal AuthenticatedAccount account,
            @Valid @RequestBody ArticleDtos.CreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(service.create(account, request)));
    }

    @GetMapping("/{articleId}")
    public ApiResponse<ArticleDtos.Response> get(@AuthenticationPrincipal AuthenticatedAccount account,
                                                  @PathVariable @Positive long articleId) {
        return ApiResponse.success(service.get(account, articleId));
    }

    @PatchMapping("/{articleId}")
    public ApiResponse<ArticleDtos.Response> update(@AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable @Positive long articleId,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody ArticleDtos.UpdateRequest request) {
        return ApiResponse.success(service.update(account, articleId, ifMatch, request));
    }

    @DeleteMapping("/{articleId}")
    public ApiResponse<Void> delete(@AuthenticationPrincipal AuthenticatedAccount account,
                                    @PathVariable @Positive long articleId) {
        service.delete(account, articleId);
        return ApiResponse.success(null);
    }

    @PutMapping("/{articleId}/outline")
    public ApiResponse<ArticleDtos.Response> outline(@AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable @Positive long articleId,
            @Valid @RequestBody ArticleDtos.ReplaceOutlineRequest request) {
        return ApiResponse.success(service.replaceOutline(account, articleId, request));
    }

    @PutMapping("/{articleId}/asset-collection")
    public ApiResponse<ArticleDtos.Response> saveToAssets(@AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable @Positive long articleId,
            @Valid @RequestBody ArticleDtos.SaveToAssetsRequest request) {
        return ApiResponse.success(service.saveToAssets(account, articleId, request));
    }

    @PutMapping("/{articleId}/assets")
    public ApiResponse<ArticleDtos.Response> assets(@AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable @Positive long articleId,
            @Valid @RequestBody ArticleDtos.ReplaceAssetsRequest request) {
        return ApiResponse.success(service.replaceAssets(account, articleId, request));
    }

    @PutMapping("/{articleId}/workflow/title")
    public ApiResponse<ArticleDtos.Response> confirmTitle(
            @AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable @Positive long articleId,
            @Valid @RequestBody ArticleDtos.ConfirmTitleRequest request) {
        return ApiResponse.success(service.confirmTitle(account, articleId, request));
    }

    @PutMapping("/{articleId}/workflow/creative-plan")
    public ApiResponse<ArticleDtos.Response> saveCreativePlan(
            @AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable @Positive long articleId,
            @Valid @RequestBody ArticleDtos.SaveCreativePlanRequest request) {
        return ApiResponse.success(service.saveCreativePlan(account, articleId, request));
    }

    @PostMapping("/{articleId}/workflow/creative-plan/confirm")
    public ApiResponse<ArticleDtos.Response> confirmCreativePlan(
            @AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable @Positive long articleId,
            @Valid @RequestBody ArticleDtos.ConfirmCreativePlanRequest request) {
        return ApiResponse.success(service.confirmCreativePlan(account, articleId, request));
    }

    @PostMapping("/{articleId}/workflow/body/confirm")
    public ApiResponse<ArticleDtos.Response> confirmBody(
            @AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable @Positive long articleId,
            @Valid @RequestBody ArticleDtos.ConfirmBodyRequest request) {
        return ApiResponse.success(service.confirmBody(account, articleId, request));
    }

    @PostMapping("/{articleId}/submission-snapshots")
    public ResponseEntity<ApiResponse<ArticleDtos.SubmissionSnapshotResponse>> snapshot(
            @AuthenticationPrincipal AuthenticatedAccount account, @PathVariable @Positive long articleId,
            @Valid @RequestBody ArticleDtos.CreateSnapshotRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(service.createSnapshot(account, articleId, request)));
    }

    @GetMapping("/{articleId}/submission-snapshots/{snapshotId}")
    public ApiResponse<ArticleDtos.SubmissionSnapshotResponse> snapshot(
            @AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable @Positive long articleId, @PathVariable @Positive long snapshotId) {
        return ApiResponse.success(service.getSnapshot(account, articleId, snapshotId));
    }
}
