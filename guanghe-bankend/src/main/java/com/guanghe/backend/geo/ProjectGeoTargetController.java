package com.guanghe.backend.geo;

import com.guanghe.backend.auth.security.AuthenticatedAccount;
import com.guanghe.backend.common.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@ConditionalOnProperty(prefix = "zhixiaohe.agent-platform", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ProjectGeoTargetController {
    private final ProjectGeoTargetService service;
    public ProjectGeoTargetController(ProjectGeoTargetService service) { this.service = service; }

    @GetMapping("/api/v1/projects/{projectId}/geo-target")
    public ApiResponse<ProjectGeoTargetDtos.View> get(@AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable @Positive long projectId) {
        return ApiResponse.success(service.get(account, projectId));
    }

    @PatchMapping("/api/v1/projects/{projectId}/geo-target")
    public ApiResponse<ProjectGeoTargetDtos.View> update(@AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable @Positive long projectId, @Valid @RequestBody ProjectGeoTargetDtos.UpdateRequest request) {
        return ApiResponse.success(service.update(account, projectId, request));
    }
}
