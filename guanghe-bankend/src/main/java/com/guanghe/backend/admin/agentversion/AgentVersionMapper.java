package com.guanghe.backend.admin.agentversion;

import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
interface AgentVersionMapper {

    String VERSION_OBSERVATIONS = """
            SELECT x.bundle_version, x.started_at AS observed_at, x.id AS execution_id
            FROM business.task_execution x
            UNION ALL
            SELECT x.bundle_version, x.started_at AS observed_at, x.id AS execution_id
            FROM business.workflow_execution x
            """;

    @Select("<script>WITH version_observation AS (" + VERSION_OBSERVATIONS
            + " <if test='currentBundle != null'>"
            + " UNION ALL SELECT CAST(#{currentBundle} AS varchar),"
            + " CAST(NULL AS timestamptz),CAST(NULL AS bigint)"
            + " </if>)"
            + " SELECT bundle_version AS bundleVersion,min(observed_at) AS firstObservedAt,"
            + " max(observed_at) AS lastObservedAt,count(execution_id) AS runCount"
            + " FROM version_observation GROUP BY bundle_version"
            + " <choose><when test='currentBundle != null'>"
            + " ORDER BY CASE WHEN bundle_version=#{currentBundle} THEN 0 ELSE 1 END,"
            + " lastObservedAt DESC NULLS LAST,bundle_version"
            + " </when><otherwise>"
            + " ORDER BY lastObservedAt DESC NULLS LAST,bundle_version"
            + " </otherwise></choose>"
            + " LIMIT #{size} OFFSET #{offset}</script>")
    List<AgentVersionRecords.VersionAggregate> listVersions(
            @Param("currentBundle") String currentBundle,
            @Param("offset") long offset, @Param("size") int size);

    @Select("<script>SELECT count(DISTINCT bundle_version) FROM (" + VERSION_OBSERVATIONS
            + " <if test='currentBundle != null'>"
            + " UNION ALL SELECT CAST(#{currentBundle} AS varchar),"
            + " CAST(NULL AS timestamptz),CAST(NULL AS bigint)"
            + " </if>) version_observation</script>")
    long countVersions(@Param("currentBundle") String currentBundle);

    @Select("SELECT bundle_version AS bundleVersion,min(observed_at) AS firstObservedAt,"
            + " max(observed_at) AS lastObservedAt,count(execution_id) AS runCount FROM ("
            + VERSION_OBSERVATIONS + ") version_observation"
            + " WHERE bundle_version=#{bundleVersion} GROUP BY bundle_version")
    Optional<AgentVersionRecords.VersionAggregate> findVersion(
            @Param("bundleVersion") String bundleVersion);

    @Select("SELECT executionId,source,parentId,taskType,status,stageCode,providerCode,"
            + " providerModel,stableErrorCode,startedAt,finishedAt FROM ("
            + " SELECT x.id AS executionId,'TASK' AS source,x.task_id AS parentId,"
            + " t.task_type AS taskType,x.status,x.stage_code AS stageCode,"
            + " x.provider_code AS providerCode,x.provider_model AS providerModel,"
            + " x.error_code AS stableErrorCode,x.started_at AS startedAt,"
            + " x.finished_at AS finishedAt,x.bundle_version AS bundleVersion"
            + " FROM business.task_execution x"
            + " JOIN business.business_task t ON t.id=x.task_id"
            + " UNION ALL"
            + " SELECT x.id AS executionId,'WORKFLOW' AS source,x.plan_id AS parentId,"
            + " 'WORKFLOW' AS taskType,x.status,NULL AS stageCode,NULL AS providerCode,"
            + " NULL AS providerModel,x.stable_error_code AS stableErrorCode,"
            + " x.started_at AS startedAt,x.finished_at AS finishedAt,"
            + " x.bundle_version AS bundleVersion"
            + " FROM business.workflow_execution x"
            + ") version_run WHERE bundleVersion=#{bundleVersion}"
            + " ORDER BY startedAt DESC,executionId DESC LIMIT #{size} OFFSET #{offset}")
    List<AgentVersionRecords.AssociatedRun> listRuns(
            @Param("bundleVersion") String bundleVersion,
            @Param("offset") long offset, @Param("size") int size);

    @Select("SELECT count(*) FROM ("
            + " SELECT x.id FROM business.task_execution x WHERE x.bundle_version=#{bundleVersion}"
            + " UNION ALL"
            + " SELECT x.id FROM business.workflow_execution x WHERE x.bundle_version=#{bundleVersion}"
            + ") version_run")
    long countRuns(@Param("bundleVersion") String bundleVersion);
}
