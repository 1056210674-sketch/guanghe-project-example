package com.guanghe.backend.conversation.resource;

import com.guanghe.backend.conversation.context.RunContextController;
import com.guanghe.backend.common.observability.ObservabilitySupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/v1/runs/{runId}/resources")
@ConditionalOnProperty(prefix = "zhixiaohe.conversation", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RunResourceController {
    private final RunResourceService service;
    private final ObservabilitySupport observability;
    private final ObjectMapper json;
    public RunResourceController(RunResourceService service,ObservabilitySupport observability,ObjectMapper json) {
        this.service=service; this.observability=observability; this.json=json;
    }

    @PostMapping("/search")
    public RunResourceDtos.SearchResponse search(@PathVariable UUID runId,
            @RequestHeader(RunContextController.CONTEXT_REF_HEADER) String contextRef,
            @Valid @RequestBody RunResourceDtos.SearchRequest request) {
        return observability.observe("java.conversation.resource.search","SEARCH",span -> {
            span.tag("runIdRef",observability.reference(runId)).tag("eventType","SEARCH").tag("taskType",request.kind());
        }, () -> {
            var result=service.search(runId,contextRef,request);
            // No raw keyword, tool-call identifier, source names or business content enters Trace.
            return observability.observe("java.conversation.resource.search.result","SEARCH_RESULT",span ->
                    span.tag("runIdRef",observability.reference(runId)).tag("resourceCount",result.candidates().size())
                            .tag("dataGap",!result.complete()), () -> result);
        });
    }

    @PostMapping("/read")
    public RunResourceDtos.ReadResponse read(@PathVariable UUID runId,
            @RequestHeader(RunContextController.CONTEXT_REF_HEADER) String contextRef,
            @Valid @RequestBody RunResourceDtos.ReadRequest request) {
        return observability.observe("java.conversation.resource.read","READ",span -> {
            span.tag("runIdRef",observability.reference(runId)).tag("eventType","READ").tag("contextSource",request.purpose());
        }, () -> {
            var result=service.read(runId,contextRef,request);
            return observability.observe("java.conversation.resource.read.result","READ_RESULT",span ->
                    span.tag("runIdRef",observability.reference(runId)).tag("taskType",result.kind())
                            .tag("contextBytes",projectionBytes(result)).tag("dataGap",result.truncated()), () -> result);
        });
    }
    private int projectionBytes(RunResourceDtos.ReadResponse value) {
        try { return json.writeValueAsBytes(value.projection()).length; }
        catch (Exception failure) { throw new IllegalStateException("Unable to size resource projection",failure); }
    }
}
