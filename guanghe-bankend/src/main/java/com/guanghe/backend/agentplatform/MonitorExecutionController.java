package com.guanghe.backend.agentplatform;

import com.guanghe.backend.auth.security.AuthenticatedAccount;
import com.guanghe.backend.common.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import static com.guanghe.backend.agentplatform.MonitorExecutionDtos.*;

@Validated
@RestController
@org.springframework.boot.autoconfigure.condition.ConditionalOnExpression("${zhixiaohe.paid-task.enabled:false} and ${zhixiaohe.agent-platform.enabled:true}")
public class MonitorExecutionController {
    private final MonitorExecutionService service;
    public MonitorExecutionController(MonitorExecutionService service) { this.service=service; }
    @GetMapping("/api/v1/execution-budget-policy")
    public ApiResponse<BudgetPolicyView> budget(@AuthenticationPrincipal AuthenticatedAccount account) {
        return ApiResponse.success(service.budget(account));
    }
    @PutMapping("/api/v1/execution-budget-policy")
    public ApiResponse<BudgetPolicyView> putBudget(@AuthenticationPrincipal AuthenticatedAccount account,@Valid @RequestBody BudgetPolicyPut request) {
        return ApiResponse.success(service.putBudget(account,request));
    }
    @PostMapping("/api/v1/conversations/{conversationId}/geo-monitor-action-preview")
    public ApiResponse<MonitorActionPreview> preview(@AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable @Positive long conversationId,@Valid @RequestBody MonitorRangeInput request) {
        return ApiResponse.success(service.preview(account,conversationId,request));
    }
    @GetMapping("/api/v1/conversations/{conversationId}/geo-monitor-authorizations")
    public ApiResponse<List<MonitorAuthorizationView>> grants(@AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable @Positive long conversationId) { return ApiResponse.success(service.grants(account,conversationId)); }
    @PostMapping("/api/v1/conversations/{conversationId}/geo-monitor-authorizations")
    public ApiResponse<MonitorAuthorizationView> create(@AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable @Positive long conversationId,@Valid @RequestBody MonitorAuthorizationCreate request) {
        return ApiResponse.success(service.createGrant(account,conversationId,request));
    }
    @PutMapping("/api/v1/conversations/{conversationId}/geo-monitor-authorizations/{grantId}")
    public ApiResponse<MonitorAuthorizationView> update(@AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable @Positive long conversationId,@PathVariable @Positive long grantId,
            @Valid @RequestBody MonitorAuthorizationPut request) { return ApiResponse.success(service.updateGrant(account,conversationId,grantId,request)); }
    @PostMapping("/api/v1/conversations/{conversationId}/geo-monitor-authorizations/{grantId}/revoke")
    public ApiResponse<MonitorAuthorizationView> revoke(@AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable @Positive long conversationId,@PathVariable @Positive long grantId,
            @Valid @RequestBody VersionRequest request) { return ApiResponse.success(service.revokeGrant(account,conversationId,grantId,request)); }
    @GetMapping("/api/v1/conversations/{conversationId}/runs/{runId}/geo-monitor-admission")
    public ApiResponse<MonitorAdmissionView> admission(@AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable @Positive long conversationId,@PathVariable UUID runId) { return ApiResponse.success(service.admission(account,conversationId,runId)); }
}
