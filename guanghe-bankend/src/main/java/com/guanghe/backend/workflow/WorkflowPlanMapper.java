package com.guanghe.backend.workflow;

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
public interface WorkflowPlanMapper {
    String PLAN_COLUMNS = "p.id,p.user_id AS userId,p.conversation_id AS conversationId,"
            + "p.source_run_id AS sourceRunId,p.space_id AS spaceId,p.product_id AS productId,"
            + "p.proposal_id AS proposalId,p.request_hash AS requestHash,p.goal_summary AS goalSummary,"
            + "p.status,p.version,p.step_count AS stepCount,p.total_quoted_points AS totalQuotedPoints,"
            + "p.max_steps AS maxSteps,p.max_replans AS maxReplans,p.max_model_calls AS maxModelCalls,"
            + "p.max_tool_calls AS maxToolCalls,p.max_provider_calls AS maxProviderCalls,"
            + "p.max_point_cost AS maxPointCost,p.deadline_at AS deadlineAt,"
            + "p.confirmation_expires_at AS confirmationExpiresAt,p.abandoned_at AS abandonedAt,"
            + "p.created_at AS createdAt,p.updated_at AS updatedAt,"
            + "p.authorized_context_snapshot::text AS authorizedContextSnapshotJson,"
            + "p.authorized_context_hash AS authorizedContextHash,"
            + "p.authorized_context_schema_version AS authorizedContextSchemaVersion,"
            + "p.authorized_context_data_gap AS authorizedContextDataGap";
    String STEP_COLUMNS = "s.id,s.plan_id AS planId,s.user_id AS userId,"
            + "s.conversation_id AS conversationId,s.step_key AS stepKey,s.ordinal,"
            + "s.step_type AS stepType,s.tool_name AS toolName,s.tool_version AS toolVersion,"
            + "s.risk_level AS riskLevel,s.depends_on::text AS dependsOnJson,"
            + "s.success_criteria::text AS successCriteriaJson,s.input_refs::text AS inputRefsJson,"
            + "s.output_ref AS outputRef,s.task_draft_id AS taskDraftId,s.task_type AS taskType,"
            + "s.pricing_version_id AS pricingVersionId,s.quoted_points AS quotedPoints,"
            + "s.status,s.created_at AS createdAt,s.task_id AS taskId,"
            + "(SELECT t.status FROM business.business_task t WHERE t.id=s.task_id) AS taskStatus,"
            + "s.task_result_ref AS taskResultRef";
    String CONFIRMATION_COLUMNS = "c.id,c.plan_id AS planId,c.user_id AS userId,"
            + "c.conversation_id AS conversationId,c.plan_version AS planVersion,"
            + "c.token_hash AS tokenHash,c.authorized_max_points AS authorizedMaxPoints,"
            + "c.status,c.expires_at AS expiresAt,"
            + "c.confirmation_idempotency_key AS confirmationIdempotencyKey,"
            + "c.request_hash AS requestHash,c.consumed_at AS consumedAt,"
            + "c.invalidated_at AS invalidatedAt,c.created_at AS createdAt";

    @Select("SELECT 1 FROM pg_advisory_xact_lock(#{userId})")
    long lockUser(@Param("userId") long userId);

    @Select("SELECT " + PLAN_COLUMNS + " FROM business.workflow_plan p"
            + " WHERE p.user_id=#{userId} AND p.proposal_id=#{proposalId}")
    Optional<WorkflowPlanRecords.Plan> findByProposal(@Param("userId") long userId,
                                                       @Param("proposalId") UUID proposalId);

    @Select("SELECT " + PLAN_COLUMNS + " FROM business.workflow_plan p"
            + " WHERE p.source_run_id=#{runId}")
    Optional<WorkflowPlanRecords.Plan> findByRun(@Param("runId") UUID runId);

    @Insert("INSERT INTO business.workflow_plan(id,user_id,conversation_id,source_run_id,space_id,"
            + "product_id,proposal_id,request_hash,goal_summary,status,version,step_count,"
            + "total_quoted_points,max_steps,max_replans,max_model_calls,max_tool_calls,"
            + "max_provider_calls,max_point_cost,deadline_at,confirmation_expires_at,abandoned_at,"
            + "created_at,updated_at,authorized_context_snapshot,authorized_context_hash,"
            + "authorized_context_schema_version,authorized_context_data_gap) VALUES(#{p.id},#{p.userId},#{p.conversationId},#{p.sourceRunId},"
            + "#{p.spaceId},#{p.productId},#{p.proposalId},#{p.requestHash},#{p.goalSummary},#{p.status},"
            + "#{p.version},#{p.stepCount},#{p.totalQuotedPoints},#{p.maxSteps},#{p.maxReplans},"
            + "#{p.maxModelCalls},#{p.maxToolCalls},#{p.maxProviderCalls},#{p.maxPointCost},"
            + "#{p.deadlineAt},#{p.confirmationExpiresAt},#{p.abandonedAt},#{p.createdAt},#{p.updatedAt},"
            + "CAST(#{p.authorizedContextSnapshotJson} AS jsonb),#{p.authorizedContextHash},"
            + "#{p.authorizedContextSchemaVersion},#{p.authorizedContextDataGap})")
    int insertPlan(@Param("p") WorkflowPlanRecords.Plan plan);

    @Insert("INSERT INTO business.workflow_plan_step(id,plan_id,user_id,conversation_id,step_key,"
            + "ordinal,step_type,tool_name,tool_version,risk_level,depends_on,success_criteria,"
            + "input_refs,output_ref,task_draft_id,task_type,pricing_version_id,quoted_points,status,"
            + "created_at) VALUES(#{s.id},#{s.planId},#{s.userId},#{s.conversationId},#{s.stepKey},"
            + "#{s.ordinal},#{s.stepType},#{s.toolName},#{s.toolVersion},#{s.riskLevel},"
            + "CAST(#{s.dependsOnJson} AS jsonb),CAST(#{s.successCriteriaJson} AS jsonb),"
            + "CAST(#{s.inputRefsJson} AS jsonb),#{s.outputRef},#{s.taskDraftId},#{s.taskType},"
            + "#{s.pricingVersionId},#{s.quotedPoints},#{s.status},#{s.createdAt})")
    int insertStep(@Param("s") WorkflowPlanRecords.Step step);

    @Insert("INSERT INTO business.workflow_confirmation(id,plan_id,user_id,conversation_id,"
            + "plan_version,token_hash,authorized_max_points,status,expires_at,consumed_at,"
            + "invalidated_at,created_at,confirmation_idempotency_key,request_hash) VALUES(#{c.id},#{c.planId},#{c.userId},#{c.conversationId},"
            + "#{c.planVersion},#{c.tokenHash},#{c.authorizedMaxPoints},#{c.status},#{c.expiresAt},"
            + "#{c.consumedAt},#{c.invalidatedAt},#{c.createdAt},#{c.confirmationIdempotencyKey},#{c.requestHash})")
    int insertConfirmation(@Param("c") WorkflowPlanRecords.Confirmation confirmation);

    @Insert("INSERT INTO business.workflow_event(id,plan_id,user_id,conversation_id,sequence_no,"
            + "event_type,from_status,to_status,stable_error_code,created_at) VALUES(#{e.id},#{e.planId},"
            + "#{e.userId},#{e.conversationId},#{e.sequenceNo},#{e.eventType},#{e.fromStatus},"
            + "#{e.toStatus},#{e.stableErrorCode},#{e.createdAt})")
    int insertEvent(@Param("e") WorkflowPlanRecords.Event event);

    @Select("<script>SELECT " + PLAN_COLUMNS + " FROM business.workflow_plan p WHERE p.user_id=#{userId}"
            + "<if test='status != null'> AND p.status=#{status}</if>"
            + " ORDER BY p.created_at DESC,p.id DESC LIMIT #{limit} OFFSET #{offset}</script>")
    List<WorkflowPlanRecords.Plan> list(@Param("userId") long userId,
                                        @Param("status") String status,
                                        @Param("offset") long offset,
                                        @Param("limit") int limit);

    @Select("<script>SELECT count(*) FROM business.workflow_plan p WHERE p.user_id=#{userId}"
            + "<if test='status != null'> AND p.status=#{status}</if></script>")
    long count(@Param("userId") long userId, @Param("status") String status);

    @Select("SELECT " + PLAN_COLUMNS + " FROM business.workflow_plan p"
            + " WHERE p.id=#{planId} AND p.user_id=#{userId}")
    Optional<WorkflowPlanRecords.Plan> find(@Param("planId") long planId,
                                            @Param("userId") long userId);

    @Select("SELECT " + PLAN_COLUMNS + " FROM business.workflow_plan p"
            + " WHERE p.id=#{planId} AND p.user_id=#{userId} FOR UPDATE")
    Optional<WorkflowPlanRecords.Plan> lock(@Param("planId") long planId,
                                            @Param("userId") long userId);

    @Select("SELECT " + CONFIRMATION_COLUMNS + " FROM business.workflow_confirmation c"
            + " WHERE c.plan_id=#{planId} AND c.user_id=#{userId} FOR UPDATE")
    Optional<WorkflowPlanRecords.Confirmation> lockConfirmation(@Param("planId") long planId,
                                                                 @Param("userId") long userId);

    @Select("SELECT " + CONFIRMATION_COLUMNS + " FROM business.workflow_confirmation c"
            + " WHERE c.user_id=#{userId} AND c.confirmation_idempotency_key=#{key}")
    Optional<WorkflowPlanRecords.Confirmation> findConfirmationByIdempotency(
            @Param("userId") long userId, @Param("key") UUID key);

    @Select("SELECT " + STEP_COLUMNS + " FROM business.workflow_plan_step s"
            + " WHERE s.plan_id=#{planId} AND s.user_id=#{userId} ORDER BY s.ordinal")
    List<WorkflowPlanRecords.Step> steps(@Param("planId") long planId,
                                         @Param("userId") long userId);

    @Select("SELECT id,plan_id AS planId,user_id AS userId,conversation_id AS conversationId,"
            + "sequence_no AS sequenceNo,event_type AS eventType,from_status AS fromStatus,"
            + "to_status AS toStatus,stable_error_code AS stableErrorCode,created_at AS createdAt"
            + " FROM business.workflow_event WHERE plan_id=#{planId} AND user_id=#{userId}"
            + " ORDER BY sequence_no")
    List<WorkflowPlanRecords.Event> events(@Param("planId") long planId,
                                           @Param("userId") long userId);

    @Update("UPDATE business.workflow_plan SET status='ABANDONED',version=version+1,"
            + "abandoned_at=#{now},updated_at=#{now} WHERE id=#{planId} AND user_id=#{userId}"
            + " AND version=#{version} AND status IN ('DRAFT','WAITING_CONFIRMATION')")
    int abandon(@Param("planId") long planId, @Param("userId") long userId,
                @Param("version") int version, @Param("now") OffsetDateTime now);

    @Update("UPDATE business.workflow_confirmation SET status='INVALIDATED',invalidated_at=#{now}"
            + " WHERE plan_id=#{planId} AND user_id=#{userId} AND status='AVAILABLE'")
    int invalidateConfirmation(@Param("planId") long planId, @Param("userId") long userId,
                               @Param("now") OffsetDateTime now);

    @Update("UPDATE business.workflow_confirmation SET status='CONSUMED',consumed_at=#{now},"
            + "authorized_max_points=#{authorizedMaxPoints},confirmation_idempotency_key=#{key},"
            + "request_hash=#{requestHash} WHERE plan_id=#{planId} AND user_id=#{userId}"
            + " AND plan_version=#{version} AND status='AVAILABLE'")
    int consumeConfirmation(@Param("planId") long planId, @Param("userId") long userId,
                            @Param("version") int version, @Param("authorizedMaxPoints") int authorizedMaxPoints,
                            @Param("key") UUID key, @Param("requestHash") String requestHash,
                            @Param("now") OffsetDateTime now);

    @Update("UPDATE business.workflow_plan SET status='CONFIRMED',updated_at=#{now}"
            + " WHERE id=#{planId} AND user_id=#{userId} AND version=#{version}"
            + " AND status='WAITING_CONFIRMATION'")
    int confirm(@Param("planId") long planId, @Param("userId") long userId,
                @Param("version") int version, @Param("now") OffsetDateTime now);

    @Update("UPDATE business.workflow_plan_step s SET authorized_input_hash=d.input_hash,"
            + "authorized_draft_expires_at=d.expires_at FROM business.task_draft d"
            + " WHERE s.plan_id=#{planId} AND s.user_id=#{userId} AND s.step_type='PREPARE'"
            + " AND s.task_draft_id=d.id AND d.user_id=s.user_id AND d.conversation_id=s.conversation_id"
            + " AND s.authorized_input_hash IS NULL AND s.authorized_draft_expires_at IS NULL")
    int authorizeWorkflowDrafts(@Param("planId") long planId, @Param("userId") long userId);
}
