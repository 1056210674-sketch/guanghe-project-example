package com.guanghe.backend.agentplatform;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.apache.ibatis.annotations.*;

@Mapper
public interface MonitorExecutionMapper {
    String POLICY = "user_id AS userId,version,enabled,max_points_per_action AS maxPointsPerAction,"
            + "max_points_per_day AS maxPointsPerDay,effective_shanghai_date AS effectiveShanghaiDate,created_at AS createdAt,updated_at AS updatedAt";
    String GRANT = "id,user_id AS userId,conversation_id AS conversationId,installation_id AS installationId,agent_key AS agentKey,"
            + "agent_version AS agentVersion,definition_hash AS definitionHash,project_id AS projectId,topic_id AS topicId,scope_snapshot::text AS scopeSnapshotJson,"
            + "version,status,expires_at AS expiresAt,revoked_at AS revokedAt,created_at AS createdAt,updated_at AS updatedAt";
    String ADMISSION = "a.id,a.user_id AS userId,a.conversation_id AS conversationId,a.run_id AS runId,a.request_message_id AS requestMessageId,"
            + "a.draft_id AS draftId,a.grant_id AS grantId,a.grant_version AS grantVersion,a.frozen_identity::text AS frozenIdentityJson,"
            + "a.intent_snapshot::text AS intentSnapshotJson,a.intent_hash AS intentHash,a.input_hash AS inputHash,a.pricing_version_id AS pricingVersionId,"
            + "a.quoted_points AS quotedPoints,a.status,a.reason_code AS reasonCode,a.task_id AS taskId,a.execution_source AS executionSource,"
            + "a.admitted_at AS admittedAt,a.created_at AS createdAt,a.updated_at AS updatedAt";

    @Select("SELECT "+POLICY+" FROM business.agent_execution_budget_policy WHERE user_id=#{userId}")
    Optional<MonitorExecutionRecords.Policy> policy(long userId);
    @Select("SELECT "+POLICY+" FROM business.agent_execution_budget_policy WHERE user_id=#{userId} FOR SHARE")
    Optional<MonitorExecutionRecords.Policy> sharePolicy(long userId);
    @Insert("""
        INSERT INTO business.agent_execution_budget_policy(user_id,version,enabled,max_points_per_action,max_points_per_day,effective_shanghai_date,created_at,updated_at)
        VALUES(#{p.userId},#{p.version},#{p.enabled},#{p.maxPointsPerAction},#{p.maxPointsPerDay},#{p.effectiveShanghaiDate},#{p.createdAt},#{p.updatedAt})
        ON CONFLICT(user_id) DO NOTHING
        """)
    int insertPolicy(@Param("p") MonitorExecutionRecords.Policy policy);
    @Update("""
        UPDATE business.agent_execution_budget_policy SET version=version+1,enabled=#{enabled},max_points_per_action=#{perAction},max_points_per_day=#{perDay},updated_at=#{now}
        WHERE user_id=#{userId} AND version=#{version}
        """)
    int updatePolicy(@Param("userId") long userId,@Param("version") int version,@Param("enabled") boolean enabled,
            @Param("perAction") int perAction,@Param("perDay") int perDay,@Param("now") OffsetDateTime now);

    @Select("SELECT "+GRANT+" FROM business.geo_monitor_execution_grant WHERE user_id=#{userId} AND conversation_id=#{conversationId} AND status='ACTIVE'")
    List<MonitorExecutionRecords.Grant> activeGrants(@Param("userId") long userId,@Param("conversationId") long conversationId);
    @Select("SELECT "+GRANT+" FROM business.geo_monitor_execution_grant WHERE id=#{id} AND user_id=#{userId} AND conversation_id=#{conversationId}")
    Optional<MonitorExecutionRecords.Grant> grant(@Param("id") long id,@Param("userId") long userId,@Param("conversationId") long conversationId);
    @Select("SELECT "+GRANT+" FROM business.geo_monitor_execution_grant WHERE id=#{id} AND user_id=#{userId} AND conversation_id=#{conversationId} FOR SHARE")
    Optional<MonitorExecutionRecords.Grant> shareGrant(@Param("id") long id,@Param("userId") long userId,@Param("conversationId") long conversationId);
    @Select("SELECT "+GRANT+" FROM business.geo_monitor_execution_grant WHERE id=#{id} AND user_id=#{userId} AND conversation_id=#{conversationId} FOR UPDATE")
    Optional<MonitorExecutionRecords.Grant> lockGrant(@Param("id") long id,@Param("userId") long userId,@Param("conversationId") long conversationId);
    @Insert("""
        INSERT INTO business.geo_monitor_execution_grant(id,user_id,conversation_id,installation_id,agent_key,agent_version,definition_hash,project_id,topic_id,scope_snapshot,version,status,expires_at,created_at,updated_at)
        VALUES(#{g.id},#{g.userId},#{g.conversationId},#{g.installationId},#{g.agentKey},#{g.agentVersion},#{g.definitionHash},#{g.projectId},#{g.topicId},CAST(#{g.scopeSnapshotJson} AS jsonb),#{g.version},'ACTIVE',#{g.expiresAt},#{g.createdAt},#{g.updatedAt})
        ON CONFLICT(user_id,conversation_id) WHERE status='ACTIVE' DO NOTHING
        """)
    int insertGrant(@Param("g") MonitorExecutionRecords.Grant grant);
    @Update("""
        UPDATE business.geo_monitor_execution_grant SET scope_snapshot=CAST(#{scope} AS jsonb),topic_id=#{topicId},version=version+1,expires_at=#{expires},updated_at=#{now}
        WHERE id=#{id} AND user_id=#{userId} AND version=#{version} AND status='ACTIVE'
        """)
    int updateGrant(@Param("id") long id,@Param("userId") long userId,@Param("version") int version,@Param("topicId") long topicId,
            @Param("scope") String scope,@Param("expires") OffsetDateTime expires,@Param("now") OffsetDateTime now);
    @Update("""
        UPDATE business.geo_monitor_execution_grant SET status='REVOKED',revoked_at=#{now},version=version+1,updated_at=#{now}
        WHERE id=#{id} AND user_id=#{userId} AND version=#{version} AND status='ACTIVE'
        """)
    int revokeGrant(@Param("id") long id,@Param("userId") long userId,@Param("version") int version,@Param("now") OffsetDateTime now);

    @Select("SELECT definition_hash FROM business.agent_definition_version WHERE agent_key=#{key} AND version=#{version}")
    String definitionHash(@Param("key") String key,@Param("version") String version);
    @Select("SELECT id FROM business.account WHERE id=#{userId} AND role='USER' AND status='ACTIVE' AND credential_state='ACTIVE' FOR SHARE")
    Long shareRunnableAccount(long userId);
    @Select("""
        SELECT COALESCE(SUM(-frozen_delta::bigint),0) FROM business.point_ledger
        WHERE user_id=#{userId} AND entry_type='TASK_SETTLE' AND frozen_delta<0
          AND created_at>=#{dayStart} AND created_at<#{dayEnd}
        """)
    long consumedPoints(@Param("userId") long userId,@Param("dayStart") OffsetDateTime dayStart,@Param("dayEnd") OffsetDateTime dayEnd);

    @Select("SELECT "+ADMISSION+" FROM business.geo_monitor_execution_admission a WHERE a.run_id=#{runId} AND a.user_id=#{userId} AND a.conversation_id=#{conversationId}")
    Optional<MonitorExecutionRecords.Admission> admission(@Param("runId") UUID runId,@Param("userId") long userId,@Param("conversationId") long conversationId);
    @Select("SELECT "+ADMISSION+" FROM business.geo_monitor_execution_admission a JOIN business.agent_run r ON r.id=a.run_id "
            + "WHERE a.status='PENDING' AND r.status IN ('COMPLETED','FAILED','STOPPED') ORDER BY a.created_at,a.id LIMIT 1 FOR UPDATE OF a SKIP LOCKED")
    Optional<MonitorExecutionRecords.Admission> lockNextAdmission();
    @Insert("""
        INSERT INTO business.geo_monitor_execution_admission(id,user_id,conversation_id,run_id,request_message_id,draft_id,grant_id,grant_version,frozen_identity,intent_snapshot,intent_hash,input_hash,pricing_version_id,quoted_points,status,reason_code,created_at,updated_at)
        VALUES(#{a.id},#{a.userId},#{a.conversationId},#{a.runId},#{a.requestMessageId},#{a.draftId},#{a.grantId},#{a.grantVersion},CAST(#{a.frozenIdentityJson} AS jsonb),CAST(#{a.intentSnapshotJson} AS jsonb),#{a.intentHash},#{a.inputHash},#{a.pricingVersionId},#{a.quotedPoints},#{a.status},#{a.reasonCode},#{a.createdAt},#{a.updatedAt})
        ON CONFLICT(run_id) DO NOTHING
        """)
    int insertAdmission(@Param("a") MonitorExecutionRecords.Admission admission);
    @Update("UPDATE business.geo_monitor_execution_admission SET status='WAIT_INPUT',reason_code=#{reason},updated_at=#{now} WHERE id=#{id} AND status='PENDING'")
    int refuseAdmission(@Param("id") long id,@Param("reason") String reason,@Param("now") OffsetDateTime now);
    @Update("""
        UPDATE business.geo_monitor_execution_admission SET status='ADMITTED',reason_code=NULL,task_id=#{taskId},execution_source=#{source},admitted_at=#{now},updated_at=#{now}
        WHERE id=#{id} AND status='PENDING'
        """)
    int admit(@Param("id") long id,@Param("taskId") long taskId,@Param("source") String source,@Param("now") OffsetDateTime now);
}
