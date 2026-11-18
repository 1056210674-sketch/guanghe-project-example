package com.guanghe.backend.conversation.context;

import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/v1/runs")
@ConditionalOnProperty(prefix = "zhixiaohe.conversation", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RunContextController {

    public static final String CONTEXT_REF_HEADER = "X-User-Context-Ref";

    private final RunContextService service;

    public RunContextController(RunContextService service) {
        this.service = service;
    }

    @GetMapping("/{runId}/context")
    public InternalRunContextDtos.RunContext get(
            @PathVariable UUID runId,
            @RequestHeader(name = CONTEXT_REF_HEADER) String contextRef
    ) {
        return service.get(runId, contextRef);
    }
}
