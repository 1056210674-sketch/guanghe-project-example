package com.guanghe.backend.geo;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.apache.ibatis.annotations.*;

/** Narrow delivery state. Sampling remains authoritative in the existing geo_run/observations. */
@Mapper
public interface GeoDiagnosticDeliveryMapper {
    record Row(long diagnosticId,long userId,long spaceId,long conversationId,long taskId,long runId,long topicId,
        UUID requestId,String requestHash,int submittedVersion,UUID quoteId,String quoteHash,long pricingVersionId,int quotedPoints,
        String status,int version,String samplingStatus,int settledPoints,int releasedPoints,
        String evidenceJson,String evidenceHash,String analysisJson,String analysisUsageJson,OffsetDateTime snapshotAt,
        int analysisAttempts,int pdfAttempts,UUID claimToken,OffsetDateTime leaseExpiresAt,String errorCode,
        String objectKey,Long pdfSize,String pdfSha256,String pdfFilename,OffsetDateTime createdAt,OffsetDateTime updatedAt,OffsetDateTime completedAt) { }
    String COLS="diagnostic_id AS diagnosticId,user_id AS userId,space_id AS spaceId,conversation_id AS conversationId,"
      +"task_id AS taskId,run_id AS runId,topic_id AS topicId,request_id AS requestId,request_hash AS requestHash,"
      +"submitted_version AS submittedVersion,quote_id AS quoteId,quote_hash AS quoteHash,pricing_version_id AS pricingVersionId,quoted_points AS quotedPoints,"
      +"status,version,sampling_status AS samplingStatus,settled_points AS settledPoints,released_points AS releasedPoints,"
      +"evidence::text AS evidenceJson,evidence_hash AS evidenceHash,analysis::text AS analysisJson,analysis_usage::text AS analysisUsageJson,snapshot_at AS snapshotAt,"
      +"analysis_attempts AS analysisAttempts,pdf_attempts AS pdfAttempts,claim_token AS claimToken,lease_expires_at AS leaseExpiresAt,error_code AS errorCode,"
      +"object_key AS objectKey,pdf_size AS pdfSize,pdf_sha256 AS pdfSha256,pdf_filename AS pdfFilename,created_at AS createdAt,updated_at AS updatedAt,completed_at AS completedAt";
    @Select("SELECT "+COLS+" FROM business.geo_diagnostic_delivery WHERE diagnostic_id=#{id} AND user_id=#{user}")
    Optional<Row> find(@Param("user") long user,@Param("id") long id);
    @Select("SELECT "+COLS+" FROM business.geo_diagnostic_delivery WHERE diagnostic_id=#{id} AND user_id=#{user} FOR UPDATE")
    Optional<Row> lock(@Param("user") long user,@Param("id") long id);
    @Select("SELECT "+COLS+" FROM business.geo_diagnostic_delivery WHERE task_id=#{task}")
    Optional<Row> byTask(@Param("task") long task);
    @Select("SELECT "+COLS+" FROM business.geo_diagnostic_delivery WHERE user_id=#{user} AND request_id=#{request}")
    Optional<Row> byRequest(@Param("user") long user,@Param("request") UUID request);
    record Recovery(long diagnosticId,int requestVersion,String requestHash) { }
    @Select("SELECT diagnostic_id AS diagnosticId,request_version AS requestVersion,request_hash AS requestHash FROM business.geo_diagnostic_delivery_request "
      +"WHERE user_id=#{user} AND request_id=#{request}")
    Optional<Recovery> recovery(@Param("user") long user,@Param("request") UUID request);
    @Insert("INSERT INTO business.geo_diagnostic_delivery_request(user_id,request_id,diagnostic_id,request_version,request_hash,created_at) "
      +"VALUES(#{user},#{request},#{id},#{version},#{hash},#{now})")
    int insertRecovery(@Param("user") long user,@Param("request") UUID request,@Param("id") long id,@Param("version") int version,@Param("hash") String hash,@Param("now") OffsetDateTime now);
    @Select("SELECT "+COLS+" FROM business.geo_diagnostic_delivery WHERE user_id=#{user} AND space_id=#{space} AND status='READY' ORDER BY completed_at DESC,diagnostic_id DESC LIMIT 1")
    Optional<Row> latestReady(@Param("user") long user,@Param("space") long space);
    @Select("SELECT "+COLS+" FROM business.geo_diagnostic_delivery WHERE user_id=#{user} AND space_id=#{space} ORDER BY created_at DESC,diagnostic_id DESC LIMIT 1")
    Optional<Row> latest(@Param("user") long user,@Param("space") long space);
    @Insert("INSERT INTO business.geo_diagnostic_delivery(diagnostic_id,user_id,space_id,conversation_id,task_id,run_id,topic_id,request_id,request_hash,"
      +"submitted_version,quote_id,quote_hash,pricing_version_id,quoted_points,status,created_at,updated_at) VALUES(#{r.diagnosticId},#{r.userId},#{r.spaceId},"
      +"#{r.conversationId},#{r.taskId},#{r.runId},#{r.topicId},#{r.requestId},#{r.requestHash},#{r.submittedVersion},#{r.quoteId},#{r.quoteHash},#{r.pricingVersionId},"
      +"#{r.quotedPoints},'SAMPLING',#{r.createdAt},#{r.updatedAt})")
    int insert(@Param("r") Row row);
    @Update("UPDATE business.geo_topic SET topic_kind='DIAGNOSTIC' WHERE id=#{topic} AND user_id=#{user} AND topic_kind='MONITORING'")
    int diagnosticTopic(@Param("user") long user,@Param("topic") long topic);
    @Update("UPDATE business.geo_diagnostic_preparation SET version=version+1,updated_at=#{now} WHERE id=#{id} AND user_id=#{user}")
    int revision(@Param("user") long user,@Param("id") long id,@Param("now") OffsetDateTime now);
    @Update("UPDATE business.geo_diagnostic_delivery SET status=#{status},sampling_status=#{samplingStatus},settled_points=#{settled},released_points=#{released},"
      +"evidence=CAST(#{evidence} AS jsonb),evidence_hash=#{hash},snapshot_at=#{now},analysis=CAST(#{analysis} AS jsonb),version=version+1,updated_at=#{now}"
      +" WHERE task_id=#{task} AND status='SAMPLING'")
    int sampling(@Param("task") long task,@Param("status") String status,@Param("samplingStatus") String samplingStatus,
      @Param("settled") int settled,@Param("released") int released,@Param("evidence") String evidence,@Param("hash") String hash,
      @Param("analysis") String analysis,@Param("now") OffsetDateTime now);
    @Select("SELECT "+COLS+" FROM business.geo_diagnostic_delivery WHERE status IN ('ANALYSIS_QUEUED','PDF_QUEUED') ORDER BY updated_at,diagnostic_id LIMIT 1 FOR UPDATE SKIP LOCKED")
    Optional<Row> next();
    @Select("SELECT "+COLS+" FROM business.geo_diagnostic_delivery WHERE status='SAMPLING' AND EXISTS(SELECT 1 FROM business.business_task t "
      +"WHERE t.id=task_id AND t.status IN ('FAILED','SYSTEM_TERMINATED')) ORDER BY updated_at,diagnostic_id LIMIT 1 FOR UPDATE SKIP LOCKED")
    Optional<Row> strandedSampling();
    @Update("UPDATE business.geo_diagnostic_delivery SET status=#{phase},claim_token=#{token},lease_expires_at=#{lease},"
      +"analysis_attempts=analysis_attempts+CASE WHEN #{phase}='ANALYSIS_RUNNING' THEN 1 ELSE 0 END,"
      +"pdf_attempts=pdf_attempts+CASE WHEN #{phase}='PDF_RUNNING' THEN 1 ELSE 0 END,version=version+1,error_code=NULL,updated_at=#{now} "
      +"WHERE diagnostic_id=#{id} AND status=#{expected} AND ((#{phase}='ANALYSIS_RUNNING' AND analysis_attempts<2) OR (#{phase}='PDF_RUNNING' AND pdf_attempts<3))")
    int claim(@Param("id") long id,@Param("expected") String expected,@Param("phase") String phase,@Param("token") UUID token,
      @Param("lease") OffsetDateTime lease,@Param("now") OffsetDateTime now);
    @Update("UPDATE business.geo_diagnostic_delivery SET status='PDF_QUEUED',analysis=CAST(#{analysis} AS jsonb),analysis_usage=CAST(#{usage} AS jsonb),"
      +"claim_token=NULL,lease_expires_at=NULL,version=version+1,updated_at=#{now} WHERE diagnostic_id=#{id} AND status='ANALYSIS_RUNNING' "
      +"AND claim_token=#{token} AND lease_expires_at>#{now}")
    int analyzed(@Param("id") long id,@Param("token") UUID token,@Param("analysis") String analysis,@Param("usage") String usage,@Param("now") OffsetDateTime now);
    @Update("UPDATE business.geo_diagnostic_delivery SET status=#{status},error_code=#{error},claim_token=NULL,lease_expires_at=NULL,version=version+1,updated_at=#{now} "
      +"WHERE diagnostic_id=#{id} AND status IN ('ANALYSIS_RUNNING','PDF_RUNNING') AND claim_token=#{token} AND lease_expires_at>#{now}")
    int failed(@Param("id") long id,@Param("token") UUID token,@Param("status") String status,@Param("error") String error,@Param("now") OffsetDateTime now);
    @Select("SELECT "+COLS+" FROM business.geo_diagnostic_delivery WHERE status IN ('ANALYSIS_RUNNING','PDF_RUNNING') AND lease_expires_at<=#{now} "
      +"ORDER BY lease_expires_at,diagnostic_id LIMIT 1 FOR UPDATE SKIP LOCKED")
    Optional<Row> expired(@Param("now") OffsetDateTime now);
    @Update("UPDATE business.geo_diagnostic_delivery SET status=#{status},error_code='DELIVERY_LEASE_EXPIRED',claim_token=NULL,lease_expires_at=NULL,version=version+1,"
      +"updated_at=#{now} WHERE diagnostic_id=#{id} AND status IN ('ANALYSIS_RUNNING','PDF_RUNNING') AND lease_expires_at<=#{now}")
    int expire(@Param("id") long id,@Param("status") String status,@Param("now") OffsetDateTime now);
    @Update("UPDATE business.geo_diagnostic_delivery SET status=#{status},error_code=NULL,version=version+1,updated_at=#{now} WHERE diagnostic_id=#{id} AND status IN ('ANALYSIS_FAILED','PDF_FAILED')")
    int retry(@Param("id") long id,@Param("status") String status,@Param("now") OffsetDateTime now);
    @Update("UPDATE business.geo_diagnostic_delivery SET status='READY',object_key=#{key},pdf_size=#{size},pdf_sha256=#{hash},pdf_filename=#{filename},"
      +"claim_token=NULL,lease_expires_at=NULL,error_code=NULL,version=version+1,completed_at=#{now},updated_at=#{now} "
      +"WHERE diagnostic_id=#{id} AND status='PDF_RUNNING' AND claim_token=#{token} AND lease_expires_at>#{now}")
    int ready(@Param("id") long id,@Param("token") UUID token,@Param("key") String key,@Param("size") long size,
      @Param("hash") String hash,@Param("filename") String filename,@Param("now") OffsetDateTime now);
    @Update("UPDATE business.business_task SET stage_code=#{stage},error_code=#{error},updated_at=#{now} WHERE id=#{task} AND task_type='GEO' "
      +"AND operation_scope='GEO_DIAGNOSTIC_SUBMIT' AND status='RUNNING'")
    int stage(@Param("task") long task,@Param("stage") String stage,@Param("error") String error,@Param("now") OffsetDateTime now);
    @Update("UPDATE business.business_task SET status=#{status},stage_code=#{stage},error_code=#{error},finished_at=#{now},result_summary=CAST(#{result} AS jsonb),updated_at=#{now} "
      +"WHERE id=#{task} AND task_type='GEO' AND operation_scope='GEO_DIAGNOSTIC_SUBMIT' AND status='RUNNING'")
    int completedTask(@Param("task") long task,@Param("status") String status,@Param("stage") String stage,@Param("error") String error,
      @Param("result") String result,@Param("now") OffsetDateTime now);
}
