package com.guanghe.backend.billing.pricing;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** Data access for pricing versions, their tiers and the commercial rate-limit rules. */
@Mapper
public interface PricingMapper {

    String VERSION_COLUMNS = """
            id, version_no AS versionNo, status, points_per_yuan AS pointsPerYuan,
            min_recharge_amount_cents AS minRechargeAmountCents,
            max_recharge_amount_cents AS maxRechargeAmountCents, note, created_by AS createdBy,
            published_by AS publishedBy, published_at AS publishedAt, retired_at AS retiredAt,
            version, created_at AS createdAt, updated_at AS updatedAt
            """;

    String TIER_COLUMNS = """
            id, pricing_version_id AS pricingVersionId, amount_cents AS amountCents,
            gift_points AS giftPoints, label, sort_order AS sortOrder, created_at AS createdAt
            """;

    String RULE_COLUMNS = """
            id, action, window_seconds AS windowSeconds, max_attempts AS maxAttempts, enabled,
            updated_by AS updatedBy, version, created_at AS createdAt, updated_at AS updatedAt
            """;

    // ---------------------------------------------------------------- versions

    @Select("SELECT " + VERSION_COLUMNS + " FROM business.pricing_version WHERE status='ACTIVE'")
    Optional<PricingRecords.PricingVersion> findActiveVersion();

    @Select("SELECT " + VERSION_COLUMNS + " FROM business.pricing_version WHERE id=#{id}")
    Optional<PricingRecords.PricingVersion> findVersionById(@Param("id") long id);

    @Select("SELECT COALESCE(max(version_no),0)+1 FROM business.pricing_version")
    int nextVersionNo();

    @Insert("""
            INSERT INTO business.pricing_version(id,version_no,status,points_per_yuan,
              min_recharge_amount_cents,max_recharge_amount_cents,note,created_by,published_by,
              published_at,retired_at,version,created_at,updated_at)
            VALUES(#{v.id},#{v.versionNo},#{v.status},#{v.pointsPerYuan},
              #{v.minRechargeAmountCents},#{v.maxRechargeAmountCents},#{v.note},#{v.createdBy},
              #{v.publishedBy},#{v.publishedAt},#{v.retiredAt},#{v.version},#{v.createdAt},
              #{v.updatedAt})
            """)
    int insertVersion(@Param("v") PricingRecords.PricingVersion version);

    /**
     * Edits a version that is still a draft. The {@code status='DRAFT'} predicate is what makes a
     * published rate immutable: once orders cite a version, no later edit can change what those
     * orders were priced at.
     */
    @Update("""
            UPDATE business.pricing_version SET points_per_yuan=#{pointsPerYuan},
              min_recharge_amount_cents=#{minCents}, max_recharge_amount_cents=#{maxCents},
              note=#{note}, version=version+1, updated_at=#{now}
            WHERE id=#{id} AND status='DRAFT' AND version=#{expectedVersion}
            """)
    int updateDraftVersion(@Param("id") long id, @Param("pointsPerYuan") int pointsPerYuan,
                           @Param("minCents") long minCents, @Param("maxCents") long maxCents,
                           @Param("note") String note, @Param("expectedVersion") int expectedVersion,
                           @Param("now") OffsetDateTime now);

    /**
     * Retires whichever version is active. Run before activating a successor, inside the same
     * transaction: the partial unique index permits exactly one ACTIVE row, so the order of the two
     * statements matters and neither may be skipped.
     */
    @Update("UPDATE business.pricing_version SET status='RETIRED', retired_at=#{now},"
            + " version=version+1, updated_at=#{now} WHERE status='ACTIVE'")
    int retireActiveVersion(@Param("now") OffsetDateTime now);

    @Update("""
            UPDATE business.pricing_version SET status='ACTIVE', published_by=#{publishedBy},
              published_at=#{now}, version=version+1, updated_at=#{now}
            WHERE id=#{id} AND status='DRAFT'
            """)
    int activateDraftVersion(@Param("id") long id, @Param("publishedBy") long publishedBy,
                             @Param("now") OffsetDateTime now);

    @Update("UPDATE business.pricing_version SET status='RETIRED', retired_at=#{now},"
            + " version=version+1, updated_at=#{now} WHERE id=#{id} AND status='ACTIVE'")
    int retireVersion(@Param("id") long id, @Param("now") OffsetDateTime now);

    @Select("<script>SELECT " + VERSION_COLUMNS + " FROM business.pricing_version"
            + " <where><if test='status != null'>status=#{status}</if></where>"
            + " ORDER BY version_no DESC LIMIT #{size} OFFSET #{offset}</script>")
    List<PricingRecords.PricingVersion> findVersions(@Param("status") String status,
                                                     @Param("offset") long offset,
                                                     @Param("size") int size);

    @Select("""
            <script>
            SELECT count(*) FROM business.pricing_version
            <where><if test="status != null">status=#{status}</if></where>
            </script>
            """)
    long countVersions(@Param("status") String status);

    // ---------------------------------------------------------------- tiers

    @Select("SELECT " + TIER_COLUMNS + " FROM business.pricing_version_tier"
            + " WHERE pricing_version_id=#{versionId} ORDER BY sort_order ASC, amount_cents ASC")
    List<PricingRecords.PricingTier> findTiers(@Param("versionId") long versionId);

    @Select("SELECT " + TIER_COLUMNS + " FROM business.pricing_version_tier"
            + " WHERE pricing_version_id=#{versionId} AND amount_cents=#{amountCents}")
    Optional<PricingRecords.PricingTier> findTierByAmount(@Param("versionId") long versionId,
                                                          @Param("amountCents") long amountCents);

    @Insert("""
            INSERT INTO business.pricing_version_tier(id,pricing_version_id,amount_cents,gift_points,
              label,sort_order,created_at)
            VALUES(#{t.id},#{t.pricingVersionId},#{t.amountCents},#{t.giftPoints},#{t.label},
              #{t.sortOrder},#{t.createdAt})
            """)
    int insertTier(@Param("t") PricingRecords.PricingTier tier);

    @Delete("DELETE FROM business.pricing_version_tier WHERE pricing_version_id=#{versionId}")
    int deleteTiers(@Param("versionId") long versionId);

    // ---------------------------------------------------------------- rate limit rules

    @Select("SELECT " + RULE_COLUMNS + " FROM business.commercial_rate_limit WHERE action=#{action}")
    Optional<PricingRecords.RateLimitRule> findRule(@Param("action") String action);

    @Select("SELECT " + RULE_COLUMNS + " FROM business.commercial_rate_limit ORDER BY action ASC")
    List<PricingRecords.RateLimitRule> findRules();

    @Insert("""
            INSERT INTO business.commercial_rate_limit(id,action,window_seconds,max_attempts,enabled,
              updated_by,version,created_at,updated_at)
            VALUES(#{r.id},#{r.action},#{r.windowSeconds},#{r.maxAttempts},#{r.enabled},
              #{r.updatedBy},#{r.version},#{r.createdAt},#{r.updatedAt})
            """)
    int insertRule(@Param("r") PricingRecords.RateLimitRule rule);

    /** Optimistic update: a stale expected version updates zero rows rather than clobbering. */
    @Update("""
            UPDATE business.commercial_rate_limit SET window_seconds=#{windowSeconds},
              max_attempts=#{maxAttempts}, enabled=#{enabled}, updated_by=#{updatedBy},
              version=version+1, updated_at=#{now}
            WHERE action=#{action} AND version=#{expectedVersion}
            """)
    int updateRule(@Param("action") String action, @Param("windowSeconds") int windowSeconds,
                   @Param("maxAttempts") int maxAttempts, @Param("enabled") boolean enabled,
                   @Param("updatedBy") long updatedBy, @Param("expectedVersion") int expectedVersion,
                   @Param("now") OffsetDateTime now);
}
