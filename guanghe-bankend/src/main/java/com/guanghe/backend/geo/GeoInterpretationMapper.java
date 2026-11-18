package com.guanghe.backend.geo;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface GeoInterpretationMapper {
    @Select("SELECT EXISTS(SELECT 1 FROM business.account a JOIN business.business_task t ON t.user_id=a.id"
            + " WHERE a.id=#{userId} AND a.status='ACTIVE' AND t.id=#{taskId} AND t.task_type='GEO'"
            + " AND t.status IN ('SUCCEEDED','PARTIAL'))")
    boolean executionEligible(@Param("userId") long userId, @Param("taskId") long taskId);

    String COLUMNS = "id,report_id AS reportId,user_id AS userId,input_hash AS inputHash,status,attempts,"
            + "claim_token AS claimToken,lease_expires_at AS leaseExpiresAt,deadline_at AS deadlineAt,"
            + "requested_at AS requestedAt,started_at AS startedAt,finished_at AS finishedAt,"
            + "last_error_code AS lastErrorCode,traceparent,available_at AS availableAt,capacity_wait_until AS capacityWaitUntil";

    @Select("SELECT " + COLUMNS + " FROM business.geo_report_interpretation_work"
            + " WHERE report_id=#{reportId} AND user_id=#{userId}")
    Optional<GeoInterpretationRecords.Work> find(@Param("reportId") long reportId, @Param("userId") long userId);

    @Select("SELECT " + COLUMNS + " FROM business.geo_report_interpretation_work"
            + " WHERE report_id=#{reportId} AND user_id=#{userId} FOR UPDATE")
    Optional<GeoInterpretationRecords.Work> lock(@Param("reportId") long reportId, @Param("userId") long userId);

    @Select("SELECT count(*) FROM business.geo_report_interpretation_work"
            + " WHERE user_id=#{userId} AND status IN ('QUEUED','RUNNING')")
    int countActive(@Param("userId") long userId);

    @Insert("INSERT INTO business.geo_report_interpretation_work"
            + "(id,report_id,user_id,input_hash,status,attempts,requested_at,traceparent,available_at,capacity_wait_until,created_at,updated_at)"
            + " VALUES(#{id},#{reportId},#{userId},#{hash},'QUEUED',1,#{now},#{traceparent},#{now},#{now}+INTERVAL '120 seconds',#{now},#{now})")
    int enqueue(@Param("id") long id, @Param("reportId") long reportId, @Param("userId") long userId,
                @Param("hash") String hash, @Param("traceparent") String traceparent, @Param("now") OffsetDateTime now);

    @Update("UPDATE business.geo_report_interpretation_work SET status='QUEUED',"
            + "attempts=CASE WHEN last_error_code='GEO_INTERPRETATION_CAPACITY_FULL' THEN attempts ELSE attempts+1 END,"
            + "requested_at=#{now},started_at=NULL,finished_at=NULL,last_error_code=NULL,usage=NULL,"
            + "traceparent=#{traceparent},available_at=#{now},capacity_wait_until=#{now}+INTERVAL '120 seconds',updated_at=#{now}"
            + " WHERE id=#{id} AND status='FAILED' AND available_at<=#{now}"
            + " AND (attempts<2 OR last_error_code='GEO_INTERPRETATION_CAPACITY_FULL')")
    int requeue(@Param("id") long id, @Param("traceparent") String traceparent, @Param("now") OffsetDateTime now);

    @Select("SELECT " + COLUMNS + " FROM business.geo_report_interpretation_work WHERE status='QUEUED' AND available_at<=#{now}"
            + " ORDER BY available_at,id LIMIT 1 FOR UPDATE SKIP LOCKED")
    Optional<GeoInterpretationRecords.Work> lockNext(@Param("now") OffsetDateTime now);

    // Only a confirmed Python capacity rejection (HTTP 429, zero provider calls) may return to QUEUED.
    @Update("UPDATE business.geo_report_interpretation_work SET"
            + " status=CASE WHEN capacity_wait_until>#{now} THEN 'QUEUED' ELSE 'FAILED' END,"
            + " finished_at=CASE WHEN capacity_wait_until>#{now} THEN NULL ELSE #{now} END,"
            + " claim_token=NULL,lease_expires_at=NULL,deadline_at=NULL,available_at=#{now}+INTERVAL '5 seconds',"
            + " last_error_code='GEO_INTERPRETATION_CAPACITY_FULL',updated_at=#{now}"
            + " WHERE id=#{id} AND status='RUNNING' AND attempts=#{attempt} AND claim_token=#{token} AND lease_expires_at>#{now}")
    int capacityRejected(@Param("id") long id,@Param("attempt") int attempt,@Param("token") UUID token,
                         @Param("now") OffsetDateTime now);

    @Select("SELECT " + COLUMNS + " FROM business.geo_report_interpretation_work"
            + " WHERE status='RUNNING' AND lease_expires_at<=#{now}"
            + " ORDER BY lease_expires_at,id LIMIT 1 FOR UPDATE SKIP LOCKED")
    Optional<GeoInterpretationRecords.Work> lockExpired(@Param("now") OffsetDateTime now);

    @Update("UPDATE business.geo_report_interpretation_work SET status='RUNNING',claim_token=#{token},"
            + "lease_expires_at=#{lease},deadline_at=#{deadline},started_at=#{now},updated_at=#{now}"
            + " WHERE id=#{id} AND status='QUEUED'")
    int claim(@Param("id") long id, @Param("token") UUID token, @Param("lease") OffsetDateTime lease,
              @Param("deadline") OffsetDateTime deadline, @Param("now") OffsetDateTime now);

    @Update("UPDATE business.geo_report_interpretation_work SET status=#{status},claim_token=NULL,"
            + "lease_expires_at=NULL,deadline_at=NULL,finished_at=#{now},last_error_code=#{error},"
            + "usage=CAST(#{usage} AS jsonb),updated_at=#{now} WHERE id=#{id} AND status='RUNNING'"
            + " AND attempts=#{attempt} AND claim_token=#{token} AND lease_expires_at>#{now}")
    int finish(@Param("id") long id, @Param("attempt") int attempt, @Param("token") UUID token,
               @Param("status") String status, @Param("error") String error, @Param("usage") String usage,
               @Param("now") OffsetDateTime now);

    @Update("UPDATE business.geo_report_interpretation_work SET status='FAILED',claim_token=NULL,"
            + "lease_expires_at=NULL,deadline_at=NULL,finished_at=#{now},last_error_code=#{error},updated_at=#{now}"
            + " WHERE id=#{id} AND (status='QUEUED' OR (status='RUNNING' AND lease_expires_at<=#{now}))")
    int rejectOrExpire(@Param("id") long id, @Param("error") String error, @Param("now") OffsetDateTime now);

    @Update("UPDATE business.geo_report SET synthesis=CAST(#{synthesis} AS jsonb),"
            + "synthesis_usage=CAST(#{usage} AS jsonb),summary=#{summary},method_version='geo-synthesis-v1'"
            + " WHERE id=#{reportId} AND user_id=#{userId} AND archived_at IS NULL"
            + " AND method_version='geo-deterministic-v1'")
    int publish(@Param("reportId") long reportId, @Param("userId") long userId,
                @Param("synthesis") String synthesis, @Param("usage") String usage, @Param("summary") String summary);
}
