package com.guanghe.backend.asset;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface AssetMapper {
    String PRODUCT_ASSOCIATION_FILTER = " AND (EXISTS (SELECT 1 FROM business.asset_product_relation r"
            + " WHERE r.asset_id=a.id AND r.user_id=#{userId} AND r.product_id=#{productId})"
            + " OR EXISTS (SELECT 1 FROM business.product_identity_asset i"
            + " WHERE i.asset_id=a.id AND i.user_id=#{userId} AND i.product_id=#{productId}))";
    String ASSET_COLUMNS = """
            a.id,a.user_id AS userId,a.space_id AS spaceId,a.primary_product_id AS primaryProductId,
            a.asset_type AS assetType,a.source_type AS sourceType,a.source_task_id AS sourceTaskId,
            a.source_artifact_index AS sourceArtifactIndex,
            a.name,a.object_key AS objectKey,a.content_type AS contentType,a.size_bytes AS sizeBytes,
            a.sha256,a.width,a.height,a.duration_seconds::text AS durationSeconds,a.status,
            a.safety_status AS safetyStatus,a.created_at AS createdAt,a.updated_at AS updatedAt
            """;
    String WORK_COLUMNS = """
            w.id,w.asset_id AS assetId,w.task_id AS taskId,w.execution_id AS executionId,
            w.execution_no AS executionNo,w.user_id AS userId,w.space_id AS spaceId,
            w.product_id AS productId,w.artifact_index AS artifactIndex,
            w.provider_code AS providerCode,w.provider_model AS providerModel,
            w.provider_request_id AS providerRequestId,w.provider_task_id AS providerTaskId,
            w.provider_url AS providerUrl,w.provider_url_expires_at AS providerUrlExpiresAt,
            w.expected_content_type AS expectedContentType,w.expected_size_bytes AS expectedSizeBytes,
            w.expected_sha256 AS expectedSha256,w.expected_width AS expectedWidth,
            w.expected_height AS expectedHeight,
            w.expected_duration_seconds AS expectedDurationSeconds,
            w.expected_aspect_ratio AS expectedAspectRatio,w.expected_resolution AS expectedResolution,
            w.ingestion_deadline_at AS ingestionDeadlineAt,w.object_key AS objectKey,w.status,w.attempt,
            w.max_attempts AS maxAttempts,w.available_at AS availableAt,
            w.lease_expires_at AS leaseExpiresAt,w.claim_token AS claimToken,
            w.last_error_code AS lastErrorCode,
            w.completed_at AS completedAt,w.created_at AS createdAt,w.updated_at AS updatedAt
            """;

    @Select("<script>SELECT count(*) FROM business.asset a WHERE a.user_id=#{userId}"
            + "<if test='type != null'> AND a.asset_type=#{type}</if>"
            + "<if test='spaceId != null'> AND a.space_id=#{spaceId}</if>"
            + "<if test='sourceType != null'> AND a.source_type=#{sourceType}</if>"
            + "<if test='productId != null'>" + PRODUCT_ASSOCIATION_FILTER + "</if>"
            + "</script>")
    long countAssets(@Param("userId") long userId, @Param("type") String type,
                     @Param("spaceId") Long spaceId, @Param("productId") Long productId,
                     @Param("sourceType") String sourceType);

    @Select("<script>SELECT " + ASSET_COLUMNS + " FROM business.asset a WHERE a.user_id=#{userId}"
            + "<if test='type != null'> AND a.asset_type=#{type}</if>"
            + "<if test='spaceId != null'> AND a.space_id=#{spaceId}</if>"
            + "<if test='sourceType != null'> AND a.source_type=#{sourceType}</if>"
            + "<if test='productId != null'>" + PRODUCT_ASSOCIATION_FILTER + "</if>"
            + " ORDER BY a.created_at DESC,a.id DESC OFFSET #{offset} LIMIT #{limit}</script>")
    List<AssetRecords.Asset> findAssetPage(@Param("userId") long userId, @Param("type") String type,
                                           @Param("spaceId") Long spaceId,
                                           @Param("productId") Long productId,
                                           @Param("sourceType") String sourceType,
                                           @Param("offset") long offset, @Param("limit") int limit);

    @Select("SELECT " + ASSET_COLUMNS + " FROM business.asset a WHERE a.id=#{assetId} AND a.user_id=#{userId}")
    Optional<AssetRecords.Asset> findAsset(@Param("assetId") long assetId, @Param("userId") long userId);

    @Select("SELECT id,user_id AS userId,idempotency_key AS idempotencyKey,request_hash AS requestHash,"
            + "asset_id AS assetId,space_id AS spaceId,created_at AS createdAt"
            + " FROM business.asset_upload_request WHERE user_id=#{userId} AND idempotency_key=#{key}")
    Optional<AssetRecords.UploadReceipt> findUploadReceipt(@Param("userId") long userId,
                                                           @Param("key") java.util.UUID key);

    @Insert("INSERT INTO business.asset_upload_request(id,user_id,idempotency_key,request_hash,asset_id,space_id,created_at)"
            + " VALUES(#{r.id},#{r.userId},#{r.idempotencyKey},#{r.requestHash},#{r.assetId},#{r.spaceId},#{r.createdAt})")
    int insertUploadReceipt(@Param("r") AssetRecords.UploadReceipt receipt);

    @Select("SELECT product_id FROM business.asset_product_relation WHERE asset_id=#{assetId}"
            + " AND user_id=#{userId} UNION SELECT product_id FROM business.product_identity_asset"
            + " WHERE asset_id=#{assetId} AND user_id=#{userId} ORDER BY product_id")
    List<Long> findProductIds(@Param("assetId") long assetId, @Param("userId") long userId);

    @Select("SELECT " + WORK_COLUMNS + " FROM business.asset_ingestion_work w WHERE w.id=#{workId}")
    Optional<AssetRecords.IngestionWork> findWork(@Param("workId") long workId);

    @Select("SELECT " + WORK_COLUMNS + " FROM business.asset_ingestion_work w WHERE w.id=#{workId} FOR UPDATE")
    Optional<AssetRecords.IngestionWork> lockWork(@Param("workId") long workId);

    @Select("SELECT " + ASSET_COLUMNS + " FROM business.asset a WHERE a.source_task_id=#{taskId}"
            + " ORDER BY a.source_artifact_index LIMIT 1")
    Optional<AssetRecords.Asset> findAssetBySourceTask(@Param("taskId") long taskId);

    @Select("SELECT " + ASSET_COLUMNS + " FROM business.asset a WHERE a.source_task_id=#{taskId}"
            + " AND a.source_artifact_index=#{artifactIndex}")
    Optional<AssetRecords.Asset> findAssetBySourceArtifact(@Param("taskId") long taskId,
                                                         @Param("artifactIndex") int artifactIndex);

    /**
     * Resolves the latest completed image task before the current USER message, then chooses its
     * newest READY persisted image. The task is selected first so a newer broken result cannot
     * silently fall back to an older image and change what "上一张" means.
     */
    @Select("SELECT " + ASSET_COLUMNS + " FROM business.conversation_message current_message"
            + " JOIN business.asset a ON a.source_task_id=(SELECT t.id FROM business.business_task t"
            + " WHERE t.user_id=#{userId} AND t.conversation_id=#{conversationId} AND t.space_id=#{spaceId}"
            + " AND t.task_type='IMAGE' AND t.status IN ('SUCCEEDED','PARTIAL')"
            + " AND t.created_at<LEAST(current_message.created_at,#{before})"
            + " AND t.finished_at IS NOT NULL AND t.finished_at<LEAST(current_message.created_at,#{before})"
            + " ORDER BY t.created_at DESC,t.id DESC LIMIT 1)"
            + " JOIN business.asset_ingestion_work w ON w.asset_id=a.id AND w.task_id=a.source_task_id"
            + " AND w.user_id=a.user_id AND w.space_id=a.space_id"
            + " AND w.artifact_index=a.source_artifact_index AND w.status='SUCCEEDED'"
            + " WHERE current_message.id=#{currentMessageId} AND current_message.user_id=#{userId}"
            + " AND current_message.conversation_id=#{conversationId} AND current_message.role='USER'"
            + " AND a.user_id=#{userId} AND a.space_id=#{spaceId} AND a.asset_type='IMAGE'"
            + " AND a.source_type='GENERATED' AND a.status='READY'"
            + " AND a.created_at<LEAST(current_message.created_at,#{before})"
            + " ORDER BY a.created_at DESC,a.id DESC LIMIT 1")
    Optional<AssetRecords.Asset> findPreviousReadyImageResult(
            @Param("userId") long userId,
            @Param("conversationId") long conversationId,
            @Param("spaceId") long spaceId,
            @Param("currentMessageId") long currentMessageId,
            @Param("before") OffsetDateTime before);

    /**
     * The latest completed image task is selected before filtering its origin project or results.
     * Returning up to two images lets the caller reject ambiguity without choosing an arbitrary
     * artifact, crossing a project switch, or falling back to an older task when a result is broken.
     */
    @Select("""
        WITH latest_task AS (
            SELECT t.*,current_message.created_at AS current_requested_at FROM business.business_task t
            JOIN business.conversation_message current_message
              ON current_message.id=#{currentMessageId} AND current_message.user_id=#{userId}
             AND current_message.conversation_id=#{conversationId} AND current_message.role='USER'
            WHERE t.user_id=#{userId} AND t.conversation_id=#{conversationId} AND t.task_type='IMAGE'
              AND t.status IN ('SUCCEEDED','PARTIAL')
              AND t.created_at<LEAST(current_message.created_at,#{before})
              AND t.finished_at IS NOT NULL AND t.finished_at<LEAST(current_message.created_at,#{before})
            ORDER BY t.created_at DESC,t.id DESC LIMIT 1
        )
        SELECT a.id AS assetId,r.request_message_id AS sourceMessageId
        FROM latest_task t
        JOIN business.task_draft d ON d.id=t.draft_id AND d.user_id=t.user_id
          AND d.conversation_id=t.conversation_id
        JOIN business.agent_run r ON r.id=d.agent_run_id AND r.user_id=t.user_id
          AND r.conversation_id=t.conversation_id
        JOIN business.asset a ON a.source_task_id=t.id AND a.user_id=t.user_id AND a.space_id=t.space_id
          AND a.asset_type='IMAGE' AND a.source_type='GENERATED' AND a.status='READY'
          AND a.created_at<LEAST(t.current_requested_at,#{before})
        JOIN business.asset_ingestion_work w ON w.asset_id=a.id AND w.task_id=t.id
          AND w.user_id=a.user_id AND w.space_id=a.space_id
          AND w.artifact_index=a.source_artifact_index AND w.status='SUCCEEDED'
          AND w.completed_at IS NOT NULL AND w.completed_at<LEAST(t.current_requested_at,#{before})
        WHERE r.selected_space_id IS NOT DISTINCT FROM CAST(#{projectId} AS bigint)
          AND (CAST(#{projectId} AS bigint) IS NULL OR t.space_id=CAST(#{projectId} AS bigint))
        ORDER BY a.created_at DESC,a.id DESC LIMIT 2
        """)
    List<AssetRecords.ContinuationImage> findContinuationImageResults(
            @Param("userId") long userId,@Param("conversationId") long conversationId,
            @Param("projectId") Long projectId,@Param("currentMessageId") long currentMessageId,
            @Param("before") OffsetDateTime before);

    /**
     * Resolves the immediately preceding USER message, not the latest older message that happens
     * to contain an attachment. This fail-closed ordering prevents a later attachment-free request
     * from silently reviving an unrelated older upload.
     */
    @Select("WITH current_message AS (SELECT sequence_no FROM business.conversation_message"
            + " WHERE id=#{currentMessageId} AND user_id=#{userId}"
            + " AND conversation_id=#{conversationId} AND role='USER'),"
            + " previous_user AS (SELECT m.id FROM business.conversation_message m,current_message cm"
            + " WHERE m.user_id=#{userId} AND m.conversation_id=#{conversationId} AND m.role='USER'"
            + " AND m.sequence_no<cm.sequence_no ORDER BY m.sequence_no DESC LIMIT 1)"
            + " SELECT previous_user.id AS messageId,count(ma.asset_id)::integer AS attachmentCount,"
            + " CASE WHEN count(ma.asset_id)=1 THEN min(ma.asset_id) ELSE NULL END AS assetId"
            + " FROM previous_user LEFT JOIN business.conversation_message_asset ma"
            + " ON ma.message_id=previous_user.id AND ma.user_id=#{userId}"
            + " AND ma.conversation_id=#{conversationId} GROUP BY previous_user.id")
    Optional<AssetRecords.PreviousUserAttachment> findPreviousUserAttachment(
            @Param("userId") long userId,
            @Param("conversationId") long conversationId,
            @Param("currentMessageId") long currentMessageId);

    // Deliberately no FOR UPDATE: each worker already owns its own work row before the task lock.
    @Select("SELECT " + WORK_COLUMNS + " FROM business.asset_ingestion_work w WHERE w.task_id=#{taskId}"
            + " ORDER BY w.artifact_index")
    List<AssetRecords.IngestionWork> findWorksByTask(@Param("taskId") long taskId);

    @Select("SELECT " + ASSET_COLUMNS + " FROM business.asset a"
            + " JOIN business.asset_ingestion_work w ON w.asset_id=a.id AND w.task_id=a.source_task_id"
            + " AND w.user_id=a.user_id AND w.artifact_index=a.source_artifact_index"
            + " WHERE w.task_id=#{taskId} AND w.status='SUCCEEDED' AND a.status='READY'"
            + " ORDER BY a.source_artifact_index")
    List<AssetRecords.Asset> findReadyAssetsByTask(@Param("taskId") long taskId);

    @Update("UPDATE business.business_task SET status=#{status},stage_code=#{stage},"
            + "settled_points=#{settled},result_summary=CAST(#{resultJson} AS jsonb),error_code=#{code},"
            + "finished_at=#{now},updated_at=#{now} WHERE id=#{taskId} AND status='RUNNING'")
    int finishImageCandidatesTask(@Param("taskId") long taskId, @Param("status") String status,
                                   @Param("stage") String stage, @Param("settled") int settled,
                                   @Param("resultJson") String resultJson, @Param("code") String code,
                                   @Param("now") OffsetDateTime now);

    @Update("UPDATE business.point_reservation SET frozen_points=0,settled_points=#{settled},"
            + "released_points=#{released},status=#{status},updated_at=#{now}"
            + " WHERE task_id=#{taskId} AND status='FROZEN'"
            + " AND frozen_points=quoted_points AND quoted_points=#{settled}+#{released}")
    int finalizeImageCandidatesReservation(@Param("taskId") long taskId, @Param("settled") int settled,
                                            @Param("released") int released, @Param("status") String status,
                                            @Param("now") OffsetDateTime now);

    @Select("SELECT " + WORK_COLUMNS + " FROM business.asset_ingestion_work w"
            + " WHERE ((w.status IN ('PENDING','RETRY_WAIT') AND w.available_at<=#{now})"
            + " OR (w.status='PROCESSING' AND w.lease_expires_at<#{now}))"
            + " ORDER BY w.available_at,w.id LIMIT 1 FOR UPDATE SKIP LOCKED")
    Optional<AssetRecords.IngestionWork> lockNextWork(@Param("now") OffsetDateTime now);

    @Update("UPDATE business.asset_ingestion_work SET status='PROCESSING',"
            + "attempt=LEAST(attempt+1,max_attempts),"
            + "lease_expires_at=#{lease},claim_token=#{claimToken},last_error_code=NULL,updated_at=#{now}"
            + " WHERE id=#{workId}"
            + " AND ((status IN ('PENDING','RETRY_WAIT') AND available_at<=#{now})"
            + " OR (status='PROCESSING' AND lease_expires_at<#{now}))")
    int claimWork(@Param("workId") long workId, @Param("lease") OffsetDateTime lease,
                  @Param("claimToken") java.util.UUID claimToken, @Param("now") OffsetDateTime now);

    @Update("UPDATE business.asset_ingestion_work SET lease_expires_at=#{lease},updated_at=#{now}"
            + " WHERE id=#{workId} AND status='PROCESSING' AND attempt=#{expectedGeneration}"
            + " AND claim_token=#{expectedClaimToken} AND lease_expires_at>=#{now}")
    int renewLease(@Param("workId") long workId, @Param("expectedGeneration") int expectedGeneration,
                   @Param("expectedClaimToken") java.util.UUID expectedClaimToken,
                   @Param("lease") OffsetDateTime lease, @Param("now") OffsetDateTime now);

    @Insert("INSERT INTO business.asset(id,user_id,space_id,primary_product_id,asset_type,source_type,"
            + "source_task_id,source_artifact_index,name,object_key,content_type,size_bytes,sha256,width,height,duration_seconds,"
            + "status,safety_status,created_at,updated_at) VALUES(#{a.id},#{a.userId},#{a.spaceId},"
            + "#{a.primaryProductId},#{a.assetType},#{a.sourceType},#{a.sourceTaskId},#{a.sourceArtifactIndex},#{a.name},#{a.objectKey},"
            + "#{a.contentType},#{a.sizeBytes},#{a.sha256},#{a.width},#{a.height},"
            + "CAST(#{a.durationSeconds} AS numeric(12,3)),"
            + "#{a.status},#{a.safetyStatus},#{a.createdAt},#{a.updatedAt})"
            + " ON CONFLICT (source_task_id,source_artifact_index) DO NOTHING")
    int insertAsset(@Param("a") AssetRecords.Asset asset);

    @Insert("INSERT INTO business.asset_product_relation(asset_id,product_id,user_id,space_id,created_at)"
            + " VALUES(#{assetId},#{productId},#{userId},#{spaceId},#{now}) ON CONFLICT DO NOTHING")
    int insertProductRelation(@Param("assetId") long assetId, @Param("productId") long productId,
                              @Param("userId") long userId, @Param("spaceId") long spaceId,
                              @Param("now") OffsetDateTime now);

    @Update("UPDATE business.asset_ingestion_work SET status='SUCCEEDED',lease_expires_at=NULL,claim_token=NULL,"
            + "provider_url=NULL,provider_url_expires_at=NULL,ingestion_deadline_at=NULL,completed_at=#{now},updated_at=#{now}"
            + " WHERE id=#{workId} AND status='PROCESSING' AND attempt=#{expectedGeneration}"
            + " AND claim_token=#{expectedClaimToken} AND lease_expires_at>=#{now}")
    int completeWork(@Param("workId") long workId, @Param("expectedGeneration") int expectedGeneration,
                     @Param("expectedClaimToken") java.util.UUID expectedClaimToken,
                     @Param("now") OffsetDateTime now);

    @Update("UPDATE business.asset_ingestion_work SET status='RETRY_WAIT',available_at=#{availableAt},"
            + "lease_expires_at=NULL,claim_token=NULL,last_error_code=#{errorCode},updated_at=#{now}"
            + " WHERE id=#{workId} AND status='PROCESSING' AND attempt=#{expectedGeneration}"
            + " AND claim_token=#{expectedClaimToken} AND lease_expires_at>=#{now}")
    int retryWork(@Param("workId") long workId, @Param("expectedGeneration") int expectedGeneration,
                  @Param("expectedClaimToken") java.util.UUID expectedClaimToken,
                  @Param("availableAt") OffsetDateTime availableAt,
                  @Param("errorCode") String errorCode, @Param("now") OffsetDateTime now);

    @Update("UPDATE business.asset_ingestion_work SET status='FAILED',lease_expires_at=NULL,claim_token=NULL,"
            + "provider_url=NULL,provider_url_expires_at=NULL,ingestion_deadline_at=NULL,last_error_code=#{errorCode},"
            + "completed_at=#{now},updated_at=#{now} WHERE id=#{workId} AND status='PROCESSING'"
            + " AND attempt=#{expectedGeneration} AND claim_token=#{expectedClaimToken}"
            + " AND lease_expires_at>=#{now}")
    int failWork(@Param("workId") long workId, @Param("expectedGeneration") int expectedGeneration,
                 @Param("expectedClaimToken") java.util.UUID expectedClaimToken,
                 @Param("errorCode") String errorCode, @Param("now") OffsetDateTime now);
}
