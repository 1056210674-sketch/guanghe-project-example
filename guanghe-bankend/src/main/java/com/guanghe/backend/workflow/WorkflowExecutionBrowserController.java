package com.guanghe.backend.workflow;

import com.guanghe.backend.auth.security.AuthenticatedAccount;
import com.guanghe.backend.common.api.ApiResponse;
import com.guanghe.backend.common.api.BusinessException;
import com.guanghe.backend.common.api.ErrorCode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/workflow-plans")
@ConditionalOnProperty(prefix = "zhixiaohe.paid-task", name = "enabled", havingValue = "true")
public class WorkflowExecutionBrowserController {
    private final WorkflowExecutionService service;
    private final WorkflowExecutionProperties properties;

    public WorkflowExecutionBrowserController(ObjectProvider<WorkflowExecutionService> service,
                                              WorkflowExecutionProperties properties) {
        this.service = service.getIfAvailable();
        this.properties = properties;
    }

    @PostMapping("/{planId}/start")
    public ApiResponse<WorkflowExecutionDtos.StartResponse> start(
            @AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable @Positive long planId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody WorkflowExecutionDtos.StartRequest request) {
        if (service == null || !properties.isCompatibleWorkerReady()) {
            throw new BusinessException(ErrorCode.WORKFLOW_EXECUTION_UNAVAILABLE);
        }
        return ApiResponse.success(service.start(account, planId, idempotencyKey, request));
    }
}
