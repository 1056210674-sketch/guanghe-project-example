package com.guanghe.backend.conversation.context;

import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/v1/runs/{runId}/compaction")
@ConditionalOnProperty(prefix="zhixiaohe.agent-platform", name="enabled", havingValue="true", matchIfMissing=true)
public class ConversationCompactionController {
    private final ConversationCompactionService service;
    public ConversationCompactionController(ConversationCompactionService service) { this.service=service; }
    @PostMapping("/source")
    public ConversationCompactionDtos.Source source(@PathVariable UUID runId,
            @RequestHeader(RunContextController.CONTEXT_REF_HEADER) String ref,
            @Valid @RequestBody ConversationCompactionDtos.SourceRequest request) { return service.source(runId,ref,request); }
    @PostMapping("/checkpoint")
    public ConversationCompactionDtos.Source checkpoint(@PathVariable UUID runId,
            @RequestHeader(RunContextController.CONTEXT_REF_HEADER) String ref,
            @Valid @RequestBody ConversationCompactionDtos.CommitRequest request) { return service.commit(runId,ref,request); }
}
