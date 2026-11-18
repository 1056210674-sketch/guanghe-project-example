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
public interface WorkflowExecutionMapper {
    String PLAN = "p.id,p.user_id AS userId,p.conversation_id AS conversationId,p.space_id AS spaceId,"
            + "p.product_id AS productId,p.version,p.status,"
            + "p.total_quoted_points AS totalQuotedPoints,p.max_point_cost AS maxPointCost,"
            + "p.max_steps AS maxSteps,p.max_replans AS maxReplans,p.max_model_calls AS maxModelCalls,"
            + "p.max_tool_calls AS maxToolCalls,p.max_provider_calls AS maxProviderCalls,"
            + "p.deadline_at AS deadlineAt,p.start_idempotency_key AS startIdempotencyKey,"
            + "p.start_request_hash AS startRequestHash,p.execution_started_at AS executionStartedAt,"
            + "p.finished_at AS finishedAt,p.last_error_code AS lastErrorCode,"
            + "p.lease_recovery_count AS leaseRecoveryCount,p.goal_summary AS goalSummary,"
            + "p.authorized_context_snapshot::text AS authorizedContextSnapshotJson,"
            + "p.authorized_context_hash AS authorizedContextHash,"
            + "p.authorized_context_schema_version AS authorizedContextSchemaVersion,"
            + "p.authorized_context_data_gap AS authorizedContextDataGap";
    String STEP = "s.id,s.plan_id AS planId,s.user_id AS userId,s.conversation_id AS conversationId,"
            + "s.step_key AS stepKey,s.ordinal,s.step_type AS stepType,s.tool_name AS toolName,"
            + "s.tool_version AS toolVersion,s.risk_level AS riskLevel,s.depends_on::text AS dependsOnJson,"
            + "s.success_criteria::text AS successCriteriaJson,s.input_refs::text AS inputRefsJson,"
            + "s.output_ref AS outputRef,s.task_draft_id AS taskDraftId,s.task_type AS taskType,"
            + "s.pricing_version_id AS pricingVersionId,s.quoted_points AS quotedPoints,s.status,"
            + "s.attempt,s.task_id AS taskId,s.last_error_code AS lastErrorCode,"
            + "s.started_at AS startedAt,s.finished_at AS finishedAt,"
            + "s.authorized_input_hash AS authorizedInputHash,"
            + "s.authorized_draft_expires_at AS authorizedDraftExpiresAt,"
            + "s.activation_event_id AS activationEventId,"
            + "s.activation_request_hash AS activationRequestHash,s.task_result_ref AS taskResultRef,"
            + "(SELECT t.status FROM business.business_task t WHERE t.id=s.task_id) AS taskStatus";
    String EXECUTION = "x.id,x.plan_id AS planId,x.user_id AS userId,x.conversation_id AS conversationId,"
            + "x.plan_version AS planVersion,x.execution_no AS executionNo,x.claim_event_id AS claimEventId,"
            + "x.status,x.consumer_name AS consumerName,x.worker_id AS workerId,x.bundle_version AS bundleVersion,"
            + "x.execution_token_hash AS executionTokenHash,x.context_snapshot::text AS contextSnapshotJson,"
            + "x.lease_expires_at AS leaseExpiresAt,x.last_heartbeat_at AS lastHeartbeatAt,"
            + "x.replan_count AS replanCount,x.stable_error_code AS stableErrorCode,"
            + "x.started_at AS startedAt,x.finished_at AS finishedAt,x.created_at AS createdAt,x.updated_at AS updatedAt";
    String OBSERVATION = "o.id,o.plan_id AS planId,o.step_id AS stepId,o.execution_id AS executionId,"
            + "o.user_id AS userId,o.conversation_id AS conversationId,o.event_id AS eventId,"
            + "o.tool_call_id AS toolCallId,o.request_hash AS requestHash,o.step_attempt AS stepAttempt,"
            + "o.status,o.result_type AS resultType,o.result_ref AS resultRef,o.summary,o.facts::text AS factsJson,"
            + "o.stable_error_code AS stableErrorCode,o.retryable,o.completed_at AS completedAt,o.created_at AS createdAt";

    @Select("SELECT " + PLAN + " FROM business.workflow_plan p WHERE p.id=#{planId} AND p.user_id=#{userId} FOR UPDATE")
    Optional<WorkflowExecutionRecords.PlanState> lockPlan(@Param("planId") long planId,
                                                           @Param("userId") long userId);

    @Select("SELECT " + PLAN + " FROM business.workflow_plan p WHERE p.id=#{planId} FOR UPDATE")
    Optional<WorkflowExecutionRecords.PlanState> lockPlanInternal(@Param("planId") long planId);

    @Select("SELECT " + PLAN + " FROM business.workflow_plan p WHERE p.id=#{planId}")
    Optional<WorkflowExecutionRecords.PlanState> findPlanInternal(@Param("planId") long planId);

    @Select("SELECT " + PLAN + " FROM business.workflow_plan p WHERE p.user_id=#{userId} AND p.start_idempotency_key=#{key}")
    Optional<WorkflowExecutionRecords.PlanState> findByStartKey(@Param("userId") long userId,
                                                                 @Param("key") UUID key);

    @Update("UPDATE business.workflow_plan SET status='QUEUED',start_idempotency_key=#{key},"
            + "start_request_hash=#{hash},execution_started_at=#{now},last_error_code=NULL,updated_at=#{now}"
            + " WHERE id=#{planId} AND user_id=#{userId} AND version=#{version} AND status='CONFIRMED'"
            + " AND start_idempotency_key IS NULL")
    int queuePlan(@Param("planId") long planId, @Param("userId") long userId,
                  @Param("version") int version, @Param("key") UUID key,
                  @Param("hash") String hash, @Param("now") OffsetDateTime now);

    @Select("SELECT " + STEP + " FROM business.workflow_plan_step s WHERE s.plan_id=#{planId}"
            + " AND s.user_id=#{userId} ORDER BY s.ordinal")
    List<WorkflowExecutionRecords.StepState> steps(@Param("planId") long planId,
                                                    @Param("userId") long userId);

    @Select("SELECT " + STEP + " FROM business.workflow_plan_step s WHERE s.plan_id=#{planId}"
            + " AND s.step_key=#{stepKey} FOR UPDATE")
    Optional<WorkflowExecutionRecords.StepState> lockStep(@Param("planId") long planId,
                                                           @Param("stepKey") String stepKey);

    @Select("SELECT " + STEP + " FROM business.workflow_plan_step s WHERE s.task_id=#{taskId} FOR UPDATE")
    Optional<WorkflowExecutionRecords.StepState> lockStepByTask(@Param("taskId") long taskId);

    @Select("SELECT count(*) FROM jsonb_array_elements_text((SELECT depends_on FROM business.workflow_plan_step"
            + " WHERE id=#{stepId} AND plan_id=#{planId})) dependency"
            + " WHERE NOT EXISTS (SELECT 1 FROM business.workflow_plan_step prerequisite"
            + " WHERE prerequisite.plan_id=#{planId} AND prerequisite.step_key=dependency.value"
            + " AND prerequisite.status IN ('SUCCEEDED','SKIPPED'))")
    int incompleteDependencies(@Param("planId") long planId, @Param("stepId") long stepId);

    @Select("SELECT user_id AS userId,available_points AS availablePoints,frozen_points AS frozenPoints,"
            + "version,created_at AS createdAt,updated_at AS updatedAt FROM business.point_account"
            + " WHERE user_id=#{userId} FOR UPDATE")
    Optional<WorkflowExecutionRecords.PointAccount> lockPointAccount(@Param("userId") long userId);

    @Update("UPDATE business.point_account SET available_points=available_points-#{points},"
            + "frozen_points=frozen_points+#{points},version=version+1,updated_at=#{now}"
            + " WHERE user_id=#{userId} AND available_points>=#{points}")
    int freezePoints(@Param("userId") long userId, @Param("points") int points,
                     @Param("now") OffsetDateTime now);

    @Update("UPDATE business.point_account SET available_points=available_points+#{points},"
            + "frozen_points=frozen_points-#{points},version=version+1,updated_at=#{now}"
            + " WHERE user_id=#{userId} AND frozen_points>=#{points}")
    int releasePoints(@Param("userId") long userId, @Param("points") int points,
                      @Param("now") OffsetDateTime now);

    @Insert("INSERT INTO business.workflow_point_reservation(id,plan_id,user_id,authorized_points,"
            + "frozen_points,allocated_points,settled_points,released_points,status,created_at,updated_at)"
            + " VALUES(#{id},#{planId},#{userId},#{points},#{points},0,0,0,'FROZEN',#{now},#{now})")
    int insertReservation(@Param("id") long id, @Param("planId") long planId,
                          @Param("userId") long userId, @Param("points") int points,
                          @Param("now") OffsetDateTime now);

    @Select("SELECT id,plan_id AS planId,user_id AS userId,authorized_points AS authorizedPoints,"
            + "frozen_points AS frozenPoints,allocated_points AS allocatedPoints,settled_points AS settledPoints,"
            + "released_points AS releasedPoints,status,created_at AS createdAt,updated_at AS updatedAt"
            + " FROM business.workflow_point_reservation WHERE plan_id=#{planId} FOR UPDATE")
    Optional<WorkflowExecutionRecords.Reservation> lockReservation(@Param("planId") long planId);

    @Update("UPDATE business.workflow_point_reservation SET frozen_points=0,"
            + "released_points=released_points+#{points},"
            + "status=CASE WHEN allocated_points=0 AND settled_points=0 THEN 'RELEASED' ELSE 'PARTIAL' END,"
            + "updated_at=#{now} WHERE plan_id=#{planId} AND status IN ('FROZEN','PARTIAL')"
            + " AND frozen_points=#{points}")
    int releaseReservation(@Param("planId") long planId, @Param("points") int points,
                           @Param("now") OffsetDateTime now);

    @Update("UPDATE business.workflow_point_reservation SET frozen_points=frozen_points-#{points},"
            + "allocated_points=allocated_points+#{points},status='PARTIAL',updated_at=#{now}"
            + " WHERE plan_id=#{planId} AND user_id=#{userId} AND frozen_points>=#{points}")
    int allocateReservation(@Param("planId") long planId, @Param("userId") long userId,
                            @Param("points") int points, @Param("now") OffsetDateTime now);

    @Update("UPDATE business.workflow_point_reservation SET allocated_points=allocated_points-#{points},"
            + "settled_points=settled_points+#{settled},released_points=released_points+#{released},"
            + "status=CASE WHEN frozen_points=0 AND allocated_points=#{points} THEN"
            + " CASE WHEN released_points+#{released}=0 THEN 'SETTLED'"
            + " WHEN settled_points+#{settled}=0 THEN 'RELEASED' ELSE 'PARTIAL' END"
            + " ELSE 'PARTIAL' END,updated_at=#{now}"
            + " WHERE plan_id=#{planId} AND user_id=#{userId} AND allocated_points>=#{points}"
            + " AND #{settled}>=0 AND #{released}>=0 AND #{settled}+#{released}=#{points}")
    int finishAllocation(@Param("planId") long planId, @Param("userId") long userId,
                         @Param("points") int points, @Param("settled") int settled,
                         @Param("released") int released,
                         @Param("now") OffsetDateTime now);

    @Insert("INSERT INTO business.point_ledger(id,user_id,available_delta,frozen_delta,available_after,"
            + "frozen_after,entry_type,business_type,business_id,idempotency_key,reason,created_at)"
            + " VALUES(#{id},#{userId},#{availableDelta},#{frozenDelta},#{availableAfter},#{frozenAfter},"
            + "#{entryType},'WORKFLOW',#{planId},#{key},#{reason},#{now})")
    int insertLedger(@Param("id") long id, @Param("userId") long userId,
                     @Param("availableDelta") int availableDelta, @Param("frozenDelta") int frozenDelta,
                     @Param("availableAfter") int availableAfter, @Param("frozenAfter") int frozenAfter,
                     @Param("entryType") String entryType, @Param("planId") long planId,
                     @Param("key") String key, @Param("reason") String reason,
                     @Param("now") OffsetDateTime now);

    @Insert("INSERT INTO business.outbox_event(id,user_id,aggregate_type,aggregate_id,schema_version,event_type,"
            + "execution_attempt,exchange_name,routing_key,payload,traceparent,status,attempts,available_at,created_at,updated_at)"
            + " VALUES(#{id},#{userId},'WORKFLOW',#{planId},1,'WORKFLOW_EXECUTION_REQUESTED',#{attempt},"
            + "#{exchange},#{routingKey},CAST(#{payload} AS jsonb),#{traceparent},'PENDING',0,#{now},#{now},#{now})")
    int insertOutbox(@Param("id") long id, @Param("userId") long userId,
                     @Param("planId") long planId, @Param("attempt") int attempt,
                     @Param("exchange") String exchange, @Param("routingKey") String routingKey,
                     @Param("payload") String payload, @Param("traceparent") String traceparent,
                     @Param("now") OffsetDateTime now);

    @Select("SELECT count(*) FROM business.outbox_event WHERE id=#{eventId} AND aggregate_type='WORKFLOW'"
            + " AND aggregate_id=#{planId} AND event_type='WORKFLOW_EXECUTION_REQUESTED'"
            + " AND execution_attempt=#{attempt}")
    int matchingOutbox(@Param("eventId") long eventId, @Param("planId") long planId,
                       @Param("attempt") int attempt);

    @Insert("INSERT INTO business.workflow_execution(id,plan_id,user_id,conversation_id,plan_version,execution_no,"
            + "claim_event_id,status,consumer_name,worker_id,bundle_version,execution_token_hash,context_snapshot,"
            + "lease_expires_at,last_heartbeat_at,replan_count,stable_error_code,started_at,finished_at,created_at,updated_at)"
            + " VALUES(#{x.id},#{x.planId},#{x.userId},#{x.conversationId},#{x.planVersion},#{x.executionNo},"
            + "#{x.claimEventId},#{x.status},#{x.consumerName},#{x.workerId},#{x.bundleVersion},#{x.executionTokenHash},"
            + "CAST(#{x.contextSnapshotJson} AS jsonb),#{x.leaseExpiresAt},#{x.lastHeartbeatAt},#{x.replanCount},"
            + "#{x.stableErrorCode},#{x.startedAt},#{x.finishedAt},#{x.createdAt},#{x.updatedAt})")
    int insertExecution(@Param("x") WorkflowExecutionRecords.Execution execution);

    @Select("SELECT " + EXECUTION + " FROM business.workflow_execution x WHERE x.plan_id=#{planId}"
            + " ORDER BY x.execution_no DESC LIMIT 1")
    Optional<WorkflowExecutionRecords.Execution> latestExecution(@Param("planId") long planId);

    @Select("SELECT " + EXECUTION + " FROM business.workflow_execution x WHERE x.plan_id=#{planId}"
            + " AND x.execution_no=#{executionNo} FOR UPDATE")
    Optional<WorkflowExecutionRecords.Execution> lockExecutionNumber(@Param("planId") long planId,
                                                                      @Param("executionNo") int executionNo);

    @Select("SELECT " + EXECUTION + " FROM business.workflow_execution x WHERE x.plan_id=#{planId}"
            + " AND x.claim_event_id=#{eventId}")
    Optional<WorkflowExecutionRecords.Execution> findExecutionByClaim(@Param("planId") long planId,
                                                                       @Param("eventId") long eventId);

    @Select("SELECT " + EXECUTION + " FROM business.workflow_execution x WHERE x.plan_id=#{planId}"
            + " ORDER BY x.execution_no DESC LIMIT 1 FOR UPDATE")
    Optional<WorkflowExecutionRecords.Execution> lockLatestExecution(@Param("planId") long planId);

    @Update("UPDATE business.workflow_plan SET status='RUNNING',updated_at=#{now} WHERE id=#{planId}"
            + " AND status='QUEUED' AND version=#{version}")
    int startPlan(@Param("planId") long planId, @Param("version") int version,
                  @Param("now") OffsetDateTime now);

    @Update("UPDATE business.workflow_plan SET updated_at=#{now} WHERE id=#{planId}"
            + " AND status='RUNNING' AND version=#{version}")
    int resumePlan(@Param("planId") long planId, @Param("version") int version,
                   @Param("now") OffsetDateTime now);

    @Update("UPDATE business.workflow_plan_step SET status='READY' WHERE plan_id=#{planId}"
            + " AND status='PENDING' AND depends_on='[]'::jsonb")
    int readyRootSteps(@Param("planId") long planId, @Param("now") OffsetDateTime now);

    @Update("UPDATE business.workflow_execution SET lease_expires_at=#{lease},last_heartbeat_at=#{now},updated_at=#{now}"
            + " WHERE id=#{executionId} AND status='RUNNING' AND execution_token_hash=#{tokenHash}"
            + " AND lease_expires_at>#{now}")
    int heartbeat(@Param("executionId") long executionId, @Param("tokenHash") String tokenHash,
                  @Param("lease") OffsetDateTime lease, @Param("now") OffsetDateTime now);

    @Insert("INSERT INTO business.workflow_observation(id,plan_id,step_id,execution_id,user_id,conversation_id,"
            + "event_id,tool_call_id,request_hash,step_attempt,status,result_type,result_ref,summary,facts,"
            + "stable_error_code,retryable,completed_at,created_at) VALUES(#{o.id},#{o.planId},#{o.stepId},"
            + "#{o.executionId},#{o.userId},#{o.conversationId},#{o.eventId},#{o.toolCallId},#{o.requestHash},"
            + "#{o.stepAttempt},#{o.status},#{o.resultType},#{o.resultRef},#{o.summary},CAST(#{o.factsJson} AS jsonb),"
            + "#{o.stableErrorCode},#{o.retryable},#{o.completedAt},#{o.createdAt}) ON CONFLICT (plan_id,event_id) DO NOTHING")
    int insertObservation(@Param("o") WorkflowExecutionRecords.Observation observation);

    @Select("SELECT " + OBSERVATION + " FROM business.workflow_observation o WHERE o.plan_id=#{planId}"
            + " AND o.event_id=#{eventId}")
    Optional<WorkflowExecutionRecords.Observation> findObservationByEvent(@Param("planId") long planId,
                                                                           @Param("eventId") UUID eventId);

    @Select("SELECT " + OBSERVATION + " FROM business.workflow_observation o WHERE o.plan_id=#{planId}"
            + " AND o.tool_call_id=#{toolCallId}")
    Optional<WorkflowExecutionRecords.Observation> findObservationByToolCall(@Param("planId") long planId,
                                                                              @Param("toolCallId") UUID toolCallId);

    @Select("SELECT " + OBSERVATION + " FROM business.workflow_observation o WHERE o.plan_id=#{planId}"
            + " AND o.step_id=#{stepId} AND o.step_attempt=#{attempt}")
    Optional<WorkflowExecutionRecords.Observation> findObservationByAttempt(@Param("planId") long planId,
                                                                             @Param("stepId") long stepId,
                                                                             @Param("attempt") int attempt);

    @Select("SELECT " + OBSERVATION + " FROM business.workflow_observation o WHERE o.plan_id=#{planId}"
            + " ORDER BY o.created_at,o.id")
    List<WorkflowExecutionRecords.Observation> observations(@Param("planId") long planId);

    @Update("UPDATE business.workflow_plan_step SET status=#{status},attempt=#{attempt},"
            + "started_at=CASE WHEN #{status} IN ('RUNNING','WAITING','SUCCEEDED','FAILED') THEN COALESCE(started_at,#{now}) ELSE started_at END,"
            + "finished_at=CASE WHEN #{status} IN ('SUCCEEDED','FAILED','SKIPPED') THEN #{now} ELSE NULL END,"
            + "last_error_code=#{errorCode} WHERE id=#{stepId} AND plan_id=#{planId} AND status=#{expectedStatus}")
    int changeStep(@Param("stepId") long stepId, @Param("planId") long planId,
                   @Param("expectedStatus") String expectedStatus, @Param("status") String status,
                   @Param("attempt") int attempt, @Param("errorCode") String errorCode,
                   @Param("now") OffsetDateTime now);

    @Update("UPDATE business.workflow_plan_step SET task_id=#{taskId},activation_event_id=#{eventId},"
            + "activation_request_hash=#{requestHash},status='WAITING',started_at=COALESCE(started_at,#{now}),"
            + "finished_at=NULL,last_error_code=NULL WHERE id=#{stepId} AND plan_id=#{planId}"
            + " AND status='RUNNING' AND task_id IS NULL AND authorized_input_hash=#{authorizedInputHash}")
    int bindTask(@Param("stepId") long stepId, @Param("planId") long planId,
                 @Param("taskId") long taskId, @Param("eventId") UUID eventId,
                 @Param("requestHash") String requestHash,
                 @Param("authorizedInputHash") String authorizedInputHash,
                 @Param("now") OffsetDateTime now);

    @Update("UPDATE business.workflow_execution SET status='WAITING_TASK',finished_at=#{now},updated_at=#{now}"
            + " WHERE id=#{executionId} AND status='RUNNING' AND execution_token_hash=#{tokenHash}"
            + " AND lease_expires_at>#{now}")
    int waitForTask(@Param("executionId") long executionId, @Param("tokenHash") String tokenHash,
                    @Param("now") OffsetDateTime now);

    @Update("UPDATE business.workflow_plan_step SET status=#{status},task_result_ref=#{resultRef},"
            + "last_error_code=#{errorCode},finished_at=#{now} WHERE id=#{stepId} AND plan_id=#{planId}"
            + " AND task_id=#{taskId} AND status='WAITING'")
    int finishTaskStep(@Param("stepId") long stepId, @Param("planId") long planId,
                       @Param("taskId") long taskId, @Param("status") String status,
                       @Param("resultRef") String resultRef, @Param("errorCode") String errorCode,
                       @Param("now") OffsetDateTime now);

    @Update("UPDATE business.workflow_plan_step s SET status='READY' WHERE s.plan_id=#{planId}"
            + " AND s.status='PENDING' AND NOT EXISTS (SELECT 1 FROM jsonb_array_elements_text(s.depends_on) d"
            + " WHERE NOT EXISTS (SELECT 1 FROM business.workflow_plan_step p WHERE p.plan_id=s.plan_id"
            + " AND p.step_key=d.value AND p.status IN ('SUCCEEDED','SKIPPED')))")
    int readySatisfiedSteps(@Param("planId") long planId);

    @Update("UPDATE business.workflow_plan_step SET status='READY' WHERE id=#{stepId} AND plan_id=#{planId}"
            + " AND status='PENDING'")
    int readyStep(@Param("stepId") long stepId, @Param("planId") long planId);

    @Select("SELECT request_hash FROM business.workflow_event WHERE plan_id=#{planId} AND source_event_id=#{eventId}")
    Optional<String> findEventHash(@Param("planId") long planId, @Param("eventId") UUID eventId);

    @Select("SELECT COALESCE(MAX(sequence_no),0)+1 FROM business.workflow_event WHERE plan_id=#{planId}")
    long nextEventSequence(@Param("planId") long planId);

    @Insert("INSERT INTO business.workflow_event(id,plan_id,user_id,conversation_id,sequence_no,event_type,"
            + "from_status,to_status,stable_error_code,created_at,source_event_id,request_hash) VALUES(#{id},#{planId},"
            + "#{userId},#{conversationId},#{sequence},#{eventType},#{fromStatus},#{toStatus},#{errorCode},#{now},"
            + "#{sourceEventId},#{requestHash}) ON CONFLICT (plan_id,source_event_id) DO NOTHING")
    int insertEvent(@Param("id") long id, @Param("planId") long planId, @Param("userId") long userId,
                    @Param("conversationId") long conversationId, @Param("sequence") long sequence,
                    @Param("eventType") String eventType, @Param("fromStatus") String fromStatus,
                    @Param("toStatus") String toStatus, @Param("errorCode") String errorCode,
                    @Param("sourceEventId") UUID sourceEventId, @Param("requestHash") String requestHash,
                    @Param("now") OffsetDateTime now);

    @Update("UPDATE business.workflow_execution SET status=#{status},stable_error_code=#{errorCode},"
            + "finished_at=CASE WHEN #{status} IN ('SUCCEEDED','FAILED','EXPIRED') THEN #{now} ELSE NULL END,updated_at=#{now}"
            + " WHERE id=#{executionId} AND status='RUNNING' AND execution_token_hash=#{tokenHash}"
            + " AND lease_expires_at>#{now}")
    int finishExecution(@Param("executionId") long executionId, @Param("tokenHash") String tokenHash,
                        @Param("status") String status, @Param("errorCode") String errorCode,
                        @Param("now") OffsetDateTime now);

    @Update("UPDATE business.workflow_plan SET status=#{status},last_error_code=#{errorCode},"
            + "finished_at=CASE WHEN #{status} IN ('SUCCEEDED','FAILED') THEN #{now} ELSE NULL END,updated_at=#{now}"
            + " WHERE id=#{planId} AND status='RUNNING'")
    int finishPlan(@Param("planId") long planId, @Param("status") String status,
                   @Param("errorCode") String errorCode, @Param("now") OffsetDateTime now);

    @Update("UPDATE business.workflow_plan_step SET depends_on=CAST(#{dependsOn} AS jsonb),"
            + "success_criteria=CAST(#{criteria} AS jsonb),input_refs=CAST(#{inputRefs} AS jsonb),"
            + "output_ref=#{outputRef} WHERE id=#{stepId} AND plan_id=#{planId} AND status IN ('PENDING','READY')")
    int reviseStep(@Param("stepId") long stepId, @Param("planId") long planId,
                   @Param("dependsOn") String dependsOn, @Param("criteria") String criteria,
                   @Param("inputRefs") String inputRefs, @Param("outputRef") String outputRef);

    @Update("UPDATE business.workflow_plan SET version=#{newVersion},updated_at=#{now} WHERE id=#{planId}"
            + " AND version=#{expectedVersion} AND status='RUNNING'")
    int updatePlanVersion(@Param("planId") long planId, @Param("expectedVersion") int expectedVersion,
                          @Param("newVersion") int newVersion, @Param("now") OffsetDateTime now);

    @Update("UPDATE business.workflow_execution SET plan_version=#{newVersion},replan_count=replan_count+1,"
            + "updated_at=#{now} WHERE id=#{executionId} AND replan_count<1 AND status='RUNNING'")
    int incrementReplan(@Param("executionId") long executionId, @Param("newVersion") int newVersion,
                        @Param("now") OffsetDateTime now);

    @Select("SELECT plan_id FROM business.workflow_execution WHERE status='RUNNING'"
            + " AND lease_expires_at<#{now} ORDER BY lease_expires_at,id LIMIT 1")
    Optional<Long> findExpiredPlanId(@Param("now") OffsetDateTime now);

    @Update("UPDATE business.workflow_execution SET status='EXPIRED',stable_error_code='WORKER_LEASE_EXPIRED',"
            + "finished_at=#{now},updated_at=#{now} WHERE id=#{executionId} AND status='RUNNING'"
            + " AND lease_expires_at<#{now}")
    int expireExecution(@Param("executionId") long executionId, @Param("now") OffsetDateTime now);

    @Update("UPDATE business.workflow_plan SET lease_recovery_count=lease_recovery_count+1,updated_at=#{now}"
            + " WHERE id=#{planId} AND status='RUNNING' AND lease_recovery_count=#{expected}")
    int incrementLeaseRecovery(@Param("planId") long planId, @Param("expected") int expected,
                               @Param("now") OffsetDateTime now);

    @Update("UPDATE business.workflow_plan SET status='QUEUED',last_error_code='WORKER_LEASE_EXPIRED',"
            + "updated_at=#{now} WHERE id=#{planId} AND status='RUNNING'")
    int retryPlan(@Param("planId") long planId, @Param("now") OffsetDateTime now);

    @Update("UPDATE business.workflow_plan SET status='FAILED',last_error_code='WORKER_LEASE_EXPIRED',"
            + "finished_at=#{now},updated_at=#{now} WHERE id=#{planId} AND status='RUNNING'")
    int failExpiredPlan(@Param("planId") long planId, @Param("now") OffsetDateTime now);
}
