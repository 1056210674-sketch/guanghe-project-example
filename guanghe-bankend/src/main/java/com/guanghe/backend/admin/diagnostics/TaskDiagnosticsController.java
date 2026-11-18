package com.guanghe.backend.admin.diagnostics;

import com.guanghe.backend.catalog.PageResponse;
import com.guanghe.backend.common.api.ApiResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
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
@RequestMapping("/api/v1/admin/diagnostics/tasks")
public class TaskDiagnosticsController {
    private final TaskDiagnosticsService service;

    public TaskDiagnosticsController(TaskDiagnosticsService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<PageResponse<TaskDiagnosticsDtos.Summary>> list(
            @RequestParam(required = false) String taskId,
            @RequestParam(required = false) String taskType,
            @RequestParam(required = false) String taskStatus,
            @RequestParam(required = false) String stableErrorCode,
            @RequestParam(required = false) String traceId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ApiResponse.success(service.list(taskId, taskType, taskStatus, stableErrorCode,
                traceId, from, to, page, size));
    }

    @GetMapping("/{taskId}")
    public ApiResponse<TaskDiagnosticsDtos.Detail> get(@PathVariable @Positive long taskId) {
        return ApiResponse.success(service.get(taskId));
    }
}
