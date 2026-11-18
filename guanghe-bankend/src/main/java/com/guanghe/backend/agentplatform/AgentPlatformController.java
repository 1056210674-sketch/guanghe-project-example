package com.guanghe.backend.agentplatform;

import com.guanghe.backend.auth.security.AuthenticatedAccount;
import com.guanghe.backend.common.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import java.util.List;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@ConditionalOnProperty(prefix = "zhixiaohe.agent-platform", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AgentPlatformController {
    private final AgentPlatformService service;

    public AgentPlatformController(AgentPlatformService service) { this.service = service; }

    @GetMapping("/api/v1/agents")
    public ApiResponse<List<AgentPlatformDtos.AgentDefinitionView>> catalogue(
            @AuthenticationPrincipal AuthenticatedAccount account) {
        return ApiResponse.success(service.catalogue(account));
    }

    @GetMapping("/api/v1/agent-installations")
    public ApiResponse<List<AgentPlatformDtos.AgentInstallationView>> installations(
            @AuthenticationPrincipal AuthenticatedAccount account) {
        return ApiResponse.success(service.installations(account));
    }

    @PostMapping("/api/v1/agent-installations")
    public ApiResponse<AgentPlatformDtos.AgentInstallationView> addInstallation(
            @AuthenticationPrincipal AuthenticatedAccount account,
            @Valid @RequestBody AgentPlatformDtos.AddInstallationRequest request) {
        return ApiResponse.success(service.addInstallation(account, request));
    }

    @PatchMapping("/api/v1/agent-installations/{id}")
    public ApiResponse<AgentPlatformDtos.AgentInstallationView> updateInstallation(
            @AuthenticationPrincipal AuthenticatedAccount account, @PathVariable @Positive long id,
            @Valid @RequestBody AgentPlatformDtos.UpdateInstallationRequest request) {
        return ApiResponse.success(service.updateInstallation(account, id, request));
    }

    @DeleteMapping("/api/v1/agent-installations/{id}")
    public ApiResponse<Void> removeInstallation(@AuthenticationPrincipal AuthenticatedAccount account,
                                               @PathVariable @Positive long id) {
        service.removeInstallation(account, id);
        return ApiResponse.success(null);
    }

    @GetMapping("/api/v1/projects")
    public ApiResponse<List<AgentPlatformDtos.ProjectView>> projects(@AuthenticationPrincipal AuthenticatedAccount account) {
        return ApiResponse.success(service.projects(account));
    }

    @PostMapping("/api/v1/agent-installations/{id}/creative-storage")
    public ApiResponse<AgentPlatformDtos.CreativeStorageView> creativeStorage(
            @AuthenticationPrincipal AuthenticatedAccount account,@PathVariable @Positive long id) {
        return ApiResponse.success(service.creativeStorage(account,id));
    }

    @PostMapping("/api/v1/projects")
    public ResponseEntity<ApiResponse<AgentPlatformDtos.ProjectView>> createProject(
            @AuthenticationPrincipal AuthenticatedAccount account,
            @Valid @RequestBody AgentPlatformDtos.CreateProjectRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(service.createProject(account, request)));
    }

    @PatchMapping("/api/v1/projects/{id}")
    public ApiResponse<AgentPlatformDtos.ProjectView> updateProject(@AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable @Positive long id, @Valid @RequestBody AgentPlatformDtos.UpdateProjectRequest request) {
        return ApiResponse.success(service.updateProject(account, id, request));
    }
}
