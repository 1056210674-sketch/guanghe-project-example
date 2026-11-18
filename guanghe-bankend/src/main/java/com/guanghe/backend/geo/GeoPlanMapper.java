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
public interface GeoPlanMapper {
    String PROJECT_SPACE = "EXISTS (SELECT 1 FROM business.business_space s"
            + " WHERE s.id=p.space_id AND s.user_id=p.user_id AND s.storage_kind='PROJECT')";
    String PLAN_COLUMNS = """
            p.id,p.user_id AS userId,p.space_id AS spaceId,p.product_id AS productId,
            p.topic_id AS topicId,p.source_draft_id AS sourceDraftId,
            p.create_idempotency_key AS createIdempotencyKey,p.request_hash AS requestHash,
            p.name,p.name_normalized AS nameNormalized,p.status,p.schedule_type AS scheduleType,
            p.schedule::text AS scheduleJson,p.input_snapshot::text AS inputSnapshotJson,
            p.input_hash AS inputHash,p.pricing_version_id AS pricingVersionId,
            p.estimated_points_per_run AS estimatedPointsPerRun,
            p.max_points_per_run AS maxPointsPerRun,p.next_run_at AS nextRunAt,
            p.last_scheduled_slot AS lastScheduledSlot,p.pause_reason AS pauseReason,
            p.version,p.created_at AS createdAt,p.updated_at AS updatedAt
            """;

    @Insert("""
            INSERT INTO business.geo_plan(
              id,user_id,space_id,product_id,topic_id,source_draft_id,create_idempotency_key,
              request_hash,name,name_normalized,status,schedule_type,schedule,input_snapshot,
              input_hash,pricing_version_id,estimated_points_per_run,max_points_per_run,
              next_run_at,last_scheduled_slot,pause_reason,version,created_at,updated_at
            ) SELECT #{p.id},#{p.userId},#{p.spaceId},#{p.productId},#{p.topicId},
              #{p.sourceDraftId},#{p.createIdempotencyKey},#{p.requestHash},#{p.name},
              #{p.nameNormalized},#{p.status},#{p.scheduleType},CAST(#{p.scheduleJson} AS jsonb),
              CAST(#{p.inputSnapshotJson} AS jsonb),#{p.inputHash},#{p.pricingVersionId},
              #{p.estimatedPointsPerRun},#{p.maxPointsPerRun},#{p.nextRunAt},
              #{p.lastScheduledSlot},#{p.pauseReason},#{p.version},#{p.createdAt},#{p.updatedAt}
            WHERE EXISTS (SELECT 1 FROM business.business_space s
              WHERE s.id=#{p.spaceId} AND s.user_id=#{p.userId} AND s.storage_kind='PROJECT')
            """)
    int insertPlan(@Param("p") GeoPlanRecords.Plan plan);

    @Select("SELECT " + PLAN_COLUMNS + " FROM business.geo_plan p"
            + " WHERE p.user_id=#{userId} AND p.create_idempotency_key=#{key} AND " + PROJECT_SPACE)
    Optional<GeoPlanRecords.Plan> findByIdempotency(@Param("userId") long userId,
                                                     @Param("key") UUID key);

    @Select("SELECT " + PLAN_COLUMNS + " FROM business.geo_plan p"
            + " WHERE p.id=#{planId} AND p.user_id=#{userId} AND " + PROJECT_SPACE)
    Optional<GeoPlanRecords.Plan> findPlan(@Param("planId") long planId,
                                           @Param("userId") long userId);

    @Select("SELECT " + PLAN_COLUMNS + " FROM business.geo_plan p"
            + " WHERE p.id=#{planId} AND p.user_id=#{userId} AND " + PROJECT_SPACE
            + " FOR UPDATE OF p")
    Optional<GeoPlanRecords.Plan> lockPlan(@Param("planId") long planId,
                                           @Param("userId") long userId);

    @Select("SELECT " + PLAN_COLUMNS + " FROM business.geo_plan p WHERE p.id=#{planId} AND "
            + PROJECT_SPACE + " FOR UPDATE OF p")
    Optional<GeoPlanRecords.Plan> lockPlanInternal(@Param("planId") long planId);

    @Select("<script>SELECT " + PLAN_COLUMNS + " FROM business.geo_plan p WHERE p.user_id=#{userId}"
            + " AND " + PROJECT_SPACE
            + " <if test='status != null'>AND p.status=#{status}</if>"
            + " <if test='topicId != null'>AND p.topic_id=#{topicId}</if>"
            + " ORDER BY p.created_at DESC,p.id DESC LIMIT #{size} OFFSET #{offset}</script>")
    List<GeoPlanRecords.Plan> findPage(@Param("userId") long userId,
                                       @Param("status") String status,
                                       @Param("topicId") Long topicId,
                                       @Param("offset") long offset,
                                       @Param("size") int size);

    @Select("<script>SELECT count(*) FROM business.geo_plan p WHERE p.user_id=#{userId}"
            + " AND " + PROJECT_SPACE
            + " <if test='status != null'>AND p.status=#{status}</if>"
            + " <if test='topicId != null'>AND p.topic_id=#{topicId}</if></script>")
    long count(@Param("userId") long userId, @Param("status") String status,
               @Param("topicId") Long topicId);

    @Select("SELECT " + PLAN_COLUMNS + " FROM business.geo_plan p"
            + " WHERE p.status='ACTIVE' AND " + PROJECT_SPACE + " ORDER BY p.id")
    List<GeoPlanRecords.Plan> findActivePlans();

    @Select("SELECT EXISTS(SELECT 1 FROM business.geo_plan p WHERE p.user_id=#{userId}"
            + " AND p.name_normalized=#{normalized} AND p.id<>#{excludedId} AND " + PROJECT_SPACE + ")")
    boolean planNameExists(@Param("userId") long userId, @Param("normalized") String normalized,
                           @Param("excludedId") long excludedId);

    @Update("UPDATE business.geo_plan p SET name=#{name},name_normalized=#{normalized},"
            + "version=version+1,updated_at=#{now} WHERE p.id=#{planId} AND p.user_id=#{userId}"
            + " AND p.version=#{version} AND p.status IN ('ACTIVE','PAUSED') AND " + PROJECT_SPACE)
    int updateName(@Param("planId") long planId, @Param("userId") long userId,
                   @Param("version") int version, @Param("name") String name,
                   @Param("normalized") String normalized, @Param("now") OffsetDateTime now);

    @Update("""
            UPDATE business.geo_plan gp SET space_id=#{p.spaceId},product_id=#{p.productId},
              topic_id=#{p.topicId},source_draft_id=#{p.sourceDraftId},name=#{p.name},
              name_normalized=#{p.nameNormalized},schedule_type=#{p.scheduleType},
              schedule=CAST(#{p.scheduleJson} AS jsonb),input_snapshot=CAST(#{p.inputSnapshotJson} AS jsonb),
              input_hash=#{p.inputHash},pricing_version_id=#{p.pricingVersionId},
              estimated_points_per_run=#{p.estimatedPointsPerRun},max_points_per_run=#{p.maxPointsPerRun},
              next_run_at=#{p.nextRunAt},pause_reason=#{p.pauseReason},status=#{p.status},version=version+1,
              updated_at=#{p.updatedAt}
            WHERE gp.id=#{p.id} AND gp.user_id=#{p.userId} AND gp.version=#{expectedVersion}
              AND EXISTS (SELECT 1 FROM business.business_space s
                WHERE s.id=gp.space_id AND s.user_id=gp.user_id AND s.storage_kind='PROJECT')
              AND EXISTS (SELECT 1 FROM business.business_space s
                WHERE s.id=#{p.spaceId} AND s.user_id=#{p.userId} AND s.storage_kind='PROJECT')
            """)
    int replacePlan(@Param("p") GeoPlanRecords.Plan replacement,
                    @Param("expectedVersion") int expectedVersion);

    @Update("UPDATE business.geo_plan p SET status='PAUSED',next_run_at=NULL,pause_reason=#{reason},"
            + "version=version+1,updated_at=#{now} WHERE p.id=#{planId} AND p.user_id=#{userId}"
            + " AND p.version=#{version} AND p.status='ACTIVE' AND " + PROJECT_SPACE)
    int pause(@Param("planId") long planId, @Param("userId") long userId,
              @Param("version") int version, @Param("reason") String reason,
              @Param("now") OffsetDateTime now);

    @Update("UPDATE business.geo_plan p SET status='ACTIVE',next_run_at=#{nextRunAt},pause_reason=NULL,"
            + "version=version+1,updated_at=#{now} WHERE p.id=#{planId} AND p.user_id=#{userId}"
            + " AND p.version=#{version} AND p.status='PAUSED' AND " + PROJECT_SPACE)
    int resume(@Param("planId") long planId, @Param("userId") long userId,
               @Param("version") int version, @Param("nextRunAt") OffsetDateTime nextRunAt,
               @Param("now") OffsetDateTime now);

    @Select("SELECT status FROM business.account WHERE id=#{userId}")
    Optional<String> findAccountStatus(@Param("userId") long userId);

    @Select("SELECT EXISTS(SELECT 1 FROM business.business_task WHERE plan_id=#{planId}"
            + " AND status IN ('QUEUED','RUNNING','RETRY_WAIT'))")
    boolean hasInFlightTask(@Param("planId") long planId);

    @Insert("""
            INSERT INTO business.geo_plan_slot(
              id,plan_id,user_id,scheduled_slot,status,task_id,quoted_points,error_code,created_at,updated_at
            ) VALUES(#{s.id},#{s.planId},#{s.userId},#{s.scheduledSlot},#{s.status},#{s.taskId},
              #{s.quotedPoints},#{s.errorCode},#{s.createdAt},#{s.updatedAt})
            ON CONFLICT (plan_id,scheduled_slot) DO NOTHING
            """)
    int insertSlot(@Param("s") GeoPlanRecords.Slot slot);

    @Select("SELECT id,plan_id AS planId,user_id AS userId,scheduled_slot AS scheduledSlot,status,"
            + "task_id AS taskId,quoted_points AS quotedPoints,error_code AS errorCode,"
            + "created_at AS createdAt,updated_at AS updatedAt FROM business.geo_plan_slot"
            + " WHERE plan_id=#{planId} AND scheduled_slot=#{slot}")
    Optional<GeoPlanRecords.Slot> findSlot(@Param("planId") long planId,
                                           @Param("slot") OffsetDateTime slot);

    @Update("UPDATE business.geo_plan_slot SET status='DISPATCHED',task_id=#{taskId},"
            + "quoted_points=#{points},error_code=NULL,updated_at=#{now}"
            + " WHERE plan_id=#{planId} AND scheduled_slot=#{slot} AND status='PROCESSING'")
    int dispatchSlot(@Param("planId") long planId, @Param("slot") OffsetDateTime slot,
                     @Param("taskId") long taskId, @Param("points") int points,
                     @Param("now") OffsetDateTime now);

    @Update("UPDATE business.geo_plan_slot SET status='SKIPPED',quoted_points=#{points},"
            + "error_code=#{errorCode},updated_at=#{now}"
            + " WHERE plan_id=#{planId} AND scheduled_slot=#{slot} AND status='PROCESSING'")
    int skipSlot(@Param("planId") long planId, @Param("slot") OffsetDateTime slot,
                 @Param("points") Integer points, @Param("errorCode") String errorCode,
                 @Param("now") OffsetDateTime now);

    @Update("UPDATE business.geo_plan p SET last_scheduled_slot=#{slot},next_run_at=#{nextRunAt},"
            + "updated_at=#{now} WHERE p.id=#{planId} AND p.status='ACTIVE' AND " + PROJECT_SPACE)
    int advance(@Param("planId") long planId, @Param("slot") OffsetDateTime slot,
                @Param("nextRunAt") OffsetDateTime nextRunAt, @Param("now") OffsetDateTime now);

    @Update("UPDATE business.geo_plan p SET status='PAUSED',next_run_at=NULL,pause_reason=#{reason},"
            + "last_scheduled_slot=#{slot},version=version+1,updated_at=#{now}"
            + " WHERE p.id=#{planId} AND p.status='ACTIVE' AND " + PROJECT_SPACE)
    int pauseAfterSkip(@Param("planId") long planId, @Param("slot") OffsetDateTime slot,
                       @Param("reason") String reason, @Param("now") OffsetDateTime now);
}
