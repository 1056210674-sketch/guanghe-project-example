package com.guanghe.backend.adminplatform;

import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** Metadata projections must stay disjoint from knowledge bodies and model context snapshots. */
@Mapper
public interface AdminPlatformMapper {
    String DEFINITION = "d.agent_key AS agentKey,d.version,d.name,d.adapter_key AS adapterKey,d.visibility,d.status,"
            + "d.definition_hash AS definitionHash,d.published_at AS publishedAt,"
            + "(SELECT count(*) FROM business.agent_installation i WHERE i.agent_key=d.agent_key"
            + " AND i.agent_version=d.version AND i.deleted_at IS NULL) AS installationCount";
    String PLAN = "id::text AS id,user_id::text AS userId,space_id::text AS projectId,topic_id::text AS topicId,status,"
            + "schedule_type AS scheduleType,estimated_points_per_run AS estimatedPointsPerRun,"
            + "max_points_per_run AS maxPointsPerRun,pricing_version_id::text AS pricingVersionId,"
            + "next_run_at AS nextRunAt,last_scheduled_slot AS lastScheduledSlot,pause_reason AS pauseReason,"
            + "version,created_at AS createdAt,updated_at AS updatedAt";
    String PLAN_FILTER = "<where><if test='userId != null'> AND user_id=#{userId} </if>"
            + "<if test='projectId != null'> AND space_id=#{projectId} </if>"
            + "<if test='status != null'> AND status=#{status} </if></where>";
    String DOC_FILTER = "<where><if test='userId != null'> AND d.user_id=#{userId} </if>"
            + "<if test='projectId != null'> AND d.project_id=#{projectId} </if></where>";
    String PREF_FILTER = "<where><if test='userId != null'> AND user_id=#{userId} </if>"
            + "<if test='projectId != null'> AND project_id=#{projectId} </if></where>";

    @Select("SELECT " + DEFINITION + " FROM business.agent_definition_version d ORDER BY d.agent_key,d.published_at DESC,d.version DESC")
    List<AdminPlatformDtos.AgentDefinitionView> definitions();

    @Select("SELECT " + DEFINITION + " FROM business.agent_definition_version d WHERE agent_key=#{key} AND version=#{version} FOR UPDATE OF d")
    Optional<AdminPlatformDtos.AgentDefinitionView> lockDefinition(@Param("key") String key, @Param("version") String version);

    @Update("UPDATE business.agent_definition_version SET status=#{status},visibility=#{visibility}"
            + " WHERE agent_key=#{key} AND version=#{version} AND definition_hash=#{hash}"
            + " AND status=#{expectedStatus} AND visibility=#{expectedVisibility}")
    int updateDefinition(@Param("key") String key, @Param("version") String version, @Param("hash") String hash,
            @Param("expectedStatus") String expectedStatus, @Param("expectedVisibility") String expectedVisibility,
            @Param("status") String status, @Param("visibility") String visibility);

    @Select("<script>SELECT " + PLAN + " FROM business.geo_plan " + PLAN_FILTER
            + " ORDER BY updated_at DESC,id DESC LIMIT #{size} OFFSET #{offset}</script>")
    List<AdminPlatformDtos.PlanView> plans(@Param("userId") Long userId, @Param("projectId") Long projectId,
            @Param("status") String status, @Param("offset") long offset, @Param("size") int size);

    @Select("<script>SELECT count(*) FROM business.geo_plan " + PLAN_FILTER + "</script>")
    long planCount(@Param("userId") Long userId, @Param("projectId") Long projectId, @Param("status") String status);

    @Select("SELECT " + PLAN + " FROM business.geo_plan WHERE id=#{id}")
    Optional<AdminPlatformDtos.PlanView> plan(@Param("id") long id);

    @Select("""
            SELECT id::text AS id,plan_id::text AS planId,user_id::text AS userId,scheduled_slot AS scheduledSlot,
                status,task_id::text AS taskId,quoted_points AS quotedPoints,error_code AS errorCode,
                created_at AS createdAt,updated_at AS updatedAt FROM business.geo_plan_slot
            WHERE plan_id=#{id} ORDER BY scheduled_slot DESC,id DESC LIMIT #{size} OFFSET #{offset}
            """)
    List<AdminPlatformDtos.PlanSlotView> slots(@Param("id") long id, @Param("offset") long offset, @Param("size") int size);

    @Select("SELECT count(*) FROM business.geo_plan_slot WHERE plan_id=#{id}")
    long slotCount(@Param("id") long id);

    @Select("<script>SELECT d.id::text AS id,d.user_id::text AS userId,d.project_id::text AS projectId,"
            + "d.source_type AS sourceType,d.status,d.current_version AS version,v.sha256,v.content_type AS contentType,"
            + "v.size_bytes AS sizeBytes,COALESCE(jsonb_array_length(v.paragraphs),0) AS paragraphCount,"
            + "v.page_count AS pageCount,d.failure_code AS failureCode,d.created_at AS createdAt,"
            + "d.updated_at AS updatedAt,d.deleted_at AS deletedAt FROM business.knowledge_document d"
            + " LEFT JOIN LATERAL (SELECT sha256,content_type,size_bytes,paragraphs,page_count"
            + " FROM business.knowledge_document_version WHERE document_id=d.id AND version &lt;=d.current_version"
            + " ORDER BY version DESC LIMIT 1) v ON true " + DOC_FILTER
            + " ORDER BY d.updated_at DESC,d.id DESC LIMIT #{size} OFFSET #{offset}</script>")
    List<AdminPlatformDtos.KnowledgeDocumentMetadata> documents(@Param("userId") Long userId,
            @Param("projectId") Long projectId, @Param("offset") long offset, @Param("size") int size);

    @Select("<script>SELECT count(*) FROM business.knowledge_document d " + DOC_FILTER + "</script>")
    long documentCount(@Param("userId") Long userId, @Param("projectId") Long projectId);

    @Select("<script>SELECT id::text AS id,user_id::text AS userId,project_id::text AS projectId,"
            + "CASE WHEN project_id IS NULL THEN 'PERSONAL' ELSE 'PROJECT' END AS scope,source_type AS sourceType,"
            + "CASE WHEN deleted_at IS NULL THEN 'ACTIVE' ELSE 'DELETED' END AS status,version,content_hash AS contentHash,"
            + "created_at AS createdAt,updated_at AS updatedAt,deleted_at AS deletedAt FROM business.agent_memory_preference "
            + PREF_FILTER + " ORDER BY updated_at DESC,id DESC LIMIT #{size} OFFSET #{offset}</script>")
    List<AdminPlatformDtos.MemoryPreferenceMetadata> preferences(@Param("userId") Long userId,
            @Param("projectId") Long projectId, @Param("offset") long offset, @Param("size") int size);

    @Select("<script>SELECT count(*) FROM business.agent_memory_preference " + PREF_FILTER + "</script>")
    long preferenceCount(@Param("userId") Long userId, @Param("projectId") Long projectId);

    @Select("<script>SELECT docs.*,prefs.* FROM (SELECT count(*) AS knowledgeDocumentCount,"
            + "count(*) FILTER(WHERE status='READY') AS readyKnowledgeDocumentCount,"
            + "count(*) FILTER(WHERE status='FAILED') AS failedKnowledgeDocumentCount,"
            + "count(*) FILTER(WHERE status='DELETED') AS deletedKnowledgeDocumentCount"
            + " FROM business.knowledge_document d " + DOC_FILTER + ") docs CROSS JOIN ("
            + "SELECT count(*) FILTER(WHERE project_id IS NULL AND deleted_at IS NULL) AS activePersonalPreferenceCount,"
            + "count(*) FILTER(WHERE project_id IS NOT NULL AND deleted_at IS NULL) AS activeProjectPreferenceCount,"
            + "count(*) FILTER(WHERE deleted_at IS NOT NULL) AS deletedPreferenceCount"
            + " FROM business.agent_memory_preference " + PREF_FILTER + ") prefs</script>")
    AdminPlatformDtos.MetadataSummary metadataSummary(@Param("userId") Long userId, @Param("projectId") Long projectId);
}
