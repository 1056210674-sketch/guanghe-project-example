package com.guanghe.backend.conversation.resource;

import com.guanghe.backend.conversation.ConversationEventRecord;
import com.guanghe.backend.conversation.ConversationMapper;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface RunResourceMapper {
    record MatchedResource(long resourceId, String name, long spaceId, String spaceName,
            Long productId, boolean exact, boolean hasReport, long totalMatches) { }

    // Literal substring matching: '%' and '_' in a user name are data, never LIKE operators.
    @Select("""
            <script>
            SELECT matched.resourceId,matched.name,matched.spaceId,matched.spaceName,
                   matched.productId,matched.exact,matched.hasReport,count(*) OVER() AS totalMatches FROM (
            <choose>
              <when test="kind == 'SPACE'">
                SELECT s.id AS resourceId,s.name,s.id AS spaceId,s.name AS spaceName,
                       NULL::bigint AS productId,
                       (s.name_normalized=#{keyword}
                        OR lower(normalize(btrim(s.brand_name),NFKC))=#{keyword}
                        OR EXISTS(SELECT 1 FROM business.space_brand_alias a
                           WHERE a.user_id=s.user_id AND a.space_id=s.id
                             AND a.alias_normalized=#{keyword})) AS exact,
                       EXISTS(SELECT 1 FROM business.geo_report r JOIN business.geo_run g ON g.id=r.run_id
                           WHERE r.user_id=s.user_id AND g.space_id=s.id
                             AND r.archived_at IS NULL AND r.status IN ('SUCCEEDED','PARTIAL')) AS hasReport,
                       s.updated_at AS updatedAt
                  FROM business.business_space s WHERE s.user_id=#{userId} AND s.storage_kind='PROJECT'
                  <if test="spaceId != null"> AND s.id=#{spaceId}</if>
                   AND (strpos(s.name_normalized,#{keyword})>0
                     OR strpos(lower(normalize(btrim(s.brand_name),NFKC)),#{keyword})>0
                     OR EXISTS(SELECT 1 FROM business.space_brand_alias a
                       WHERE a.user_id=s.user_id AND a.space_id=s.id
                         AND strpos(a.alias_normalized,#{keyword})>0))
              </when>
              <when test="kind == 'PRODUCT'">
                SELECT p.id AS resourceId,p.name,p.space_id AS spaceId,s.name AS spaceName,
                       p.id AS productId,
                       (p.name_normalized=#{keyword} OR EXISTS(SELECT 1 FROM business.product_alias a
                         WHERE a.user_id=p.user_id AND a.product_id=p.id
                           AND a.alias_normalized=#{keyword})) AS exact,
                       EXISTS(SELECT 1 FROM business.geo_report r JOIN business.geo_run g ON g.id=r.run_id
                         WHERE r.user_id=p.user_id AND g.product_id=p.id AND r.archived_at IS NULL
                           AND r.status IN ('SUCCEEDED','PARTIAL')) AS hasReport,p.updated_at AS updatedAt
                  FROM business.product p JOIN business.business_space s
                    ON s.id=p.space_id AND s.user_id=p.user_id AND s.storage_kind='PROJECT'
                 WHERE p.user_id=#{userId}
                  <if test="spaceId != null"> AND p.space_id=#{spaceId}</if>
                   AND (strpos(p.name_normalized,#{keyword})>0
                     OR EXISTS(SELECT 1 FROM business.product_alias a
                       WHERE a.user_id=p.user_id AND a.product_id=p.id
                         AND strpos(a.alias_normalized,#{keyword})>0))
              </when>
              <otherwise>
                SELECT t.id AS resourceId,t.name,t.space_id AS spaceId,s.name AS spaceName,
                       t.product_id AS productId,
                       (t.name_normalized=#{keyword} OR EXISTS(SELECT 1 FROM business.geo_keyword k
                         WHERE k.user_id=t.user_id AND k.topic_id=t.id
                           AND k.text_normalized=#{keyword})) AS exact,
                       EXISTS(SELECT 1 FROM business.geo_report r WHERE r.user_id=t.user_id
                         AND r.topic_id=t.id AND r.archived_at IS NULL
                         AND r.status IN ('SUCCEEDED','PARTIAL')) AS hasReport,t.updated_at AS updatedAt
                  FROM business.geo_topic t JOIN business.business_space s
                    ON s.id=t.space_id AND s.user_id=t.user_id AND s.storage_kind='PROJECT'
                  LEFT JOIN business.product p ON p.id=t.product_id AND p.user_id=t.user_id AND p.space_id=t.space_id
                  WHERE t.user_id=#{userId}
                  <if test="spaceId != null"> AND t.space_id=#{spaceId}</if>
                  <if test="productId != null"> AND t.product_id=#{productId}</if>
                   AND (strpos(t.name_normalized,#{keyword})>0
                     OR EXISTS(SELECT 1 FROM business.geo_keyword k
                       WHERE k.user_id=t.user_id AND k.topic_id=t.id
                         AND strpos(k.text_normalized,#{keyword})>0)
                     OR strpos(p.name_normalized,#{keyword})>0
                     OR EXISTS(SELECT 1 FROM business.product_alias a
                       WHERE a.user_id=t.user_id AND a.space_id=t.space_id AND a.product_id=t.product_id
                         AND strpos(a.alias_normalized,#{keyword})>0)
                     OR strpos(lower(normalize(btrim(s.brand_name),NFKC)),#{keyword})>0
                     OR EXISTS(SELECT 1 FROM business.space_brand_alias a
                       WHERE a.user_id=t.user_id AND a.space_id=t.space_id
                         AND strpos(a.alias_normalized,#{keyword})>0))
              </otherwise>
            </choose>
            ) matched ORDER BY matched.exact DESC,matched.updatedAt DESC,matched.resourceId DESC LIMIT 6
            </script>
            """)
    List<MatchedResource> search(@Param("userId") long userId, @Param("kind") String kind,
            @Param("keyword") String keyword, @Param("spaceId") Long spaceId,
            @Param("productId") Long productId);

    @Select("SELECT " + ConversationMapper.EVENT_COLUMNS + " FROM business.conversation_event e"
            + " WHERE e.agent_run_id=#{runId} AND e.source_event_id=#{sourceId}")
    Optional<ConversationEventRecord> findCall(@Param("runId") UUID runId,
            @Param("sourceId") UUID sourceId);

    @Select("SELECT " + ConversationMapper.EVENT_COLUMNS + " FROM business.conversation_event e"
            + " JOIN business.agent_run r ON r.id=e.agent_run_id AND r.user_id=e.user_id"
            + " JOIN business.conversation_message request ON request.id=r.request_message_id"
            + " AND request.user_id=r.user_id AND request.conversation_id=r.conversation_id"
            + " WHERE e.id=#{eventId} AND e.user_id=#{userId} AND e.conversation_id=#{conversationId}"
            + " AND e.event_type='context.resource' AND request.sequence_no >= #{resetSequence}")
    Optional<ConversationEventRecord> findReference(@Param("userId") long userId,
            @Param("conversationId") long conversationId, @Param("eventId") long eventId,
            @Param("resetSequence") long resetSequence);

    @Select("SELECT COALESCE(max(sequence_no),0) FROM business.conversation_message"
            + " WHERE user_id=#{userId} AND conversation_id=#{conversationId} AND role='USER'"
            + " AND content->>'resourceContextReset'='true'")
    long resetSequence(@Param("userId") long userId, @Param("conversationId") long conversationId);

    @Select("SELECT " + ConversationMapper.EVENT_COLUMNS + " FROM business.conversation_event e"
            + " JOIN business.agent_run r ON r.id=e.agent_run_id AND r.user_id=e.user_id"
            + " JOIN business.conversation_message request ON request.id=r.request_message_id"
            + " AND request.user_id=r.user_id AND request.conversation_id=r.conversation_id"
            + " WHERE e.user_id=#{userId} AND e.conversation_id=#{conversationId}"
            + " AND e.event_type='context.resource' AND e.payload->>'kind'='SEARCH'"
            + " AND e.payload->>'success'='true' AND request.sequence_no >= #{resetSequence}"
            + " ORDER BY e.sequence_no DESC LIMIT 1")
    Optional<ConversationEventRecord> latestSearch(@Param("userId") long userId,
            @Param("conversationId") long conversationId, @Param("resetSequence") long resetSequence);

    @Select("<script>SELECT " + ConversationMapper.EVENT_COLUMNS + " FROM business.conversation_event e"
            + " JOIN business.agent_run r ON r.id=e.agent_run_id AND r.user_id=e.user_id"
            + " JOIN business.conversation_message request ON request.id=r.request_message_id"
            + " AND request.user_id=r.user_id AND request.conversation_id=r.conversation_id"
            + " WHERE e.user_id=#{userId} AND e.conversation_id=#{conversationId}"
            + " AND e.event_type='context.resource' AND e.payload->>'kind'='READ'"
            + " AND e.payload->>'success'='true' AND request.sequence_no >= #{resetSequence}"
            + "<if test='reportOnly'> AND e.payload#>>'{input,purpose}'"
            + " IN ('LATEST_GEO_REPORT','GEO_ANSWER_EVIDENCE')</if>"
            + " ORDER BY e.sequence_no DESC LIMIT 1</script>")
    Optional<ConversationEventRecord> latestRead(@Param("userId") long userId,
            @Param("conversationId") long conversationId, @Param("resetSequence") long resetSequence,
            @Param("reportOnly") boolean reportOnly);

    @Select("SELECT " + ConversationMapper.EVENT_COLUMNS + " FROM business.conversation_event e"
            + " WHERE e.user_id=#{userId} AND e.conversation_id=#{conversationId} AND e.agent_run_id=#{runId}"
            + " AND e.event_type='context.resource' AND e.payload->>'kind'='READ'"
            + " AND e.payload->>'success'='true'"
            + " AND e.payload#>>'{input,purpose}' IN ('LATEST_GEO_REPORT','GEO_ANSWER_EVIDENCE')"
            + " ORDER BY e.sequence_no DESC LIMIT 1")
    Optional<ConversationEventRecord> latestReportReadForRun(@Param("userId") long userId,
            @Param("conversationId") long conversationId, @Param("runId") UUID runId);

    @Select("<script>SELECT EXISTS(SELECT 1 FROM business.conversation_event e"
            + " WHERE e.user_id=#{userId} AND e.conversation_id=#{conversationId}"
            + " AND e.event_type='context.resource' AND e.payload->>'kind'='READ'"
            + " AND e.payload->>'success'='true' AND e.payload#>>'{result,observationId}' IS NOT NULL"
            + " AND e.payload#>>'{input,candidateSetId}'=#{candidateSetId}"
            + "<if test='choiceId != null'> AND e.payload#>>'{input,choiceId}'=#{choiceId}</if>"
            + "<if test='runId != null'> AND e.agent_run_id=#{runId}</if>)</script>")
    boolean consumed(@Param("userId") long userId, @Param("conversationId") long conversationId,
            @Param("runId") UUID runId, @Param("candidateSetId") String candidateSetId,
            @Param("choiceId") String choiceId);

    @Select("SELECT count(*) FROM business.conversation_event"
            + " WHERE agent_run_id=#{runId} AND event_type='context.resource'")
    long callCount(@Param("runId") UUID runId);

    record GeoAttempt(long runId, String status, java.time.OffsetDateTime createdAt, Long reportId) { }

    @Select("SELECT g.id AS runId,g.status,g.created_at AS createdAt,r.id AS reportId"
            + " FROM business.geo_run g LEFT JOIN business.geo_report r"
            + " ON r.run_id=g.id AND r.user_id=g.user_id AND r.archived_at IS NULL"
            + " WHERE g.user_id=#{userId} AND g.topic_id=#{topicId}"
            + " ORDER BY g.created_at DESC,g.id DESC LIMIT 1")
    Optional<GeoAttempt> latestAttempt(@Param("userId") long userId, @Param("topicId") long topicId);

    @Select("SELECT g.id AS runId,g.status,g.created_at AS createdAt,r.id AS reportId"
            + " FROM business.geo_run g JOIN business.geo_report r ON r.run_id=g.id AND r.user_id=g.user_id"
            + " WHERE g.user_id=#{userId} AND g.topic_id=#{topicId} AND r.archived_at IS NULL"
            + " AND g.status IN ('SUCCEEDED','PARTIAL') AND r.status IN ('SUCCEEDED','PARTIAL')"
            + " ORDER BY g.created_at DESC,g.id DESC LIMIT 1")
    Optional<GeoAttempt> latestUsable(@Param("userId") long userId, @Param("topicId") long topicId);
}
