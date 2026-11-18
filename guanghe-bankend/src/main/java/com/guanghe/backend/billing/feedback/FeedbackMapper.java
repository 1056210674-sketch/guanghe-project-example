package com.guanghe.backend.billing.feedback;

import com.guanghe.backend.billing.bookkeeping.StatusCount;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** Data access for user feedback. */
@Mapper
public interface FeedbackMapper {

    String COLUMNS = """
            id, user_id AS userId, category, content, contact, status, reply,
            handled_by AS handledBy, handled_at AS handledAt, idempotency_key AS idempotencyKey,
            request_hash AS requestHash, version, created_at AS createdAt, updated_at AS updatedAt
            """;

    @Insert("""
            INSERT INTO business.user_feedback(id,user_id,category,content,contact,status,reply,
              handled_by,handled_at,idempotency_key,request_hash,version,created_at,updated_at)
            VALUES(#{f.id},#{f.userId},#{f.category},#{f.content},#{f.contact},#{f.status},
              #{f.reply},#{f.handledBy},#{f.handledAt},#{f.idempotencyKey},#{f.requestHash},
              #{f.version},#{f.createdAt},#{f.updatedAt})
            """)
    int insert(@Param("f") FeedbackRecords.UserFeedback feedback);

    @Select("SELECT " + COLUMNS + " FROM business.user_feedback WHERE id=#{id}")
    Optional<FeedbackRecords.UserFeedback> findById(@Param("id") long id);

    @Select("SELECT " + COLUMNS + " FROM business.user_feedback WHERE idempotency_key=#{key}")
    Optional<FeedbackRecords.UserFeedback> findByIdempotencyKey(@Param("key") String key);

    @Select("SELECT " + COLUMNS + " FROM business.user_feedback WHERE user_id=#{userId}"
            + " ORDER BY created_at DESC, id DESC LIMIT #{size} OFFSET #{offset}")
    List<FeedbackRecords.UserFeedback> findByUser(@Param("userId") long userId,
                                                  @Param("offset") long offset,
                                                  @Param("size") int size);

    @Select("SELECT count(*) FROM business.user_feedback WHERE user_id=#{userId}")
    long countByUser(@Param("userId") long userId);

    /** Counting real rows, so a rolled-back submission does not consume the caller's budget. */
    @Select("SELECT count(*) FROM business.user_feedback"
            + " WHERE user_id=#{userId} AND created_at >= #{since}")
    long countSubmittedSince(@Param("userId") long userId, @Param("since") OffsetDateTime since);

    @Select("<script>SELECT " + COLUMNS + " FROM business.user_feedback"
            + " <where><if test='status != null'>status=#{status}</if>"
            + " <if test='userId != null'>AND user_id=#{userId}</if></where>"
            + " ORDER BY created_at ASC, id ASC LIMIT #{size} OFFSET #{offset}</script>")
    List<FeedbackRecords.UserFeedback> findForAdmin(@Param("userId") Long userId,
                                                    @Param("status") String status,
                                                    @Param("offset") long offset,
                                                    @Param("size") int size);

    @Select("""
            <script>
            SELECT count(*) FROM business.user_feedback
            <where>
              <if test="status != null">status=#{status}</if>
              <if test="userId != null">AND user_id=#{userId}</if>
            </where>
            </script>
            """)
    long countForAdmin(@Param("userId") Long userId, @Param("status") String status);

    /**
     * Records an operator response. The {@code status=#{expectedFrom}} predicate makes a stale
     * screen fail rather than silently overwrite a colleague's answer.
     */
    @Update("""
            UPDATE business.user_feedback SET status=#{to}, reply=#{reply}, handled_by=#{handledBy},
              handled_at=#{now}, version=version+1, updated_at=#{now}
            WHERE id=#{id} AND status=#{expectedFrom}
            """)
    int applyHandling(@Param("id") long id, @Param("expectedFrom") String expectedFrom,
                      @Param("to") String to, @Param("reply") String reply,
                      @Param("handledBy") long handledBy, @Param("now") OffsetDateTime now);

    /** Per-status totals for the administrator summary. Aggregated in the database. */
    @Select("SELECT status, count(*) AS total FROM business.user_feedback GROUP BY status")
    List<StatusCount> countByStatus();
}
