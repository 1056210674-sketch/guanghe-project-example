package com.guanghe.backend.geo;

import com.guanghe.backend.auth.security.AuthenticatedAccount;
import com.guanghe.backend.catalog.PageResponse;
import com.guanghe.backend.common.api.ApiResponse;
import com.guanghe.backend.common.api.BusinessException;
import com.guanghe.backend.common.api.ErrorCode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@Validated
@RestController
@RequestMapping("/api/v1/geo/period-reports")
@ConditionalOnProperty(prefix = "zhixiaohe.paid-task", name = "enabled", havingValue = "true")
public class GeoPeriodReportController {
    private final GeoPeriodReportService service;
    private final GeoPeriodReportExportService exports;
    public GeoPeriodReportController(GeoPeriodReportService service, GeoPeriodReportExportService exports) {
        this.service = service; this.exports = exports;
    }
    @PostMapping
    public ResponseEntity<ApiResponse<GeoPeriodReportDtos.Detail>> create(@AuthenticationPrincipal AuthenticatedAccount account,
                                                                         @Valid @RequestBody GeoPeriodReportDtos.Create request) {
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).body(ApiResponse.success(service.create(account, request)));
    }
    @GetMapping
    public ApiResponse<PageResponse<GeoPeriodReportDtos.Summary>> list(@AuthenticationPrincipal AuthenticatedAccount account,
            @RequestParam @Positive long projectId, @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ApiResponse.success(service.list(account, projectId, page, size));
    }
    @GetMapping("/{reportId}")
    public ApiResponse<GeoPeriodReportDtos.Detail> get(@AuthenticationPrincipal AuthenticatedAccount account,
                                                     @PathVariable @Positive long reportId) {
        return ApiResponse.success(service.get(account, reportId));
    }
    @PostMapping("/{reportId}/retry-summary")
    public ResponseEntity<ApiResponse<GeoPeriodReportDtos.Detail>> retry(@AuthenticationPrincipal AuthenticatedAccount account,
                                                                        @PathVariable @Positive long reportId) {
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).body(ApiResponse.success(service.retry(account, reportId)));
    }
    @GetMapping("/{reportId}/export")
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ResponseEntity<byte[]> export(@AuthenticationPrincipal AuthenticatedAccount account,
                                          @PathVariable @Positive long reportId, @RequestParam @Positive int version) {
        if (service.get(account, reportId).version() != version) throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);
        var result = exports.export(account, reportId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + result.filename() + "\"")
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.parseMediaType(result.contentType())).body(result.bytes());
    }
}
