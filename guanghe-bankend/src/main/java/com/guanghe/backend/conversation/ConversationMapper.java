package com.guanghe.backend.conversation;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ConversationMapper {

    String CONVERSATION_COLUMNS = """
            c.id, c.user_id AS userId, c.title,
            c.selected_space_id AS selectedSpaceId,
            c.selected_product_id AS selectedProductId,
            c.version, c.last_message_at AS lastMessageAt,
            c.created_at AS createdAt, c.updated_at AS updatedAt
            """;

    String MESSAGE_COLUMNS = """
            m.id, m.conversation_id AS conversationId, m.user_id AS userId,
            m.role, m.content_type AS contentType, m.content::text AS contentJson,
            m.reply_to_message_id AS replyToMessageId, m.agent_run_id AS agentRunId,
            m.client_message_id AS clientMessageId, m.idempotency_key AS idempotencyKey,
            m.request_fingerprint AS requestFingerprint, m.sequence_no AS sequenceNo,
            m.status, m.created_at AS createdAt, m.updated_at AS updatedAt
            """;

    String RUN_COLUMNS = """
            r.id, r.conversation_id AS conversationId, r.user_id AS userId,
            r.request_message_id AS requestMessageId, r.response_message_id AS responseMessageId,
            r.selected_space_id AS selectedSpaceId, r.selected_product_id AS selectedProductId,
            r.attachment_asset_ids::text AS attachmentAssetIdsJson, r.status,
            r.context_version AS contextVersion, r.context_snapshot::text AS contextSnapshotJson,
            r.context_ref_hash AS contextRefHash,
            r.context_expires_at AS contextExpiresAt, r.python_events_url AS pythonEventsUrl,
            r.last_python_sequence AS lastPythonSequence, r.usage::text AS usageJson,
            r.error_code AS errorCode, r.trace_id AS traceId, r.stop_requested_at AS stopRequestedAt,
            r.started_at AS startedAt, r.completed_at AS completedAt,
            r.created_at AS createdAt, r.updated_at AS updatedAt, r.deadline_at AS deadlineAt
            """;

    String EVENT_COLUMNS = """
            e.id, e.conversation_id AS conversationId, e.user_id AS userId,
            e.agent_run_id AS agentRunId, e.message_id AS messageId,
            e.task_id AS taskId,
            e.source_event_id AS sourceEventId, e.event_type AS eventType,
            e.sequence_no AS sequenceNo, e.payload::text AS payloadJson,
            e.occurred_at AS occurredAt, e.created_at AS createdAt
            """;

    @Select("SELECT " + CONVERSATION_COLUMNS + " FROM business.conversation c"
            + " WHERE c.user_id = #{userId}"
            + " ORDER BY c.last_message_at DESC NULLS LAST, c.created_at DESC, c.id DESC"
            + " LIMIT #{size} OFFSET #{offset}")
    List<ConversationRecord> findPage(
            @Param("userId") long userId,
            @Param("offset") long offset,
            @Param("size") int size
    );

    @Select("SELECT COUNT(*) FROM business.conversation WHERE user_id = #{userId}")
    long countByUserId(@Param("userId") long userId);

    @Select("SELECT " + CONVERSATION_COLUMNS + " FROM business.conversation c"
            + " WHERE c.id = #{conversationId} AND c.user_id = #{userId}")
    Optional<ConversationRecord> findConversation(
            @Param("conversationId") long conversationId,
            @Param("userId") long userId
    );

    @Select("SELECT " + CONVERSATION_COLUMNS + " FROM business.conversation c"
            + " WHERE c.id = #{conversationId} AND c.user_id = #{userId} FOR UPDATE")
    Optional<ConversationRecord> lockConversation(
            @Param("conversationId") long conversationId, @Param("userId") long userId);

    @Select("SELECT next_event_sequence - 1 FROM business.conversation"
            + " WHERE id = #{conversationId} AND user_id = #{userId}")
    long eventHighWatermark(@Param("conversationId") long conversationId, @Param("userId") long userId);

    @Select("SELECT id FROM business.account WHERE id=#{userId} AND role='USER' AND status='ACTIVE'"
            + " AND credential_state='ACTIVE' AND token_version=#{tokenVersion} FOR UPDATE")
    Long lockAdmissionAccount(@Param("userId") long userId, @Param("tokenVersion") int tokenVersion);

    @Select("SELECT COUNT(*) FROM business.agent_run WHERE user_id=#{userId}"
            + " AND status IN ('QUEUED', 'STARTING', 'RUNNING', 'STOP_REQUESTED')")
    long countActiveRuns(@Param("userId") long userId);

    @Select("""
            SELECT COUNT(*) FILTER (WHERE accepted_at >= #{minuteStart}) AS minuteAccepted,
                   COUNT(*) FILTER (WHERE accepted_at >= #{dayStart}) AS dailyAccepted,
                   COUNT(*) FILTER (WHERE accepted_at >= #{dayStart} AND vision) AS visionAccepted
              FROM business.conversation_admission
             WHERE user_id=#{userId} AND accepted_at >= LEAST(#{minuteStart}, #{dayStart})
            """)
    ConversationQuotaUsage admissionUsage(@Param("userId") long userId,
            @Param("minuteStart") OffsetDateTime minuteStart, @Param("dayStart") OffsetDateTime dayStart);

    @Insert("INSERT INTO business.conversation_admission(run_id,user_id,accepted_at,vision)"
            + " VALUES (#{runId},#{userId},#{acceptedAt},#{vision})")
    int insertAdmission(@Param("runId") UUID runId, @Param("userId") long userId,
            @Param("acceptedAt") OffsetDateTime acceptedAt, @Param("vision") boolean vision);

    @Select("""
            SELECT c.id, c.user_id AS userId, c.summary::text AS summaryJson,
                   c.summary_version AS summaryVersion,
                   c.next_message_sequence - 1 AS lastMessageSequence
              FROM business.conversation c
             WHERE c.id = #{conversationId} AND c.user_id = #{userId}
            """)
    Optional<ConversationContextMetadata> findContextMetadata(
            @Param("conversationId") long conversationId,
            @Param("userId") long userId
    );

    @Insert("""
            INSERT INTO business.conversation(
                id, user_id, title, summary, summary_version, version,
                next_message_sequence, next_event_sequence, created_at, updated_at
            ) VALUES (
                #{conversation.id}, #{conversation.userId}, #{conversation.title}, '{}'::jsonb, 1,
                #{conversation.version}, 1, 1, #{conversation.createdAt}, #{conversation.updatedAt}
            )
            """)
    int insertConversation(@Param("conversation") ConversationRecord conversation);

    @Update("""
            UPDATE business.conversation
               SET title = #{title}, version = version + 1, updated_at = #{updatedAt}
             WHERE id = #{conversationId} AND user_id = #{userId} AND version = #{expectedVersion}
            """)
    int updateConversation(
            @Param("conversationId") long conversationId,
            @Param("userId") long userId,
            @Param("expectedVersion") int expectedVersion,
            @Param("title") String title,
            @Param("updatedAt") OffsetDateTime updatedAt
    );

    @Update("""
            UPDATE business.conversation
               SET title = #{title}, version = version + 1, updated_at = #{updatedAt}
             WHERE id = #{conversationId} AND user_id = #{userId}
               AND title = #{defaultTitle} AND next_message_sequence = 2
            """)
    int replaceDefaultTitleFromFirstMessage(
            @Param("conversationId") long conversationId,
            @Param("userId") long userId,
            @Param("defaultTitle") String defaultTitle,
            @Param("title") String title,
            @Param("updatedAt") OffsetDateTime updatedAt
    );

    @Update("""
            UPDATE business.conversation
               SET selected_space_id = #{spaceId}, selected_product_id = #{productId},
                   last_message_at = #{lastMessageAt}, updated_at = #{lastMessageAt}
             WHERE id = #{conversationId} AND user_id = #{userId}
            """)
    int updateConversationSelection(
            @Param("conversationId") long conversationId,
            @Param("userId") long userId,
            @Param("spaceId") Long spaceId,
            @Param("productId") Long productId,
            @Param("lastMessageAt") OffsetDateTime lastMessageAt
    );

    @Delete("DELETE FROM business.conversation WHERE id = #{conversationId} AND user_id = #{userId}")
    int deleteConversation(@Param("conversationId") long conversationId, @Param("userId") long userId);

    @Select("SELECT count(*) FROM business.business_task WHERE conversation_id=#{conversationId} AND user_id=#{userId}")
    long countPaidTasks(@Param("conversationId") long conversationId, @Param("userId") long userId);

    @Select("""
            SELECT EXISTS(
                SELECT 1 FROM business.geo_monitor_execution_grant
                 WHERE conversation_id=#{conversationId} AND user_id=#{userId}
                UNION ALL
                SELECT 1 FROM business.geo_monitor_execution_admission
                 WHERE conversation_id=#{conversationId} AND user_id=#{userId}
            )
            """)
    boolean hasMonitorExecutionRecords(@Param("conversationId") long conversationId, @Param("userId") long userId);

    @Select("SELECT EXISTS (SELECT 1 FROM business.article_execution_grant WHERE conversation_id=#{conversationId} AND user_id=#{userId})")
    boolean hasArticleExecutionRecords(@Param("conversationId") long conversationId, @Param("userId") long userId);

    @Select("SELECT EXISTS(SELECT 1 FROM business.business_task"
            + " WHERE conversation_id=#{conversationId} AND user_id=#{userId}"
            + " AND status IN ('QUEUED','RUNNING','RETRY_WAIT'))")
    boolean hasActivePaidTask(@Param("conversationId") long conversationId,
                              @Param("userId") long userId);

    @Select("""
            UPDATE business.conversation
               SET next_message_sequence = next_message_sequence + 1
             WHERE id = #{conversationId} AND user_id = #{userId}
             RETURNING next_message_sequence - 1
            """)
    Long nextMessageSequence(@Param("conversationId") long conversationId, @Param("userId") long userId);

    @Select("""
            UPDATE business.conversation
               SET next_event_sequence = next_event_sequence + 1
             WHERE id = #{conversationId} AND user_id = #{userId}
             RETURNING next_event_sequence - 1
            """)
    Long nextEventSequence(@Param("conversationId") long conversationId, @Param("userId") long userId);

    @Insert("""
            INSERT INTO business.conversation_message(
                id, conversation_id, user_id, role, content_type, content, reply_to_message_id,
                agent_run_id, client_message_id, idempotency_key, request_fingerprint,
                sequence_no, status, created_at, updated_at
            ) VALUES (
                #{message.id}, #{message.conversationId}, #{message.userId}, #{message.role},
                #{message.contentType}, CAST(#{message.contentJson} AS jsonb), #{message.replyToMessageId},
                #{message.agentRunId}, #{message.clientMessageId}, #{message.idempotencyKey},
                #{message.requestFingerprint}, #{message.sequenceNo}, #{message.status},
                #{message.createdAt}, #{message.updatedAt}
            ) ON CONFLICT DO NOTHING
            """)
    int insertUserMessage(@Param("message") ConversationMessageRecord message);

    @Insert("""
            INSERT INTO business.conversation_message_asset(
                message_id, asset_id, user_id, conversation_id, space_id, sequence_no, created_at
            ) VALUES (
                #{messageId}, #{assetId}, #{userId}, #{conversationId}, #{spaceId}, #{sequence}, #{now}
            )
            """)
    int insertMessageAsset(@Param("messageId") long messageId,
                           @Param("assetId") long assetId,
                           @Param("userId") long userId,
                           @Param("conversationId") long conversationId,
                           @Param("spaceId") long spaceId,
                           @Param("sequence") int sequence,
                           @Param("now") OffsetDateTime now);

    @Select("SELECT asset_id FROM business.conversation_message_asset"
            + " WHERE message_id=#{messageId} AND user_id=#{userId} AND conversation_id=#{conversationId}"
            + " ORDER BY sequence_no")
    List<Long> findMessageAssetIds(@Param("messageId") long messageId,
                                   @Param("userId") long userId,
                                   @Param("conversationId") long conversationId);

    @Insert("""
            INSERT INTO business.conversation_message(
                id, conversation_id, user_id, role, content_type, content, agent_run_id,
                sequence_no, status, created_at, updated_at
            ) VALUES (
                #{message.id}, #{message.conversationId}, #{message.userId}, #{message.role},
                #{message.contentType}, CAST(#{message.contentJson} AS jsonb), #{message.agentRunId},
                #{message.sequenceNo}, #{message.status}, #{message.createdAt}, #{message.updatedAt}
            )
            """)
    int insertAssistantMessage(@Param("message") ConversationMessageRecord message);

    @Update("UPDATE business.conversation_message SET agent_run_id = #{runId}, updated_at = #{updatedAt}"
            + " WHERE id = #{messageId} AND user_id = #{userId}")
    int bindMessageRun(
            @Param("messageId") long messageId,
            @Param("userId") long userId,
            @Param("runId") UUID runId,
            @Param("updatedAt") OffsetDateTime updatedAt
    );

    @Select("SELECT " + MESSAGE_COLUMNS + " FROM business.conversation_message m"
            + " WHERE m.user_id = #{userId} AND m.conversation_id = #{conversationId}"
            + " AND m.sequence_no > #{afterSequence} ORDER BY m.sequence_no ASC LIMIT #{limit}")
    List<ConversationMessageRecord> findMessagesAfter(
            @Param("userId") long userId,
            @Param("conversationId") long conversationId,
            @Param("afterSequence") long afterSequence,
            @Param("limit") int limit
    );

    @Select("SELECT " + MESSAGE_COLUMNS + " FROM business.conversation_message m"
            + " WHERE m.user_id = #{userId} AND m.conversation_id = #{conversationId}"
            + " AND m.sequence_no < #{beforeSequence} ORDER BY m.sequence_no DESC LIMIT #{limit}")
    List<ConversationMessageRecord> findMessagesBefore(
            @Param("userId") long userId, @Param("conversationId") long conversationId,
            @Param("beforeSequence") long beforeSequence, @Param("limit") int limit);

    @Select("SELECT " + MESSAGE_COLUMNS + " FROM business.conversation_message m"
            + " WHERE m.user_id = #{userId} AND m.conversation_id = #{conversationId}"
            + " AND m.sequence_no <= #{throughSequence}"
            + " AND m.status IN ('COMPLETED', 'FAILED', 'STOPPED')"
            + " ORDER BY m.sequence_no ASC")
    List<ConversationMessageRecord> findContextMessages(
            @Param("userId") long userId,
            @Param("conversationId") long conversationId,
            @Param("throughSequence") long throughSequence
    );

    /**
     * An interaction is consumed only by durable, explicit evidence. Ordinary assistant text is
     * deliberately not a barrier: a user may chat while a clarification or choice remains open.
     */
    @Select("""
            SELECT
              CASE WHEN EXISTS (
                SELECT 1 FROM business.conversation_message selection
                 WHERE selection.user_id = #{userId} AND selection.conversation_id = #{conversationId}
                   AND selection.id = CAST(#{interactionMessageId} AS bigint)
                   AND selection.role = 'ASSISTANT'
                   AND selection.content #>> '{interaction,interactionType}' = 'RESOURCE_SELECTION'
              ) THEN EXISTS (
                SELECT 1 FROM business.conversation_event evidence
                  JOIN business.conversation_message selection
                    ON selection.user_id = evidence.user_id AND selection.conversation_id = evidence.conversation_id
                 WHERE evidence.user_id = #{userId} AND evidence.conversation_id = #{conversationId}
                   AND selection.id = CAST(#{interactionMessageId} AS bigint)
                   AND evidence.event_type = 'context.resource'
                   AND evidence.payload ->> 'kind' = 'READ'
                   AND evidence.payload ->> 'success' = 'true'
                   AND evidence.payload #>> '{result,observationId}' IS NOT NULL
                   AND evidence.payload ->> 'candidateSetId' = selection.content #>> '{interaction,candidateSetId}'
              ) ELSE (
              EXISTS (
                SELECT 1
                  FROM business.task_draft d
                  JOIN business.agent_run r
                    ON r.id = d.agent_run_id
                   AND r.user_id = d.user_id
                   AND r.conversation_id = d.conversation_id
                 WHERE d.user_id = #{userId}
                   AND d.conversation_id = #{conversationId}
                   AND r.context_snapshot #>> '{taskWorkspace,pendingInteraction,messageId}'
                       = #{interactionMessageId}
              )
              OR EXISTS (
                SELECT 1
                  FROM business.conversation_message reply
                  JOIN business.agent_run r
                    ON r.request_message_id = reply.id
                   AND r.user_id = reply.user_id
                   AND r.conversation_id = reply.conversation_id
                 WHERE reply.user_id = #{userId}
                   AND reply.conversation_id = #{conversationId}
                   AND reply.role = 'USER'
                   AND reply.reply_to_message_id = CAST(#{interactionMessageId} AS bigint)
                   AND r.status = 'COMPLETED'
              )
              ) END
            """)
    boolean hasInteractionConsumptionEvidence(
            @Param("userId") long userId,
            @Param("conversationId") long conversationId,
            @Param("interactionMessageId") String interactionMessageId
    );

    @Select("SELECT " + MESSAGE_COLUMNS + " FROM business.conversation_message m"
            + " WHERE m.id = #{messageId} AND m.user_id = #{userId}"
            + " AND m.conversation_id = #{conversationId}")
    Optional<ConversationMessageRecord> findMessage(
            @Param("messageId") long messageId,
            @Param("userId") long userId,
            @Param("conversationId") long conversationId
    );

    @Select("""
            SELECT m.id AS messageId, r.id AS runId, m.client_message_id AS clientMessageId,
                   m.idempotency_key AS idempotencyKey,
                   m.request_fingerprint AS requestFingerprint, r.status AS runStatus
              FROM business.conversation_message m
              JOIN business.agent_run r ON r.request_message_id = m.id
             WHERE m.user_id = #{userId} AND m.conversation_id = #{conversationId}
               AND m.idempotency_key = #{idempotencyKey}
            """)
    Optional<MessageSubmissionRecord> findSubmissionByIdempotencyKey(
            @Param("userId") long userId,
            @Param("conversationId") long conversationId,
            @Param("idempotencyKey") UUID idempotencyKey
    );

    @Select("""
            SELECT m.id AS messageId, r.id AS runId, m.client_message_id AS clientMessageId,
                   m.idempotency_key AS idempotencyKey,
                   m.request_fingerprint AS requestFingerprint, r.status AS runStatus
              FROM business.conversation_message m
              JOIN business.agent_run r ON r.request_message_id = m.id
             WHERE m.user_id = #{userId} AND m.conversation_id = #{conversationId}
               AND m.client_message_id = #{clientMessageId}
            """)
    Optional<MessageSubmissionRecord> findSubmissionByClientMessageId(
            @Param("userId") long userId,
            @Param("conversationId") long conversationId,
            @Param("clientMessageId") UUID clientMessageId
    );

    @Insert("""
            INSERT INTO business.agent_run(
                id, conversation_id, user_id, request_message_id, response_message_id,
                selected_space_id, selected_product_id, attachment_asset_ids, status,
                context_version, context_snapshot, context_ref_hash, context_expires_at, last_python_sequence,
                usage, trace_id, created_at, updated_at, deadline_at
            ) VALUES (
                #{run.id}, #{run.conversationId}, #{run.userId}, #{run.requestMessageId},
                #{run.responseMessageId}, #{run.selectedSpaceId}, #{run.selectedProductId},
                CAST(#{run.attachmentAssetIdsJson} AS jsonb), #{run.status}, #{run.contextVersion},
                CAST(#{run.contextSnapshotJson} AS jsonb), #{run.contextRefHash},
                #{run.contextExpiresAt}, #{run.lastPythonSequence},
                CAST(#{run.usageJson} AS jsonb), #{run.traceId}, #{run.createdAt}, #{run.updatedAt}, #{run.deadlineAt}
            )
            """)
    int insertRun(@Param("run") AgentRunRecord run);

    @Update("UPDATE business.agent_run SET response_message_id = #{messageId}, updated_at = #{updatedAt}"
            + " WHERE id = #{runId} AND user_id = #{userId}")
    int bindRunResponseMessage(
            @Param("runId") UUID runId,
            @Param("userId") long userId,
            @Param("messageId") long messageId,
            @Param("updatedAt") OffsetDateTime updatedAt
    );

    @Select("SELECT " + RUN_COLUMNS + " FROM business.agent_run r WHERE r.id = #{runId}")
    Optional<AgentRunRecord> findRun(@Param("runId") UUID runId);

    @Select("SELECT " + RUN_COLUMNS + " FROM business.agent_run r WHERE r.id = #{runId} FOR UPDATE")
    Optional<AgentRunRecord> lockRun(@Param("runId") UUID runId);

    @Select("SELECT " + RUN_COLUMNS + " FROM business.agent_run r"
            + " WHERE r.status IN ('QUEUED','STARTING','RUNNING','STOP_REQUESTED')"
            + " AND COALESCE(r.deadline_at, r.context_expires_at) <= #{cutoff}"
            + " ORDER BY COALESCE(r.deadline_at, r.context_expires_at), r.id LIMIT 1 FOR UPDATE SKIP LOCKED")
    Optional<AgentRunRecord> lockExpiredRun(@Param("cutoff") OffsetDateTime cutoff);

    @Select("SELECT " + RUN_COLUMNS + " FROM business.agent_run r"
            + " WHERE r.id = #{runId} AND r.user_id = #{userId}"
            + " AND r.conversation_id = #{conversationId} FOR UPDATE")
    Optional<AgentRunRecord> lockOwnedRun(@Param("runId") UUID runId,
            @Param("userId") long userId, @Param("conversationId") long conversationId);

    @Select("SELECT " + RUN_COLUMNS + " FROM business.agent_run r"
            + " WHERE r.id = #{runId} AND r.user_id = #{userId} AND r.conversation_id = #{conversationId}")
    Optional<AgentRunRecord> findOwnedRun(
            @Param("runId") UUID runId,
            @Param("userId") long userId,
            @Param("conversationId") long conversationId
    );

    @Select("SELECT " + RUN_COLUMNS + " FROM business.agent_run r"
            + " WHERE r.user_id = #{userId} AND r.conversation_id = #{conversationId}"
            + " AND r.status IN ('QUEUED', 'STARTING', 'RUNNING', 'STOP_REQUESTED')"
            + " ORDER BY r.created_at DESC LIMIT 1")
    Optional<AgentRunRecord> findActiveRun(
            @Param("userId") long userId,
            @Param("conversationId") long conversationId
    );

    @Update("UPDATE business.agent_run SET status = 'STARTING', started_at = #{now}, updated_at = #{now}"
            + " WHERE id = #{runId} AND status = 'QUEUED'")
    int markRunStarting(@Param("runId") UUID runId, @Param("now") OffsetDateTime now);

    @Update("UPDATE business.agent_run SET status = 'RUNNING', python_events_url = #{eventsUrl},"
            + " updated_at = #{now} WHERE id = #{runId} AND status = 'STARTING'")
    int markRunRunning(
            @Param("runId") UUID runId,
            @Param("eventsUrl") String eventsUrl,
            @Param("now") OffsetDateTime now
    );

    @Update("UPDATE business.agent_run SET last_python_sequence = #{sequence}, updated_at = #{now}"
            + " WHERE id = #{runId} AND last_python_sequence = #{sequence} - 1"
            + " AND status IN ('STARTING', 'RUNNING', 'STOP_REQUESTED')")
    int advancePythonSequence(
            @Param("runId") UUID runId,
            @Param("sequence") long sequence,
            @Param("now") OffsetDateTime now
    );

    @Update("UPDATE business.agent_run SET last_python_sequence = #{sequence}, updated_at = #{now}"
            + " WHERE id = #{runId} AND last_python_sequence < #{sequence}"
            + " AND status IN ('COMPLETED', 'FAILED', 'STOPPED')")
    int advanceTerminalUsageSequence(
            @Param("runId") UUID runId,
            @Param("sequence") long sequence,
            @Param("now") OffsetDateTime now
    );

    @Update("UPDATE business.agent_run SET usage = CAST(#{usageJson} AS jsonb), updated_at = #{now}"
            + " WHERE id = #{runId}")
    int updateRunUsage(
            @Param("runId") UUID runId,
            @Param("usageJson") String usageJson,
            @Param("now") OffsetDateTime now
    );

    @Update("""
            UPDATE business.agent_run
               SET status = #{status}, error_code = #{errorCode}, completed_at = #{now}, updated_at = #{now}
             WHERE id = #{runId} AND status NOT IN ('COMPLETED', 'FAILED', 'STOPPED')
            """)
    int completeRun(
            @Param("runId") UUID runId,
            @Param("status") String status,
            @Param("errorCode") String errorCode,
            @Param("now") OffsetDateTime now
    );

    @Select("""
            UPDATE business.agent_run
               SET status = CASE
                       WHEN status = 'STOP_REQUESTED' OR #{reportedStatus} = 'STOPPED' THEN 'STOPPED'
                       ELSE 'COMPLETED'
                   END,
                   error_code = NULL,
                   completed_at = #{now},
                   updated_at = #{now}
             WHERE id = #{runId}
               AND status IN ('QUEUED', 'STARTING', 'RUNNING', 'STOP_REQUESTED')
             RETURNING status
            """)
    String completeRunFromPython(
            @Param("runId") UUID runId,
            @Param("reportedStatus") String reportedStatus,
            @Param("now") OffsetDateTime now
    );

    @Update("UPDATE business.agent_run SET status = 'STOP_REQUESTED', stop_requested_at = #{now},"
            + " updated_at = #{now} WHERE id = #{runId}"
            + " AND user_id = #{userId} AND conversation_id = #{conversationId}"
            + " AND status IN ('QUEUED', 'STARTING', 'RUNNING')")
    int requestStop(
            @Param("runId") UUID runId,
            @Param("userId") long userId,
            @Param("conversationId") long conversationId,
            @Param("now") OffsetDateTime now
    );

    @Update("UPDATE business.conversation_message"
            + " SET content_type = 'TEXT', content = jsonb_build_object('text', #{text})"
            + " || CASE WHEN jsonb_exists(content,'articlePreparation') THEN jsonb_build_object('articlePreparation',content->'articlePreparation') ELSE '{}'::jsonb END,"
            + " status = #{status}, updated_at = #{now}"
            + " WHERE id = #{messageId} AND user_id = #{userId} AND agent_run_id = #{runId}")
    int updateAssistantMessage(
            @Param("messageId") long messageId,
            @Param("userId") long userId,
            @Param("runId") UUID runId,
            @Param("text") String text,
            @Param("status") String status,
            @Param("now") OffsetDateTime now
    );

    @Update("UPDATE business.conversation_message SET status = #{status}, updated_at = #{now}"
            + " WHERE id = #{messageId} AND user_id = #{userId} AND agent_run_id = #{runId}")
    int updateAssistantStatus(
            @Param("messageId") long messageId,
            @Param("userId") long userId,
            @Param("runId") UUID runId,
            @Param("status") String status,
            @Param("now") OffsetDateTime now
    );

    @Update("UPDATE business.conversation_message"
            + " SET content = jsonb_set(content, '{geoContext}', CAST(#{geoContextJson} AS jsonb)), updated_at = #{now}"
            + " WHERE id = #{messageId} AND user_id = #{userId} AND agent_run_id = #{runId}"
            + " AND role = 'ASSISTANT' AND content_type = 'TEXT' AND status = 'COMPLETED'")
    int setAssistantGeoContext(
            @Param("messageId") long messageId,
            @Param("userId") long userId,
            @Param("runId") UUID runId,
            @Param("geoContextJson") String geoContextJson,
            @Param("now") OffsetDateTime now
    );

    @Update("UPDATE business.conversation_message SET content=jsonb_set(content,'{articlePreparation}',"
            + "jsonb_build_object('preparationId',CAST(#{preparationId} AS text),'articleId',CAST(#{articleId} AS text))),updated_at=#{now}"
            + " WHERE id=#{messageId} AND user_id=#{userId} AND agent_run_id=#{runId} AND role='ASSISTANT'")
    int setAssistantArticlePreparation(@Param("messageId") long messageId,@Param("userId") long userId,
            @Param("runId") UUID runId,@Param("preparationId") String preparationId,@Param("articleId") String articleId,
            @Param("now") OffsetDateTime now);

    @Update("UPDATE business.conversation_message"
            + " SET content = jsonb_set(content, '{businessOutcome}', CAST(#{outcomeJson} AS jsonb)), updated_at = #{now}"
            + " WHERE id = #{messageId} AND user_id = #{userId} AND agent_run_id = #{runId}"
            + " AND role = 'ASSISTANT' AND status IN ('COMPLETED', 'FAILED')")
    int setAssistantBusinessOutcome(@Param("messageId") long messageId, @Param("userId") long userId,
                                    @Param("runId") UUID runId, @Param("outcomeJson") String outcomeJson,
                                    @Param("now") OffsetDateTime now);

    @Select("SELECT EXISTS (SELECT 1 FROM business.task_draft d"
            + " JOIN business.task_confirmation c ON c.draft_id=d.id AND c.user_id=d.user_id"
            + " WHERE d.agent_run_id=#{runId} AND d.user_id=#{userId} AND d.conversation_id=#{conversationId}"
            + " AND d.status='WAITING_CONFIRMATION' AND d.expires_at > #{now}"
            + " AND c.status='AVAILABLE' AND c.expires_at > #{now} AND c.input_hash=d.input_hash)")
    boolean hasReadyRunDraft(@Param("runId") UUID runId, @Param("userId") long userId,
                             @Param("conversationId") long conversationId, @Param("now") OffsetDateTime now);

    @Update("UPDATE business.conversation_message"
            + " SET content_type = 'INTERACTION', content = CAST(#{contentJson} AS jsonb)"
            + " || CASE WHEN jsonb_exists(content,'articlePreparation') THEN jsonb_build_object('articlePreparation',content->'articlePreparation') ELSE '{}'::jsonb END,"
            + " status = 'COMPLETED', updated_at = #{now}"
            + " WHERE id = #{messageId} AND user_id = #{userId} AND agent_run_id = #{runId}")
    int updateAssistantInteraction(
            @Param("messageId") long messageId,
            @Param("userId") long userId,
            @Param("runId") UUID runId,
            @Param("contentJson") String contentJson,
            @Param("now") OffsetDateTime now
    );

    @Select("SELECT " + EVENT_COLUMNS + " FROM business.conversation_event e"
            + " WHERE e.agent_run_id = #{runId} AND e.event_type = 'agent.progress'"
            + " AND e.payload ->> 'stageCode' = #{stageCode}"
            + " AND e.payload ->> 'status' = #{status}"
            + " AND (e.payload ->> 'ordinal')::integer = #{ordinal} LIMIT 1")
    Optional<ConversationEventRecord> findAgentProgressByIdentity(
            @Param("runId") UUID runId,
            @Param("stageCode") String stageCode,
            @Param("status") String status,
            @Param("ordinal") int ordinal
    );

    @Select("SELECT COUNT(*) FROM business.conversation_event"
            + " WHERE agent_run_id = #{runId} AND event_type = 'agent.progress'")
    long countAgentProgress(@Param("runId") UUID runId);

    @Select("SELECT " + EVENT_COLUMNS + " FROM business.conversation_event e"
            + " WHERE e.agent_run_id = #{runId} AND e.event_type = 'agent.progress'"
            + " ORDER BY e.sequence_no DESC LIMIT 1")
    Optional<ConversationEventRecord> findLatestAgentProgress(@Param("runId") UUID runId);

    @Insert("""
            INSERT INTO business.conversation_event(
                id, conversation_id, user_id, agent_run_id, message_id, task_id, source_event_id,
                event_type, sequence_no, payload, occurred_at, created_at
            ) VALUES (
                #{event.id}, #{event.conversationId}, #{event.userId}, #{event.agentRunId},
                #{event.messageId}, #{event.taskId}, #{event.sourceEventId}, #{event.eventType}, #{event.sequenceNo},
                CAST(#{event.payloadJson} AS jsonb), #{event.occurredAt}, #{event.createdAt}
            ) ON CONFLICT (agent_run_id, source_event_id) DO NOTHING
            """)
    int insertEvent(@Param("event") ConversationEventRecord event);

    @Select("SELECT " + EVENT_COLUMNS + " FROM business.conversation_event e"
            + " WHERE e.user_id = #{userId} AND e.conversation_id = #{conversationId}"
            + " AND e.sequence_no > #{afterSequence} ORDER BY e.sequence_no ASC LIMIT #{limit}")
    List<ConversationEventRecord> findEventsAfter(
            @Param("userId") long userId,
            @Param("conversationId") long conversationId,
            @Param("afterSequence") long afterSequence,
            @Param("limit") int limit
    );
}
