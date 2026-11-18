package com.guanghe.backend.conversation.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.guanghe.backend.conversation.ConversationContextMetadata;
import com.guanghe.backend.conversation.ConversationMapper;
import com.guanghe.backend.conversation.ConversationMessageRecord;
import com.guanghe.backend.conversation.ConversationProperties;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConversationWorkspaceServiceTests {

    private static final long USER_ID = 41;
    private static final long CONVERSATION_ID = 9001;

    private ConversationMapper mapper;
    private StringRedisTemplate redis;
    private ValueOperations<String, String> values;
    private ConversationProperties properties;
    private ConversationWorkspaceService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        mapper = mock(ConversationMapper.class);
        redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        ObjectProvider<StringRedisTemplate> redisProvider = mock(ObjectProvider.class);
        when(redisProvider.getIfAvailable()).thenReturn(redis);
        ObjectProvider<TaskWorkspaceStateSource> taskProvider = mock(ObjectProvider.class);
        when(taskProvider.getIfAvailable()).thenReturn(null);
        properties = new ConversationProperties();
        properties.setWorkspaceKeyPrefix("zhixiaohe:test:conversation:workspace:v1:");
        properties.setWorkspaceTtl(Duration.ofMinutes(30));
        service = new ConversationWorkspaceService(
                mapper, new ObjectMapper().findAndRegisterModules(), redisProvider, taskProvider, properties
        );
    }

    @Test
    void redisHitAppendsCurrentMessageWithoutScanningFullPostgresHistory() {
        ConversationMessageRecord first = message(8001, 1, "USER", "第一轮", null);
        ConversationMessageRecord second = message(8002, 2, "ASSISTANT", "第一轮回答", null);
        when(mapper.findContextMetadata(CONVERSATION_ID, USER_ID))
                .thenReturn(java.util.Optional.of(metadata(2)), java.util.Optional.of(metadata(3)));
        when(mapper.findContextMessages(USER_ID, CONVERSATION_ID, 2)).thenReturn(List.of(first, second));

        ConversationWorkspaceService.WorkspaceContext rebuilt = service.assemble(
                USER_ID, CONVERSATION_ID, second, null, null, List.of(), 10
        );
        ArgumentCaptor<String> cachedJson = ArgumentCaptor.forClass(String.class);
        verify(values).set(eq(key(USER_ID)), cachedJson.capture(), eq(Duration.ofMinutes(30)));
        assertThat(rebuilt.conversationMemory().contextSource()).isEqualTo("POSTGRESQL");

        reset(values);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(key(USER_ID))).thenReturn(cachedJson.getValue());
        ConversationMessageRecord current = message(8003, 3, "USER", "第二轮", 8002L);
        ConversationWorkspaceService.WorkspaceContext hit = service.assemble(
                USER_ID, CONVERSATION_ID, current, null, null, List.of(), 10
        );

        assertThat(hit.conversationMemory().contextSource()).isEqualTo("REDIS");
        assertThat(hit.conversationMemory().messages())
                .extracting(InternalRunContextDtos.MemoryMessage::messageId)
                .containsExactly("8001", "8002", "8003");
        verify(mapper).findContextMessages(USER_ID, CONVERSATION_ID, 2);
        verify(mapper, never()).findContextMessages(USER_ID, CONVERSATION_ID, 3);
    }

    @Test
    void redisFailureFallsBackToPostgresAndStillBuildsContext() {
        ConversationMessageRecord current = message(8001, 1, "USER", "目标", null);
        when(mapper.findContextMetadata(CONVERSATION_ID, USER_ID)).thenReturn(java.util.Optional.of(metadata(1)));
        when(values.get(key(USER_ID))).thenThrow(new IllegalStateException("redis unavailable"));
        when(mapper.findContextMessages(USER_ID, CONVERSATION_ID, 1)).thenReturn(List.of(current));

        ConversationWorkspaceService.WorkspaceContext result = service.assemble(
                USER_ID, CONVERSATION_ID, current, null, null, List.of(), 10
        );

        assertThat(result.conversationMemory().contextSource()).isEqualTo("POSTGRESQL");
        assertThat(result.conversationMemory().messages()).hasSize(1);
    }

    @Test
    void sequenceMismatchRebuildsFromPostgresInsteadOfTrustingStaleCache() {
        ConversationMessageRecord seed = message(8001, 1, "USER", "第一轮", null);
        when(mapper.findContextMetadata(CONVERSATION_ID, USER_ID))
                .thenReturn(java.util.Optional.of(metadata(1)));
        when(mapper.findContextMessages(USER_ID, CONVERSATION_ID, 1)).thenReturn(List.of(seed));
        service.assemble(USER_ID, CONVERSATION_ID, seed, null, null, List.of(), 10);
        ArgumentCaptor<String> cached = ArgumentCaptor.forClass(String.class);
        verify(values).set(eq(key(USER_ID)), cached.capture(), any(Duration.class));

        reset(values);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(key(USER_ID))).thenReturn(cached.getValue());
        ConversationMessageRecord current = message(8004, 4, "USER", "第四条", null);
        when(mapper.findContextMetadata(CONVERSATION_ID, USER_ID))
                .thenReturn(java.util.Optional.of(metadata(4)));
        when(mapper.findContextMessages(USER_ID, CONVERSATION_ID, 4)).thenReturn(List.of(current));

        ConversationWorkspaceService.WorkspaceContext result = service.assemble(
                USER_ID, CONVERSATION_ID, current, null, null, List.of(), 10
        );

        assertThat(result.conversationMemory().contextSource()).isEqualTo("POSTGRESQL");
        verify(mapper).findContextMessages(USER_ID, CONVERSATION_ID, 4);
    }

    @Test
    void cachedOwnerBindingCannotCrossUsers() {
        ConversationMessageRecord seed = message(8001, 1, "USER", "用户A", null);
        when(mapper.findContextMetadata(CONVERSATION_ID, USER_ID)).thenReturn(java.util.Optional.of(metadata(1)));
        when(mapper.findContextMessages(USER_ID, CONVERSATION_ID, 1)).thenReturn(List.of(seed));
        service.assemble(USER_ID, CONVERSATION_ID, seed, null, null, List.of(), 10);
        ArgumentCaptor<String> cached = ArgumentCaptor.forClass(String.class);
        verify(values).set(eq(key(USER_ID)), cached.capture(), any(Duration.class));

        long otherUser = 42;
        reset(values);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(key(otherUser))).thenReturn(cached.getValue());
        ConversationMessageRecord other = message(9001, 1, otherUser, "用户B");
        when(mapper.findContextMetadata(CONVERSATION_ID, otherUser))
                .thenReturn(java.util.Optional.of(new ConversationContextMetadata(
                        CONVERSATION_ID, otherUser, "{}", 1, 1
                )));
        when(mapper.findContextMessages(otherUser, CONVERSATION_ID, 1)).thenReturn(List.of(other));

        ConversationWorkspaceService.WorkspaceContext result = service.assemble(
                otherUser, CONVERSATION_ID, other, null, null, List.of(), 10
        );

        assertThat(result.conversationMemory().contextSource()).isEqualTo("POSTGRESQL");
        assertThat(result.conversationMemory().messages()).extracting(InternalRunContextDtos.MemoryMessage::messageId)
                .containsExactly("9001");
    }

    @Test
    void tokenBudgetKeepsCurrentMessageAndTrimsByRelevanceWithoutFixedRoundCount() {
        properties.setTargetInputTokens(128);
        properties.setReservedOutputTokens(40);
        properties.setReservedToolTokens(30);
        properties.setReservedSafetyTokens(20);
        ConversationMessageRecord old = message(8001, 1, "USER", "旧".repeat(500), null);
        ConversationMessageRecord current = message(8002, 2, "USER", "当前目标", null);
        when(mapper.findContextMetadata(CONVERSATION_ID, USER_ID)).thenReturn(java.util.Optional.of(metadata(2)));
        when(mapper.findContextMessages(USER_ID, CONVERSATION_ID, 2)).thenReturn(List.of(old, current));

        ConversationWorkspaceService.WorkspaceContext result = service.assemble(
                USER_ID, CONVERSATION_ID, current, null, null, List.of(), 10
        );

        assertThat(result.conversationMemory().messages())
                .extracting(InternalRunContextDtos.MemoryMessage::messageId).containsExactly("8002");
        assertThat(result.contextBudget().truncated()).isTrue();
        assertThat(result.contextBudget().targetInputTokens()).isEqualTo(128);
        assertThat(result.contextBudget().selectionStrategy())
                .isEqualTo("TOKEN_BUDGET_REPLY_CHAIN_THEN_RECENCY");
    }

    @Test
    @SuppressWarnings("unchecked")
    void bindsPendingInteractionAndTaskReferencesAsTrustedWorkspaceObjects() {
        TaskWorkspaceStateSource taskSource = mock(TaskWorkspaceStateSource.class);
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        when(taskSource.read(USER_ID, CONVERSATION_ID, 2001L, 3001L)).thenReturn(new TaskWorkspaceStateSource.State(
                "6001", "5001", "IMAGE", objectMapper.createObjectNode().put("aspectRatio", "1:1"),
                List.of(new InternalRunContextDtos.RunningTask("5001", "IMAGE", "RUNNING", "CLAIMED"))
        ));
        ObjectProvider<StringRedisTemplate> redisProvider = mock(ObjectProvider.class);
        when(redisProvider.getIfAvailable()).thenReturn(redis);
        ObjectProvider<TaskWorkspaceStateSource> taskProvider = mock(ObjectProvider.class);
        when(taskProvider.getIfAvailable()).thenReturn(taskSource);
        service = new ConversationWorkspaceService(mapper, objectMapper, redisProvider, taskProvider, properties);

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        ConversationMessageRecord question = new ConversationMessageRecord(
                7001, CONVERSATION_ID, USER_ID, "ASSISTANT", "INTERACTION",
                "{\"text\":\"\",\"interaction\":{\"interactionType\":\"CLARIFICATION\","
                        + "\"intent\":\"IMAGE\",\"question\":\"请选择第二张图\","
                        + "\"missingFields\":[\"referenceAssetId\"],\"choices\":[\"第一张\",\"第二张\"]}}",
                null, UUID.randomUUID(), null, null, null, 1, "COMPLETED", now, now
        );
        ConversationMessageRecord current = message(8002, 2, "USER", "用第二个", 7001L);
        when(mapper.findContextMetadata(CONVERSATION_ID, USER_ID)).thenReturn(java.util.Optional.of(metadata(2)));
        when(mapper.findContextMessages(USER_ID, CONVERSATION_ID, 2)).thenReturn(List.of(question, current));

        ConversationWorkspaceService.WorkspaceContext result = service.assemble(
                USER_ID, CONVERSATION_ID, current, 2001L, 3001L, List.of("4001"), 10
        );

        assertThat(result.taskWorkspace().pendingInteraction().messageId()).isEqualTo("7001");
        assertThat(result.taskWorkspace().activeDraftId()).isEqualTo("6001");
        assertThat(result.taskWorkspace().activeTaskId()).isEqualTo("5001");
        assertThat(result.taskWorkspace().activeScope().competitorIds()).containsExactly("4001");
        assertThat(result.loadedToolGroups()).containsExactly("TASK_PREPARATION");
        assertThat(result.taskWorkspace().confirmedConstraints().path("aspectRatio").asText()).isEqualTo("1:1");
    }

    @Test
    void ordinaryChatAfterClarificationDoesNotDiscardPendingInteraction() {
        ConversationMessageRecord question = interaction(
                7001, 1, "IMAGE", "请选择参考图", List.of("第一张", "第二张")
        );
        ConversationMessageRecord chat = message(7002, 2, "USER", "顺便问一下今天星期几", null);
        ConversationMessageRecord chatReply = message(7003, 3, "ASSISTANT", "今天是周四", null);
        ConversationMessageRecord current = message(7004, 4, "USER", "第二张", null);
        when(mapper.findContextMetadata(CONVERSATION_ID, USER_ID))
                .thenReturn(java.util.Optional.of(metadata(4)));
        when(mapper.findContextMessages(USER_ID, CONVERSATION_ID, 4))
                .thenReturn(List.of(question, chat, chatReply, current));

        ConversationWorkspaceService.WorkspaceContext result = service.assemble(
                USER_ID, CONVERSATION_ID, current, null, null, List.of(), 10
        );

        assertThat(result.taskWorkspace().pendingInteraction().messageId()).isEqualTo("7001");
        assertThat(result.taskWorkspace().pendingInteraction().question()).isEqualTo("请选择参考图");
    }

    @Test
    void durableDraftConsumptionPreventsHistoricalInteractionFromReappearing() {
        ConversationMessageRecord question = interaction(
                7001, 1, "ARTICLE", "确认使用这个标题吗", List.of("确认", "修改")
        );
        ConversationMessageRecord answer = message(7002, 2, "USER", "确认", 7001L);
        ConversationMessageRecord completed = message(7003, 3, "ASSISTANT", "文章任务草稿已生成", null);
        ConversationMessageRecord current = message(7004, 4, "USER", "再聊聊受众", null);
        when(mapper.findContextMetadata(CONVERSATION_ID, USER_ID))
                .thenReturn(java.util.Optional.of(metadata(4)));
        when(mapper.findContextMessages(USER_ID, CONVERSATION_ID, 4))
                .thenReturn(List.of(question, answer, completed, current));
        when(mapper.hasInteractionConsumptionEvidence(USER_ID, CONVERSATION_ID, "7001"))
                .thenReturn(true);

        ConversationWorkspaceService.WorkspaceContext result = service.assemble(
                USER_ID, CONVERSATION_ID, current, null, null, List.of(), 10
        );

        assertThat(result.taskWorkspace().pendingInteraction()).isNull();
    }

    @Test
    void newerClarificationSupersedesOlderConsumedOrUnresolvedQuestion() {
        ConversationMessageRecord oldQuestion = interaction(
                7001, 1, "ARTICLE", "请选择标题", List.of("标题一", "标题二")
        );
        ConversationMessageRecord newerQuestion = interaction(
                7003, 3, "ARTICLE", "请选择文章语气", List.of("专业", "轻松")
        );
        ConversationMessageRecord current = message(7004, 4, "USER", "我先想想", null);
        when(mapper.findContextMetadata(CONVERSATION_ID, USER_ID))
                .thenReturn(java.util.Optional.of(metadata(4)));
        when(mapper.findContextMessages(USER_ID, CONVERSATION_ID, 4))
                .thenReturn(List.of(oldQuestion, message(7002, 2, "USER", "标题一", 7001L),
                        newerQuestion, current));
        when(mapper.hasInteractionConsumptionEvidence(USER_ID, CONVERSATION_ID, "7001"))
                .thenReturn(true);

        ConversationWorkspaceService.WorkspaceContext result = service.assemble(
                USER_ID, CONVERSATION_ID, current, null, null, List.of(), 10
        );

        assertThat(result.taskWorkspace().pendingInteraction().messageId()).isEqualTo("7003");
        assertThat(result.taskWorkspace().pendingInteraction().question()).isEqualTo("请选择文章语气");
    }

    private ConversationContextMetadata metadata(long sequence) {
        return new ConversationContextMetadata(CONVERSATION_ID, USER_ID, "{}", 1, sequence);
    }

    private ConversationMessageRecord message(long id, long sequence, String role, String text, Long replyTo) {
        return message(id, sequence, USER_ID, text, role, replyTo);
    }

    private ConversationMessageRecord interaction(
            long id, long sequence, String intent, String question, List<String> choices
    ) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(sequence);
        String content;
        try {
            content = new ObjectMapper().writeValueAsString(java.util.Map.of(
                    "text", "",
                    "interaction", java.util.Map.of(
                            "interactionType", "CLARIFICATION",
                            "intent", intent,
                            "question", question,
                            "missingFields", List.of("choice"),
                            "choices", choices
                    )
            ));
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
        return new ConversationMessageRecord(
                id, CONVERSATION_ID, USER_ID, "ASSISTANT", "INTERACTION", content,
                null, UUID.randomUUID(), null, null, null, sequence, "COMPLETED", now, now
        );
    }

    private ConversationMessageRecord message(long id, long sequence, long userId, String text) {
        return message(id, sequence, userId, text, "USER", null);
    }

    private ConversationMessageRecord message(
            long id, long sequence, long userId, String text, String role, Long replyTo
    ) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(sequence);
        return new ConversationMessageRecord(
                id, CONVERSATION_ID, userId, role, "TEXT", "{\"text\":\"" + text + "\"}",
                replyTo, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "0".repeat(64),
                sequence, "COMPLETED", now, now
        );
    }

    private String key(long userId) {
        return "zhixiaohe:test:conversation:workspace:v1:" + userId + ":" + CONVERSATION_ID;
    }
}
