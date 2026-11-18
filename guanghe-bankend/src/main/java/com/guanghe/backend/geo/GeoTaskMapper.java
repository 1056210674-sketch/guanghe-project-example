package com.guanghe.backend.geo;

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
public interface GeoTaskMapper {
    @Select("SELECT EXISTS(SELECT 1 FROM business.business_space WHERE id=#{spaceId}"
            + " AND user_id=#{userId} AND project_status='ACTIVE' AND storage_kind='PROJECT')")
    boolean activeProject(@Param("userId") long userId, @Param("spaceId") long spaceId);

    record ReportMetadata(long runId, String topicName, String platformCodesJson) { }

    @Select("""
            <script>SELECT id AS runId,context_snapshot#>>'{authorizedContext,topic,name}' AS topicName,
                   COALESCE(context_snapshot#>'{parameters,platformCodes}','[]'::jsonb)::text AS platformCodesJson
              FROM business.geo_run WHERE user_id=#{userId} AND id IN
              <foreach collection="runIds" item="runId" open="(" separator="," close=")">#{runId}</foreach>
            </script>
            """)
    List<ReportMetadata> reportMetadata(@Param("userId") long userId, @Param("runIds") List<Long> runIds);

    String RUN_COLUMNS = """
            r.id, r.task_id AS taskId, r.user_id AS userId, r.space_id AS spaceId,
            r.product_id AS productId, r.topic_id AS topicId, r.status,
            r.sample_mode AS sampleMode, r.expected_units AS expectedUnits,
            r.succeeded_units AS succeededUnits, r.failed_units AS failedUnits,
            r.missing_units AS missingUnits, r.context_snapshot::text AS contextSnapshotJson,
            r.metric_version AS metricVersion, r.method_version AS methodVersion,
            r.started_at AS startedAt, r.finished_at AS finishedAt,
            r.created_at AS createdAt, r.updated_at AS updatedAt,
            t.quoted_points AS quotedPoints, t.settled_points AS settledPoints,
            COALESCE(p.released_points, 0) AS releasedPoints
            """;

    String OBSERVATION_COLUMNS = """
            o.id, o.run_id AS runId, o.task_id AS taskId, o.user_id AS userId,
            o.source_event_id AS sourceEventId, o.request_hash AS requestHash,
            o.unit_key AS unitKey, o.unit_sequence AS unitSequence, o.question_id AS questionId,
            o.question_text_snapshot AS questionTextSnapshot, o.platform_code AS platformCode,
            o.sample_no AS sampleNo, o.status, o.answer_text AS answerText,
            o.stable_error_code AS stableErrorCode, o.provider, o.model,
            o.started_at AS startedAt, o.finished_at AS finishedAt,
            o.latency_ms AS latencyMs, o.usage::text AS usageJson,
            o.provider_call_count AS providerCallCount, o.created_at AS createdAt,
            o.collection_method AS collectionMethod
            """;

    @Insert("""
            INSERT INTO business.geo_run(
                id, task_id, user_id, space_id, product_id, topic_id, status, sample_mode,
                expected_units, succeeded_units, failed_units, missing_units, context_snapshot,
                metric_version, method_version, created_at, updated_at
            ) VALUES (
                #{r.id}, #{r.taskId}, #{r.userId}, #{r.spaceId}, #{r.productId}, #{r.topicId},
                #{r.status}, #{r.sampleMode}, #{r.expectedUnits}, 0, 0, #{r.expectedUnits},
                CAST(#{r.contextSnapshotJson} AS jsonb), #{r.metricVersion}, #{r.methodVersion},
                #{r.createdAt}, #{r.updatedAt}
            )
            """)
    int insertRun(@Param("r") GeoTaskRecords.Run run);

    @Select("SELECT " + RUN_COLUMNS + " FROM business.geo_run r"
            + " JOIN business.business_task t ON t.id=r.task_id"
            + " LEFT JOIN business.point_reservation p ON p.task_id=r.task_id"
            + " WHERE r.task_id=#{taskId}")
    Optional<GeoTaskRecords.Run> findRunByTask(@Param("taskId") long taskId);

    @Select("SELECT " + RUN_COLUMNS + " FROM business.geo_run r"
            + " JOIN business.business_task t ON t.id=r.task_id"
            + " LEFT JOIN business.point_reservation p ON p.task_id=r.task_id"
            + " JOIN business.geo_topic topic ON topic.id=r.topic_id AND topic.topic_kind='MONITORING'"
            + " JOIN business.business_space s ON s.id=r.space_id AND s.user_id=r.user_id"
            + " AND s.storage_kind='PROJECT'"
            + " WHERE r.id=#{runId} AND r.user_id=#{userId}")
    Optional<GeoTaskRecords.Run> findRun(@Param("runId") long runId, @Param("userId") long userId);

    @Select("SELECT " + RUN_COLUMNS + " FROM business.geo_run r"
            + " JOIN business.business_task t ON t.id=r.task_id"
            + " LEFT JOIN business.point_reservation p ON p.task_id=r.task_id"
            + " WHERE r.task_id=#{taskId} FOR UPDATE OF r")
    Optional<GeoTaskRecords.Run> lockRunByTask(@Param("taskId") long taskId);

    @Select("""
            <script>SELECT
            """ + RUN_COLUMNS + """
              FROM business.geo_run r
              JOIN business.business_task t ON t.id=r.task_id
              JOIN business.geo_topic topic ON topic.id=r.topic_id AND topic.topic_kind='MONITORING'
              JOIN business.business_space s ON s.id=r.space_id AND s.user_id=r.user_id
               AND s.storage_kind='PROJECT'
              LEFT JOIN business.point_reservation p ON p.task_id=r.task_id
             WHERE r.user_id=#{userId}
            <if test="topicId != null"> AND r.topic_id=#{topicId}</if>
            <if test="status != null"> AND r.status=#{status}</if>
             ORDER BY r.created_at DESC,r.id DESC LIMIT #{size} OFFSET #{offset}</script>
            """)
    List<GeoTaskRecords.Run> findRunPage(@Param("userId") long userId,
                                         @Param("topicId") Long topicId,
                                         @Param("status") String status,
                                         @Param("offset") long offset,
                                         @Param("size") int size);

    @Select("""
            <script>SELECT count(*) FROM business.geo_run r
              JOIN business.geo_topic topic ON topic.id=r.topic_id AND topic.topic_kind='MONITORING'
              JOIN business.business_space s ON s.id=r.space_id AND s.user_id=r.user_id
               AND s.storage_kind='PROJECT'
             WHERE r.user_id=#{userId}
            <if test="topicId != null"> AND r.topic_id=#{topicId}</if>
            <if test="status != null"> AND r.status=#{status}</if></script>
            """)
    long countRuns(@Param("userId") long userId, @Param("topicId") Long topicId,
                   @Param("status") String status);

    @Update("UPDATE business.geo_run SET status='RUNNING',started_at=COALESCE(started_at,#{now}),"
            + "updated_at=#{now} WHERE task_id=#{taskId} AND status IN ('QUEUED','RUNNING')")
    int markRunRunning(@Param("taskId") long taskId, @Param("now") OffsetDateTime now);

    @Update("""
            UPDATE business.geo_run r SET
              succeeded_units=(SELECT count(*) FROM business.geo_observation o
                                WHERE o.run_id=r.id AND o.status='SUCCEEDED'),
              failed_units=(SELECT count(*) FROM business.geo_observation o
                             WHERE o.run_id=r.id AND o.status='FAILED'),
              missing_units=r.expected_units-(SELECT count(*) FROM business.geo_observation o
                                               WHERE o.run_id=r.id),
              updated_at=#{now}
             WHERE r.id=#{runId} AND r.status='RUNNING'
            """)
    int refreshRunCounts(@Param("runId") long runId, @Param("now") OffsetDateTime now);

    @Update("""
            UPDATE business.geo_run r SET status=#{status},
              succeeded_units=(SELECT count(*) FROM business.geo_observation o
                                WHERE o.run_id=r.id AND o.status='SUCCEEDED'),
              failed_units=(SELECT count(*) FROM business.geo_observation o
                             WHERE o.run_id=r.id AND o.status='FAILED'),
              missing_units=r.expected_units-(SELECT count(*) FROM business.geo_observation o
                                               WHERE o.run_id=r.id),
              finished_at=#{now},updated_at=#{now}
             WHERE r.task_id=#{taskId} AND r.status IN ('QUEUED','RUNNING')
            """)
    int finishRun(@Param("taskId") long taskId, @Param("status") String status,
                  @Param("now") OffsetDateTime now);

    @Select("""
            SELECT m.competitor_id AS id, m.competitor_name_snapshot AS name,
                   m.competitor_type_snapshot AS type,
                   COALESCE(jsonb_agg(a.alias ORDER BY a.alias_normalized)
                     FILTER (WHERE a.alias IS NOT NULL),'[]'::jsonb)::text AS aliasesJson
              FROM business.geo_competitor_set_member m
              LEFT JOIN business.competitor_alias a
                ON a.competitor_id=m.competitor_id AND a.user_id=m.user_id AND a.space_id=m.space_id
             WHERE m.competitor_set_id=#{competitorSetId}
             GROUP BY m.id,m.competitor_id,m.competitor_name_snapshot,m.competitor_type_snapshot,m.sequence_no
             ORDER BY m.sequence_no
            """)
    List<GeoTaskRecords.CompetitorSnapshot> findCompetitorSnapshots(
            @Param("competitorSetId") long competitorSetId);

    @Select("SELECT " + OBSERVATION_COLUMNS + " FROM business.geo_observation o"
            + " WHERE o.run_id=#{runId} AND o.source_event_id=#{eventId}")
    Optional<GeoTaskRecords.Observation> findObservationByEvent(
            @Param("runId") long runId, @Param("eventId") UUID eventId);

    @Select("SELECT " + OBSERVATION_COLUMNS + " FROM business.geo_observation o"
            + " WHERE o.run_id=#{runId} AND o.unit_key=#{unitKey}")
    Optional<GeoTaskRecords.Observation> findObservationByUnit(
            @Param("runId") long runId, @Param("unitKey") String unitKey);

    @Insert("""
            INSERT INTO business.geo_observation(
              id,run_id,task_id,user_id,source_event_id,request_hash,unit_key,question_id,
              unit_sequence,question_text_snapshot,platform_code,sample_no,status,answer_text,stable_error_code,
              provider,model,started_at,finished_at,latency_ms,usage,provider_call_count,created_at,collection_method
            ) VALUES (
              #{o.id},#{o.runId},#{o.taskId},#{o.userId},#{o.sourceEventId},#{o.requestHash},
              #{o.unitKey},#{o.questionId},#{o.unitSequence},#{o.questionTextSnapshot},#{o.platformCode},#{o.sampleNo},
              #{o.status},#{o.answerText},#{o.stableErrorCode},#{o.provider},#{o.model},
              #{o.startedAt},#{o.finishedAt},#{o.latencyMs},CAST(#{o.usageJson} AS jsonb),
              #{o.providerCallCount},#{o.createdAt},#{o.collectionMethod}
            ) ON CONFLICT DO NOTHING
            """)
    int insertObservation(@Param("o") GeoTaskRecords.Observation observation);

    @Insert("""
            INSERT INTO business.geo_citation(
              id,observation_id,run_id,user_id,sequence_no,title,url,domain,snippet,observed_at,created_at
            ) VALUES (#{c.id},#{c.observationId},#{c.runId},#{c.userId},#{c.sequence},#{c.title},
              #{c.url},#{c.domain},#{c.snippet},#{c.observedAt},#{c.createdAt})
            """)
    int insertCitation(@Param("c") GeoTaskRecords.Citation citation);

    @Select("SELECT " + OBSERVATION_COLUMNS + " FROM business.geo_observation o"
            + " WHERE o.run_id=#{runId} ORDER BY o.unit_sequence"
            + " LIMIT #{size} OFFSET #{offset}")
    List<GeoTaskRecords.Observation> findObservationPage(@Param("runId") long runId,
                                                         @Param("offset") long offset,
                                                         @Param("size") int size);

    @Select("SELECT " + OBSERVATION_COLUMNS + " FROM business.geo_observation o"
            + " WHERE o.run_id=#{runId} AND o.user_id=#{userId} AND o.status='SUCCEEDED'"
            + " AND NULLIF(BTRIM(o.answer_text),'') IS NOT NULL"
            + " AND NULLIF(BTRIM(o.question_text_snapshot),'') IS NOT NULL"
            + " AND NULLIF(BTRIM(o.platform_code),'') IS NOT NULL"
            + " AND length(o.unit_key) BETWEEN 1 AND 80"
            + " ORDER BY o.unit_sequence LIMIT #{size}")
    List<GeoTaskRecords.Observation> findSuccessfulEvidence(@Param("runId") long runId,
                                                             @Param("userId") long userId,
                                                             @Param("size") int size);

    @Select("SELECT count(*) FROM business.geo_observation WHERE run_id=#{runId}")
    long countObservations(@Param("runId") long runId);

    @Select("""
            SELECT id,observation_id AS observationId,run_id AS runId,user_id AS userId,
                   sequence_no AS sequence,title,url,domain,snippet,observed_at AS observedAt,
                   created_at AS createdAt
              FROM business.geo_citation WHERE observation_id=#{observationId}
             ORDER BY sequence_no
            """)
    List<GeoTaskRecords.Citation> findCitations(@Param("observationId") long observationId);

    @Select("""
            SELECT observation_id AS observationId,title,url,domain,snippet,observed_at AS observedAt,
                   total AS totalForObservation
              FROM (SELECT c.*,count(*) OVER(PARTITION BY observation_id)::integer AS total,
                           row_number() OVER(PARTITION BY observation_id ORDER BY sequence_no,id) AS rank
                      FROM business.geo_citation c WHERE run_id=#{runId} AND user_id=#{userId}) bounded
             WHERE rank<=3 ORDER BY observation_id,sequence_no,id LIMIT 630
            """)
    List<GeoTaskRecords.InterpretationCitation> findInterpretationCitations(@Param("runId") long runId,@Param("userId") long userId);

    @Insert("""
            INSERT INTO business.geo_report(
              id,run_id,task_id,user_id,topic_id,status,synthesis,synthesis_usage,summary,
              method_version,sample_size,generated_at,created_at
            ) VALUES (#{r.id},#{r.runId},#{r.taskId},#{r.userId},#{r.topicId},#{r.status},
              CAST(#{r.synthesisJson} AS jsonb),CAST(#{r.synthesisUsageJson} AS jsonb),#{r.summary},
              #{r.methodVersion},#{r.sampleSize},#{r.generatedAt},#{r.createdAt})
            ON CONFLICT (run_id) DO NOTHING
            """)
    int insertReport(@Param("r") GeoTaskRecords.Report report);

    @Select("""
            SELECT r.id,r.run_id AS runId,r.task_id AS taskId,r.user_id AS userId,r.topic_id AS topicId,
                   r.status,r.synthesis::text AS synthesisJson,r.synthesis_usage::text AS synthesisUsageJson,
                   r.summary,r.method_version AS methodVersion,r.sample_size AS sampleSize,
                   r.generated_at AS generatedAt,r.created_at AS createdAt,
                   t.settled_points AS settledPoints,COALESCE(p.released_points,0) AS releasedPoints,
                   r.archived_at AS archivedAt
              FROM business.geo_report r JOIN business.business_task t ON t.id=r.task_id
              LEFT JOIN business.point_reservation p ON p.task_id=r.task_id
             WHERE r.run_id=#{runId}
            """)
    Optional<GeoTaskRecords.Report> findReportByRun(@Param("runId") long runId);

    @Select("""
            SELECT r.id,r.run_id AS runId,r.task_id AS taskId,r.user_id AS userId,r.topic_id AS topicId,
                   r.status,r.synthesis::text AS synthesisJson,r.synthesis_usage::text AS synthesisUsageJson,
                   r.summary,r.method_version AS methodVersion,r.sample_size AS sampleSize,
                   r.generated_at AS generatedAt,r.created_at AS createdAt,
                   t.settled_points AS settledPoints,COALESCE(p.released_points,0) AS releasedPoints,
                   r.archived_at AS archivedAt
              FROM business.geo_report r JOIN business.business_task t ON t.id=r.task_id
              JOIN business.geo_run g ON g.id=r.run_id AND g.user_id=r.user_id
              JOIN business.business_space s ON s.id=g.space_id AND s.user_id=g.user_id
               AND s.storage_kind='PROJECT'
              LEFT JOIN business.point_reservation p ON p.task_id=r.task_id
             WHERE r.id=#{reportId} AND r.user_id=#{userId}
            """)
    Optional<GeoTaskRecords.Report> findReport(@Param("reportId") long reportId,
                                               @Param("userId") long userId);

    @Select("SELECT id FROM business.account WHERE id=#{userId} FOR UPDATE")
    Optional<Long> lockOwner(@Param("userId") long userId);

    @Select("SELECT r.id FROM business.geo_report r WHERE r.id=#{reportId} AND r.user_id=#{userId}"
            + " AND EXISTS(SELECT 1 FROM business.geo_run g JOIN business.business_space s"
            + " ON s.id=g.space_id AND s.user_id=g.user_id AND s.storage_kind='PROJECT'"
            + " WHERE g.id=r.run_id AND g.user_id=r.user_id) FOR UPDATE OF r")
    Optional<Long> lockReport(@Param("reportId") long reportId, @Param("userId") long userId);

    @Update("""
            UPDATE business.geo_report r
               SET archived_at=COALESCE(archived_at,#{archivedAt})
             WHERE r.id=#{reportId} AND r.user_id=#{userId}
               AND EXISTS(SELECT 1 FROM business.geo_run g JOIN business.business_space s
                     ON s.id=g.space_id AND s.user_id=g.user_id AND s.storage_kind='PROJECT'
                    WHERE g.id=r.run_id AND g.user_id=r.user_id)
            """)
    int archiveReport(@Param("reportId") long reportId, @Param("userId") long userId,
                      @Param("archivedAt") OffsetDateTime archivedAt);

    @Update("""
            UPDATE business.geo_report r SET archived_at=NULL
             WHERE r.id=#{reportId} AND r.user_id=#{userId}
               AND EXISTS(SELECT 1 FROM business.geo_run g JOIN business.business_space s
                     ON s.id=g.space_id AND s.user_id=g.user_id AND s.storage_kind='PROJECT'
                    WHERE g.id=r.run_id AND g.user_id=r.user_id)
            """)
    int restoreReport(@Param("reportId") long reportId, @Param("userId") long userId);

    @Select("""
            <script>SELECT r.id,r.run_id AS runId,r.task_id AS taskId,r.user_id AS userId,
                   r.topic_id AS topicId,r.status,r.synthesis::text AS synthesisJson,
                   r.synthesis_usage::text AS synthesisUsageJson,r.summary,
                   r.method_version AS methodVersion,r.sample_size AS sampleSize,
                   r.generated_at AS generatedAt,r.created_at AS createdAt,
                   t.settled_points AS settledPoints,COALESCE(p.released_points,0) AS releasedPoints,
                   r.archived_at AS archivedAt
              FROM business.geo_report r JOIN business.business_task t ON t.id=r.task_id
              JOIN business.geo_run g ON g.id=r.run_id AND g.user_id=r.user_id
              JOIN business.business_space s ON s.id=g.space_id AND s.user_id=g.user_id
               AND s.storage_kind='PROJECT'
              LEFT JOIN business.point_reservation p ON p.task_id=r.task_id
             WHERE r.user_id=#{userId}
            <if test="spaceId != null"> AND g.space_id=#{spaceId}</if>
            <if test="topicId != null"> AND r.topic_id=#{topicId}</if>
            <if test="archived != null"> AND (r.archived_at IS NOT NULL)=#{archived}</if>
             ORDER BY r.generated_at DESC,r.id DESC LIMIT #{size} OFFSET #{offset}</script>
            """)
    List<GeoTaskRecords.Report> findReportPage(@Param("userId") long userId,
                                               @Param("spaceId") Long spaceId,
                                               @Param("topicId") Long topicId,
                                               @Param("archived") Boolean archived,
                                               @Param("offset") long offset,
                                               @Param("size") int size);

    default List<GeoTaskRecords.Report> findReportPage(long userId, Long topicId, Boolean archived,
            long offset, int size) {
        return findReportPage(userId, null, topicId, archived, offset, size);
    }

    @Select("""
            <script>SELECT count(*) FROM business.geo_report r
              JOIN business.geo_run g ON g.id=r.run_id AND g.user_id=r.user_id
              JOIN business.business_space s ON s.id=g.space_id AND s.user_id=g.user_id
               AND s.storage_kind='PROJECT'
             WHERE r.user_id=#{userId}
            <if test="spaceId != null"> AND g.space_id=#{spaceId}</if>
            <if test="topicId != null"> AND r.topic_id=#{topicId}</if>
            <if test="archived != null"> AND (r.archived_at IS NOT NULL)=#{archived}</if></script>
            """)
    long countReports(@Param("userId") long userId, @Param("spaceId") Long spaceId,
                      @Param("topicId") Long topicId,
                      @Param("archived") Boolean archived);

    default long countReports(long userId, Long topicId, Boolean archived) {
        return countReports(userId, null, topicId, archived);
    }
}
