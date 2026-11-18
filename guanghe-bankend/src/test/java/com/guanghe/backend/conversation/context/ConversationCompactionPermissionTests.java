package com.guanghe.backend.conversation.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.guanghe.backend.agentplatform.*;
import com.guanghe.backend.common.api.BusinessException;
import com.guanghe.backend.conversation.*;
import com.guanghe.backend.knowledge.*;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class ConversationCompactionPermissionTests {
    @Test void resourcesReadAloneCannotReadMemoryThroughCompaction() {
        var conversations=mock(ConversationMapper.class);
        var contexts=mock(RunContextService.class);
        var agents=mock(AgentPlatformService.class);
        var memory=mock(AgentMemoryService.class);
        var knowledge=mock(KnowledgeMapper.class);
        var checkpoints=mock(ConversationCompactionMapper.class);
        var service=new ConversationCompactionService(conversations,checkpoints,contexts,agents,memory,
                mock(ConversationWorkspaceService.class),new ObjectMapper(),knowledge);
        var runId=UUID.randomUUID();
        var now=OffsetDateTime.now(ZoneOffset.UTC);
        var run=new AgentRunRecord(runId,51,41,61,null,null,null,"[]","RUNNING",1,"{}","ref",
                now.plusMinutes(5),null,0,"{}",null,null,null,now,null,now,now);
        when(conversations.findRun(runId)).thenReturn(Optional.of(run));
        when(knowledge.lockAccountForPreferenceWrite(41)).thenReturn(Optional.of(41L));
        var catalog=new AgentRuntimeCatalog();
        var full=catalog.freeze(new AgentPlatformDtos.AgentIdentity("71","geo","1.0.0","GEO",null,List.of("chat","resources.read")));
        var reader=new AgentPlatformDtos.AgentIdentity("71","geo","1.0.0","GEO",null,List.of("chat","resources.read"),
                full.executionRelease(),full.catalogHash());
        var context=mock(InternalRunContextDtos.RunContext.class);
        when(context.agentIdentity()).thenReturn(reader);
        when(contexts.get(runId,"ref")).thenReturn(context);
        when(agents.assertTaskConfirmationAllowed(41,51)).thenReturn(full);
        assertThatThrownBy(()->service.source(runId,"ref",new ConversationCompactionDtos.SourceRequest(4000,true)))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(memory,checkpoints);
    }
}
