package com.guanghe.backend.article;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.apache.ibatis.annotations.*;

@Mapper
public interface ArticleFullMapper {
    record ConfirmedFactSource(long assistantMessageId,long confirmationMessageId,String sourceText,
            String confirmationText,OffsetDateTime sourceCreatedAt,OffsetDateTime confirmedAt) { }

    // Facts are confirmed by a persisted USER message, even when its subsequent model run failed.
    // The immediately preceding conversational message must be a completed same-project source.
    @Select("""
        SELECT a.id AS assistantMessageId,u.id AS confirmationMessageId,
               a.content->>'text' AS sourceText,u.content->>'text' AS confirmationText,
               a.created_at AS sourceCreatedAt,u.created_at AS confirmedAt
        FROM business.conversation_message u
          JOIN business.agent_run ur ON ur.id=u.agent_run_id AND ur.request_message_id=u.id
          JOIN LATERAL (
            SELECT m.* FROM business.conversation_message m
            WHERE m.user_id=#{user} AND m.conversation_id=#{conversation}
              AND m.sequence_no<u.sequence_no AND m.role IN ('USER','ASSISTANT')
            ORDER BY m.sequence_no DESC LIMIT 1
          ) a ON a.role='ASSISTANT' AND a.status='COMPLETED'
          JOIN business.agent_run ar ON ar.id=a.agent_run_id AND ar.response_message_id=a.id
        WHERE u.user_id=#{user} AND u.conversation_id=#{conversation}
          AND u.role='USER' AND u.status='COMPLETED' AND u.sequence_no<=#{sequence}
          AND u.created_at<=#{requestedAt} AND a.created_at<=u.created_at
          AND (u.reply_to_message_id IS NULL OR u.reply_to_message_id=a.id)
          AND ur.user_id=#{user} AND ur.conversation_id=#{conversation}
          AND ar.user_id=#{user} AND ar.conversation_id=#{conversation}
          AND ar.status='COMPLETED' AND ar.error_code IS NULL AND ar.stop_requested_at IS NULL
          AND ar.completed_at<=u.created_at
          AND (ur.selected_space_id=#{project} OR (ur.selected_space_id IS NULL
            AND ur.context_snapshot#>>'{agentIdentity,projectId}'=CAST(#{project} AS text)))
          AND (ar.selected_space_id=#{project} OR (ar.selected_space_id IS NULL
            AND ar.context_snapshot#>>'{agentIdentity,projectId}'=CAST(#{project} AS text)))
          AND char_length(coalesce(u.content->>'text','')) BETWEEN 1 AND 500
          AND char_length(coalesce(a.content->>'text','')) BETWEEN 1 AND 16000
          AND u.content->>'text' ~ '(信息|资料|消息|事实|内容|这些|以上|上面).{0,20}(都(是)?准确|都(是)?正确|都属实|全部准确|全部属实|均属实|准确无误)'
          AND NOT EXISTS (SELECT 1 FROM business.agent_memory_entry memory
            WHERE memory.user_id=#{user} AND (memory.project_id IS NULL OR memory.project_id=#{project})
              AND memory.history_cutoff_at>a.created_at)
        ORDER BY u.sequence_no DESC LIMIT 20
        """)
    List<ConfirmedFactSource> confirmedFactSources(@Param("user") long user,@Param("conversation") long conversation,
            @Param("project") long project,@Param("sequence") long sequence,@Param("requestedAt") OffsetDateTime requestedAt);

    record WritingMessage(long messageId,String role,String text,int totalCharacters) { }
    record LatestArticle(long preparationId,long articleId,String title,String bodyHtml,String bodyText,
            int version,String articleStatus,String preparationStatus,String workflowStage,OffsetDateTime updatedAt,
            boolean bodyPredatesMemoryBoundary) { }

    // Data references only. Neither prior assistant prose nor a stored article grants execution authority.
    @Select("""
        SELECT m.id AS messageId,m.role,left(m.content->>'text',800) AS text,
               char_length(m.content->>'text') AS totalCharacters
        FROM business.conversation_message m JOIN business.agent_run r ON r.id=m.agent_run_id
        WHERE m.user_id=#{user} AND m.conversation_id=#{conversation}
          AND m.sequence_no < #{sequence} AND m.id <> #{currentMessage}
          AND m.role IN ('USER','ASSISTANT') AND m.status='COMPLETED'
          AND m.id IN (r.request_message_id,r.response_message_id)
          AND r.user_id=#{user} AND r.conversation_id=#{conversation}
          AND r.status='COMPLETED' AND r.error_code IS NULL AND r.stop_requested_at IS NULL
          AND (r.selected_space_id=#{project} OR (r.selected_space_id IS NULL
               AND r.context_snapshot#>>'{agentIdentity,projectId}'=CAST(#{project} AS text)))
          AND r.completed_at <= #{requestedAt}
          AND r.completed_at > CAST(#{requestedAt} AS timestamptz) - INTERVAL '24 hours'
          AND length(coalesce(m.content->>'text','')) > 0
          AND NOT EXISTS (SELECT 1 FROM business.agent_memory_entry memory
              WHERE memory.user_id=#{user} AND (memory.project_id IS NULL OR memory.project_id=#{project})
                AND memory.history_cutoff_at > m.created_at)
        ORDER BY m.sequence_no DESC LIMIT 4
        """)
    List<WritingMessage> writingMessages(@Param("user") long user,@Param("conversation") long conversation,
            @Param("project") long project,@Param("sequence") long sequence,@Param("currentMessage") long currentMessage,
            @Param("requestedAt") OffsetDateTime requestedAt);

    @Select("""
        SELECT p.plan_id AS preparationId,a.id AS articleId,a.title,a.body_html AS bodyHtml,
               a.body_text AS bodyText,a.version,a.status AS articleStatus,p.status AS preparationStatus,
               a.workflow_stage AS workflowStage,a.updated_at AS updatedAt,
               EXISTS (SELECT 1 FROM business.agent_memory_entry memory
                 WHERE memory.user_id=#{user} AND (memory.project_id IS NULL OR memory.project_id=#{project})
                   AND memory.history_cutoff_at > p.created_at) AS bodyPredatesMemoryBoundary
        FROM business.article_full_preparation p JOIN business.article a ON a.id=p.article_id AND a.user_id=p.user_id
          JOIN business.conversation_message m ON m.id=p.request_message_id AND m.agent_run_id=p.source_run_id
        WHERE p.user_id=#{user} AND p.conversation_id=#{conversation} AND a.space_id=#{project}
          AND m.user_id=#{user} AND m.conversation_id=#{conversation} AND m.role='USER'
          AND m.sequence_no < #{sequence} AND m.id <> #{currentMessage} AND m.created_at <= #{requestedAt}
          AND a.status <> 'ARCHIVED'
        ORDER BY m.sequence_no DESC,p.plan_id DESC LIMIT 1
        """)
    Optional<LatestArticle> latestArticle(@Param("user") long user,@Param("conversation") long conversation,
            @Param("project") long project,@Param("sequence") long sequence,@Param("currentMessage") long currentMessage,
            @Param("requestedAt") OffsetDateTime requestedAt);

    record WritingOffer(long offerMessageId,long sourceMessageId,UUID sourceRunId) { }
    // Only the latest preceding conversational message is eligible. A newer USER or failed
    // response blocks continuation; summaries and background task notices are not offers.
    @Select("""
        WITH previous AS (
          SELECT * FROM business.conversation_message
          WHERE user_id=#{user} AND conversation_id=#{conversation} AND sequence_no < #{sequence}
            AND role IN ('USER','ASSISTANT')
          ORDER BY sequence_no DESC LIMIT 1
        )
        SELECT p.id AS offerMessageId,u.id AS sourceMessageId,r.id AS sourceRunId
        FROM previous p JOIN business.agent_run r ON r.id=p.agent_run_id AND r.response_message_id=p.id
          JOIN business.conversation_message u ON u.id=r.request_message_id
        WHERE p.role='ASSISTANT' AND p.status='COMPLETED'
          AND u.role='USER' AND u.status='COMPLETED' AND u.agent_run_id=r.id
          AND u.user_id=#{user} AND u.conversation_id=#{conversation}
          AND r.user_id=#{user} AND r.conversation_id=#{conversation}
          AND (r.selected_space_id=#{project} OR (r.selected_space_id IS NULL
               AND r.context_snapshot#>>'{agentIdentity,projectId}'=CAST(#{project} AS text)))
          AND r.status='COMPLETED' AND r.error_code IS NULL AND r.stop_requested_at IS NULL
          AND r.completed_at <= #{requestedAt}
          AND r.completed_at > CAST(#{requestedAt} AS timestamptz) - INTERVAL '24 hours'
          AND NOT EXISTS (SELECT 1 FROM business.conversation_message m
              WHERE m.user_id=#{user} AND m.conversation_id=#{conversation} AND m.role='USER'
                AND m.sequence_no > u.sequence_no AND m.sequence_no < #{sequence})
          AND NOT EXISTS (SELECT 1 FROM business.task_draft d WHERE d.agent_run_id=r.id)
          AND NOT EXISTS (SELECT 1 FROM business.article_full_preparation a WHERE a.source_run_id=r.id)
          AND NOT EXISTS (SELECT 1 FROM business.agent_tool_action a WHERE a.run_id=r.id
              AND a.action IN ('geo.monitor','article.generate','image.generate','video.generate'))
          AND NOT EXISTS (SELECT 1 FROM business.agent_memory_entry memory
              WHERE memory.user_id=#{user} AND (memory.project_id IS NULL OR memory.project_id=#{project})
                AND memory.history_cutoff_at > u.created_at)
        """)
    Optional<WritingOffer> writingOffer(@Param("user") long user,@Param("conversation") long conversation,
            @Param("project") long project,@Param("sequence") long sequence,@Param("requestedAt") OffsetDateTime requestedAt);

    @Select("SELECT "+PREP+" FROM business.article_full_preparation WHERE user_id=#{user} AND conversation_id=#{conversation} "
            + "AND snapshot#>>'{brief,authorizedContext,articleContinuation,offerMessageId}'=#{offer} ORDER BY created_at LIMIT 1")
    Optional<ArticleFullRecords.Preparation> byWritingOffer(@Param("user") long user,@Param("conversation") long conversation,@Param("offer") String offer);
    String GRANT="id,user_id AS userId,conversation_id AS conversationId,installation_id AS installationId,agent_key AS agentKey,agent_version AS agentVersion,project_id AS projectId,space_id AS spaceId,product_id AS productId,version,status,max_points AS maxPoints,expires_at AS expiresAt,created_at AS createdAt,updated_at AS updatedAt,execution_source AS executionSource,source_run_id AS sourceRunId";
    String PREP="plan_id AS planId,user_id AS userId,conversation_id AS conversationId,grant_id AS grantId,grant_version AS grantVersion,article_id AS articleId,expected_article_version AS expectedArticleVersion,source_run_id AS sourceRunId,request_message_id AS requestMessageId,idempotency_key AS idempotencyKey,request_hash AS requestHash,snapshot::text AS snapshotJson,brief_hash AS briefHash,max_calls AS maxCalls,max_input_tokens AS maxInputTokens,max_output_tokens AS maxOutputTokens,deadline_at AS deadlineAt,status,error_code AS errorCode,created_at AS createdAt,updated_at AS updatedAt";
    @Select("SELECT "+GRANT+" FROM business.article_execution_grant g"
            + " WHERE g.user_id=#{user} AND g.conversation_id=#{conversation}"
            + " AND EXISTS (SELECT 1 FROM business.business_space s WHERE s.id=g.space_id"
            + " AND s.user_id=g.user_id AND s.storage_kind='PROJECT')"
            + " ORDER BY g.created_at DESC LIMIT 100")
    List<ArticleFullRecords.Grant> grants(@Param("user") long user,@Param("conversation") long conversation);
    @Select("SELECT "+GRANT+" FROM business.article_execution_grant g"
            + " WHERE g.id=#{id} AND g.user_id=#{user} AND g.conversation_id=#{conversation}"
            + " AND EXISTS (SELECT 1 FROM business.business_space s WHERE s.id=g.space_id"
            + " AND s.user_id=g.user_id AND s.storage_kind='PROJECT') FOR UPDATE OF g")
    Optional<ArticleFullRecords.Grant> lockGrant(@Param("id") long id,@Param("user") long user,@Param("conversation") long conversation);
    @Insert("INSERT INTO business.article_execution_grant(id,user_id,conversation_id,installation_id,agent_key,agent_version,project_id,space_id,product_id,version,status,max_points,expires_at,created_at,updated_at,execution_source,source_run_id) VALUES(#{g.id},#{g.userId},#{g.conversationId},#{g.installationId},#{g.agentKey},#{g.agentVersion},#{g.projectId},#{g.spaceId},#{g.productId},#{g.version},#{g.status},#{g.maxPoints},#{g.expiresAt},#{g.createdAt},#{g.updatedAt},#{g.executionSource},#{g.sourceRunId})")
    int insertGrant(@Param("g") ArticleFullRecords.Grant grant);
    @Update("UPDATE business.article_execution_grant g SET status=#{status},version=version+1,updated_at=#{now}"
            + " WHERE g.id=#{id} AND g.user_id=#{user} AND g.version=#{version}"
            + " AND (g.status='ACTIVE' OR (g.status='USED' AND #{status}='REVOKED'))"
            + " AND EXISTS (SELECT 1 FROM business.business_space s WHERE s.id=g.space_id"
            + " AND s.user_id=g.user_id AND s.storage_kind='PROJECT')")
    int closeGrant(@Param("id") long id,@Param("user") long user,@Param("version") int version,@Param("status") String status,@Param("now") OffsetDateTime now);
    @Select("SELECT "+PREP+" FROM business.article_full_preparation WHERE user_id=#{user} AND idempotency_key=#{key}")
    Optional<ArticleFullRecords.Preparation> replay(@Param("user") long user,@Param("key") UUID key);
    @Select("SELECT "+PREP+" FROM business.article_full_preparation WHERE source_run_id=#{run} AND user_id=#{user}")
    Optional<ArticleFullRecords.Preparation> bySourceRun(@Param("run") UUID run,@Param("user") long user);
    @Select("SELECT "+PREP+" FROM business.article_full_preparation WHERE plan_id=#{id} AND user_id=#{user}")
    Optional<ArticleFullRecords.Preparation> get(@Param("id") long id,@Param("user") long user);
    @Select("SELECT "+PREP+" FROM business.article_full_preparation WHERE plan_id=#{id} FOR UPDATE")
    Optional<ArticleFullRecords.Preparation> lock(@Param("id") long id);
    @Select("SELECT p.plan_id FROM business.article_full_preparation p WHERE p.status='RUNNING' "
            + "AND NOT EXISTS (SELECT 1 FROM business.workflow_plan_step s JOIN business.business_task t ON t.id=s.task_id "
            + "WHERE s.plan_id=p.plan_id AND s.status='WAITING' "
            + "AND t.status NOT IN ('SUCCEEDED','PARTIAL','FAILED','SKIPPED','SYSTEM_TERMINATED')) "
            + "ORDER BY p.updated_at,p.plan_id LIMIT 20")
    List<Long> pending();
    @Select("SELECT id,plan_id AS planId,user_id AS userId,authorized_points AS authorizedPoints,frozen_points AS frozenPoints,allocated_points AS allocatedPoints,settled_points AS settledPoints,released_points AS releasedPoints,status,created_at AS createdAt,updated_at AS updatedAt FROM business.workflow_point_reservation WHERE plan_id=#{plan} AND user_id=#{user}")
    Optional<com.guanghe.backend.workflow.WorkflowExecutionRecords.Reservation> reservation(@Param("plan") long plan,@Param("user") long user);
    @Insert("INSERT INTO business.article_full_preparation(plan_id,user_id,conversation_id,grant_id,grant_version,article_id,expected_article_version,source_run_id,request_message_id,idempotency_key,request_hash,snapshot,brief_hash,max_calls,max_input_tokens,max_output_tokens,deadline_at,status,created_at,updated_at) VALUES(#{p.planId},#{p.userId},#{p.conversationId},#{p.grantId},#{p.grantVersion},#{p.articleId},#{p.expectedArticleVersion},#{p.sourceRunId},#{p.requestMessageId},#{p.idempotencyKey},#{p.requestHash},CAST(#{p.snapshotJson} AS jsonb),#{p.briefHash},#{p.maxCalls},#{p.maxInputTokens},#{p.maxOutputTokens},#{p.deadlineAt},#{p.status},#{p.createdAt},#{p.updatedAt})")
    int insert(@Param("p") ArticleFullRecords.Preparation preparation);
    @Update("UPDATE business.article_full_preparation SET expected_article_version=#{version},updated_at=#{now} WHERE plan_id=#{id} AND status='RUNNING'")
    int version(@Param("id") long id,@Param("version") int version,@Param("now") OffsetDateTime now);
    @Update("UPDATE business.article_full_preparation SET status=#{status},error_code=#{error},updated_at=#{now} WHERE plan_id=#{id} AND status='RUNNING'")
    int finish(@Param("id") long id,@Param("status") String status,@Param("error") String error,@Param("now") OffsetDateTime now);
    @Update("UPDATE business.workflow_plan SET status='RUNNING',start_idempotency_key=#{key},start_request_hash=#{hash},execution_started_at=#{now},updated_at=#{now} WHERE id=#{id} AND status='CONFIRMED'")
    int startPlan(@Param("id") long id,@Param("key") UUID key,@Param("hash") String hash,@Param("now") OffsetDateTime now);
    @Update("UPDATE business.workflow_plan_step SET deferred_article_mode=#{mode} WHERE id=#{id} AND task_type='ARTICLE'")
    int mode(@Param("id") long id,@Param("mode") String mode);
    @Insert("INSERT INTO business.workflow_plan_step(id,plan_id,user_id,conversation_id,step_key,ordinal,step_type,tool_name,tool_version,risk_level,depends_on,success_criteria,input_refs,task_type,pricing_version_id,quoted_points,status,created_at,deferred_article_mode) VALUES(#{s.id},#{s.planId},#{s.userId},#{s.conversationId},#{s.stepKey},#{s.ordinal},'PREPARE','task_draft.propose','1','PREPARE',CAST(#{s.dependsOnJson} AS jsonb),CAST(#{s.successCriteriaJson} AS jsonb),'[]'::jsonb,'ARTICLE',#{s.pricingVersionId},#{s.quotedPoints},'PENDING',#{s.createdAt},#{mode})")
    int insertDeferred(@Param("s") com.guanghe.backend.workflow.WorkflowPlanRecords.Step step,@Param("mode") String mode);
    @Update("UPDATE business.workflow_plan_step SET task_draft_id=#{draft},authorized_input_hash=#{hash},authorized_draft_expires_at=#{expires} WHERE id=#{id} AND status IN ('PENDING','READY') AND task_draft_id IS NULL AND deferred_article_mode=#{mode}")
    int materialize(@Param("id") long id,@Param("draft") long draft,@Param("hash") String hash,@Param("expires") OffsetDateTime expires,@Param("mode") String mode);
    @Insert("INSERT INTO business.article_automatic_adoption(id,plan_id,article_id,user_id,mode,source_task_id,artifact_version,source,adopted_at) VALUES(#{id},#{plan},#{article},#{user},#{mode},#{task},#{version},'AUTO_POLICY',#{now})")
    int adoption(@Param("id") long id,@Param("plan") long plan,@Param("article") long article,@Param("user") long user,@Param("mode") String mode,@Param("task") long task,@Param("version") int version,@Param("now") OffsetDateTime now);
    @Select("SELECT count(*) FROM business.article_automatic_adoption WHERE plan_id=#{plan} AND mode=#{mode}")
    int adopted(@Param("plan") long plan,@Param("mode") String mode);
    @Select("SELECT id FROM business.account WHERE id=#{user} AND role='USER' AND status='ACTIVE' AND credential_state='ACTIVE' FOR SHARE")
    Long runnable(long user);
    @Select("SELECT id FROM business.account WHERE id=#{user} AND role='USER' AND status='ACTIVE' AND credential_state='ACTIVE' FOR UPDATE")
    Long lockNativeAccount(@Param("user") long user);
}
