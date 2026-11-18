package com.guanghe.backend.geo;

import com.guanghe.backend.auth.security.AuthenticatedAccount;
import com.guanghe.backend.catalog.PageResponse;
import com.guanghe.backend.common.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
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
@RequestMapping("/api/v1/geo")
@ConditionalOnProperty(prefix = "zhixiaohe.paid-task", name = "enabled", havingValue = "true")
public class GeoTaskController {
    private final GeoTaskService service;
    private final GeoArticleWorkflowService articleWorkflows;

    public GeoTaskController(GeoTaskService service, GeoArticleWorkflowService articleWorkflows) {
        this.service = service;
        this.articleWorkflows = articleWorkflows;
    }

    @GetMapping("/platforms")
    public ApiResponse<List<GeoTaskDtos.PlatformResponse>> platforms(
            @AuthenticationPrincipal AuthenticatedAccount account) {
        return ApiResponse.success(service.platforms(account));
    }

    @GetMapping("/runs")
    public ApiResponse<PageResponse<GeoTaskDtos.RunSummary>> runs(
            @AuthenticationPrincipal AuthenticatedAccount account,
            @RequestParam(required = false) String topicId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ApiResponse.success(service.runs(account, topicId, status, page, size));
    }

    @GetMapping("/runs/{runId}")
    public ApiResponse<GeoTaskDtos.RunDetail> run(
            @AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable @Positive long runId) {
        return ApiResponse.success(service.run(account, runId));
    }

    @GetMapping("/runs/{runId}/progress")
    public ApiResponse<GeoTaskDtos.RunProgressResponse> runProgress(
            @AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable @Positive long runId) {
        return ApiResponse.success(service.runProgress(account, runId));
    }

    @GetMapping("/reports")
    public ApiResponse<PageResponse<GeoTaskDtos.ReportSummary>> reports(
            @AuthenticationPrincipal AuthenticatedAccount account,
            @RequestParam(required = false) String spaceId,
            @RequestParam(required = false) String topicId,
            @RequestParam(required = false) Boolean archived,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ApiResponse.success(service.reports(account, spaceId, topicId, archived, page, size));
    }

    @GetMapping("/reports/{reportId}")
    public ApiResponse<GeoTaskDtos.ReportDetail> report(
            @AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable @Positive long reportId) {
        return ApiResponse.success(service.report(account, reportId));
    }

    @PostMapping("/reports/{reportId}/archive")
    public ApiResponse<GeoTaskDtos.ReportSummary> archiveReport(
            @AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable @Positive long reportId) {
        return ApiResponse.success(service.setReportArchived(account, reportId, true));
    }

    @PostMapping("/reports/{reportId}/restore")
    public ApiResponse<GeoTaskDtos.ReportSummary> restoreReport(
            @AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable @Positive long reportId) {
        return ApiResponse.success(service.setReportArchived(account, reportId, false));
    }

    @PostMapping("/reports/{reportId}/article-workflows")
    public ResponseEntity<ApiResponse<GeoArticleWorkflowDtos.Response>> createArticleWorkflow(
            @AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable @Positive long reportId,
            @RequestHeader(name = "Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody(required = false) GeoArticleWorkflowDtos.CreateRequest request) {
        GeoArticleWorkflowDtos.Response response = articleWorkflows.create(
                account, reportId,
                request == null ? new GeoArticleWorkflowDtos.CreateRequest(null, null) : request,
                idempotencyKey);
        return ResponseEntity.status(response.reused() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(ApiResponse.success(response));
    }
}
