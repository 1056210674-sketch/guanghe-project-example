package com.guanghe.backend.catalog.space;

import com.guanghe.backend.catalog.AliasValue;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface BusinessSpaceMapper {
    @Select("SELECT " + SPACE_COLUMNS + " FROM business.business_space s"
            + " WHERE s.id=#{spaceId} AND s.user_id=#{userId} AND s.storage_kind='PROJECT' FOR SHARE OF s")
    Optional<BusinessSpaceRecord> shareProjectByIdAndUserId(
            @Param("spaceId") long spaceId,
            @Param("userId") long userId
    );

    String SPACE_COLUMNS = """
            s.id, s.user_id AS userId, s.name, s.name_normalized AS nameNormalized,
            s.brand_name AS brandName, s.brand_profile::text AS brandProfileJson,
            s.logo_asset_id AS logoAssetId, s.version, s.created_at AS createdAt,
            s.updated_at AS updatedAt,
            (SELECT COUNT(*) FROM business.product p
              WHERE p.user_id = s.user_id AND p.space_id = s.id) AS productCount,
            (SELECT COUNT(*) FROM business.competitor c
              WHERE c.user_id = s.user_id AND c.space_id = s.id) AS competitorCount
            """;

    @Select("SELECT " + SPACE_COLUMNS + " FROM business.business_space s"
            + " WHERE s.user_id = #{userId} AND s.storage_kind='PROJECT' AND s.brand_name IS NOT NULL"
            + " ORDER BY s.created_at DESC, s.id DESC"
            + " LIMIT #{size} OFFSET #{offset}")
    List<BusinessSpaceRecord> findPage(
            @Param("userId") long userId,
            @Param("offset") long offset,
            @Param("size") int size
    );

    @Select("SELECT COUNT(*) FROM business.business_space WHERE user_id = #{userId}"
            + " AND storage_kind='PROJECT' AND brand_name IS NOT NULL")
    long countByUserId(@Param("userId") long userId);

    @Select("SELECT " + SPACE_COLUMNS + " FROM business.business_space s"
            + " WHERE s.id = #{spaceId} AND s.user_id = #{userId}")
    Optional<BusinessSpaceRecord> findOwnedStorageByIdAndUserId(
            @Param("spaceId") long spaceId,
            @Param("userId") long userId
    );

    @Select("SELECT " + SPACE_COLUMNS + " FROM business.business_space s"
            + " WHERE s.id = #{spaceId} AND s.user_id = #{userId} AND s.storage_kind='PERSONAL_CREATIVE'")
    Optional<BusinessSpaceRecord> findPersonalCreativeByIdAndUserId(
            @Param("spaceId") long spaceId,
            @Param("userId") long userId
    );

    @Select("SELECT " + SPACE_COLUMNS + " FROM business.business_space s"
            + " WHERE s.id = #{spaceId} AND s.user_id = #{userId} AND s.storage_kind='PROJECT'")
    Optional<BusinessSpaceRecord> findProjectByIdAndUserId(
            @Param("spaceId") long spaceId,
            @Param("userId") long userId
    );

    @Select("""
            SELECT EXISTS(
                SELECT 1 FROM business.business_space
                 WHERE user_id = #{userId}
                   AND name_normalized = #{normalized}
                   AND id <> #{excludedId}
            )
            """)
    boolean nameExists(
            @Param("userId") long userId,
            @Param("normalized") String normalized,
            @Param("excludedId") long excludedId
    );

    @Insert("""
            INSERT INTO business.business_space(
                id, user_id, name, name_normalized, brand_name, brand_profile,
                logo_asset_id, version, created_at, updated_at
            ) VALUES (
                #{space.id}, #{space.userId}, #{space.name}, #{space.nameNormalized},
                #{space.brandName}, CAST(#{space.brandProfileJson} AS jsonb),
                #{space.logoAssetId}, #{space.version}, #{space.createdAt}, #{space.updatedAt}
            )
            """)
    int insert(@Param("space") BusinessSpaceRecord space);

    @Update("""
            UPDATE business.business_space
               SET name = #{space.name},
                   name_normalized = #{space.nameNormalized},
                   brand_name = #{space.brandName},
                   brand_profile = CAST(#{space.brandProfileJson} AS jsonb),
                   logo_asset_id = #{space.logoAssetId},
                   version = version + 1,
                   updated_at = #{space.updatedAt}
             WHERE id = #{space.id}
               AND user_id = #{space.userId}
               AND version = #{expectedVersion}
            """)
    int update(
            @Param("space") BusinessSpaceRecord space,
            @Param("expectedVersion") int expectedVersion
    );

    @Update("""
            UPDATE business.business_space SET brand_name=#{name},version=version+1,updated_at=#{now}
            WHERE id=#{spaceId} AND user_id=#{userId} AND version=#{version} AND project_status='ACTIVE'
            """)
    int updateGeoTarget(@Param("spaceId") long spaceId, @Param("userId") long userId,
                        @Param("version") int version, @Param("name") String name,
                        @Param("now") OffsetDateTime now);

    @Delete("DELETE FROM business.business_space WHERE id = #{spaceId} AND user_id = #{userId}")
    int delete(@Param("spaceId") long spaceId, @Param("userId") long userId);

    @Select("""
            SELECT
                (SELECT COUNT(*) FROM business.product
                  WHERE user_id = #{userId} AND space_id = #{spaceId}) AS productCount,
                (SELECT COUNT(*) FROM business.competitor
                  WHERE user_id = #{userId} AND space_id = #{spaceId}) AS competitorCount
            """)
    SpaceUsageCounts usageCounts(
            @Param("spaceId") long spaceId,
            @Param("userId") long userId
    );

    @Select("""
            SELECT alias AS value, alias_normalized AS normalized
              FROM business.space_brand_alias
             WHERE user_id = #{userId} AND space_id = #{spaceId}
             ORDER BY alias_normalized ASC
            """)
    List<AliasValue> findAliases(
            @Param("spaceId") long spaceId,
            @Param("userId") long userId
    );

    @Delete("DELETE FROM business.space_brand_alias WHERE user_id = #{userId} AND space_id = #{spaceId}")
    int deleteAliases(@Param("spaceId") long spaceId, @Param("userId") long userId);

    @Insert("""
            INSERT INTO business.space_brand_alias(
                id, user_id, space_id, alias, alias_normalized, created_at
            ) VALUES (#{id}, #{userId}, #{spaceId}, #{alias.value}, #{alias.normalized}, #{createdAt})
            """)
    int insertAlias(
            @Param("id") long id,
            @Param("userId") long userId,
            @Param("spaceId") long spaceId,
            @Param("alias") AliasValue alias,
            @Param("createdAt") OffsetDateTime createdAt
    );
}
