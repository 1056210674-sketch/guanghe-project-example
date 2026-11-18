package com.guanghe.backend.admin.diagnostics;

import com.guanghe.backend.catalog.PageResponse;
import com.guanghe.backend.common.api.ApiResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.OffsetDateTime;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/admin/diagnostics/runs")
public class RunDiagnosticsController {
    private final RunDiagnosticsService service;

    public RunDiagnosticsController(RunDiagnosticsService service) { this.service = service; }

    @GetMapping
    public ApiResponse<PageResponse<RunDiagnosticsDtos.Summary>> list(
            @RequestParam(required = false) String runId,
            @RequestParam(required = false) String conversationId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String stableErrorCode,
            @RequestParam(required = false) String traceId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ApiResponse.success(service.list(runId, conversationId, status, stableErrorCode,
                traceId, from, to, page, size));
    }

    @GetMapping("/{runId}")
    public ApiResponse<RunDiagnosticsDtos.Detail> get(@PathVariable String runId) {
        return ApiResponse.success(service.get(runId));
    }
}
