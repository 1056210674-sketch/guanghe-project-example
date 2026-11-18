package com.guanghe.backend.admin.diagnostics;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface RunDiagnosticsMapper {
    String COLUMNS = """
            r.id,r.conversation_id AS conversationId,r.status,
            r.last_python_sequence AS lastPythonSequence,r.error_code AS errorCode,r.trace_id AS traceId,
            (SELECT t.id FROM business.task_draft d JOIN business.business_task t ON t.draft_id=d.id
              WHERE d.agent_run_id=r.id ORDER BY t.created_at,t.id LIMIT 1) AS linkedTaskId,
            CASE WHEN r.usage->>'workflowStepCount' ~ '^[0-9]{1,9}$' THEN (r.usage->>'workflowStepCount')::integer END AS workflowStepCount,
            CASE WHEN r.usage->>'modelCallCount' ~ '^[0-9]{1,9}$' THEN (r.usage->>'modelCallCount')::integer END AS modelCallCount,
            CASE WHEN r.usage->>'contextLoadCount' ~ '^[0-9]{1,9}$' THEN (r.usage->>'contextLoadCount')::integer END AS contextLoadCount,
            CASE WHEN r.usage->>'toolCallCount' ~ '^[0-9]{1,9}$' THEN (r.usage->>'toolCallCount')::integer END AS toolCallCount,
            CASE WHEN r.usage->>'providerCallCount' ~ '^[0-9]{1,9}$' THEN (r.usage->>'providerCallCount')::integer END AS providerCallCount,
            CASE WHEN r.usage->>'estimatedInputTokens' ~ '^[0-9]{1,9}$' THEN (r.usage->>'estimatedInputTokens')::integer END AS estimatedInputTokens,
            CASE WHEN r.usage->>'providerInputTokens' ~ '^[0-9]{1,9}$' THEN (r.usage->>'providerInputTokens')::integer END AS providerInputTokens,
            CASE WHEN r.usage->>'providerOutputTokens' ~ '^[0-9]{1,9}$' THEN (r.usage->>'providerOutputTokens')::integer END AS providerOutputTokens,
            CASE WHEN r.usage->>'providerLatencyMs' ~ '^[0-9]{1,9}$' THEN (r.usage->>'providerLatencyMs')::integer END AS providerLatencyMs,
            r.usage->>'provider' AS provider,r.usage->>'model' AS model,
            CASE WHEN r.context_snapshot#>>'{contextBudget,targetInputTokens}' ~ '^[0-9]{1,9}$' THEN (r.context_snapshot#>>'{contextBudget,targetInputTokens}')::integer END AS targetInputTokens,
            CASE WHEN r.context_snapshot#>>'{contextBudget,reservedOutputTokens}' ~ '^[0-9]{1,9}$' THEN (r.context_snapshot#>>'{contextBudget,reservedOutputTokens}')::integer END AS reservedOutputTokens,
            CASE WHEN r.context_snapshot#>>'{contextBudget,reservedToolTokens}' ~ '^[0-9]{1,9}$' THEN (r.context_snapshot#>>'{contextBudget,reservedToolTokens}')::integer END AS reservedToolTokens,
            CASE WHEN r.context_snapshot#>>'{contextBudget,reservedSafetyTokens}' ~ '^[0-9]{1,9}$' THEN (r.context_snapshot#>>'{contextBudget,reservedSafetyTokens}')::integer END AS reservedSafetyTokens,
            CASE WHEN r.context_snapshot#>>'{contextBudget,availableHistoryTokens}' ~ '^[0-9]{1,9}$' THEN (r.context_snapshot#>>'{contextBudget,availableHistoryTokens}')::integer END AS availableHistoryTokens,
            CASE WHEN r.context_snapshot#>>'{contextBudget,estimatedHistoryTokens}' ~ '^[0-9]{1,9}$' THEN (r.context_snapshot#>>'{contextBudget,estimatedHistoryTokens}')::integer END AS estimatedHistoryTokens,
            CASE WHEN r.context_snapshot#>>'{contextBudget,truncated}' IN ('true','false') THEN (r.context_snapshot#>>'{contextBudget,truncated}')::boolean END AS contextTruncated,
            r.context_snapshot#>>'{conversationMemory,contextSource}' AS contextSource,
            r.stop_requested_at AS stopRequestedAt,r.started_at AS startedAt,
            r.completed_at AS completedAt,r.created_at AS createdAt,r.updated_at AS updatedAt
            """;

    @Select("<script>SELECT " + COLUMNS + " FROM business.agent_run r WHERE 1=1"
            + " <if test='runId != null'>AND r.id=#{runId}</if>"
            + " <if test='conversationId != null'>AND r.conversation_id=#{conversationId}</if>"
            + " <if test='status != null'>AND r.status=#{status}</if>"
            + " <if test='errorCode != null'>AND r.error_code=#{errorCode}</if>"
            + " <if test='traceId != null'>AND r.trace_id=#{traceId}</if>"
            + " <if test='fromTime != null'>AND r.created_at &gt;=#{fromTime}</if>"
            + " <if test='toTime != null'>AND r.created_at &lt;=#{toTime}</if>"
            + " ORDER BY r.created_at DESC,r.id DESC LIMIT #{size} OFFSET #{offset}</script>")
    List<RunDiagnosticsRecords.Run> list(@Param("runId") UUID runId,
            @Param("conversationId") Long conversationId, @Param("status") String status,
            @Param("errorCode") String errorCode, @Param("traceId") String traceId,
            @Param("fromTime") OffsetDateTime fromTime, @Param("toTime") OffsetDateTime toTime,
            @Param("offset") long offset, @Param("size") int size);

    @Select("<script>SELECT count(*) FROM business.agent_run r WHERE 1=1"
            + " <if test='runId != null'>AND r.id=#{runId}</if>"
            + " <if test='conversationId != null'>AND r.conversation_id=#{conversationId}</if>"
            + " <if test='status != null'>AND r.status=#{status}</if>"
            + " <if test='errorCode != null'>AND r.error_code=#{errorCode}</if>"
            + " <if test='traceId != null'>AND r.trace_id=#{traceId}</if>"
            + " <if test='fromTime != null'>AND r.created_at &gt;=#{fromTime}</if>"
            + " <if test='toTime != null'>AND r.created_at &lt;=#{toTime}</if></script>")
    long count(@Param("runId") UUID runId, @Param("conversationId") Long conversationId,
            @Param("status") String status, @Param("errorCode") String errorCode,
            @Param("traceId") String traceId, @Param("fromTime") OffsetDateTime fromTime,
            @Param("toTime") OffsetDateTime toTime);

    @Select("SELECT " + COLUMNS + " FROM business.agent_run r WHERE r.id=#{runId}")
    Optional<RunDiagnosticsRecords.Run> find(@Param("runId") UUID runId);

    @Select("""
            SELECT event_type AS eventType,sequence_no AS sequence,
              CASE WHEN COALESCE(payload->>'code',payload->>'errorCode') ~ '^[A-Z][A-Z0-9_]{0,63}$'
                   THEN COALESCE(payload->>'code',payload->>'errorCode') END AS stableErrorCode,
              occurred_at AS occurredAt
            FROM business.conversation_event WHERE agent_run_id=#{runId}
            ORDER BY sequence_no
            """)
    List<RunDiagnosticsRecords.Event> events(@Param("runId") UUID runId);
}
