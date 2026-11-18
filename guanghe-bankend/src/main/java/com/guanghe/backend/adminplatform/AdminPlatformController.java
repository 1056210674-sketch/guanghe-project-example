package com.guanghe.backend.adminplatform;

import com.guanghe.backend.auth.security.AuthenticatedAccount;
import com.guanghe.backend.catalog.PageResponse;
import com.guanghe.backend.common.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@ConditionalOnProperty(prefix = "zhixiaohe.agent-platform", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AdminPlatformController {
    private final AdminPlatformService service;
    public AdminPlatformController(AdminPlatformService service) { this.service = service; }

    @GetMapping("/api/v1/admin/agent-definitions")
    public ApiResponse<List<AdminPlatformDtos.AgentDefinitionView>> definitions(@AuthenticationPrincipal AuthenticatedAccount account) {
        return ApiResponse.success(service.definitions(account));
    }

    @PatchMapping("/api/v1/admin/agent-definitions/{agentKey}/versions/{version}")
    public ApiResponse<AdminPlatformDtos.AgentDefinitionView> updateDefinition(@AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable @Pattern(regexp = "^[a-z][a-z0-9-]{0,63}$") String agentKey,
            @PathVariable @Size(max = 32) @Pattern(regexp = "^[0-9]+\\.[0-9]+\\.[0-9]+$") String version,
            @Valid @RequestBody AdminPlatformDtos.UpdateDefinitionRequest request) {
        return ApiResponse.success(service.updateDefinition(account, agentKey, version, request));
    }

    @GetMapping("/api/v1/admin/geo-plans")
    public ApiResponse<PageResponse<AdminPlatformDtos.PlanView>> plans(@AuthenticationPrincipal AuthenticatedAccount account,
            @RequestParam(required = false) @Positive Long userId, @RequestParam(required = false) @Positive Long projectId,
            @RequestParam(required = false) @Pattern(regexp = "^(ACTIVE|PAUSED)$") String status,
            @RequestParam(defaultValue = "1") @Min(1) int page, @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ApiResponse.success(service.plans(account, userId, projectId, status, page, size));
    }

    @GetMapping("/api/v1/admin/geo-plans/{id}/slots")
    public ApiResponse<PageResponse<AdminPlatformDtos.PlanSlotView>> slots(@AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable @Positive long id, @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ApiResponse.success(service.slots(account, id, page, size));
    }

    @PostMapping("/api/v1/admin/geo-plans/{id}/pause")
    public ApiResponse<AdminPlatformDtos.PlanView> pause(@AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable @Positive long id, @Valid @RequestBody AdminPlatformDtos.PausePlanRequest request) {
        return ApiResponse.success(service.pause(account, id, request));
    }

    @PostMapping("/api/v1/admin/geo-plans/{id}/resume")
    public ApiResponse<AdminPlatformDtos.PlanView> resume(@AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable @Positive long id, @Valid @RequestBody AdminPlatformDtos.ResumePlanRequest request) {
        return ApiResponse.success(service.resume(account, id, request));
    }

    @GetMapping("/api/v1/admin/knowledge-documents")
    public ApiResponse<PageResponse<AdminPlatformDtos.KnowledgeDocumentMetadata>> documents(@AuthenticationPrincipal AuthenticatedAccount account,
            @RequestParam(required = false) @Positive Long userId, @RequestParam(required = false) @Positive Long projectId,
            @RequestParam(defaultValue = "1") @Min(1) int page, @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ApiResponse.success(service.documents(account, userId, projectId, page, size));
    }

    @GetMapping("/api/v1/admin/memory-preferences")
    public ApiResponse<PageResponse<AdminPlatformDtos.MemoryPreferenceMetadata>> preferences(@AuthenticationPrincipal AuthenticatedAccount account,
            @RequestParam(required = false) @Positive Long userId, @RequestParam(required = false) @Positive Long projectId,
            @RequestParam(defaultValue = "1") @Min(1) int page, @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ApiResponse.success(service.preferences(account, userId, projectId, page, size));
    }

    @GetMapping("/api/v1/admin/platform-metadata")
    public ApiResponse<AdminPlatformDtos.MetadataSummary> summary(@AuthenticationPrincipal AuthenticatedAccount account,
            @RequestParam(required = false) @Positive Long userId, @RequestParam(required = false) @Positive Long projectId) {
        return ApiResponse.success(service.summary(account, userId, projectId));
    }
}
