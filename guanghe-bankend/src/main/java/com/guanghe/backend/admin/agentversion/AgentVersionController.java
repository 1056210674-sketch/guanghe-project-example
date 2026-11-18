package com.guanghe.backend.admin.agentversion;

import com.guanghe.backend.auth.security.AuthenticatedAccount;
import com.guanghe.backend.catalog.PageResponse;
import com.guanghe.backend.common.api.ApiResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/admin/agent-versions")
public class AgentVersionController {
    private final AgentVersionService service;

    public AgentVersionController(AgentVersionService service) { this.service = service; }

    @GetMapping
    public ApiResponse<AgentVersionDtos.VersionPage> list(
            @AuthenticationPrincipal AuthenticatedAccount account,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ApiResponse.success(service.list(account, page, size));
    }

    @GetMapping("/{bundleVersion}")
    public ApiResponse<AgentVersionDtos.VersionDetail> get(
            @AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable @Size(min = 1, max = 128) String bundleVersion) {
        return ApiResponse.success(service.get(account, bundleVersion));
    }

    @GetMapping("/{bundleVersion}/runs")
    public ApiResponse<PageResponse<AgentVersionDtos.AssociatedRun>> runs(
            @AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable @Size(min = 1, max = 128) String bundleVersion,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ApiResponse.success(service.runs(account, bundleVersion, page, size));
    }

    @GetMapping("/{bundleVersion}/evaluations")
    public ApiResponse<AgentVersionDtos.EvaluationPage> evaluations(
            @AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable @Size(min = 1, max = 128) String bundleVersion,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ApiResponse.success(service.evaluations(account, bundleVersion, page, size));
    }
}
