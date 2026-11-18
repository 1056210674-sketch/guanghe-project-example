package com.guanghe.backend.geo;

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
public interface GeoMapper {
    @Select("SELECT " + TOPIC_COLUMNS + " FROM business.geo_topic t WHERE t.id=#{topicId} AND t.user_id=#{userId} AND t.topic_kind='MONITORING'"
            + " AND EXISTS (SELECT 1 FROM business.business_space s WHERE s.id=t.space_id"
            + " AND s.user_id=t.user_id AND s.storage_kind='PROJECT') FOR SHARE OF t")
    Optional<GeoRecords.Topic> shareTopic(@Param("topicId") long topicId,@Param("userId") long userId);
    String TOPIC_COLUMNS = """
            t.id, t.user_id AS userId, t.space_id AS spaceId, t.product_id AS productId,
            t.name, t.name_normalized AS nameNormalized, t.region, t.version,
            t.created_at AS createdAt, t.updated_at AS updatedAt
            """;

    @Select("""
            <script>
            SELECT
            """ + TOPIC_COLUMNS + """
              FROM business.geo_topic t
             WHERE t.user_id = #{userId} AND t.topic_kind='MONITORING'
               AND EXISTS (SELECT 1 FROM business.business_space s WHERE s.id=t.space_id
                           AND s.user_id=t.user_id AND s.storage_kind='PROJECT')
            <if test="spaceId != null"> AND t.space_id = #{spaceId}</if>
            <if test="productId != null"> AND t.product_id = #{productId}</if>
            <if test="keyword != null">
               AND (t.name_normalized LIKE CONCAT('%', #{keyword}, '%')
                    OR EXISTS (SELECT 1 FROM business.geo_keyword k
                                WHERE k.topic_id = t.id
                                  AND k.text_normalized LIKE CONCAT('%', #{keyword}, '%')))
            </if>
             ORDER BY t.created_at DESC, t.id DESC
             LIMIT #{size} OFFSET #{offset}
            </script>
            """)
    List<GeoRecords.Topic> findPage(
            @Param("userId") long userId,
            @Param("spaceId") Long spaceId,
            @Param("productId") Long productId,
            @Param("keyword") String keyword,
            @Param("offset") long offset,
            @Param("size") int size
    );

    @Select("""
            <script>
            SELECT COUNT(*) FROM business.geo_topic t
             WHERE t.user_id = #{userId} AND t.topic_kind='MONITORING'
               AND EXISTS (SELECT 1 FROM business.business_space s WHERE s.id=t.space_id
                           AND s.user_id=t.user_id AND s.storage_kind='PROJECT')
            <if test="spaceId != null"> AND t.space_id = #{spaceId}</if>
            <if test="productId != null"> AND t.product_id = #{productId}</if>
            <if test="keyword != null">
               AND (t.name_normalized LIKE CONCAT('%', #{keyword}, '%')
                    OR EXISTS (SELECT 1 FROM business.geo_keyword k
                                WHERE k.topic_id = t.id
                                  AND k.text_normalized LIKE CONCAT('%', #{keyword}, '%')))
            </if>
            </script>
            """)
    long count(
            @Param("userId") long userId,
            @Param("spaceId") Long spaceId,
            @Param("productId") Long productId,
            @Param("keyword") String keyword
    );

    @Select("SELECT " + TOPIC_COLUMNS + " FROM business.geo_topic t"
            + " WHERE t.id = #{topicId} AND t.user_id = #{userId} AND t.topic_kind='MONITORING'"
            + " AND EXISTS (SELECT 1 FROM business.business_space s WHERE s.id=t.space_id"
            + " AND s.user_id=t.user_id AND s.storage_kind='PROJECT')")
    Optional<GeoRecords.Topic> findTopic(
            @Param("topicId") long topicId,
            @Param("userId") long userId
    );

    @Select("""
            SELECT EXISTS(
                SELECT 1 FROM business.geo_topic
                 WHERE user_id = #{userId} AND space_id = #{spaceId}
                   AND name_normalized = #{normalized} AND id <> #{excludedTopicId}
            )
            """)
    boolean topicNameExists(
            @Param("userId") long userId,
            @Param("spaceId") long spaceId,
            @Param("normalized") String normalized,
            @Param("excludedTopicId") long excludedTopicId
    );

    @Insert("""
            INSERT INTO business.geo_topic(
                id, user_id, space_id, product_id, name, name_normalized,
                region, version, created_at, updated_at
            ) VALUES (
                #{topic.id}, #{topic.userId}, #{topic.spaceId}, #{topic.productId},
                #{topic.name}, #{topic.nameNormalized}, #{topic.region}, #{topic.version},
                #{topic.createdAt}, #{topic.updatedAt}
            )
            """)
    int insertTopic(@Param("topic") GeoRecords.Topic topic);

    @Update("""
            UPDATE business.geo_topic
               SET product_id = #{topic.productId}, name = #{topic.name},
                   name_normalized = #{topic.nameNormalized}, region = #{topic.region},
                   version = version + 1, updated_at = #{topic.updatedAt}
             WHERE id = #{topic.id} AND user_id = #{topic.userId}
               AND space_id = #{topic.spaceId} AND version = #{expectedVersion}
            """)
    int updateTopic(
            @Param("topic") GeoRecords.Topic topic,
            @Param("expectedVersion") int expectedVersion
    );

    @Update("""
            UPDATE business.geo_topic
               SET version = version + 1, updated_at = #{updatedAt}
             WHERE id = #{topicId} AND user_id = #{userId}
               AND space_id = #{spaceId} AND version = #{expectedVersion}
            """)
    int incrementTopicVersion(
            @Param("topicId") long topicId,
            @Param("userId") long userId,
            @Param("spaceId") long spaceId,
            @Param("expectedVersion") int expectedVersion,
            @Param("updatedAt") OffsetDateTime updatedAt
    );

    @Delete("DELETE FROM business.geo_topic WHERE id = #{topicId} AND user_id = #{userId}")
    int deleteTopic(@Param("topicId") long topicId, @Param("userId") long userId);

    @Select("""
            SELECT id, user_id AS userId, space_id AS spaceId, topic_id AS topicId,
                   sequence_no AS sequence, keyword_type AS type,
                   keyword_text AS text, text_normalized AS textNormalized,
                   created_at AS createdAt, updated_at AS updatedAt
              FROM business.geo_keyword
             WHERE topic_id = #{topicId} AND user_id = #{userId}
             ORDER BY sequence_no ASC
            """)
    List<GeoRecords.Keyword> findKeywords(
            @Param("topicId") long topicId,
            @Param("userId") long userId
    );

    @Delete("DELETE FROM business.geo_keyword WHERE topic_id = #{topicId} AND user_id = #{userId}")
    int deleteKeywords(@Param("topicId") long topicId, @Param("userId") long userId);

    @Insert("""
            INSERT INTO business.geo_keyword(
                id, user_id, space_id, topic_id, sequence_no, keyword_type,
                keyword_text, text_normalized, created_at, updated_at
            ) VALUES (
                #{keyword.id}, #{keyword.userId}, #{keyword.spaceId}, #{keyword.topicId},
                #{keyword.sequence}, #{keyword.type}, #{keyword.text},
                #{keyword.textNormalized}, #{keyword.createdAt}, #{keyword.updatedAt}
            )
            """)
    int insertKeyword(@Param("keyword") GeoRecords.Keyword keyword);

    @Select("""
            SELECT id, user_id AS userId, space_id AS spaceId, topic_id AS topicId,
                   sequence_no AS sequence, category, question_text AS text,
                   text_normalized AS textNormalized, source, enabled,
                   created_at AS createdAt, updated_at AS updatedAt
              FROM business.geo_question
             WHERE topic_id = #{topicId} AND user_id = #{userId}
             ORDER BY sequence_no ASC
            """)
    List<GeoRecords.Question> findQuestions(
            @Param("topicId") long topicId,
            @Param("userId") long userId
    );

    @Delete("DELETE FROM business.geo_question WHERE topic_id = #{topicId} AND user_id = #{userId}")
    int deleteQuestions(@Param("topicId") long topicId, @Param("userId") long userId);

    @Insert("""
            INSERT INTO business.geo_question(
                id, user_id, space_id, topic_id, sequence_no, category,
                question_text, text_normalized, source, enabled, created_at, updated_at
            ) VALUES (
                #{question.id}, #{question.userId}, #{question.spaceId}, #{question.topicId},
                #{question.sequence}, #{question.category}, #{question.text},
                #{question.textNormalized}, #{question.source}, #{question.enabled},
                #{question.createdAt}, #{question.updatedAt}
            )
            """)
    int insertQuestion(@Param("question") GeoRecords.Question question);

    @Select("""
            SELECT id, user_id AS userId, space_id AS spaceId, topic_id AS topicId,
                   version_no AS version, created_at AS createdAt
              FROM business.geo_competitor_set
             WHERE topic_id = #{topicId} AND user_id = #{userId}
             ORDER BY version_no DESC LIMIT 1
            """)
    Optional<GeoRecords.CompetitorSet> findLatestCompetitorSet(
            @Param("topicId") long topicId,
            @Param("userId") long userId
    );

    @Select("SELECT COALESCE(MAX(version_no), 0) + 1 FROM business.geo_competitor_set"
            + " WHERE topic_id = #{topicId}")
    int nextCompetitorSetVersion(@Param("topicId") long topicId);

    @Insert("""
            INSERT INTO business.geo_competitor_set(
                id, user_id, space_id, topic_id, version_no, created_at
            ) VALUES (
                #{set.id}, #{set.userId}, #{set.spaceId}, #{set.topicId},
                #{set.version}, #{set.createdAt}
            )
            """)
    int insertCompetitorSet(@Param("set") GeoRecords.CompetitorSet set);

    @Select("""
            SELECT id, user_id AS userId, space_id AS spaceId, topic_id AS topicId,
                   competitor_set_id AS competitorSetId, competitor_id AS competitorId,
                   sequence_no AS sequence, competitor_name_snapshot AS competitorNameSnapshot,
                   competitor_type_snapshot AS competitorTypeSnapshot, created_at AS createdAt
              FROM business.geo_competitor_set_member
             WHERE competitor_set_id = #{competitorSetId}
             ORDER BY sequence_no ASC
            """)
    List<GeoRecords.CompetitorSetMember> findCompetitorSetMembers(
            @Param("competitorSetId") long competitorSetId
    );

    @Insert("""
            INSERT INTO business.geo_competitor_set_member(
                id, user_id, space_id, topic_id, competitor_set_id, competitor_id,
                sequence_no, competitor_name_snapshot, competitor_type_snapshot, created_at
            ) VALUES (
                #{member.id}, #{member.userId}, #{member.spaceId}, #{member.topicId},
                #{member.competitorSetId}, #{member.competitorId}, #{member.sequence},
                #{member.competitorNameSnapshot}, #{member.competitorTypeSnapshot},
                #{member.createdAt}
            )
            """)
    int insertCompetitorSetMember(@Param("member") GeoRecords.CompetitorSetMember member);
}
