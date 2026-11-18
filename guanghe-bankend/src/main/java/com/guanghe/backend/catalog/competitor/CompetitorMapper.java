package com.guanghe.backend.catalog.competitor;

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
public interface CompetitorMapper {
    @Select("SELECT " + COMPETITOR_COLUMNS + " FROM business.competitor c WHERE c.id=#{competitorId} AND c.user_id=#{userId}"
            + " AND EXISTS (SELECT 1 FROM business.business_space s WHERE s.id=c.space_id"
            + " AND s.user_id=c.user_id AND s.storage_kind='PROJECT') FOR SHARE OF c")
    Optional<CompetitorRecord> shareByIdAndUserId(@Param("competitorId") long competitorId,@Param("userId") long userId);

    String COMPETITOR_COLUMNS = """
            c.id, c.user_id AS userId, c.space_id AS spaceId,
            c.name, c.name_normalized AS nameNormalized,
            c.competitor_type AS type, c.description, c.status, c.version,
            c.created_at AS createdAt, c.updated_at AS updatedAt,
            (SELECT COUNT(*) FROM business.competitor_alias a
              WHERE a.user_id = c.user_id AND a.space_id = c.space_id
                AND a.competitor_id = c.id) AS aliasCount
            """;

    @Select("SELECT " + COMPETITOR_COLUMNS + " FROM business.competitor c"
            + " WHERE c.user_id = #{userId} AND c.space_id = #{spaceId}"
            + " AND EXISTS (SELECT 1 FROM business.business_space s WHERE s.id=c.space_id"
            + " AND s.user_id=c.user_id AND s.storage_kind='PROJECT')"
            + " ORDER BY c.created_at DESC, c.id DESC LIMIT #{size} OFFSET #{offset}")
    List<CompetitorRecord> findPage(
            @Param("userId") long userId,
            @Param("spaceId") long spaceId,
            @Param("offset") long offset,
            @Param("size") int size
    );

    @Select("SELECT COUNT(*) FROM business.competitor c"
            + " WHERE c.user_id = #{userId} AND c.space_id = #{spaceId}"
            + " AND EXISTS (SELECT 1 FROM business.business_space s WHERE s.id=c.space_id"
            + " AND s.user_id=c.user_id AND s.storage_kind='PROJECT')")
    long countBySpace(@Param("userId") long userId, @Param("spaceId") long spaceId);

    @Select("SELECT " + COMPETITOR_COLUMNS + " FROM business.competitor c"
            + " WHERE c.id = #{competitorId} AND c.user_id = #{userId}"
            + " AND EXISTS (SELECT 1 FROM business.business_space s WHERE s.id=c.space_id"
            + " AND s.user_id=c.user_id AND s.storage_kind='PROJECT')")
    Optional<CompetitorRecord> findByIdAndUserId(
            @Param("competitorId") long competitorId,
            @Param("userId") long userId
    );

    @Select("SELECT " + COMPETITOR_COLUMNS + " FROM business.competitor c"
            + " WHERE c.user_id = #{userId} AND c.space_id = #{spaceId} AND c.status = 'ACTIVE'"
            + " AND EXISTS (SELECT 1 FROM business.business_space s WHERE s.id=c.space_id"
            + " AND s.user_id=c.user_id AND s.storage_kind='PROJECT')"
            + " ORDER BY c.created_at ASC, c.id ASC")
    List<CompetitorRecord> findActiveForRunContext(
            @Param("userId") long userId,
            @Param("spaceId") long spaceId
    );

    @Select("""
            SELECT EXISTS(
                SELECT 1 FROM business.competitor
                 WHERE user_id = #{userId} AND space_id = #{spaceId}
                   AND name_normalized = #{normalized} AND id <> #{excludedCompetitorId}
            )
            """)
    boolean nameExists(
            @Param("userId") long userId,
            @Param("spaceId") long spaceId,
            @Param("normalized") String normalized,
            @Param("excludedCompetitorId") long excludedCompetitorId
    );

    @Select("""
            SELECT EXISTS(
                SELECT 1 FROM business.competitor_alias
                 WHERE user_id = #{userId} AND space_id = #{spaceId}
                   AND alias_normalized = #{normalized} AND competitor_id <> #{excludedCompetitorId}
            )
            """)
    boolean aliasExists(
            @Param("userId") long userId,
            @Param("spaceId") long spaceId,
            @Param("normalized") String normalized,
            @Param("excludedCompetitorId") long excludedCompetitorId
    );

    @Insert("""
            INSERT INTO business.competitor(
                id, user_id, space_id, name, name_normalized, competitor_type,
                description, status, version, created_at, updated_at
            ) VALUES (
                #{competitor.id}, #{competitor.userId}, #{competitor.spaceId},
                #{competitor.name}, #{competitor.nameNormalized}, #{competitor.type},
                #{competitor.description}, #{competitor.status}, #{competitor.version},
                #{competitor.createdAt}, #{competitor.updatedAt}
            )
            """)
    int insert(@Param("competitor") CompetitorRecord competitor);

    @Update("""
            UPDATE business.competitor
               SET name = #{competitor.name}, name_normalized = #{competitor.nameNormalized},
                   competitor_type = #{competitor.type}, description = #{competitor.description},
                   status = #{competitor.status}, version = version + 1,
                   updated_at = #{competitor.updatedAt}
             WHERE id = #{competitor.id} AND user_id = #{competitor.userId}
               AND space_id = #{competitor.spaceId} AND version = #{expectedVersion}
            """)
    int update(
            @Param("competitor") CompetitorRecord competitor,
            @Param("expectedVersion") int expectedVersion
    );

    @Update("""
            UPDATE business.competitor SET version = version + 1, updated_at = #{updatedAt}
             WHERE id = #{competitorId} AND user_id = #{userId}
               AND space_id = #{spaceId} AND version = #{expectedVersion}
            """)
    int incrementVersion(
            @Param("competitorId") long competitorId,
            @Param("userId") long userId,
            @Param("spaceId") long spaceId,
            @Param("expectedVersion") int expectedVersion,
            @Param("updatedAt") OffsetDateTime updatedAt
    );

    @Delete("DELETE FROM business.competitor WHERE id = #{competitorId} AND user_id = #{userId}")
    int delete(@Param("competitorId") long competitorId, @Param("userId") long userId);

    @Select("""
            SELECT alias AS value, alias_normalized AS normalized
              FROM business.competitor_alias
             WHERE user_id = #{userId} AND space_id = #{spaceId}
               AND competitor_id = #{competitorId}
             ORDER BY alias_normalized ASC
            """)
    List<AliasValue> findAliases(
            @Param("competitorId") long competitorId,
            @Param("userId") long userId,
            @Param("spaceId") long spaceId
    );

    @Delete("DELETE FROM business.competitor_alias"
            + " WHERE user_id = #{userId} AND space_id = #{spaceId} AND competitor_id = #{competitorId}")
    int deleteAliases(
            @Param("competitorId") long competitorId,
            @Param("userId") long userId,
            @Param("spaceId") long spaceId
    );

    @Insert("""
            INSERT INTO business.competitor_alias(
                id, user_id, space_id, competitor_id, alias, alias_normalized, created_at
            ) VALUES (
                #{id}, #{userId}, #{spaceId}, #{competitorId},
                #{alias.value}, #{alias.normalized}, #{createdAt}
            )
            """)
    int insertAlias(
            @Param("id") long id,
            @Param("userId") long userId,
            @Param("spaceId") long spaceId,
            @Param("competitorId") long competitorId,
            @Param("alias") AliasValue alias,
            @Param("createdAt") OffsetDateTime createdAt
    );
}
