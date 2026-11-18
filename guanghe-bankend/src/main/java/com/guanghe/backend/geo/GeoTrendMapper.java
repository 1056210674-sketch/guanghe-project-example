package com.guanghe.backend.geo;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** Finite owner-filtered reads. Neither synthesis nor citations are needed to compute a trend. */
@Mapper
public interface GeoTrendMapper {
    record Batch(long runId, Long reportId, long spaceId, Long productId, String contextSnapshotJson,
                 String metricVersion, OffsetDateTime observedAt) { }
    record Evidence(long runId, String unitKey, long questionId, String questionText, String platformCode,
                    int sampleNo, String status, boolean hasAnswer, String answerText, String provider,
                    String model, String collectionMethod, boolean evidenceTruncated) { }

    String BATCH_COLUMNS = "r.id AS runId,p.id AS reportId,r.space_id AS spaceId,r.product_id AS productId,"
            + "r.context_snapshot::text AS contextSnapshotJson,r.metric_version AS metricVersion,"
            + "COALESCE(r.started_at,r.created_at) AS observedAt";
    String BATCH_FROM = " FROM business.geo_run r LEFT JOIN business.geo_report p"
            + " ON p.run_id=r.id AND p.user_id=r.user_id WHERE r.user_id=#{userId} AND r.topic_id=#{topicId}"
            + " AND r.context_snapshot#>>'{parameters,mode}' IS DISTINCT FROM 'DIAGNOSTIC_RUN' AND r.status NOT IN ('QUEUED','RUNNING')";

    @Select("SELECT " + BATCH_COLUMNS + BATCH_FROM
            + " ORDER BY COALESCE(r.started_at,r.created_at),r.id LIMIT #{limit}")
    List<Batch> firstBatches(@Param("userId") long userId, @Param("topicId") long topicId,
                             @Param("limit") int limit);

    @Select("SELECT " + BATCH_COLUMNS + BATCH_FROM
            + " AND r.id=#{runId} LIMIT 1")
    Optional<Batch> anchorBatch(@Param("userId") long userId, @Param("topicId") long topicId,
                                @Param("runId") long runId);

    @Select("SELECT " + BATCH_COLUMNS + BATCH_FROM
            + " AND (COALESCE(r.started_at,r.created_at),r.id) <= (#{through},#{runId})"
            + " ORDER BY COALESCE(r.started_at,r.created_at),r.id LIMIT #{limit}")
    List<Batch> firstBatchesThrough(@Param("userId") long userId, @Param("topicId") long topicId,
                                    @Param("through") OffsetDateTime through, @Param("runId") long runId,
                                    @Param("limit") int limit);

    @Select("SELECT " + BATCH_COLUMNS + BATCH_FROM
            + " AND COALESCE(r.started_at,r.created_at) >= #{from}"
            + " AND COALESCE(r.started_at,r.created_at) <= #{to}"
            + " ORDER BY COALESCE(r.started_at,r.created_at) DESC,r.id DESC LIMIT #{limit}")
    List<Batch> recentBatches(@Param("userId") long userId, @Param("topicId") long topicId,
                              @Param("from") OffsetDateTime from, @Param("to") OffsetDateTime to,
                              @Param("limit") int limit);

    @Select("SELECT " + BATCH_COLUMNS + BATCH_FROM
            + " AND COALESCE(r.started_at,r.created_at) >= #{from}"
            + " AND (COALESCE(r.started_at,r.created_at),r.id) <= (#{through},#{runId})"
            + " ORDER BY COALESCE(r.started_at,r.created_at) DESC,r.id DESC LIMIT #{limit}")
    List<Batch> recentBatchesThrough(@Param("userId") long userId, @Param("topicId") long topicId,
                                     @Param("from") OffsetDateTime from, @Param("through") OffsetDateTime through,
                                     @Param("runId") long runId, @Param("limit") int limit);

    @Select("SELECT " + BATCH_COLUMNS + BATCH_FROM
            + " AND (COALESCE(r.started_at,r.created_at),r.id) < (#{before},#{runId})"
            + " ORDER BY COALESCE(r.started_at,r.created_at) DESC,r.id DESC LIMIT 1")
    List<Batch> precedingBatch(@Param("userId") long userId, @Param("topicId") long topicId,
                                @Param("before") OffsetDateTime before, @Param("runId") long runId);

    @Select("""
            <script>
            SELECT runId,unitKey,questionId,questionText,platformCode,sampleNo,status,hasAnswer,
                   CASE WHEN answerBytes &lt;= #{byteLimit} THEN answerText ELSE NULL END AS answerText,
                   provider,model,collectionMethod,answerBytes > #{byteLimit} AND hasAnswer AS evidenceTruncated
              FROM (
                SELECT o.run_id AS runId,o.unit_key AS unitKey,o.question_id AS questionId,
                       o.question_text_snapshot AS questionText,o.platform_code AS platformCode,
                       o.sample_no AS sampleNo,o.status,
                       NULLIF(BTRIM(o.answer_text),'') IS NOT NULL AS hasAnswer,o.answer_text AS answerText,
                       o.provider,o.model,o.collection_method AS collectionMethod,
                       SUM(COALESCE(octet_length(o.answer_text),0)) OVER
                         (ORDER BY o.run_id DESC,o.unit_sequence,o.id) AS answerBytes
                  FROM business.geo_observation o
                 WHERE o.user_id=#{userId} AND o.run_id IN
                 <foreach collection="runIds" item="runId" open="(" separator="," close=")">#{runId}</foreach>
                 ORDER BY o.run_id DESC,o.unit_sequence,o.id LIMIT #{limit}
              ) bounded
             ORDER BY runId,unitKey
            </script>
            """)
    List<Evidence> evidence(@Param("userId") long userId, @Param("runIds") List<Long> runIds,
                            @Param("byteLimit") long byteLimit, @Param("limit") int limit);
}
