package com.guanghe.backend.admin.diagnostics;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface TaskDiagnosticsMapper {
    String TASK_COLUMNS = """
            t.id,t.task_type AS taskType,t.status,t.stage_code AS stageCode,
            t.quoted_points AS quotedPoints,t.settled_points AS settledPoints,
            t.error_code AS errorCode,
            (SELECT substring(o.traceparent from 4 for 32) FROM business.outbox_event o
             WHERE o.aggregate_type='TASK' AND o.aggregate_id=t.id
             ORDER BY o.created_at,o.id LIMIT 1) AS traceId,
            t.queued_at AS queuedAt,t.started_at AS startedAt,t.finished_at AS finishedAt,
            t.created_at AS createdAt,t.updated_at AS updatedAt
            """;

    @Select("<script>SELECT " + TASK_COLUMNS + " FROM business.business_task t WHERE 1=1"
            + " <if test='taskId != null'>AND t.id=#{taskId}</if>"
            + " <if test='taskType != null'>AND t.task_type=#{taskType}</if>"
            + " <if test='taskStatus != null'>AND t.status=#{taskStatus}</if>"
            + " <if test='errorCode != null'>AND t.error_code=#{errorCode}</if>"
            + " <if test='traceId != null'>AND EXISTS (SELECT 1 FROM business.outbox_event f"
            + " WHERE f.aggregate_type='TASK' AND f.aggregate_id=t.id"
            + " AND substring(f.traceparent from 4 for 32)=#{traceId})</if>"
            + " <if test='fromTime != null'>AND t.created_at &gt;=#{fromTime}</if>"
            + " <if test='toTime != null'>AND t.created_at &lt;=#{toTime}</if>"
            + " ORDER BY t.created_at DESC,t.id DESC LIMIT #{size} OFFSET #{offset}</script>")
    List<TaskDiagnosticsRecords.Task> list(
            @Param("taskId") Long taskId, @Param("taskType") String taskType,
            @Param("taskStatus") String taskStatus, @Param("errorCode") String errorCode,
            @Param("traceId") String traceId, @Param("fromTime") OffsetDateTime fromTime,
            @Param("toTime") OffsetDateTime toTime, @Param("offset") long offset,
            @Param("size") int size);

    @Select("<script>SELECT count(*) FROM business.business_task t WHERE 1=1"
            + " <if test='taskId != null'>AND t.id=#{taskId}</if>"
            + " <if test='taskType != null'>AND t.task_type=#{taskType}</if>"
            + " <if test='taskStatus != null'>AND t.status=#{taskStatus}</if>"
            + " <if test='errorCode != null'>AND t.error_code=#{errorCode}</if>"
            + " <if test='traceId != null'>AND EXISTS (SELECT 1 FROM business.outbox_event f"
            + " WHERE f.aggregate_type='TASK' AND f.aggregate_id=t.id"
            + " AND substring(f.traceparent from 4 for 32)=#{traceId})</if>"
            + " <if test='fromTime != null'>AND t.created_at &gt;=#{fromTime}</if>"
            + " <if test='toTime != null'>AND t.created_at &lt;=#{toTime}</if></script>")
    long count(@Param("taskId") Long taskId, @Param("taskType") String taskType,
               @Param("taskStatus") String taskStatus, @Param("errorCode") String errorCode,
               @Param("traceId") String traceId, @Param("fromTime") OffsetDateTime fromTime,
               @Param("toTime") OffsetDateTime toTime);

    @Select("SELECT " + TASK_COLUMNS + " FROM business.business_task t WHERE t.id=#{taskId}")
    Optional<TaskDiagnosticsRecords.Task> find(@Param("taskId") long taskId);

    @Select("""
            SELECT event_type AS eventType,from_status AS fromStatus,to_status AS toStatus,
              source,error_code AS errorCode,created_at AS occurredAt
            FROM business.business_task_event WHERE task_id=#{taskId} ORDER BY created_at,id
            """)
    List<TaskDiagnosticsRecords.Event> events(@Param("taskId") long taskId);

    @Select("""
            SELECT execution_no AS executionNo,status,stage_code AS stageCode,
              provider_code AS providerCode,provider_model AS providerModel,
              submission_state AS submissionState,latency_ms AS latencyMs,error_code AS errorCode,
              last_heartbeat_at AS lastHeartbeatAt,started_at AS startedAt,
              finished_at AS finishedAt,created_at AS createdAt,updated_at AS updatedAt
            FROM business.task_execution WHERE task_id=#{taskId} ORDER BY execution_no
            """)
    List<TaskDiagnosticsRecords.Execution> executions(@Param("taskId") long taskId);

    @Select("""
            SELECT execution_attempt AS executionAttempt,status,attempts,
              last_error_code AS lastErrorCode,available_at AS availableAt,
              published_at AS publishedAt,created_at AS createdAt
            FROM business.outbox_event WHERE aggregate_type='TASK' AND aggregate_id=#{taskId}
            ORDER BY created_at,id
            """)
    List<TaskDiagnosticsRecords.Outbox> outbox(@Param("taskId") long taskId);

    @Select("SELECT disposition,created_at AS createdAt FROM business.inbox_event WHERE task_id=#{taskId} ORDER BY created_at,id")
    List<TaskDiagnosticsRecords.Inbox> inbox(@Param("taskId") long taskId);

    @Select("""
            SELECT status,quoted_points AS quotedPoints,frozen_points AS frozenPoints,
              settled_points AS settledPoints,released_points AS releasedPoints,
              created_at AS createdAt,updated_at AS updatedAt
            FROM business.point_reservation WHERE task_id=#{taskId}
            """)
    Optional<TaskDiagnosticsRecords.Reservation> reservation(@Param("taskId") long taskId);

    @Select("""
            SELECT entry_type AS entryType,available_delta AS availableDelta,
              frozen_delta AS frozenDelta,created_at AS createdAt
            FROM business.point_ledger WHERE business_type='TASK' AND business_id=#{taskId}
            ORDER BY created_at,id
            """)
    List<TaskDiagnosticsRecords.Ledger> ledger(@Param("taskId") long taskId);

    @Select("""
            SELECT status,attempt,max_attempts AS maxAttempts,provider_code AS providerCode,
              provider_model AS providerModel,last_error_code AS lastErrorCode,
              available_at AS availableAt,completed_at AS completedAt,updated_at AS updatedAt
            FROM business.asset_ingestion_work WHERE task_id=#{taskId} ORDER BY artifact_index
            """)
    List<TaskDiagnosticsRecords.Ingestion> ingestions(@Param("taskId") long taskId);

    @Select("""
            SELECT id,status,expected_units AS expectedUnits,succeeded_units AS succeededUnits,
              failed_units AS failedUnits,missing_units AS missingUnits,started_at AS startedAt,
              finished_at AS finishedAt,updated_at AS updatedAt
            FROM business.geo_run WHERE task_id=#{taskId}
            """)
    Optional<TaskDiagnosticsRecords.Geo> geo(@Param("taskId") long taskId);

    @Select("SELECT id,status,version,updated_at AS updatedAt FROM business.article WHERE source_task_id=#{taskId}")
    Optional<TaskDiagnosticsRecords.Article> article(@Param("taskId") long taskId);

    @Select("""
            SELECT status,quoted_points AS quotedPoints,settled_points AS settledPoints,
              released_points AS releasedPoints,finished_at AS finishedAt,updated_at AS updatedAt
            FROM business.media_submission_batch WHERE task_id=#{taskId}
            """)
    Optional<TaskDiagnosticsRecords.MediaBatch> mediaBatch(@Param("taskId") long taskId);

    @Select("""
            SELECT status,submission_state AS submissionState,provider_code AS providerCode,
              upstream_model_id AS upstreamModelId,stable_error_code AS stableErrorCode,
              updated_at AS updatedAt FROM business.media_submission
            WHERE task_id=#{taskId} ORDER BY created_at,id
            """)
    List<TaskDiagnosticsRecords.MediaSubmission> mediaSubmissions(@Param("taskId") long taskId);
}
