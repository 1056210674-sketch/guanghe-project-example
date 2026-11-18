package com.guanghe.backend.conversation.action;

import com.guanghe.backend.conversation.context.RunContextController;
import com.guanghe.backend.common.observability.ObservabilitySupport;
import com.guanghe.backend.common.api.BusinessException;
import com.guanghe.backend.common.api.ErrorCode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/v1/runs/{runId}/actions")
@ConditionalOnExpression("${zhixiaohe.paid-task.enabled:false} and ${zhixiaohe.agent-platform.enabled:true}")
public class RunActionController {
    private final RunActionService service;
    private final ObservabilitySupport observability;
    public RunActionController(RunActionService service,ObservabilitySupport observability) {
        this.service=service;this.observability=observability;
    }
    @PostMapping
    public RunActionDtos.Response execute(@PathVariable UUID runId,
            @RequestHeader(RunContextController.CONTEXT_REF_HEADER) String contextRef,
            @Valid @RequestBody RunActionDtos.Request request) {
        try { return observability.observe("java.agent.action",request.action(),span->
                span.tag("runIdRef",observability.reference(runId)).tag("eventType",request.action()),
                ()->service.execute(runId,contextRef,request));
        } catch(BusinessException failure) {
            // Catch outside the transactional service, after rollback; authority errors remain HTTP errors.
            String reason=(String)failure.details().get("reasonCode");
            if(reason==null && failure.errorCode()==ErrorCode.INSUFFICIENT_POINTS) reason="INSUFFICIENT_POINTS";
            if(reason==null && failure.errorCode()==ErrorCode.PROVIDER_NOT_CONFIGURED) reason="PLATFORM_UNAVAILABLE";
            if(reason==null) throw failure;
            var data=JsonNodeFactory.instance.objectNode().put("reasonCode",reason).put("message",failure.getMessage());
            return new RunActionDtos.Response(request.action(),"WAIT_INPUT",data);
        }
    }
}
