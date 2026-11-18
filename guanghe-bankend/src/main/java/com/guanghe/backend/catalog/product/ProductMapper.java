package com.guanghe.backend.catalog.product;

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
public interface ProductMapper {
    @Select("SELECT " + PRODUCT_COLUMNS + " FROM business.product p WHERE p.id=#{productId} AND p.user_id=#{userId}"
            + " AND EXISTS (SELECT 1 FROM business.business_space s WHERE s.id=p.space_id"
            + " AND s.user_id=p.user_id AND s.storage_kind='PROJECT') FOR SHARE OF p")
    Optional<ProductRecord> shareByIdAndUserId(@Param("productId") long productId,@Param("userId") long userId);

    String PRODUCT_COLUMNS = """
            p.id, p.user_id AS userId, p.space_id AS spaceId,
            p.name, p.name_normalized AS nameNormalized, p.category,
            p.model_spec AS modelSpec, p.selling_points::text AS sellingPointsJson,
            p.parameters::text AS parametersJson, p.audience::text AS audienceJson,
            p.usage_scenarios::text AS usageScenariosJson,
            p.prohibited_claims::text AS prohibitedClaimsJson,
            p.primary_asset_id AS primaryAssetId, p.version,
            p.created_at AS createdAt, p.updated_at AS updatedAt,
            (SELECT COUNT(*) FROM business.product_alias a
              WHERE a.user_id = p.user_id AND a.space_id = p.space_id AND a.product_id = p.id) AS aliasCount
            """;

    @Select("SELECT " + PRODUCT_COLUMNS + " FROM business.product p"
            + " WHERE p.user_id = #{userId} AND p.space_id = #{spaceId}"
            + " AND EXISTS (SELECT 1 FROM business.business_space s WHERE s.id=p.space_id"
            + " AND s.user_id=p.user_id AND s.storage_kind='PROJECT')"
            + " ORDER BY p.created_at DESC, p.id DESC LIMIT #{size} OFFSET #{offset}")
    List<ProductRecord> findPage(
            @Param("userId") long userId,
            @Param("spaceId") long spaceId,
            @Param("offset") long offset,
            @Param("size") int size
    );

    @Select("SELECT COUNT(*) FROM business.product p"
            + " WHERE p.user_id = #{userId} AND p.space_id = #{spaceId}"
            + " AND EXISTS (SELECT 1 FROM business.business_space s WHERE s.id=p.space_id"
            + " AND s.user_id=p.user_id AND s.storage_kind='PROJECT')")
    long countBySpace(
            @Param("userId") long userId,
            @Param("spaceId") long spaceId
    );

    @Select("SELECT " + PRODUCT_COLUMNS + " FROM business.product p"
            + " WHERE p.id = #{productId} AND p.user_id = #{userId}"
            + " AND EXISTS (SELECT 1 FROM business.business_space s WHERE s.id=p.space_id"
            + " AND s.user_id=p.user_id AND s.storage_kind='PROJECT')")
    Optional<ProductRecord> findByIdAndUserId(
            @Param("productId") long productId,
            @Param("userId") long userId
    );

    @Select("""
            SELECT EXISTS(
                SELECT 1 FROM business.product
                 WHERE user_id = #{userId} AND space_id = #{spaceId}
                   AND name_normalized = #{normalized} AND id <> #{excludedProductId}
            )
            """)
    boolean nameExists(
            @Param("userId") long userId,
            @Param("spaceId") long spaceId,
            @Param("normalized") String normalized,
            @Param("excludedProductId") long excludedProductId
    );

    @Select("""
            SELECT EXISTS(
                SELECT 1 FROM business.product_alias
                 WHERE user_id = #{userId} AND space_id = #{spaceId}
                   AND alias_normalized = #{normalized} AND product_id <> #{excludedProductId}
            )
            """)
    boolean aliasExists(
            @Param("userId") long userId,
            @Param("spaceId") long spaceId,
            @Param("normalized") String normalized,
            @Param("excludedProductId") long excludedProductId
    );

    @Insert("""
            INSERT INTO business.product(
                id, user_id, space_id, name, name_normalized, category, model_spec,
                selling_points, parameters, audience, usage_scenarios, prohibited_claims,
                primary_asset_id, version, created_at, updated_at
            ) VALUES (
                #{product.id}, #{product.userId}, #{product.spaceId}, #{product.name},
                #{product.nameNormalized}, #{product.category}, #{product.modelSpec},
                CAST(#{product.sellingPointsJson} AS jsonb), CAST(#{product.parametersJson} AS jsonb),
                CAST(#{product.audienceJson} AS jsonb), CAST(#{product.usageScenariosJson} AS jsonb),
                CAST(#{product.prohibitedClaimsJson} AS jsonb), #{product.primaryAssetId},
                #{product.version}, #{product.createdAt}, #{product.updatedAt}
            )
            """)
    int insert(@Param("product") ProductRecord product);

    @Update("""
            UPDATE business.product
               SET name = #{product.name}, name_normalized = #{product.nameNormalized},
                   category = #{product.category}, model_spec = #{product.modelSpec},
                   selling_points = CAST(#{product.sellingPointsJson} AS jsonb),
                   parameters = CAST(#{product.parametersJson} AS jsonb),
                   audience = CAST(#{product.audienceJson} AS jsonb),
                   usage_scenarios = CAST(#{product.usageScenariosJson} AS jsonb),
                   prohibited_claims = CAST(#{product.prohibitedClaimsJson} AS jsonb),
                   primary_asset_id = #{product.primaryAssetId},
                   version = version + 1, updated_at = #{product.updatedAt}
             WHERE id = #{product.id} AND user_id = #{product.userId}
               AND space_id = #{product.spaceId} AND version = #{expectedVersion}
            """)
    int update(
            @Param("product") ProductRecord product,
            @Param("expectedVersion") int expectedVersion
    );

    @Update("""
            UPDATE business.product SET version = version + 1, updated_at = #{updatedAt}
             WHERE id = #{productId} AND user_id = #{userId}
               AND space_id = #{spaceId} AND version = #{expectedVersion}
            """)
    int incrementVersion(
            @Param("productId") long productId,
            @Param("userId") long userId,
            @Param("spaceId") long spaceId,
            @Param("expectedVersion") int expectedVersion,
            @Param("updatedAt") OffsetDateTime updatedAt
    );

    @Delete("DELETE FROM business.product WHERE id = #{productId} AND user_id = #{userId}")
    int delete(@Param("productId") long productId, @Param("userId") long userId);

    @Select("""
            SELECT alias AS value, alias_normalized AS normalized
              FROM business.product_alias
             WHERE user_id = #{userId} AND space_id = #{spaceId} AND product_id = #{productId}
             ORDER BY alias_normalized ASC
            """)
    List<AliasValue> findAliases(
            @Param("productId") long productId,
            @Param("userId") long userId,
            @Param("spaceId") long spaceId
    );

    @Delete("DELETE FROM business.product_alias"
            + " WHERE user_id = #{userId} AND space_id = #{spaceId} AND product_id = #{productId}")
    int deleteAliases(
            @Param("productId") long productId,
            @Param("userId") long userId,
            @Param("spaceId") long spaceId
    );

    @Insert("""
            INSERT INTO business.product_alias(
                id, user_id, space_id, product_id, alias, alias_normalized, created_at
            ) VALUES (
                #{id}, #{userId}, #{spaceId}, #{productId},
                #{alias.value}, #{alias.normalized}, #{createdAt}
            )
            """)
    int insertAlias(
            @Param("id") long id,
            @Param("userId") long userId,
            @Param("spaceId") long spaceId,
            @Param("productId") long productId,
            @Param("alias") AliasValue alias,
            @Param("createdAt") OffsetDateTime createdAt
    );

    @Select("""
            SELECT i.asset_id AS assetId,i.role,i.sequence_no AS sequence,a.status,
                   a.safety_status AS safetyStatus,
                   a.content_type AS contentType,a.size_bytes AS sizeBytes,a.sha256,
                   a.width,a.height,a.object_key AS objectKey
              FROM business.product_identity_asset i
              JOIN business.asset a ON a.id=i.asset_id AND a.user_id=i.user_id AND a.space_id=i.space_id
             WHERE i.product_id=#{productId} AND i.user_id=#{userId} AND i.space_id=#{spaceId}
             ORDER BY i.sequence_no,i.asset_id
            """)
    List<ProductIdentityAssetRecord> findIdentityAssets(
            @Param("productId") long productId,
            @Param("userId") long userId,
            @Param("spaceId") long spaceId
    );

    @Delete("DELETE FROM business.product_identity_asset WHERE product_id=#{productId}"
            + " AND user_id=#{userId} AND space_id=#{spaceId}")
    int deleteIdentityAssets(
            @Param("productId") long productId,
            @Param("userId") long userId,
            @Param("spaceId") long spaceId
    );

    @Insert("""
            INSERT INTO business.product_identity_asset(
                product_id,asset_id,user_id,space_id,role,sequence_no,created_at
            ) VALUES(#{productId},#{assetId},#{userId},#{spaceId},#{role},#{sequence},#{createdAt})
            """)
    int insertIdentityAsset(
            @Param("productId") long productId,
            @Param("assetId") long assetId,
            @Param("userId") long userId,
            @Param("spaceId") long spaceId,
            @Param("role") String role,
            @Param("sequence") int sequence,
            @Param("createdAt") OffsetDateTime createdAt
    );

    @Update("""
            UPDATE business.product
               SET primary_asset_id=#{primaryAssetId},version=version+1,updated_at=#{updatedAt}
             WHERE id=#{productId} AND user_id=#{userId} AND space_id=#{spaceId}
               AND version=#{expectedVersion}
            """)
    int updateIdentityVersion(
            @Param("productId") long productId,
            @Param("userId") long userId,
            @Param("spaceId") long spaceId,
            @Param("expectedVersion") int expectedVersion,
            @Param("primaryAssetId") Long primaryAssetId,
            @Param("updatedAt") OffsetDateTime updatedAt
    );
}
