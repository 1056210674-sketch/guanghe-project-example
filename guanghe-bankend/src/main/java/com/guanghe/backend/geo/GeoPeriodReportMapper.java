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
public interface GeoPeriodReportMapper {
    record Row(long id, long userId, long spaceId, UUID requestId, String inputHash,
               String snapshotJson, String evidenceJson, String evidenceHash, String status,
               String summaryMarkdown, String summaryError, int summaryAttempts, UUID claimToken,
               OffsetDateTime leaseExpiresAt, int version, OffsetDateTime createdAt, OffsetDateTime updatedAt) { }
    record Topic(long id, String name) { }
    record RunCounts(long total, long completed, long partial, long failed, long running,
                     long expected, long valid, long failedSamples, long missing) { }
    record Publication(long id, long articleId, String articleTitle, String mediaName, String status,
                       OffsetDateTime createdAt, OffsetDateTime publishedAt, String linksJson, String link) { }
    record PublishCounts(long published, long articles, long pending, long failed) { }

    String COLUMNS = "id,user_id AS userId,space_id AS spaceId,request_id AS requestId,input_hash AS inputHash,"
            + "snapshot::text AS snapshotJson,evidence::text AS evidenceJson,evidence_hash AS evidenceHash,status,"
            + "summary_markdown AS summaryMarkdown,summary_error AS summaryError,summary_attempts AS summaryAttempts,"
            + "claim_token AS claimToken,lease_expires_at AS leaseExpiresAt,version,created_at AS createdAt,updated_at AS updatedAt";
    @Select("SELECT CURRENT_TIMESTAMP")
    OffsetDateTime snapshotTime();
    @Select("SELECT name FROM business.business_space WHERE id=#{projectId} AND user_id=#{userId} "
            + "AND project_status='ACTIVE' AND storage_kind='PROJECT'")
    Optional<String> project(@Param("userId") long userId, @Param("projectId") long projectId);
    @Select("SELECT id FROM business.account WHERE id=#{userId} FOR UPDATE")
    Optional<Long> lockOwner(@Param("userId") long userId);
    @Select("SELECT " + COLUMNS + " FROM business.geo_period_report WHERE user_id=#{userId} AND request_id=#{requestId}")
    Optional<Row> byRequest(@Param("userId") long userId,@Param("requestId") UUID requestId);
    @Select("SELECT " + COLUMNS + " FROM business.geo_period_report WHERE user_id=#{userId} AND id=#{id}")
    Optional<Row> find(@Param("userId") long userId,@Param("id") long id);
    @Select("SELECT " + COLUMNS + " FROM business.geo_period_report WHERE user_id=#{userId} AND id=#{id} FOR UPDATE")
    Optional<Row> lock(@Param("userId") long userId,@Param("id") long id);
    @Select("SELECT count(*) FROM business.geo_period_report WHERE user_id=#{userId} AND status IN ('QUEUED','RUNNING')")
    long active(@Param("userId") long userId);
    @Insert("""
            INSERT INTO business.geo_period_report(id,user_id,space_id,request_id,input_hash,snapshot,evidence,evidence_hash,
                status,summary_markdown,created_at,updated_at)
            VALUES(#{r.id},#{r.userId},#{r.spaceId},#{r.requestId},#{r.inputHash},CAST(#{r.snapshotJson} AS jsonb),
                CAST(#{r.evidenceJson} AS jsonb),#{r.evidenceHash},#{r.status},#{r.summaryMarkdown},#{r.createdAt},#{r.updatedAt})
            """)
    int insert(@Param("r") Row row);
    @Select("SELECT " + COLUMNS + " FROM business.geo_period_report WHERE user_id=#{userId} AND space_id=#{projectId} "
            + "ORDER BY created_at DESC,id DESC LIMIT #{size} OFFSET #{offset}")
    List<Row> list(@Param("userId") long userId,@Param("projectId") long projectId,@Param("size") int size,@Param("offset") long offset);
    @Select("SELECT count(*) FROM business.geo_period_report WHERE user_id=#{userId} AND space_id=#{projectId}")
    long count(@Param("userId") long userId,@Param("projectId") long projectId);
    @Select("SELECT " + COLUMNS + " FROM business.geo_period_report WHERE status='QUEUED' ORDER BY created_at,id FOR UPDATE SKIP LOCKED LIMIT 1")
    Optional<Row> next();
    @Update("UPDATE business.geo_period_report SET status='RUNNING',summary_attempts=summary_attempts+1,claim_token=#{token},"
            + "lease_expires_at=#{expires},updated_at=#{now} WHERE id=#{id} AND status='QUEUED' AND summary_attempts<2")
    int claim(@Param("id") long id,@Param("token") UUID token,@Param("expires") OffsetDateTime expires,@Param("now") OffsetDateTime now);
    @Update("UPDATE business.geo_period_report SET status=#{status},summary_markdown=#{summary},summary_error=#{error},"
            + "claim_token=NULL,lease_expires_at=NULL,version=version+1,updated_at=#{now} "
            + "WHERE id=#{id} AND status='RUNNING' AND claim_token=#{token} AND lease_expires_at>#{now}")
    int finish(@Param("id") long id,@Param("token") UUID token,@Param("status") String status,
               @Param("summary") String summary,@Param("error") String error,@Param("now") OffsetDateTime now);
    @Update("UPDATE business.geo_period_report SET status='SUMMARY_UNAVAILABLE',summary_error='SUMMARY_TIMEOUT',"
            + "claim_token=NULL,lease_expires_at=NULL,version=version+1,updated_at=#{now} "
            + "WHERE status='RUNNING' AND lease_expires_at<=#{now}")
    int expire(@Param("now") OffsetDateTime now);
    @Update("UPDATE business.geo_period_report SET status='QUEUED',summary_error=NULL,updated_at=#{now} "
            + "WHERE id=#{id} AND user_id=#{userId} AND status='SUMMARY_UNAVAILABLE' AND summary_attempts<2")
    int retry(@Param("id") long id,@Param("userId") long userId,@Param("now") OffsetDateTime now);

    String RUN_RANGE = " FROM business.geo_run r WHERE r.user_id=#{userId} AND r.space_id=#{projectId} "
            + "AND r.context_snapshot#>>'{parameters,mode}' IS DISTINCT FROM 'DIAGNOSTIC_RUN' "
            + "AND ((r.finished_at>=#{from} AND r.finished_at<#{to}) OR "
            + "(r.finished_at IS NULL AND r.created_at>=#{from} AND r.created_at<#{to}))";
    @Select("SELECT count(*) AS total,count(*) FILTER(WHERE r.status='SUCCEEDED') AS completed,"
            + "count(*) FILTER(WHERE r.status='PARTIAL') AS partial,"
            + "count(*) FILTER(WHERE r.status IN ('FAILED','SYSTEM_TERMINATED')) AS failed,"
            + "count(*) FILTER(WHERE r.status IN ('QUEUED','RUNNING')) AS running,"
            + "COALESCE(sum(r.expected_units) FILTER(WHERE r.status NOT IN ('QUEUED','RUNNING')),0) AS expected,"
            + "COALESCE(sum(r.succeeded_units) FILTER(WHERE r.status NOT IN ('QUEUED','RUNNING')),0) AS valid,"
            + "COALESCE(sum(r.failed_units) FILTER(WHERE r.status NOT IN ('QUEUED','RUNNING')),0) AS failedSamples,"
            + "COALESCE(sum(r.missing_units) FILTER(WHERE r.status NOT IN ('QUEUED','RUNNING')),0) AS missing" + RUN_RANGE)
    RunCounts runs(@Param("userId") long userId,@Param("projectId") long projectId,@Param("from") OffsetDateTime from,@Param("to") OffsetDateTime to);
    @Select("SELECT t.id,t.name FROM business.geo_topic t WHERE t.user_id=#{userId} AND t.space_id=#{projectId} "
            + "AND EXISTS(SELECT 1" + RUN_RANGE + " AND r.topic_id=t.id) ORDER BY t.id LIMIT 7")
    List<Topic> topics(@Param("userId") long userId,@Param("projectId") long projectId,@Param("from") OffsetDateTime from,@Param("to") OffsetDateTime to);
    String PERIOD_BATCH_COLUMNS = "r.id AS runId,p.id AS reportId,r.space_id AS spaceId,r.product_id AS productId,"
            + "r.context_snapshot::text AS contextSnapshotJson,r.metric_version AS metricVersion,r.finished_at AS observedAt";
    @Select("SELECT " + PERIOD_BATCH_COLUMNS + GeoTrendMapper.BATCH_FROM
            + " AND r.space_id=#{projectId} AND r.finished_at>=#{from} "
            + "AND r.finished_at<#{to} ORDER BY r.finished_at,r.id LIMIT 101")
    List<GeoTrendMapper.Batch> batches(@Param("userId") long userId,@Param("projectId") long projectId,
                                     @Param("topicId") long topicId,@Param("from") OffsetDateTime from,@Param("to") OffsetDateTime to);
    @Select("SELECT " + PERIOD_BATCH_COLUMNS + GeoTrendMapper.BATCH_FROM
            + " AND r.space_id=#{projectId} AND r.finished_at<#{from} "
            + "AND r.succeeded_units>0 AND p.id IS NOT NULL ORDER BY r.finished_at DESC,r.id DESC LIMIT 1")
    Optional<GeoTrendMapper.Batch> baseline(@Param("userId") long userId,@Param("projectId") long projectId,
                                          @Param("topicId") long topicId,@Param("from") OffsetDateTime from);
    String ARTICLE_RANGE = " FROM business.article WHERE user_id=#{userId} AND space_id=#{projectId} "
            + "AND created_at>=#{from} AND created_at<#{to}";
    @Select("SELECT count(*)" + ARTICLE_RANGE)
    long articleCount(@Param("userId") long userId,@Param("projectId") long projectId,@Param("from") OffsetDateTime from,@Param("to") OffsetDateTime to);
    @Select("SELECT COALESCE(jsonb_object_agg(status,total ORDER BY status),'{}'::jsonb)::text "
            + "FROM (SELECT status,count(*) AS total" + ARTICLE_RANGE + " GROUP BY status) grouped")
    String articleStatusCounts(@Param("userId") long userId,@Param("projectId") long projectId,
                               @Param("from") OffsetDateTime from,@Param("to") OffsetDateTime to);
    @Select("SELECT id::text AS id,title,version,created_at AS createdAt" + ARTICLE_RANGE + " ORDER BY created_at,id LIMIT 100")
    List<GeoPeriodReportDtos.Article> articles(@Param("userId") long userId,@Param("projectId") long projectId,
                                            @Param("from") OffsetDateTime from,@Param("to") OffsetDateTime to);
    @Select("SELECT count(*) FILTER(WHERE status='PUBLISHED' AND published_at>=#{from} AND published_at<#{to}) AS published,"
            + "count(DISTINCT article_id) FILTER(WHERE status='PUBLISHED' AND published_at>=#{from} AND published_at<#{to}) AS articles,"
            + "count(*) FILTER(WHERE created_at>=#{from} AND created_at<#{to} AND status NOT IN ('PUBLISHED','FAILED','REJECTED')) AS pending,"
            + "count(*) FILTER(WHERE created_at>=#{from} AND created_at<#{to} AND status IN ('FAILED','REJECTED')) AS failed "
            + "FROM business.media_submission WHERE user_id=#{userId} AND space_id=#{projectId}")
    PublishCounts publicationCount(@Param("userId") long userId,@Param("projectId") long projectId,
                                   @Param("from") OffsetDateTime from,@Param("to") OffsetDateTime to);
    @Select("SELECT s.id,s.article_id AS articleId,a.title AS articleTitle,s.media_name AS mediaName,s.status,"
            + "s.created_at AS createdAt,s.published_at AS publishedAt,s.published_urls::text AS linksJson,s.published_url AS link "
            + "FROM business.media_submission s JOIN business.article_submission_snapshot a ON a.id=s.article_snapshot_id "
            + "AND a.user_id=s.user_id AND a.space_id=s.space_id WHERE s.user_id=#{userId} AND s.space_id=#{projectId} "
            + "AND ((s.status='PUBLISHED' AND s.published_at>=#{from} AND s.published_at<#{to}) "
            + "OR (s.created_at>=#{from} AND s.created_at<#{to})) ORDER BY COALESCE(s.published_at,s.created_at),s.id LIMIT 101")
    List<Publication> publications(@Param("userId") long userId,@Param("projectId") long projectId,
                                  @Param("from") OffsetDateTime from,@Param("to") OffsetDateTime to);
    @Select("""
            SELECT count(DISTINCT link) FROM (
              SELECT jsonb_array_elements_text(CASE WHEN jsonb_array_length(published_urls)>0 THEN published_urls
                WHEN published_url IS NOT NULL THEN jsonb_build_array(published_url) ELSE '[]'::jsonb END) AS link
              FROM business.media_submission WHERE user_id=#{userId} AND space_id=#{projectId}
                AND status='PUBLISHED' AND published_at>=#{from} AND published_at<#{to}
            ) urls WHERE link ~ '^https?://'
            """)
    long linkCount(@Param("userId") long userId,@Param("projectId") long projectId,@Param("from") OffsetDateTime from,@Param("to") OffsetDateTime to);
}
