package com.guanghe.backend.media;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface MediaMapper {
    @Insert("""
            INSERT INTO business.media_submission_image(submission_id,asset_id,user_id,space_id,
                object_key,sha256,size_bytes,content_type,created_at,published_object_key)
            SELECT #{submissionId},a.id,a.user_id,a.space_id,a.object_key,a.sha256,a.size_bytes,a.content_type,#{now},#{publicationKey}
            FROM business.asset a WHERE a.id=#{assetId} AND a.user_id=#{userId} AND a.space_id=#{spaceId}
              AND a.status='READY' AND a.asset_type='IMAGE' AND a.object_key=#{objectKey}
              AND a.sha256=#{sha256} AND a.size_bytes=#{sizeBytes} AND a.content_type=#{contentType}
            FOR SHARE OF a
            """)
    int pinSubmissionImage(@Param("submissionId") long submissionId, @Param("userId") long userId,
            @Param("spaceId") long spaceId, @Param("assetId") long assetId, @Param("objectKey") String objectKey,
            @Param("sha256") String sha256, @Param("sizeBytes") long sizeBytes,
            @Param("contentType") String contentType, @Param("publicationKey") String publicationKey,
            @Param("now") OffsetDateTime now);

    @Select("SELECT i.asset_id AS assetId,i.object_key AS objectKey,i.sha256,i.size_bytes AS sizeBytes,"
            + "i.content_type AS contentType,i.published_object_key AS publicationKey "
            + "FROM business.media_submission_image i JOIN business.media_submission s ON s.id=i.submission_id "
            + "WHERE i.submission_id=#{submissionId} AND i.user_id=#{userId} AND i.space_id=#{spaceId} "
            + "AND s.article_snapshot_id=#{snapshotId} AND s.status='SUBMITTING' "
            + "AND s.submission_state='NOT_SUBMITTED' ORDER BY i.asset_id")
    List<PublicationImage> publicationImages(@Param("submissionId") long submissionId,
            @Param("userId") long userId, @Param("spaceId") long spaceId, @Param("snapshotId") long snapshotId);

    @Update("UPDATE business.media_submission_image i SET publication_ready_at=COALESCE(publication_ready_at,#{now}) "
            + "WHERE i.submission_id=#{submissionId} AND published_object_key IS NOT NULL "
            + "AND EXISTS(SELECT 1 FROM business.media_submission s WHERE s.id=i.submission_id "
            + "AND s.status='SUBMITTING' AND s.submission_state='NOT_SUBMITTED')")
    int markPublicationImagesReady(@Param("submissionId") long submissionId, @Param("now") OffsetDateTime now);

    record PublicationImage(long assetId, String objectKey, String sha256, long sizeBytes,
                            String contentType, String publicationKey) { }

    String RESOURCE_COLUMNS = "r.id,r.provider_code AS providerCode,r.upstream_model_id AS upstreamModelId,"
            + "r.upstream_resource_id AS upstreamResourceId,r.name,r.status,r.resource_type AS resourceType,"
            + "r.channel_category AS channelCategory,r.region,r.estimated_days AS estimatedDays,"
            + "r.capabilities::text AS capabilitiesJson,r.upstream_price AS upstreamPrice,"
            + "r.raw_json::text AS rawJson,r.last_seen_at AS lastSeenAt,r.synced_at AS syncedAt,r.version";
    String SUBMISSION_COLUMNS = "s.id,s.batch_id AS batchId,s.task_id AS taskId,s.user_id AS userId,"
            + "s.space_id AS spaceId,s.article_id AS articleId,s.article_snapshot_id AS articleSnapshotId,"
            + "s.media_resource_id AS mediaResourceId,s.price_snapshot_id AS priceSnapshotId,"
            + "s.provider_code AS providerCode,s.upstream_model_id AS upstreamModelId,"
            + "s.upstream_resource_id AS upstreamResourceId,s.media_name AS mediaName,"
            + "s.upstream_price AS upstreamPrice,s.markup_rate AS markupRate,"
            + "s.sale_amount_cents AS saleAmountCents,s.charged_points AS chargedPoints,s.remark,"
            + "s.customer_order_id AS customerOrderId,s.upstream_order_id AS upstreamOrderId,"
            + "s.upstream_order_no AS upstreamOrderNo,s.upstream_status AS upstreamStatus,s.status,"
            + "s.submission_state AS submissionState,s.published_url AS publishedUrl,"
            + "s.published_urls::text AS publishedUrlsJson,"
            + "s.stable_error_code AS stableErrorCode,s.submit_attempted_at AS submitAttemptedAt,"
            + "s.next_poll_at AS nextPollAt,s.published_at AS publishedAt,s.rejected_at AS rejectedAt,"
            + "s.created_at AS createdAt,s.updated_at AS updatedAt";
    /**
     * The back office never selects `payload_hash`: it is a digest of the raw upstream body, and the
     * operator-facing contract identifies a receipt by its own id instead.
     */
    String CALLBACK_COLUMNS = "c.id,c.provider_code AS providerCode,c.customer_order_id AS customerOrderId,"
            + "c.upstream_model_id AS upstreamModelId,c.upstream_resource_id AS upstreamResourceId,"
            + "c.event_type AS eventType,c.status,c.attempts,c.stable_error_code AS stableErrorCode,"
            + "c.received_at AS receivedAt,c.processed_at AS processedAt,c.updated_at AS updatedAt";
    String SYNC_STATE_COLUMNS = "t.provider_code AS providerCode,t.upstream_model_id AS upstreamModelId,"
            + "t.status,t.last_attempted_at AS lastAttemptedAt,t.last_succeeded_at AS lastSucceededAt,"
            + "t.last_error_code AS lastErrorCode,t.last_resource_count AS lastResourceCount,"
            + "t.version,t.updated_at AS updatedAt";

    @Select("<script>SELECT " + RESOURCE_COLUMNS + " FROM business.media_resource r WHERE 1=1"
            + " <if test='search != null'>AND lower(r.name) LIKE lower(#{search})</if>"
            + " <if test='modelId != null'>AND r.upstream_model_id=#{modelId}</if>"
            + " <if test='providerCode != null'>AND r.provider_code=#{providerCode}"
            + " AND r.upstream_model_id=#{supportedModel}</if>"
            + " <if test='type != null'>AND r.resource_type=#{type}</if>"
            + " <if test='status != null and freshAfter == null'>AND r.status=#{status}</if>"
            + " <if test='status != null and freshAfter != null'>"
            + " AND ((#{status}='ACTIVE' AND r.status='ACTIVE' AND r.synced_at&gt;=#{freshAfter})"
            + " OR (#{status}='INACTIVE' AND (r.status='INACTIVE' OR r.synced_at&lt;#{freshAfter})))"
            + " </if>"
            + " <if test='minPrice != null'>AND CEIL(r.upstream_price*1.35)*100&gt;=#{minPrice}</if>"
            + " <if test='maxPrice != null'>AND CEIL(r.upstream_price*1.35)*100&lt;=#{maxPrice}</if>"
            + " ORDER BY r.upstream_price,r.id LIMIT #{limit} OFFSET #{offset}</script>")
    List<MediaRecords.Resource> listResources(@Param("search") String search, @Param("modelId") String modelId,
            @Param("type") String type, @Param("status") String status, @Param("minPrice") Integer minPrice,
            @Param("maxPrice") Integer maxPrice, @Param("freshAfter") OffsetDateTime freshAfter,
            @Param("providerCode") String providerCode, @Param("supportedModel") String supportedModel,
            @Param("limit") int limit, @Param("offset") long offset);

    @Select("SELECT " + RESOURCE_COLUMNS + " FROM business.media_resource r "
            + "WHERE r.status='ACTIVE' AND r.provider_code=#{providerCode}"
            + " AND r.upstream_model_id=#{modelId} AND r.synced_at>=#{freshAfter}"
            + " ORDER BY r.id LIMIT #{limit}")
    List<MediaRecords.Resource> listRecommendationCandidates(@Param("providerCode") String providerCode,
            @Param("modelId") String modelId, @Param("freshAfter") OffsetDateTime freshAfter,
            @Param("limit") int limit);

    @Select("<script>SELECT count(*) FROM business.media_resource r WHERE 1=1"
            + " <if test='search != null'>AND lower(r.name) LIKE lower(#{search})</if>"
            + " <if test='modelId != null'>AND r.upstream_model_id=#{modelId}</if>"
            + " <if test='providerCode != null'>AND r.provider_code=#{providerCode}"
            + " AND r.upstream_model_id=#{supportedModel}</if>"
            + " <if test='type != null'>AND r.resource_type=#{type}</if>"
            + " <if test='status != null and freshAfter == null'>AND r.status=#{status}</if>"
            + " <if test='status != null and freshAfter != null'>"
            + " AND ((#{status}='ACTIVE' AND r.status='ACTIVE' AND r.synced_at&gt;=#{freshAfter})"
            + " OR (#{status}='INACTIVE' AND (r.status='INACTIVE' OR r.synced_at&lt;#{freshAfter})))"
            + " </if>"
            + " <if test='minPrice != null'>AND CEIL(r.upstream_price*1.35)*100&gt;=#{minPrice}</if>"
            + " <if test='maxPrice != null'>AND CEIL(r.upstream_price*1.35)*100&lt;=#{maxPrice}</if></script>")
    long countResources(@Param("search") String search, @Param("modelId") String modelId,
            @Param("type") String type, @Param("status") String status, @Param("minPrice") Integer minPrice,
            @Param("maxPrice") Integer maxPrice, @Param("freshAfter") OffsetDateTime freshAfter,
            @Param("providerCode") String providerCode, @Param("supportedModel") String supportedModel);

    @Select("SELECT " + RESOURCE_COLUMNS + " FROM business.media_resource r WHERE r.id=#{id}")
    Optional<MediaRecords.Resource> findResource(@Param("id") long id);

    @Select("SELECT " + RESOURCE_COLUMNS + " FROM business.media_resource r WHERE r.id=#{id} FOR SHARE")
    Optional<MediaRecords.Resource> lockResourceForConfirmation(@Param("id") long id);

    @Select("SELECT " + RESOURCE_COLUMNS + " FROM business.media_resource r"
            + " WHERE r.provider_code=#{provider} AND r.upstream_model_id=#{model}"
            + " AND r.upstream_resource_id=#{resourceId}")
    Optional<MediaRecords.Resource> findResourceByUpstream(@Param("provider") String provider,
            @Param("model") String model, @Param("resourceId") String resourceId);

    @Insert("""
            INSERT INTO business.media_resource(id,provider_code,upstream_model_id,upstream_resource_id,name,status,
              resource_type,channel_category,region,estimated_days,capabilities,upstream_price,raw_json,
              last_seen_at,synced_at,version)
            VALUES(#{r.id},#{r.providerCode},#{r.upstreamModelId},#{r.upstreamResourceId},#{r.name},#{r.status},
              #{r.resourceType},#{r.channelCategory},#{r.region},#{r.estimatedDays},CAST(#{r.capabilitiesJson} AS jsonb),
              #{r.upstreamPrice},CAST(#{r.rawJson} AS jsonb),#{r.lastSeenAt},#{r.syncedAt},#{r.version})
            ON CONFLICT(provider_code,upstream_model_id,upstream_resource_id) DO UPDATE SET
              name=EXCLUDED.name,status=EXCLUDED.status,resource_type=EXCLUDED.resource_type,
              channel_category=EXCLUDED.channel_category,region=EXCLUDED.region,
              estimated_days=EXCLUDED.estimated_days,capabilities=EXCLUDED.capabilities,
              upstream_price=EXCLUDED.upstream_price,raw_json=EXCLUDED.raw_json,last_seen_at=EXCLUDED.last_seen_at,
              synced_at=EXCLUDED.synced_at,version=CASE WHEN
                (business.media_resource.name,business.media_resource.status,business.media_resource.resource_type,
                 business.media_resource.channel_category,business.media_resource.region,
                 business.media_resource.estimated_days,business.media_resource.capabilities,
                 business.media_resource.upstream_price,business.media_resource.raw_json)
                IS DISTINCT FROM
                (EXCLUDED.name,EXCLUDED.status,EXCLUDED.resource_type,EXCLUDED.channel_category,EXCLUDED.region,
                 EXCLUDED.estimated_days,EXCLUDED.capabilities,EXCLUDED.upstream_price,EXCLUDED.raw_json)
                THEN business.media_resource.version+1 ELSE business.media_resource.version END
            WHERE GREATEST(business.media_resource.last_seen_at,business.media_resource.synced_at)
              < EXCLUDED.last_seen_at
            """)
    int upsertResource(@Param("r") MediaRecords.Resource resource);

    @Update("WITH missing AS MATERIALIZED (SELECT id FROM business.media_resource"
            + " WHERE provider_code=#{provider} AND upstream_model_id=#{model} AND status='ACTIVE'"
            + " AND last_seen_at<#{syncStartedAt} ORDER BY upstream_resource_id FOR UPDATE)"
            + " UPDATE business.media_resource r SET status='INACTIVE',version=r.version+1,synced_at=#{now}"
            + " FROM missing WHERE r.id=missing.id")
    int inactivateMissingResources(@Param("provider") String provider, @Param("model") String model,
            @Param("syncStartedAt") OffsetDateTime syncStartedAt, @Param("now") OffsetDateTime now);

    @Insert("""
            INSERT INTO business.media_price_snapshot(id,media_resource_id,provider_code,upstream_model_id,
              upstream_resource_id,upstream_price,markup_rate,sale_amount_cents,charged_points,quoted_at,expires_at)
            VALUES(#{p.id},#{p.mediaResourceId},#{p.providerCode},#{p.upstreamModelId},#{p.upstreamResourceId},
              #{p.upstreamPrice},#{p.markupRate},#{p.saleAmountCents},#{p.chargedPoints},#{p.quotedAt},#{p.expiresAt})
            """)
    int insertPrice(@Param("p") MediaRecords.Price price);

    @Select("SELECT id,media_resource_id AS mediaResourceId,provider_code AS providerCode,"
            + "upstream_model_id AS upstreamModelId,upstream_resource_id AS upstreamResourceId,"
            + "upstream_price AS upstreamPrice,markup_rate AS markupRate,sale_amount_cents AS saleAmountCents,"
            + "charged_points AS chargedPoints,quoted_at AS quotedAt,expires_at AS expiresAt "
            + "FROM business.media_price_snapshot WHERE id=#{id}")
    Optional<MediaRecords.Price> findPrice(@Param("id") long id);

    @Insert("""
            INSERT INTO business.media_submission_batch(id,task_id,draft_id,user_id,space_id,product_id,
              article_id,article_snapshot_id,status,quoted_points,settled_points,released_points,created_at,updated_at)
            VALUES(#{b.id},#{b.taskId},#{b.draftId},#{b.userId},#{b.spaceId},#{b.productId},#{b.articleId},
              #{b.articleSnapshotId},#{b.status},#{b.quotedPoints},#{b.settledPoints},#{b.releasedPoints},
              #{b.createdAt},#{b.updatedAt})
            """)
    int insertBatch(@Param("b") MediaRecords.Batch batch);

    @Insert("""
            INSERT INTO business.media_submission(id,batch_id,task_id,user_id,space_id,article_id,
              article_snapshot_id,media_resource_id,price_snapshot_id,provider_code,upstream_model_id,
              upstream_resource_id,media_name,upstream_price,markup_rate,sale_amount_cents,charged_points,
              remark,customer_order_id,status,submission_state,created_at,updated_at)
            VALUES(#{s.id},#{s.batchId},#{s.taskId},#{s.userId},#{s.spaceId},#{s.articleId},
              #{s.articleSnapshotId},#{s.mediaResourceId},#{s.priceSnapshotId},#{s.providerCode},
              #{s.upstreamModelId},#{s.upstreamResourceId},#{s.mediaName},#{s.upstreamPrice},#{s.markupRate},
              #{s.saleAmountCents},#{s.chargedPoints},#{s.remark},#{s.customerOrderId},#{s.status},
              #{s.submissionState},#{s.createdAt},#{s.updatedAt})
            """)
    int insertSubmission(@Param("s") MediaRecords.Submission submission);

    @Insert("""
            INSERT INTO business.outbox_event(id,user_id,aggregate_type,aggregate_id,schema_version,event_type,
              execution_attempt,exchange_name,routing_key,payload,traceparent,status,attempts,available_at,
              created_at,updated_at)
            VALUES(#{id},#{userId},'TASK',#{taskId},1,'MEDIA_SUBMISSION_REQUESTED',1,'LOCAL',
              'media.submit',CAST(#{payload} AS jsonb),#{traceparent},'PENDING',0,#{now},#{now},#{now})
            """)
    int insertLocalOutbox(@Param("id") long id, @Param("userId") long userId,
            @Param("taskId") long taskId, @Param("payload") String payload,
            @Param("traceparent") String traceparent, @Param("now") OffsetDateTime now);

    @Select("""
            WITH candidate AS (
              SELECT id FROM business.outbox_event
              WHERE event_type='MEDIA_SUBMISSION_REQUESTED' AND status IN ('PENDING','PUBLISHING')
                AND available_at<=#{now} AND (locked_until IS NULL OR locked_until<#{now})
              ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED
            ), claimed AS (
              UPDATE business.outbox_event o SET status='PUBLISHING',locked_until=#{leaseUntil},
                attempts=attempts+1,updated_at=#{now} FROM candidate c WHERE o.id=c.id
              RETURNING o.id,o.user_id,o.aggregate_id,o.payload::text,o.traceparent,o.attempts,o.created_at
            )
            SELECT id,user_id AS userId,aggregate_id AS taskId,payload AS payloadJson,traceparent,attempts,
              created_at AS createdAt FROM claimed
            """)
    Optional<MediaRecords.LocalOutbox> claimLocalOutbox(@Param("now") OffsetDateTime now,
            @Param("leaseUntil") OffsetDateTime leaseUntil);

    @Update("UPDATE business.outbox_event SET status=#{status},locked_until=NULL,published_at=CASE WHEN #{status}='PUBLISHED' THEN #{now} ELSE published_at END,available_at=#{availableAt},last_error_code=#{error},updated_at=#{now} WHERE id=#{id} AND event_type='MEDIA_SUBMISSION_REQUESTED' AND status='PUBLISHING' AND attempts=#{attempt}")
    int finishLocalOutbox(@Param("id") long id, @Param("attempt") int attempt,
            @Param("status") String status,
            @Param("availableAt") OffsetDateTime availableAt, @Param("error") String error,
            @Param("now") OffsetDateTime now);

    @Select("SELECT " + SUBMISSION_COLUMNS + " FROM business.media_submission s WHERE s.task_id=#{taskId} ORDER BY s.id")
    List<MediaRecords.Submission> findByTask(@Param("taskId") long taskId);

    @Select("SELECT " + SUBMISSION_COLUMNS + " FROM business.media_submission s WHERE s.id=#{id} AND s.user_id=#{userId}")
    Optional<MediaRecords.Submission> findSubmission(@Param("id") long id, @Param("userId") long userId);

    @Select("SELECT " + SUBMISSION_COLUMNS + " FROM business.media_submission s WHERE s.id=#{id} FOR UPDATE")
    Optional<MediaRecords.Submission> lockSubmission(@Param("id") long id);

    @Select("SELECT " + SUBMISSION_COLUMNS + " FROM business.media_submission s WHERE s.customer_order_id=#{customerOrderId} FOR UPDATE")
    Optional<MediaRecords.Submission> lockByCustomerOrder(@Param("customerOrderId") String customerOrderId);

    @Update("UPDATE business.media_submission SET status='SUBMITTING',submit_attempted_at=#{now},updated_at=#{now} WHERE id=#{id} AND status='QUEUED' AND submission_state='NOT_SUBMITTED'")
    int claimSubmission(@Param("id") long id, @Param("now") OffsetDateTime now);

    @Update("UPDATE business.media_submission SET status='ACCEPTED',submission_state='CONFIRMED',upstream_order_id=#{orderId},upstream_order_no=#{orderNo},next_poll_at=#{nextPoll},updated_at=#{now} WHERE id=#{id} AND status='SUBMITTING' AND submission_state='NOT_SUBMITTED'")
    int acceptSubmission(@Param("id") long id, @Param("orderId") String orderId,
            @Param("orderNo") String orderNo, @Param("nextPoll") OffsetDateTime nextPoll,
            @Param("now") OffsetDateTime now);

    @Update("UPDATE business.media_submission SET upstream_order_id=COALESCE(upstream_order_id,#{orderId}),upstream_order_no=COALESCE(upstream_order_no,#{orderNo}),next_poll_at=COALESCE(next_poll_at,#{nextPoll}),updated_at=#{now} WHERE id=#{id} AND status IN ('ACCEPTED','RECONCILING') AND submission_state='CONFIRMED' AND (upstream_order_id IS NULL OR upstream_order_id=#{orderId})")
    int hydrateSubmittedOrder(@Param("id") long id, @Param("orderId") String orderId,
            @Param("orderNo") String orderNo, @Param("nextPoll") OffsetDateTime nextPoll,
            @Param("now") OffsetDateTime now);

    @Update("UPDATE business.media_submission SET status='SUBMIT_UNKNOWN',submission_state='UNKNOWN',stable_error_code=#{code},next_poll_at=#{nextPoll},updated_at=#{now} WHERE id=#{id} AND status='SUBMITTING' AND submission_state='NOT_SUBMITTED'")
    int markUnknown(@Param("id") long id, @Param("code") String code,
            @Param("nextPoll") OffsetDateTime nextPoll, @Param("now") OffsetDateTime now);

    @Update("UPDATE business.media_submission SET status='SUBMIT_UNKNOWN',submission_state='UNKNOWN',stable_error_code=#{code},next_poll_at=#{nextPoll},updated_at=#{now} WHERE id=#{id} AND status='SUBMITTING' AND submission_state='NOT_SUBMITTED'")
    int recoverInterruptedSubmission(@Param("id") long id, @Param("code") String code,
            @Param("nextPoll") OffsetDateTime nextPoll, @Param("now") OffsetDateTime now);

    @Update("UPDATE business.media_submission SET status='FAILED',stable_error_code=#{code},rejected_at=#{now},updated_at=#{now} WHERE id=#{id} AND status='SUBMITTING' AND submission_state='NOT_SUBMITTED'")
    int markSubmitFailed(@Param("id") long id, @Param("code") String code, @Param("now") OffsetDateTime now);

    @Select("SELECT " + SUBMISSION_COLUMNS + " FROM business.media_submission s WHERE s.status IN ('SUBMIT_UNKNOWN','ACCEPTED','RECONCILING') AND s.upstream_order_id IS NOT NULL AND s.next_poll_at<=#{now} ORDER BY s.next_poll_at,s.id LIMIT 1")
    Optional<MediaRecords.Submission> findPollCandidate(@Param("now") OffsetDateTime now);

    @Update("UPDATE business.media_submission SET upstream_status=#{upstreamStatus},status=#{status},submission_state=#{submissionState},upstream_order_id=COALESCE(upstream_order_id,#{orderId}),published_url=#{publishedUrl},published_urls=CASE WHEN #{status}='PUBLISHED' THEN CAST(#{publishedUrlsJson} AS jsonb) ELSE published_urls END,stable_error_code=#{error},next_poll_at=#{nextPoll},published_at=CASE WHEN #{status}='PUBLISHED' THEN COALESCE(published_at,#{now}) ELSE published_at END,rejected_at=CASE WHEN #{status} IN ('REJECTED','FAILED') THEN COALESCE(rejected_at,#{now}) ELSE rejected_at END,updated_at=#{now} WHERE id=#{id} AND status=#{expectedStatus}")
    int transition(@Param("id") long id, @Param("expectedStatus") String expectedStatus,
            @Param("upstreamStatus") String upstreamStatus, @Param("status") String status,
            @Param("submissionState") String submissionState, @Param("orderId") String orderId,
            @Param("publishedUrl") String publishedUrl,
            @Param("publishedUrlsJson") String publishedUrlsJson, @Param("error") String error,
            @Param("nextPoll") OffsetDateTime nextPoll, @Param("now") OffsetDateTime now);

    @Select("<script>SELECT " + SUBMISSION_COLUMNS + " FROM business.media_submission s WHERE s.user_id=#{userId}"
            + " <if test='status != null'>AND s.status=#{status}</if>"
            + " <if test='taskId != null'>AND s.task_id=#{taskId}</if>"
            + " ORDER BY s.created_at DESC,s.id DESC LIMIT #{limit} OFFSET #{offset}</script>")
    List<MediaRecords.Submission> listSubmissions(@Param("userId") long userId,
            @Param("status") String status, @Param("taskId") Long taskId,
            @Param("limit") int limit, @Param("offset") long offset);

    @Select("<script>SELECT count(*) FROM business.media_submission s WHERE s.user_id=#{userId}"
            + " <if test='status != null'>AND s.status=#{status}</if>"
            + " <if test='taskId != null'>AND s.task_id=#{taskId}</if></script>")
    long countSubmissions(@Param("userId") long userId, @Param("status") String status,
            @Param("taskId") Long taskId);

    @Select("SELECT id,submission_id AS submissionId,batch_id AS batchId,user_id AS userId,"
            + "sequence_no AS sequenceNo,event_type AS eventType,from_status AS fromStatus,to_status AS toStatus,"
            + "stable_error_code AS stableErrorCode,created_at AS createdAt FROM business.media_submission_event "
            + "WHERE submission_id=#{submissionId} AND user_id=#{userId} ORDER BY sequence_no,id")
    List<MediaRecords.Event> events(@Param("submissionId") long submissionId, @Param("userId") long userId);

    @Select("SELECT COALESCE(max(sequence_no),0)+1 FROM business.media_submission_event WHERE submission_id=#{submissionId}")
    long nextEventSequence(@Param("submissionId") long submissionId);

    @Insert("INSERT INTO business.media_submission_event(id,submission_id,batch_id,user_id,sequence_no,source_event_id,event_type,from_status,to_status,source,stable_error_code,details,created_at) VALUES(#{id},#{submissionId},#{batchId},#{userId},#{sequence},#{sourceEventId},#{eventType},#{fromStatus},#{toStatus},#{source},#{error},'{}'::jsonb,#{now}) ON CONFLICT DO NOTHING")
    int insertEvent(@Param("id") long id, @Param("submissionId") long submissionId,
            @Param("batchId") long batchId, @Param("userId") long userId,
            @Param("sequence") long sequence, @Param("sourceEventId") UUID sourceEventId,
            @Param("eventType") String eventType, @Param("fromStatus") String fromStatus,
            @Param("toStatus") String toStatus, @Param("source") String source,
            @Param("error") String error, @Param("now") OffsetDateTime now);

    @Insert("INSERT INTO business.media_callback_event(id,provider_code,customer_order_id,upstream_model_id,"
            + "upstream_resource_id,event_type,payload_hash,status,attempts,received_at,updated_at)"
            + " VALUES(#{id},#{provider},#{customerOrderId},#{model},#{resourceId},#{eventType},#{hash},"
            + "'RECEIVED',0,#{now},#{now}) ON CONFLICT DO NOTHING")
    int insertCallback(@Param("id") long id, @Param("provider") String provider,
            @Param("customerOrderId") String customerOrderId, @Param("model") String model,
            @Param("resourceId") String resourceId, @Param("eventType") String eventType,
            @Param("hash") String hash, @Param("now") OffsetDateTime now);

    @Select("SELECT status FROM business.media_callback_event WHERE provider_code=#{provider}"
            + " AND customer_order_id IS NOT DISTINCT FROM #{customerOrderId}"
            + " AND upstream_model_id IS NOT DISTINCT FROM #{model}"
            + " AND upstream_resource_id IS NOT DISTINCT FROM #{resourceId}"
            + " AND event_type=#{eventType} AND payload_hash=#{hash}")
    Optional<String> callbackStatus(@Param("provider") String provider,
            @Param("customerOrderId") String customerOrderId, @Param("model") String model,
            @Param("resourceId") String resourceId, @Param("eventType") String eventType,
            @Param("hash") String hash);

    @Update("UPDATE business.media_callback_event SET status=#{status},attempts=attempts+1,"
            + "stable_error_code=#{error},processed_at=CASE WHEN #{status}='PROCESSED' THEN #{now}"
            + " ELSE processed_at END,updated_at=#{now} WHERE provider_code=#{provider}"
            + " AND customer_order_id IS NOT DISTINCT FROM #{customerOrderId}"
            + " AND upstream_model_id IS NOT DISTINCT FROM #{model}"
            + " AND upstream_resource_id IS NOT DISTINCT FROM #{resourceId}"
            + " AND event_type=#{eventType} AND payload_hash=#{hash}")
    int finishCallback(@Param("provider") String provider, @Param("customerOrderId") String customerOrderId,
            @Param("model") String model, @Param("resourceId") String resourceId,
            @Param("eventType") String eventType, @Param("hash") String hash,
            @Param("status") String status, @Param("error") String error, @Param("now") OffsetDateTime now);

    @Update("UPDATE business.point_reservation SET frozen_points=frozen_points-#{points},settled_points=settled_points+#{points},status=CASE WHEN frozen_points=#{points} THEN CASE WHEN released_points=0 THEN 'SETTLED' ELSE 'PARTIAL' END ELSE 'FROZEN' END,updated_at=#{now} WHERE task_id=#{taskId} AND status IN ('FROZEN','PARTIAL') AND frozen_points>=#{points}")
    int settleReservationItem(@Param("taskId") long taskId, @Param("points") int points,
            @Param("now") OffsetDateTime now);

    @Update("UPDATE business.point_reservation SET frozen_points=frozen_points-#{points},released_points=released_points+#{points},status=CASE WHEN frozen_points=#{points} THEN CASE WHEN settled_points=0 THEN 'RELEASED' ELSE 'PARTIAL' END ELSE 'FROZEN' END,updated_at=#{now} WHERE task_id=#{taskId} AND status IN ('FROZEN','PARTIAL') AND frozen_points>=#{points}")
    int releaseReservationItem(@Param("taskId") long taskId, @Param("points") int points,
            @Param("now") OffsetDateTime now);

    @Update("UPDATE business.media_submission_batch SET settled_points=settled_points+#{settled},released_points=released_points+#{released},updated_at=#{now} WHERE id=#{batchId} AND settled_points+released_points+#{settled}+#{released}<=quoted_points")
    int updateBatchPoints(@Param("batchId") long batchId, @Param("settled") int settled,
            @Param("released") int released, @Param("now") OffsetDateTime now);

    @Select("SELECT id,task_id AS taskId,draft_id AS draftId,user_id AS userId,space_id AS spaceId,"
            + "product_id AS productId,article_id AS articleId,article_snapshot_id AS articleSnapshotId,status,"
            + "quoted_points AS quotedPoints,settled_points AS settledPoints,released_points AS releasedPoints,"
            + "created_at AS createdAt,updated_at AS updatedAt,finished_at AS finishedAt "
            + "FROM business.media_submission_batch WHERE id=#{id} FOR UPDATE")
    Optional<MediaRecords.Batch> lockBatch(@Param("id") long id);

    @Update("UPDATE business.business_task SET settled_points=settled_points+#{points},updated_at=#{now} WHERE id=#{taskId} AND task_type='MEDIA' AND status IN ('QUEUED','RUNNING') AND settled_points+#{points}<=quoted_points")
    int addTaskSettled(@Param("taskId") long taskId, @Param("points") int points,
            @Param("now") OffsetDateTime now);

    @Update("UPDATE business.business_task SET status='RUNNING',stage_code='MEDIA_SUBMITTING',started_at=COALESCE(started_at,#{now}),updated_at=#{now} WHERE id=#{taskId} AND task_type='MEDIA' AND status='QUEUED'")
    int startTask(@Param("taskId") long taskId, @Param("now") OffsetDateTime now);

    @Update("UPDATE business.business_task SET stage_code=#{stage},updated_at=#{now} WHERE id=#{taskId} AND task_type='MEDIA' AND status='RUNNING' AND stage_code<>#{stage}")
    int updateTaskStage(@Param("taskId") long taskId, @Param("stage") String stage,
            @Param("now") OffsetDateTime now);

    @Update("UPDATE business.media_submission_batch SET status=#{status},finished_at=#{now},updated_at=#{now} WHERE id=#{batchId} AND status NOT IN ('PUBLISHED','PARTIAL','REJECTED','FAILED')")
    int finishBatch(@Param("batchId") long batchId, @Param("status") String status,
            @Param("now") OffsetDateTime now);

    @Update("UPDATE business.business_task SET status=#{status},stage_code=#{stage},error_code=#{error},result_summary=CAST(#{result} AS jsonb),finished_at=#{now},updated_at=#{now} WHERE id=#{taskId} AND task_type='MEDIA' AND status IN ('QUEUED','RUNNING')")
    int finishTask(@Param("taskId") long taskId, @Param("status") String status,
            @Param("stage") String stage, @Param("error") String error,
            @Param("result") String result, @Param("now") OffsetDateTime now);

    @Insert("INSERT INTO business.media_provider_sync_state(provider_code,upstream_model_id,status,last_attempted_at,last_succeeded_at,last_error_code,last_resource_count,version,updated_at) VALUES(#{provider},#{model},#{status},#{now},CASE WHEN #{status}='SUCCEEDED' THEN #{now} ELSE NULL END,#{error},#{count},1,#{now}) ON CONFLICT(provider_code,upstream_model_id) DO UPDATE SET status=EXCLUDED.status,last_attempted_at=EXCLUDED.last_attempted_at,last_succeeded_at=CASE WHEN EXCLUDED.status='SUCCEEDED' THEN EXCLUDED.last_attempted_at ELSE business.media_provider_sync_state.last_succeeded_at END,last_error_code=EXCLUDED.last_error_code,last_resource_count=EXCLUDED.last_resource_count,version=business.media_provider_sync_state.version+1,updated_at=EXCLUDED.updated_at")
    int updateSyncState(@Param("provider") String provider, @Param("model") String model,
            @Param("status") String status, @Param("error") String error,
            @Param("count") int count, @Param("now") OffsetDateTime now);

    // ---------------------------------------------------------------------------------------------
    // Operator console reads.
    //
    // `listSubmissions`/`findSubmission`/`events` are user-scoped on purpose: they exist so a
    // customer can only ever read their own orders, and loosening that filter to serve the back
    // office would put every customer's orders behind one parameter. The operator surface therefore
    // gets its own statements over the same tables instead of a shared one with a nullable owner.
    // ---------------------------------------------------------------------------------------------

    @Select("<script>SELECT " + SUBMISSION_COLUMNS + " FROM business.media_submission s"
            + " <where><if test='statuses != null'>s.status IN"
            + " <foreach item='item' collection='statuses' open='(' separator=',' close=')'>#{item}</foreach>"
            + "</if></where>"
            + " ORDER BY s.created_at DESC,s.id DESC LIMIT #{limit} OFFSET #{offset}</script>")
    List<MediaRecords.Submission> listSubmissionsForAdmin(@Param("statuses") List<String> statuses,
            @Param("limit") int limit, @Param("offset") long offset);

    @Select("<script>SELECT count(*) FROM business.media_submission s"
            + " <where><if test='statuses != null'>s.status IN"
            + " <foreach item='item' collection='statuses' open='(' separator=',' close=')'>#{item}</foreach>"
            + "</if></where></script>")
    long countSubmissionsForAdmin(@Param("statuses") List<String> statuses);

    @Select("SELECT " + SUBMISSION_COLUMNS + " FROM business.media_submission s WHERE s.id=#{id}")
    Optional<MediaRecords.Submission> findSubmissionForAdmin(@Param("id") long id);

    @Select("SELECT id,submission_id AS submissionId,batch_id AS batchId,user_id AS userId,"
            + "sequence_no AS sequenceNo,event_type AS eventType,from_status AS fromStatus,"
            + "to_status AS toStatus,stable_error_code AS stableErrorCode,created_at AS createdAt "
            + "FROM business.media_submission_event WHERE submission_id=#{submissionId} "
            + "ORDER BY sequence_no,id")
    List<MediaRecords.Event> eventsForAdmin(@Param("submissionId") long submissionId);

    @Select("<script>SELECT " + CALLBACK_COLUMNS + " FROM business.media_callback_event c"
            + " <where><if test='statuses != null'>c.status IN"
            + " <foreach item='item' collection='statuses' open='(' separator=',' close=')'>#{item}</foreach>"
            + "</if></where>"
            + " ORDER BY c.received_at DESC,c.id DESC LIMIT #{limit} OFFSET #{offset}</script>")
    List<MediaRecords.Callback> listCallbacksForAdmin(@Param("statuses") List<String> statuses,
            @Param("limit") int limit, @Param("offset") long offset);

    @Select("<script>SELECT count(*) FROM business.media_callback_event c"
            + " <where><if test='statuses != null'>c.status IN"
            + " <foreach item='item' collection='statuses' open='(' separator=',' close=')'>#{item}</foreach>"
            + "</if></where></script>")
    long countCallbacksForAdmin(@Param("statuses") List<String> statuses);

    @Select("SELECT " + CALLBACK_COLUMNS + " FROM business.media_callback_event c WHERE c.id=#{id}")
    Optional<MediaRecords.Callback> findCallbackForAdmin(@Param("id") long id);

    @Select("SELECT " + SYNC_STATE_COLUMNS + " FROM business.media_provider_sync_state t"
            + " ORDER BY t.provider_code,t.upstream_model_id")
    List<MediaRecords.SyncState> listSyncStates();
}
