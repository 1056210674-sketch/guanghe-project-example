package com.guanghe.backend.task;

import com.guanghe.backend.asset.AssetRecords;
import com.guanghe.backend.conversation.ConversationEventRecord;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface TaskControlMapper {
    @Select("SELECT " + TASK_COLUMNS + " FROM business.business_task t WHERE t.user_id=#{userId} AND t.conversation_id=#{conversationId} AND t.operation_scope='AGENT_GEO_ACTION' AND t.request_hash=#{hash} AND t.status IN ('QUEUED','RUNNING','RETRY_WAIT') ORDER BY t.created_at DESC LIMIT 1")
    Optional<TaskRecords.Task> activeAgentGeoAction(@Param("userId") long userId,
            @Param("conversationId") long conversationId,@Param("hash") String hash);
    @Select("SELECT count(*) FROM business.task_draft d JOIN business.workflow_plan_step s ON s.task_draft_id=d.id "
            + "JOIN business.article_full_preparation p ON p.plan_id=s.plan_id AND p.user_id=d.user_id "
            + "WHERE d.id=#{draft} AND d.user_id=#{user} AND p.status='RUNNING' "
            + "AND d.task_type='ARTICLE' AND s.task_type='ARTICLE' AND d.draft_source='PLAN' "
            + "AND d.pricing_version_id=#{price} AND s.pricing_version_id=#{price} "
            + "AND d.quoted_points=#{points} AND s.quoted_points=#{points} "
            + "AND d.input_snapshot->'articleFull'->>'templateCode'='ARTICLE_FULL_V1' "
            + "AND d.input_snapshot->'articleFull'->>'planId'=p.plan_id::text "
            + "AND d.input_snapshot->'parameters'->>'articleId'=p.article_id::text "
            + "AND d.input_snapshot->'parameters'->>'mode'=s.deferred_article_mode")
    int matchesFixedArticleDraftPrice(@Param("draft") long draft,@Param("user") long user,
            @Param("price") long price,@Param("points") int points);
    String DRAFT_COLUMNS = """
            d.id, d.user_id AS userId, d.conversation_id AS conversationId,
            d.agent_run_id AS agentRunId, d.previous_draft_id AS previousDraftId,
            d.draft_source AS draftSource, d.operation_scope AS operationScope,
            d.idempotency_key AS idempotencyKey, d.request_hash AS requestHash,
            d.task_type AS taskType,
            d.parameter_schema_version AS parameterSchemaVersion, d.space_id AS spaceId,
            d.product_id AS productId, d.parameters::text AS parametersJson,
            d.input_snapshot::text AS inputSnapshotJson, d.input_hash AS inputHash,
            d.pricing_version_id AS pricingVersionId, d.quoted_points AS quotedPoints,
            d.warnings::text AS warningsJson, d.status, d.expires_at AS expiresAt,
            d.confirmed_at AS confirmedAt, d.created_at AS createdAt, d.updated_at AS updatedAt
            """;
    String TASK_COLUMNS = """
            t.id, t.user_id AS userId, t.space_id AS spaceId, t.product_id AS productId,
            t.conversation_id AS conversationId, t.draft_id AS draftId, t.task_type AS taskType,
            t.status, t.stage_code AS stageCode, t.snapshot_schema_version AS snapshotSchemaVersion,
            t.input_snapshot::text AS inputSnapshotJson, t.input_hash AS inputHash,
            t.pricing_version_id AS pricingVersionId, t.quoted_points AS quotedPoints,
            t.settled_points AS settledPoints, t.result_summary::text AS resultSummaryJson,
            t.error_code AS errorCode,
            t.operation_scope AS operationScope, t.idempotency_key AS idempotencyKey,
            t.request_hash AS requestHash, t.queued_at AS queuedAt, t.started_at AS startedAt,
            t.finished_at AS finishedAt, t.created_at AS createdAt, t.updated_at AS updatedAt,
            t.plan_id AS planId, t.scheduled_slot AS scheduledSlot
            """;
    String EXECUTION_COLUMNS = """
            x.id, x.task_id AS taskId, x.user_id AS userId, x.execution_no AS executionNo,
            x.status, x.consumer_name AS consumerName, x.worker_id AS workerId,
            x.bundle_version AS bundleVersion, x.execution_token_hash AS executionTokenHash,
            x.lease_expires_at AS leaseExpiresAt, x.stage_code AS stageCode,
            x.provider_code AS providerCode, x.provider_model AS providerModel,
            x.provider_request_id AS providerRequestId, x.provider_task_id AS providerTaskId,
            x.submission_state AS submissionState, x.latency_ms AS latencyMs,
            x.usage::text AS usageJson,
            x.error_code AS errorCode, x.last_heartbeat_at AS lastHeartbeatAt,
            x.started_at AS startedAt, x.finished_at AS finishedAt,
            x.created_at AS createdAt, x.updated_at AS updatedAt
            """;

    @Insert("""
            INSERT INTO business.task_draft(
              id,user_id,conversation_id,agent_run_id,previous_draft_id,draft_source,
              operation_scope,idempotency_key,request_hash,task_type,parameter_schema_version,
              space_id,product_id,parameters,input_snapshot,input_hash,pricing_version_id,
              quoted_points,warnings,status,expires_at,created_at,updated_at
            ) VALUES (#{d.id},#{d.userId},#{d.conversationId},#{d.agentRunId},#{d.previousDraftId},
              #{d.draftSource},#{d.operationScope},#{d.idempotencyKey},#{d.requestHash},#{d.taskType},
              #{d.parameterSchemaVersion},#{d.spaceId},#{d.productId},CAST(#{d.parametersJson} AS jsonb),
              CAST(#{d.inputSnapshotJson} AS jsonb),#{d.inputHash},#{d.pricingVersionId},
              #{d.quotedPoints},CAST(#{d.warningsJson} AS jsonb),#{d.status},#{d.expiresAt},
              #{d.createdAt},#{d.updatedAt})
            ON CONFLICT (agent_run_id) DO NOTHING
            """)
    int insertDraft(@Param("d") TaskRecords.Draft draft);

    @Insert("""
            INSERT INTO business.task_confirmation(
              id,draft_id,user_id,token_hash,input_hash,pricing_version_id,status,expires_at,created_at
            ) VALUES (#{c.id},#{c.draftId},#{c.userId},#{c.tokenHash},#{c.inputHash},
              #{c.pricingVersionId},#{c.status},#{c.expiresAt},#{c.createdAt})
            """)
    int insertConfirmation(@Param("c") TaskRecords.Confirmation confirmation);

    @Select("SELECT " + DRAFT_COLUMNS + " FROM business.task_draft d WHERE d.agent_run_id=#{runId}")
    Optional<TaskRecords.Draft> findDraftByRunId(@Param("runId") UUID runId);

    @Select("SELECT " + DRAFT_COLUMNS + " FROM business.task_draft d WHERE d.id=#{draftId} AND d.user_id=#{userId}")
    Optional<TaskRecords.Draft> findDraft(@Param("draftId") long draftId, @Param("userId") long userId);

    @Select("SELECT " + DRAFT_COLUMNS + " FROM business.task_draft d"
            + " WHERE d.user_id=#{userId} AND d.operation_scope=#{scope} AND d.idempotency_key=#{key}")
    Optional<TaskRecords.Draft> findDraftByIdempotency(@Param("userId") long userId,
                                                        @Param("scope") String scope,
                                                        @Param("key") UUID key);

    @Select("SELECT " + DRAFT_COLUMNS + " FROM business.task_draft d "
            + "JOIN business.article a ON a.id::text=d.parameters->>'articleId' AND a.user_id=d.user_id "
            + "WHERE d.user_id=#{userId} AND d.task_type='ARTICLE' "
            + "AND d.parameters->>'geoReportId'=#{reportId} "
            + "AND COALESCE(d.parameters->>'geoRecommendationId','')=#{suggestionKey} "
            + "AND d.status<>'ABANDONED' AND a.status NOT IN ('FAILED','ARCHIVED') "
            + "ORDER BY d.created_at DESC,d.id DESC LIMIT 1 FOR UPDATE")
    Optional<TaskRecords.Draft> lockGeoArticleDraftByIdentity(@Param("userId") long userId,
                                                               @Param("reportId") String reportId,
                                                               @Param("suggestionKey") String suggestionKey);

    @Select("SELECT " + DRAFT_COLUMNS + " FROM business.task_draft d "
            + "JOIN business.article a ON a.id::text=d.parameters->>'articleId' AND a.user_id=d.user_id "
            + "WHERE d.user_id=#{userId} AND d.task_type='ARTICLE' "
            + "AND d.parameters->>'articleId'=#{articleId} "
            + "AND d.status<>'ABANDONED' AND a.status NOT IN ('FAILED','ARCHIVED') "
            + "ORDER BY d.created_at DESC,d.id DESC LIMIT 1 FOR UPDATE")
    Optional<TaskRecords.Draft> lockLatestArticleDraftByArticle(@Param("userId") long userId,
                                                                  @Param("articleId") String articleId);

    @Select("SELECT " + DRAFT_COLUMNS + " FROM business.task_draft d "
            + "JOIN business.article a ON a.id::text=d.parameters->>'articleId' AND a.user_id=d.user_id "
            + "WHERE d.user_id=#{userId} AND d.conversation_id=#{conversationId} AND d.task_type='ARTICLE' "
            + "AND a.status NOT IN ('FAILED','ARCHIVED') "
            + "AND ((d.status IN ('EDITING','WAITING_CONFIRMATION') AND d.expires_at>#{now}) "
            + "OR EXISTS (SELECT 1 FROM business.business_task t WHERE t.user_id=d.user_id "
            + "AND t.draft_id=d.id AND t.task_type='ARTICLE' "
            + "AND t.status IN ('QUEUED','RUNNING','RETRY_WAIT'))) "
            + "ORDER BY d.created_at DESC,d.id DESC LIMIT 1 FOR UPDATE")
    Optional<TaskRecords.Draft> lockActiveArticleDraftInConversation(@Param("userId") long userId,
            @Param("conversationId") long conversationId, @Param("now") OffsetDateTime now);

    @Select("SELECT " + DRAFT_COLUMNS + " FROM business.task_draft d"
            + " WHERE d.user_id=#{userId} AND d.conversation_id=#{conversationId}"
            + " AND d.status='WAITING_CONFIRMATION' AND d.expires_at>#{now}"
            + " ORDER BY d.created_at DESC, d.id DESC LIMIT 1")
    Optional<TaskRecords.Draft> findActiveWorkspaceDraft(
            @Param("userId") long userId,
            @Param("conversationId") long conversationId,
            @Param("now") OffsetDateTime now
    );

    @Select("SELECT d.id FROM business.task_draft d"
            + " WHERE d.user_id=#{userId} AND d.conversation_id=#{conversationId}"
            + " AND d.status='WAITING_CONFIRMATION' AND d.expires_at>#{now}"
            + " ORDER BY d.created_at DESC, d.id DESC LIMIT 2")
    List<Long> findActiveWorkspaceDraftIds(
            @Param("userId") long userId,
            @Param("conversationId") long conversationId,
            @Param("now") OffsetDateTime now
    );

    @Select("""
            WITH latest_article AS (
              SELECT a.id AS articleId,a.version AS articleVersion,
                     a.workflow_stage AS workflowStage,a.title,a.product_id AS productId
              FROM business.task_draft d
              JOIN business.article a
                ON a.user_id=d.user_id AND a.id::text=d.parameters->>'articleId'
              WHERE d.user_id=#{userId} AND d.conversation_id=#{conversationId}
                AND d.task_type='ARTICLE'
                AND (NOT #{scopeSelected} OR (d.space_id=#{spaceId}
                     AND d.product_id IS NOT DISTINCT FROM CAST(#{productId} AS bigint)))
              ORDER BY d.created_at DESC,d.id DESC
              LIMIT 1
            )
            SELECT articleId,articleVersion,workflowStage,title,productId
            FROM latest_article a
            WHERE NOT EXISTS (
              SELECT 1 FROM business.business_task t
              WHERE t.user_id=#{userId} AND t.conversation_id=#{conversationId}
                AND t.task_type='MEDIA' AND t.status IN ('SUCCEEDED','PARTIAL')
                AND t.input_snapshot->'parameters'->>'articleId'=a.articleId::text
            )
            """)
    Optional<TaskRecords.ActiveArticle> findActiveConversationArticle(
            @Param("userId") long userId,
            @Param("conversationId") long conversationId,
            @Param("scopeSelected") boolean scopeSelected,
            @Param("spaceId") Long spaceId,
            @Param("productId") Long productId
    );

    @Select("SELECT " + DRAFT_COLUMNS + " FROM business.task_draft d WHERE d.id=#{draftId} AND d.user_id=#{userId} FOR UPDATE")
    Optional<TaskRecords.Draft> lockDraft(@Param("draftId") long draftId, @Param("userId") long userId);

    @Select("""
            SELECT c.id,c.draft_id AS draftId,c.user_id AS userId,c.token_hash AS tokenHash,
              c.input_hash AS inputHash,c.pricing_version_id AS pricingVersionId,c.status,
              c.expires_at AS expiresAt,c.used_at AS usedAt,c.created_at AS createdAt
            FROM business.task_confirmation c WHERE c.draft_id=#{draftId} AND c.user_id=#{userId} FOR UPDATE
            """)
    Optional<TaskRecords.Confirmation> lockConfirmation(@Param("draftId") long draftId,
                                                         @Param("userId") long userId);

    @Update("""
            UPDATE business.task_confirmation SET status='CONSUMED',used_at=#{now}
             WHERE draft_id=#{draftId} AND user_id=#{userId} AND status='AVAILABLE'
            """)
    int consumeConfirmation(@Param("draftId") long draftId, @Param("userId") long userId,
                            @Param("now") OffsetDateTime now);

    @Update("UPDATE business.task_draft SET status='CONFIRMED',confirmed_at=#{now},updated_at=#{now} WHERE id=#{draftId} AND user_id=#{userId} AND status='WAITING_CONFIRMATION'")
    int confirmDraft(@Param("draftId") long draftId, @Param("userId") long userId,
                     @Param("now") OffsetDateTime now);

    @Update("UPDATE business.task_draft SET status='ADMITTED',admitted_at=#{now},updated_at=#{now} WHERE id=#{draftId} AND user_id=#{userId} AND status='WAITING_CONFIRMATION' AND task_type='GEO'")
    int admitMonitorDraft(@Param("draftId") long draftId,@Param("userId") long userId,@Param("now") OffsetDateTime now);

    @Update("UPDATE business.task_draft SET status='ADMITTED',admitted_at=#{now},updated_at=#{now} WHERE id=#{draftId} AND user_id=#{userId} AND status='WAITING_CONFIRMATION' AND task_type IN ('IMAGE','VIDEO') AND draft_source='CHAT' AND input_snapshot->'nativeCreative'->>'runId'=agent_run_id::text")
    int admitCreativeDraft(@Param("draftId") long draftId,@Param("userId") long userId,@Param("now") OffsetDateTime now);

    @Update("UPDATE business.task_draft SET status='ADMITTED',admitted_at=#{now},updated_at=#{now} WHERE id=#{draftId} AND user_id=#{userId} AND status='WAITING_CONFIRMATION' AND task_type='ARTICLE' AND draft_source='PLAN' AND input_snapshot->'articleFull'->>'templateCode'='ARTICLE_FULL_V1'")
    int admitFullArticleDraft(@Param("draftId") long draftId,@Param("userId") long userId,@Param("now") OffsetDateTime now);

    @Select("SELECT count(*) FROM business.workflow_plan_step s JOIN business.article_full_preparation p ON p.plan_id=s.plan_id WHERE s.task_id=#{taskId}")
    int isFullArticleTask(long taskId);

    @Update("UPDATE business.task_draft SET status='ABANDONED',updated_at=#{now}"
            + " WHERE id=#{draftId} AND user_id=#{userId}"
            + " AND status IN ('WAITING_CONFIRMATION','EDITING')")
    int abandonDraft(@Param("draftId") long draftId, @Param("userId") long userId,
                     @Param("now") OffsetDateTime now);

    @Update("UPDATE business.task_confirmation SET status='INVALIDATED'"
            + " WHERE draft_id=#{draftId} AND user_id=#{userId} AND status='AVAILABLE'")
    int invalidateConfirmation(@Param("draftId") long draftId, @Param("userId") long userId);

    @Select("SELECT user_id AS userId,available_points AS availablePoints,frozen_points AS frozenPoints,version,created_at AS createdAt,updated_at AS updatedAt FROM business.point_account WHERE user_id=#{userId}")
    Optional<TaskRecords.PointAccount> findPointAccount(@Param("userId") long userId);

    @Select("SELECT user_id AS userId,available_points AS availablePoints,frozen_points AS frozenPoints,version,created_at AS createdAt,updated_at AS updatedAt FROM business.point_account WHERE user_id=#{userId} FOR UPDATE")
    Optional<TaskRecords.PointAccount> lockPointAccount(@Param("userId") long userId);

    @Select("<script>SELECT id,user_id AS userId,available_delta AS availableDelta,"
            + "frozen_delta AS frozenDelta,available_after AS availableAfter,frozen_after AS frozenAfter,"
            + "entry_type AS entryType,business_type AS businessType,business_id AS businessId,"
            + "created_at AS createdAt FROM business.point_ledger WHERE user_id=#{userId}"
            + " <if test='ledgerId != null'>AND id=#{ledgerId}</if>"
            + " <if test='entryType != null'>AND entry_type=#{entryType}</if>"
            + " <if test='businessType != null'>AND business_type=#{businessType}</if>"
            + " ORDER BY created_at DESC,id DESC LIMIT #{size} OFFSET #{offset}</script>")
    List<TaskRecords.PointLedgerEntry> findPointLedgerPage(
            @Param("userId") long userId,
            @Param("ledgerId") Long ledgerId,
            @Param("entryType") String entryType,
            @Param("businessType") String businessType,
            @Param("offset") long offset,
            @Param("size") int size);

    @Select("<script>SELECT count(*) FROM business.point_ledger WHERE user_id=#{userId}"
            + " <if test='ledgerId != null'>AND id=#{ledgerId}</if>"
            + " <if test='entryType != null'>AND entry_type=#{entryType}</if>"
            + " <if test='businessType != null'>AND business_type=#{businessType}</if></script>")
    long countPointLedger(@Param("userId") long userId,
                          @Param("ledgerId") Long ledgerId,
                          @Param("entryType") String entryType,
                          @Param("businessType") String businessType);

    @Update("UPDATE business.point_account SET available_points=available_points-#{points},frozen_points=frozen_points+#{points},version=version+1,updated_at=#{now} WHERE user_id=#{userId} AND available_points>=#{points}")
    int freezePoints(@Param("userId") long userId,@Param("points") int points,@Param("now") OffsetDateTime now);

    @Update("UPDATE business.point_account SET frozen_points=frozen_points-#{points},version=version+1,updated_at=#{now} WHERE user_id=#{userId} AND frozen_points>=#{points}")
    int settlePoints(@Param("userId") long userId,@Param("points") int points,@Param("now") OffsetDateTime now);

    @Update("UPDATE business.point_account SET available_points=available_points+#{points},frozen_points=frozen_points-#{points},version=version+1,updated_at=#{now} WHERE user_id=#{userId} AND frozen_points>=#{points}")
    int releasePoints(@Param("userId") long userId,@Param("points") int points,@Param("now") OffsetDateTime now);

    @Insert("""
            INSERT INTO business.business_task(
              id,user_id,space_id,product_id,conversation_id,draft_id,task_type,status,stage_code,
              snapshot_schema_version,input_snapshot,input_hash,pricing_version_id,quoted_points,
              settled_points,result_summary,error_code,operation_scope,idempotency_key,request_hash,
              queued_at,created_at,updated_at,plan_id,scheduled_slot
            ) VALUES (#{t.id},#{t.userId},#{t.spaceId},#{t.productId},#{t.conversationId},#{t.draftId},
              #{t.taskType},#{t.status},#{t.stageCode},#{t.snapshotSchemaVersion},
              CAST(#{t.inputSnapshotJson} AS jsonb),#{t.inputHash},#{t.pricingVersionId},
              #{t.quotedPoints},#{t.settledPoints},CAST(#{t.resultSummaryJson} AS jsonb),#{t.errorCode},
              #{t.operationScope},#{t.idempotencyKey},#{t.requestHash},#{t.queuedAt},#{t.createdAt},#{t.updatedAt},
              #{t.planId},#{t.scheduledSlot})
            """)
    int insertTask(@Param("t") TaskRecords.Task task);

    @Select("SELECT " + TASK_COLUMNS + " FROM business.business_task t WHERE t.id=#{taskId} AND t.user_id=#{userId}")
    Optional<TaskRecords.Task> findTask(@Param("taskId") long taskId,@Param("userId") long userId);

    @Select("<script>SELECT task_id AS taskId,frozen_points AS frozenPoints,"
            + "settled_points AS settledPoints,released_points AS releasedPoints"
            + " FROM business.point_reservation WHERE user_id=#{userId}"
            + " AND task_id IN <foreach collection='taskIds' item='taskId' open='(' separator=',' close=')'>"
            + "#{taskId}</foreach></script>")
    List<TaskRecords.TaskFunding> findTaskFunding(@Param("userId") long userId,
                                                  @Param("taskIds") List<Long> taskIds);

    @Select("SELECT " + TASK_COLUMNS + " FROM business.business_task t"
            + " WHERE t.user_id=#{userId} AND t.conversation_id=#{conversationId}"
            + " AND t.status IN ('QUEUED','RUNNING','RETRY_WAIT')"
            + " ORDER BY t.created_at DESC, t.id DESC")
    List<TaskRecords.Task> findActiveWorkspaceTasks(
            @Param("userId") long userId,
            @Param("conversationId") long conversationId
    );

    @Select("SELECT " + TASK_COLUMNS + " FROM business.business_task t WHERE t.id=#{taskId}")
    Optional<TaskRecords.Task> findTaskInternal(@Param("taskId") long taskId);

    @Select("<script>SELECT " + TASK_COLUMNS + " FROM business.business_task t"
            + " WHERE t.user_id=#{userId}"
            + " <if test='taskType != null'>AND t.task_type=#{taskType}</if>"
            + " <if test='status != null'>AND t.status=#{status}</if>"
            + " <if test='conversationId != null'>AND t.conversation_id=#{conversationId}</if>"
            + " <if test='spaceId != null'>AND t.space_id=#{spaceId}</if>"
            + " <if test='productId != null'>AND t.product_id=#{productId}</if>"
            + " <choose>"
            + " <when test='conversationId != null'>"
            + " ORDER BY (t.status IN ('QUEUED','RUNNING','RETRY_WAIT')) DESC,"
            + " t.created_at DESC,t.id DESC"
            + " </when>"
            + " <otherwise>ORDER BY t.created_at DESC,t.id DESC</otherwise>"
            + " </choose> LIMIT #{size} OFFSET #{offset}</script>")
    List<TaskRecords.Task> findTaskPage(@Param("userId") long userId,
                                        @Param("taskType") String taskType,
                                        @Param("status") String status,
                                        @Param("conversationId") Long conversationId,
                                        @Param("spaceId") Long spaceId,
                                        @Param("productId") Long productId,
                                        @Param("offset") long offset,
                                        @Param("size") int size);

    @Select("<script>SELECT count(*) FROM business.business_task t WHERE t.user_id=#{userId}"
            + " <if test='taskType != null'>AND t.task_type=#{taskType}</if>"
            + " <if test='status != null'>AND t.status=#{status}</if>"
            + " <if test='conversationId != null'>AND t.conversation_id=#{conversationId}</if>"
            + " <if test='spaceId != null'>AND t.space_id=#{spaceId}</if>"
            + " <if test='productId != null'>AND t.product_id=#{productId}</if></script>")
    long countTasks(@Param("userId") long userId,
                    @Param("taskType") String taskType,
                    @Param("status") String status,
                    @Param("conversationId") Long conversationId,
                    @Param("spaceId") Long spaceId,
                    @Param("productId") Long productId);

    @Select("SELECT e.id,e.conversation_id AS conversationId,e.user_id AS userId,"
            + "e.agent_run_id AS agentRunId,e.message_id AS messageId,e.task_id AS taskId,"
            + "e.source_event_id AS sourceEventId,e.event_type AS eventType,e.sequence_no AS sequenceNo,"
            + "e.payload::text AS payloadJson,e.occurred_at AS occurredAt,e.created_at AS createdAt "
            + "FROM business.conversation_event e WHERE e.task_id=#{taskId} AND e.user_id=#{userId} "
            + "ORDER BY e.sequence_no ASC,e.id ASC")
    List<ConversationEventRecord> findTaskBrowserEvents(@Param("taskId") long taskId,
                                                         @Param("userId") long userId);

    @Select("SELECT " + TASK_COLUMNS + " FROM business.business_task t WHERE t.id=#{taskId} FOR UPDATE")
    Optional<TaskRecords.Task> lockTask(@Param("taskId") long taskId);

    @Select("SELECT " + TASK_COLUMNS + " FROM business.business_task t WHERE t.user_id=#{userId} AND t.operation_scope='TASK_CONFIRM' AND t.idempotency_key=#{key}")
    Optional<TaskRecords.Task> findTaskByIdempotency(@Param("userId") long userId,@Param("key") UUID key);

    @Select("SELECT " + TASK_COLUMNS + " FROM business.business_task t WHERE t.user_id=#{userId}"
            + " AND t.operation_scope=#{scope} AND t.idempotency_key=#{key}")
    Optional<TaskRecords.Task> findTaskByOperationIdempotency(@Param("userId") long userId,
                                                               @Param("scope") String scope,
                                                               @Param("key") UUID key);

    @Select("SELECT " + TASK_COLUMNS + " FROM business.business_task t WHERE t.draft_id=#{draftId}")
    Optional<TaskRecords.Task> findTaskByDraft(@Param("draftId") long draftId);

    @Insert("INSERT INTO business.point_reservation(id,user_id,task_id,quoted_points,frozen_points,settled_points,released_points,status,created_at,updated_at) VALUES(#{id},#{userId},#{taskId},#{points},#{points},0,0,'FROZEN',#{now},#{now})")
    int insertReservation(@Param("id") long id,@Param("userId") long userId,@Param("taskId") long taskId,
                          @Param("points") int points,@Param("now") OffsetDateTime now);

    @Update("UPDATE business.point_reservation SET frozen_points=0,settled_points=#{points},status='SETTLED',updated_at=#{now} WHERE task_id=#{taskId} AND status='FROZEN'")
    int settleReservation(@Param("taskId") long taskId,@Param("points") int points,@Param("now") OffsetDateTime now);

    @Update("UPDATE business.point_reservation SET frozen_points=0,released_points=#{points},status='RELEASED',updated_at=#{now} WHERE task_id=#{taskId} AND status='FROZEN'")
    int releaseReservation(@Param("taskId") long taskId,@Param("points") int points,@Param("now") OffsetDateTime now);

    @Insert("""
            INSERT INTO business.point_ledger(id,user_id,available_delta,frozen_delta,available_after,
              frozen_after,entry_type,business_type,business_id,idempotency_key,reason,created_at)
            VALUES(#{id},#{userId},#{availableDelta},#{frozenDelta},#{availableAfter},#{frozenAfter},
              #{entryType},'TASK',#{taskId},#{key},#{reason},#{now})
            """)
    int insertLedger(@Param("id") long id,@Param("userId") long userId,
                     @Param("availableDelta") int availableDelta,@Param("frozenDelta") int frozenDelta,
                     @Param("availableAfter") int availableAfter,@Param("frozenAfter") int frozenAfter,
                     @Param("entryType") String entryType,@Param("taskId") long taskId,
                     @Param("key") String key,@Param("reason") String reason,@Param("now") OffsetDateTime now);

    @Insert("INSERT INTO business.business_task_event(id,task_id,user_id,execution_id,source_event_id,request_hash,from_status,to_status,event_type,source,error_code,message,details,created_at) VALUES(#{id},#{taskId},#{userId},#{executionId},#{sourceEventId},#{requestHash},#{fromStatus},#{toStatus},#{eventType},#{source},#{errorCode},#{message},CAST(#{detailsJson} AS jsonb),#{now}) ON CONFLICT (task_id,source_event_id) DO NOTHING")
    int insertTaskEvent(@Param("id") long id,@Param("taskId") long taskId,@Param("userId") long userId,
                        @Param("executionId") Long executionId,@Param("sourceEventId") UUID sourceEventId,
                        @Param("requestHash") String requestHash,@Param("fromStatus") String fromStatus,
                        @Param("toStatus") String toStatus,@Param("eventType") String eventType,
                        @Param("source") String source,@Param("errorCode") String errorCode,
                        @Param("message") String message,@Param("detailsJson") String detailsJson,
                        @Param("now") OffsetDateTime now);

    @Select("SELECT request_hash FROM business.business_task_event WHERE task_id=#{taskId} AND source_event_id=#{sourceEventId}")
    Optional<String> findTaskEventRequestHash(@Param("taskId") long taskId,
                                               @Param("sourceEventId") UUID sourceEventId);

    @Insert("INSERT INTO business.outbox_event(id,user_id,aggregate_type,aggregate_id,schema_version,event_type,execution_attempt,exchange_name,routing_key,payload,traceparent,status,attempts,available_at,created_at,updated_at) VALUES(#{o.id},#{o.userId},'TASK',#{o.aggregateId},1,'TASK_EXECUTION_REQUESTED',#{o.executionAttempt},#{exchange},#{routingKey},CAST(#{o.payloadJson} AS jsonb),#{o.traceparent},'PENDING',0,#{o.availableAt},#{o.createdAt},#{o.createdAt})")
    int insertOutbox(@Param("o") TaskRecords.Outbox outbox,@Param("exchange") String exchange,
                     @Param("routingKey") String routingKey);

    @Select("SELECT id,user_id AS userId,aggregate_id AS aggregateId,execution_attempt AS executionAttempt,"
            + "payload::text AS payloadJson,traceparent,exchange_name AS exchangeName,routing_key AS routingKey,"
            + "status,attempts,available_at AS availableAt,created_at AS createdAt FROM business.outbox_event "
            + "WHERE status='PENDING' AND event_type IN ('TASK_EXECUTION_REQUESTED','WORKFLOW_EXECUTION_REQUESTED')"
            + " AND available_at<=#{now} ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED")
    Optional<TaskRecords.Outbox> lockNextOutbox(@Param("now") OffsetDateTime now);

    @Select("SELECT id,user_id AS userId,aggregate_id AS aggregateId,execution_attempt AS executionAttempt,"
            + "payload::text AS payloadJson,traceparent,exchange_name AS exchangeName,routing_key AS routingKey,"
            + "status,attempts,available_at AS availableAt,created_at AS createdAt FROM business.outbox_event "
            + "WHERE id=#{eventId} AND aggregate_type='TASK' AND aggregate_id=#{taskId} "
            + "AND event_type='TASK_EXECUTION_REQUESTED'")
    Optional<TaskRecords.Outbox> findOutbox(@Param("eventId") long eventId,@Param("taskId") long taskId);

    @Update("UPDATE business.outbox_event SET status=#{status},attempts=attempts+1,published_at=CASE WHEN #{status}='PUBLISHED' THEN #{now} ELSE published_at END,last_error_code=#{errorCode},updated_at=#{now} WHERE id=#{id}")
    int finishOutbox(@Param("id") long id,@Param("status") String status,@Param("errorCode") String errorCode,
                     @Param("now") OffsetDateTime now);

    @Update("UPDATE business.outbox_event SET status=CASE WHEN attempts>=4 THEN 'FAILED' ELSE 'PENDING' END,attempts=attempts+1,available_at=#{availableAt},last_error_code=#{errorCode},updated_at=#{now} WHERE id=#{id}")
    int failOutbox(@Param("id") long id,@Param("availableAt") OffsetDateTime availableAt,
                   @Param("errorCode") String errorCode,@Param("now") OffsetDateTime now);

    @Select("SELECT id,consumer_name AS consumerName,event_id AS eventId,task_id AS taskId,user_id AS userId,execution_id AS executionId,disposition,created_at AS createdAt FROM business.inbox_event WHERE consumer_name=#{consumer} AND event_id=#{eventId}")
    Optional<TaskRecords.Inbox> findInbox(@Param("consumer") String consumer,@Param("eventId") long eventId);

    @Insert("INSERT INTO business.inbox_event(id,consumer_name,event_id,task_id,user_id,execution_id,disposition,created_at) VALUES(#{id},#{consumer},#{eventId},#{taskId},#{userId},#{executionId},#{disposition},#{now})")
    int insertInbox(@Param("id") long id,@Param("consumer") String consumer,@Param("eventId") long eventId,
                    @Param("taskId") long taskId,@Param("userId") long userId,@Param("executionId") Long executionId,
                    @Param("disposition") String disposition,@Param("now") OffsetDateTime now);

    @Insert("INSERT INTO business.task_execution(id,task_id,user_id,execution_no,status,consumer_name,worker_id,bundle_version,execution_token_hash,lease_expires_at,stage_code,provider_code,provider_model,provider_request_id,provider_task_id,submission_state,usage,started_at,created_at,updated_at) VALUES(#{x.id},#{x.taskId},#{x.userId},#{x.executionNo},#{x.status},#{x.consumerName},#{x.workerId},#{x.bundleVersion},#{x.executionTokenHash},#{x.leaseExpiresAt},#{x.stageCode},#{x.providerCode},#{x.providerModel},#{x.providerRequestId},#{x.providerTaskId},#{x.submissionState},CAST(#{x.usageJson} AS jsonb),#{x.startedAt},#{x.createdAt},#{x.updatedAt})")
    int insertExecution(@Param("x") TaskRecords.Execution execution);

    @Select("SELECT " + EXECUTION_COLUMNS + " FROM business.task_execution x WHERE x.id=#{id}")
    Optional<TaskRecords.Execution> findExecution(@Param("id") long id);

    @Select("SELECT " + EXECUTION_COLUMNS + " FROM business.task_execution x WHERE x.task_id=#{taskId} ORDER BY x.execution_no DESC LIMIT 1")
    Optional<TaskRecords.Execution> findLatestExecution(@Param("taskId") long taskId);

    @Select("SELECT id FROM business.business_task WHERE status='QUEUED'"
            + " AND queued_at<=#{cutoff} ORDER BY queued_at,id LIMIT 1")
    Optional<Long> findStaleQueuedTaskId(@Param("cutoff") OffsetDateTime cutoff);

    @Update("UPDATE business.business_task SET status='SYSTEM_TERMINATED',"
            + "stage_code='DISPATCH_EXPIRED',error_code='DISPATCH_EXPIRED',"
            + "finished_at=#{now},updated_at=#{now} WHERE id=#{taskId} AND status='QUEUED'"
            + " AND queued_at<=#{cutoff}")
    int terminateStaleQueuedTask(@Param("taskId") long taskId,
                                 @Param("cutoff") OffsetDateTime cutoff,
                                 @Param("now") OffsetDateTime now);

    @Update("UPDATE business.business_task SET status='RUNNING',stage_code=#{stage},error_code=NULL,started_at=COALESCE(started_at,#{now}),updated_at=#{now} WHERE id=#{taskId} AND status IN ('QUEUED','RETRY_WAIT')")
    int claimTask(@Param("taskId") long taskId,@Param("stage") String stage,
                  @Param("now") OffsetDateTime now);

    @Select("SELECT x.task_id FROM business.task_execution x JOIN business.business_task t ON t.id=x.task_id WHERE t.status='RUNNING' AND x.status='RUNNING' AND x.lease_expires_at<#{now} ORDER BY x.lease_expires_at,x.id LIMIT 1")
    Optional<Long> findExpiredTaskId(@Param("now") OffsetDateTime now);

    @Select("SELECT " + EXECUTION_COLUMNS + " FROM business.task_execution x WHERE x.task_id=#{taskId} AND x.status='RUNNING' AND x.lease_expires_at<#{now} ORDER BY x.execution_no DESC LIMIT 1 FOR UPDATE")
    Optional<TaskRecords.Execution> lockExpiredExecution(@Param("taskId") long taskId,
                                                          @Param("now") OffsetDateTime now);

    @Update("UPDATE business.task_execution SET status='EXPIRED',error_code='WORKER_LEASE_EXPIRED',finished_at=#{now},updated_at=#{now} WHERE id=#{executionId} AND status='RUNNING' AND lease_expires_at<#{now}")
    int expireExecution(@Param("executionId") long executionId,@Param("now") OffsetDateTime now);

    @Update("UPDATE business.business_task SET status='RETRY_WAIT',stage_code=#{stage},error_code=#{errorCode},updated_at=#{now} WHERE id=#{taskId} AND status='RUNNING'")
    int scheduleTaskRetry(@Param("taskId") long taskId,@Param("stage") String stage,
                          @Param("errorCode") String errorCode,@Param("now") OffsetDateTime now);

    @Update("UPDATE business.business_task SET status='FAILED',stage_code='FAILED',error_code='WORKER_LEASE_EXPIRED',finished_at=#{now},updated_at=#{now} WHERE id=#{taskId} AND status='RUNNING'")
    int failExpiredTask(@Param("taskId") long taskId,@Param("now") OffsetDateTime now);

    @Update("UPDATE business.business_task SET status='SYSTEM_TERMINATED',stage_code='PROVIDER_RESULT_CONFIRMATION_FAILED',error_code='PROVIDER_RESULT_RECOVERY_EXHAUSTED',finished_at=#{now},updated_at=#{now} WHERE id=#{taskId} AND status IN ('RUNNING','RETRY_WAIT')")
    int terminateUnconfirmedTask(@Param("taskId") long taskId,@Param("now") OffsetDateTime now);

    @Update("UPDATE business.task_execution SET lease_expires_at=#{lease},last_heartbeat_at=#{now},stage_code=#{stage},updated_at=#{now} WHERE task_id=#{taskId} AND execution_no=#{executionNo} AND execution_token_hash=#{tokenHash} AND status='RUNNING' AND lease_expires_at>=#{now}")
    int heartbeat(@Param("taskId") long taskId,@Param("executionNo") int executionNo,
                  @Param("tokenHash") String tokenHash,@Param("stage") String stage,
                  @Param("lease") OffsetDateTime lease,@Param("now") OffsetDateTime now);

    @Update("UPDATE business.business_task SET stage_code=#{stage},updated_at=#{now} WHERE id=#{taskId} AND status='RUNNING'")
    int updateProgress(@Param("taskId") long taskId,@Param("stage") String stage,@Param("now") OffsetDateTime now);

    // Observation arrivals must not undo the worker's bounded retry-pass stage.
    @Update("UPDATE business.business_task SET stage_code=CASE WHEN stage_code='GEO_RETRYING' THEN stage_code ELSE 'GEO_SAMPLING' END,updated_at=#{now} WHERE id=#{taskId} AND task_type='GEO' AND status='RUNNING'")
    int updateGeoSamplingProgress(@Param("taskId") long taskId,@Param("now") OffsetDateTime now);

    @Update("UPDATE business.task_execution SET provider_code=#{provider},provider_model=#{model},provider_request_id=#{requestId},provider_task_id=#{providerTaskId},submission_state='CONFIRMED',latency_ms=#{latencyMs},usage=CAST(#{usageJson} AS jsonb),status='SUCCEEDED',finished_at=#{now},updated_at=#{now} WHERE task_id=#{taskId} AND execution_no=#{executionNo} AND execution_token_hash=#{tokenHash} AND status='RUNNING' AND lease_expires_at>=#{now}")
    int completeExecution(@Param("taskId") long taskId,@Param("executionNo") int executionNo,
                          @Param("tokenHash") String tokenHash,@Param("provider") String provider,
                          @Param("model") String model,@Param("requestId") UUID requestId,
                          @Param("providerTaskId") String providerTaskId,
                          @Param("latencyMs") int latencyMs,@Param("usageJson") String usageJson,
                          @Param("now") OffsetDateTime now);

    @Update("UPDATE business.business_task SET status='SUCCEEDED',stage_code='COMPLETED',settled_points=quoted_points,result_summary=CAST(#{resultJson} AS jsonb),finished_at=#{now},updated_at=#{now} WHERE id=#{taskId} AND status='RUNNING'")
    int completeTask(@Param("taskId") long taskId,@Param("resultJson") String resultJson,@Param("now") OffsetDateTime now);

    @Update("UPDATE business.task_execution SET provider_code=#{provider},provider_model=#{model},"
            + "provider_request_id=#{requestId},provider_task_id=#{providerTaskId},"
            + "submission_state='CONFIRMED',latency_ms=#{latencyMs},usage=CAST(#{usageJson} AS jsonb),"
            + "status='RESULT_READY',stage_code='ASSET_INGESTION_PENDING',lease_expires_at=#{now},"
            + "updated_at=#{now} WHERE task_id=#{taskId} AND execution_no=#{executionNo}"
            + " AND execution_token_hash=#{tokenHash} AND status='RUNNING' AND lease_expires_at>=#{now}")
    int markExecutionResultReady(@Param("taskId") long taskId,@Param("executionNo") int executionNo,
                                 @Param("tokenHash") String tokenHash,@Param("provider") String provider,
                                 @Param("model") String model,@Param("requestId") UUID requestId,
                                 @Param("providerTaskId") String providerTaskId,
                                 @Param("latencyMs") int latencyMs,@Param("usageJson") String usageJson,
                                 @Param("now") OffsetDateTime now);

    @Update("UPDATE business.business_task SET stage_code='ASSET_INGESTION_PENDING',error_code=NULL,"
            + "updated_at=#{now} WHERE id=#{taskId} AND status='RUNNING'")
    int markTaskAssetIngestionPending(@Param("taskId") long taskId,@Param("now") OffsetDateTime now);

    @org.apache.ibatis.annotations.Insert("INSERT INTO business.asset_ingestion_work(id,asset_id,task_id,"
            + "execution_id,execution_no,user_id,space_id,product_id,artifact_index,provider_code,"
            + "provider_model,provider_request_id,provider_task_id,provider_url,provider_url_expires_at,"
            + "expected_content_type,expected_size_bytes,expected_sha256,expected_width,expected_height,"
            + "expected_duration_seconds,expected_aspect_ratio,expected_resolution,ingestion_deadline_at,object_key,status,attempt,max_attempts,available_at,created_at,updated_at) VALUES(#{w.id},"
            + "#{w.assetId},#{w.taskId},#{w.executionId},#{w.executionNo},#{w.userId},#{w.spaceId},"
            + "#{w.productId},#{w.artifactIndex},#{w.providerCode},#{w.providerModel},#{w.providerRequestId},"
            + "#{w.providerTaskId},#{w.providerUrl},#{w.providerUrlExpiresAt},#{w.expectedContentType},"
            + "#{w.expectedSizeBytes},#{w.expectedSha256},#{w.expectedWidth},#{w.expectedHeight},"
            + "CAST(#{w.expectedDurationSeconds} AS numeric(12,3)),#{w.expectedAspectRatio},#{w.expectedResolution},#{w.ingestionDeadlineAt},#{w.objectKey},#{w.status},#{w.attempt},#{w.maxAttempts},#{w.availableAt},#{w.createdAt},"
            + "#{w.updatedAt}) ON CONFLICT (task_id,artifact_index) DO NOTHING")
    int insertAssetIngestionWork(@Param("w") AssetRecords.IngestionWork work);

    @Update("UPDATE business.task_execution SET status='SUCCEEDED',stage_code='COMPLETED',"
            + "finished_at=#{now},updated_at=#{now} WHERE id=#{executionId} AND status='RESULT_READY'")
    int completeResultReadyExecution(@Param("executionId") long executionId,
                                     @Param("now") OffsetDateTime now);

    @Update("UPDATE business.task_execution SET status='FAILED',stage_code='ASSET_INGESTION_FAILED',"
            + "error_code=#{code},finished_at=#{now},updated_at=#{now}"
            + " WHERE id=#{executionId} AND status='RESULT_READY'")
    int failResultReadyExecution(@Param("executionId") long executionId,@Param("code") String code,
                                 @Param("now") OffsetDateTime now);

    @Update("UPDATE business.business_task SET status='FAILED',stage_code='ASSET_INGESTION_FAILED',"
            + "error_code=#{code},finished_at=#{now},updated_at=#{now}"
            + " WHERE id=#{taskId} AND status='RUNNING'")
    int failAssetIngestionTask(@Param("taskId") long taskId,@Param("code") String code,
                               @Param("now") OffsetDateTime now);

    @Update("UPDATE business.business_task SET stage_code=#{stage},error_code=#{code},updated_at=#{now}"
            + " WHERE id=#{taskId} AND status='RUNNING'")
    int updateAssetIngestionStage(@Param("taskId") long taskId,@Param("stage") String stage,
                                  @Param("code") String code,@Param("now") OffsetDateTime now);

    @Update("UPDATE business.task_execution SET status='FAILED',submission_state=#{submissionState},error_code=#{code},finished_at=#{now},updated_at=#{now} WHERE task_id=#{taskId} AND execution_no=#{executionNo} AND execution_token_hash=#{tokenHash} AND status='RUNNING'")
    int failExecution(@Param("taskId") long taskId,@Param("executionNo") int executionNo,
                      @Param("tokenHash") String tokenHash,@Param("submissionState") String submissionState,
                      @Param("code") String code,@Param("now") OffsetDateTime now);

    @Update("UPDATE business.task_execution SET status='FAILED',stage_code='PROVIDER_RESULT_UNKNOWN',provider_code=#{provider},provider_model=#{model},provider_request_id=#{providerRequestId},provider_task_id=#{providerTaskId},submission_state='UNKNOWN',error_code=#{code},finished_at=#{now},updated_at=#{now} WHERE task_id=#{taskId} AND execution_no=#{executionNo} AND execution_token_hash=#{tokenHash} AND status='RUNNING' AND lease_expires_at>=#{now}")
    int recordUnknownExecution(@Param("taskId") long taskId,@Param("executionNo") int executionNo,
                               @Param("tokenHash") String tokenHash,@Param("provider") String provider,
                               @Param("model") String model,@Param("providerRequestId") UUID providerRequestId,
                               @Param("providerTaskId") String providerTaskId,@Param("code") String code,
                               @Param("now") OffsetDateTime now);

    @Update("UPDATE business.business_task SET status=#{status},stage_code=#{stage},error_code=#{code},finished_at=CASE WHEN #{status}='FAILED' THEN #{now} ELSE NULL END,updated_at=#{now} WHERE id=#{taskId} AND status='RUNNING'")
    int failOrRetryTask(@Param("taskId") long taskId,@Param("status") String status,
                        @Param("stage") String stage,@Param("code") String code,@Param("now") OffsetDateTime now);

    @Update("UPDATE business.task_execution SET status='SUCCEEDED',stage_code=#{stage},"
            + "provider_code=#{provider},provider_model=#{model},submission_state='CONFIRMED',"
            + "latency_ms=#{latencyMs},usage=CAST(#{usageJson} AS jsonb),finished_at=#{now},updated_at=#{now}"
            + " WHERE task_id=#{taskId} AND execution_no=#{executionNo}"
            + " AND execution_token_hash=#{tokenHash} AND status='RUNNING' AND lease_expires_at>=#{now}")
    int completeGeoExecution(@Param("taskId") long taskId,@Param("executionNo") int executionNo,
                             @Param("tokenHash") String tokenHash,@Param("stage") String stage,
                             @Param("provider") String provider,@Param("model") String model,
                             @Param("latencyMs") int latencyMs,
                             @Param("usageJson") String usageJson,@Param("now") OffsetDateTime now);

    @Update("UPDATE business.business_task SET status=#{status},stage_code=#{stage},"
            + "settled_points=#{settledPoints},result_summary=CAST(#{resultJson} AS jsonb),"
            + "error_code=NULL,finished_at=#{now},updated_at=#{now}"
            + " WHERE id=#{taskId} AND task_type='GEO' AND status='RUNNING'")
    int completeGeoTask(@Param("taskId") long taskId,@Param("status") String status,
                        @Param("stage") String stage,@Param("settledPoints") int settledPoints,
                        @Param("resultJson") String resultJson,@Param("now") OffsetDateTime now);

    @Update("UPDATE business.business_task SET stage_code='DIAGNOSTIC_ANALYSIS_QUEUED',settled_points=#{settled},result_summary=CAST(#{result} AS jsonb),"
            +"error_code=NULL,finished_at=NULL,updated_at=#{now} WHERE id=#{task} AND task_type='GEO' AND operation_scope='GEO_DIAGNOSTIC_SUBMIT' AND status='RUNNING'")
    int handoffDiagnosticTask(@Param("task") long task,@Param("settled") int settled,@Param("result") String result,@Param("now") OffsetDateTime now);

    @Update("UPDATE business.point_reservation SET frozen_points=0,settled_points=#{settledPoints},"
            + "released_points=#{releasedPoints},status=#{status},updated_at=#{now}"
            + " WHERE task_id=#{taskId} AND status='FROZEN'"
            + " AND quoted_points=#{settledPoints}+#{releasedPoints}")
    int finalizeGeoReservation(@Param("taskId") long taskId,
                               @Param("settledPoints") int settledPoints,
                               @Param("releasedPoints") int releasedPoints,
                               @Param("status") String status,
                               @Param("now") OffsetDateTime now);
}
