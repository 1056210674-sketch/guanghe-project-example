package com.guanghe.backend.conversation.action;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.apache.ibatis.annotations.*;

@Mapper
public interface RunActionMapper {
    record Receipt(UUID runId, String toolCallId, String action, String inputHash, String responseJson) { }
    record RecentTask(String taskId, String status, String topicId, String reportId,
                      int quotedPoints, int settledPoints) { }
    record RecentCreativeTask(String taskId,String taskType,String status,int quotedPoints,int settledPoints) { }
    record UnsubmittedTurn(String sourceMessageId, String requestText) { }

    // Only the immediately preceding, same-project USER turn can be continued.
    // Match counted calls to durable action or read-only resource receipts. This
    // excludes unknown writes (and conservatively external calls). Summaries are not proof.
    @Select("""
        WITH previous AS (
            SELECT id,content FROM business.conversation_message
            WHERE user_id=#{user} AND conversation_id=#{conversation} AND role='USER'
              AND sequence_no < #{sequence}
            ORDER BY sequence_no DESC LIMIT 1
        )
        SELECT p.id::text AS sourceMessageId,p.content->>'text' AS requestText
        FROM previous p JOIN business.agent_run r ON r.request_message_id=p.id
        WHERE r.user_id=#{user} AND r.conversation_id=#{conversation}
          AND r.selected_space_id=#{project} AND r.status='COMPLETED' AND r.error_code IS NULL
          AND r.completed_at <= #{requestedAt}
          AND r.completed_at > CAST(#{requestedAt} AS timestamptz) - INTERVAL '24 hours'
          AND NOT EXISTS (SELECT 1 FROM business.task_draft d WHERE d.agent_run_id=r.id)
          AND NOT EXISTS (SELECT 1 FROM business.agent_tool_action a WHERE a.run_id=r.id
              AND a.action IN ('geo.monitor','article.generate','image.generate','video.generate'))
          AND COALESCE((r.usage->>'toolCallCount')::integer,-1) =
              (SELECT count(*) FROM business.agent_tool_action a WHERE a.run_id=r.id) +
              (SELECT count(DISTINCT e.payload#>>'{input,toolCallId}')
                 FROM business.conversation_event e WHERE e.agent_run_id=r.id
                   AND e.user_id=r.user_id AND e.conversation_id=r.conversation_id
                   AND e.event_type='context.resource' AND e.payload->>'kind' IN ('SEARCH','READ'))
        """)
    Optional<UnsubmittedTurn> unsubmittedPreviousTurn(@Param("user") long user,
            @Param("conversation") long conversation,@Param("project") long project,
            @Param("sequence") long sequence,@Param("requestedAt") OffsetDateTime requestedAt);

    @Select("SELECT run_id AS runId,tool_call_id AS toolCallId,action,input_hash AS inputHash,response::text AS responseJson FROM business.agent_tool_action WHERE run_id=#{runId} AND tool_call_id=#{toolCallId}")
    Optional<Receipt> receipt(@Param("runId") UUID runId,@Param("toolCallId") String toolCallId);

    @Insert("INSERT INTO business.agent_tool_action(run_id,tool_call_id,user_id,conversation_id,source_message_id,action,input_hash,response,created_at) VALUES(#{runId},#{call},#{user},#{conversation},#{source},#{action},#{hash},CAST(#{response} AS jsonb),#{now})")
    int insert(@Param("runId") UUID runId,@Param("call") String call,@Param("user") long user,
            @Param("conversation") long conversation,@Param("source") long source,@Param("action") String action,
            @Param("hash") String hash,@Param("response") String response,@Param("now") OffsetDateTime now);

    @Select("SELECT count(*) FROM business.agent_tool_action WHERE run_id=#{runId}")
    int count(@Param("runId") UUID runId);

    @Select("SELECT t.id::text AS taskId,t.status,g.topic_id::text AS topicId,r.id::text AS reportId,t.quoted_points AS quotedPoints,t.settled_points AS settledPoints FROM business.business_task t LEFT JOIN business.geo_run g ON g.task_id=t.id LEFT JOIN business.geo_report r ON r.task_id=t.id AND r.archived_at IS NULL WHERE t.user_id=#{user} AND t.space_id=#{project} AND t.task_type='GEO' ORDER BY t.created_at DESC,t.id DESC LIMIT 5")
    List<RecentTask> recentTasks(@Param("user") long user,@Param("project") long project);

    @Select("SELECT id::text AS taskId,task_type AS taskType,status,quoted_points AS quotedPoints,settled_points AS settledPoints FROM business.business_task WHERE user_id=#{user} AND conversation_id=#{conversation} AND task_type IN ('IMAGE','VIDEO') ORDER BY created_at DESC,id DESC LIMIT 5")
    List<RecentCreativeTask> recentCreativeTasks(@Param("user") long user,@Param("conversation") long conversation);
}
