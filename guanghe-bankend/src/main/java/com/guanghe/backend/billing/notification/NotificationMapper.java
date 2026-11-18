package com.guanghe.backend.billing.notification;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** Data access for the in-app notification inbox. */
@Mapper
public interface NotificationMapper {

    String COLUMNS = """
            id, user_id AS userId, category, title, body, target_type AS targetType,
            target_id AS targetId, dedup_key AS dedupKey, read_at AS readAt,
            created_at AS createdAt
            """;

    /**
     * Creates a notification unless the same fact was already announced.
     *
     * <p>The dedup key is what makes creation idempotent rather than the caller's discipline: a
     * retried settlement can call this twice and still produce one row.
     */
    @Insert("""
            INSERT INTO business.user_notification(id,user_id,category,title,body,target_type,
              target_id,dedup_key,read_at,created_at)
            VALUES(#{n.id},#{n.userId},#{n.category},#{n.title},#{n.body},#{n.targetType},
              #{n.targetId},#{n.dedupKey},#{n.readAt},#{n.createdAt})
            ON CONFLICT (user_id,dedup_key) DO NOTHING
            """)
    int insert(@Param("n") NotificationRecords.Notification notification);

    @Select("SELECT " + COLUMNS + " FROM business.user_notification"
            + " WHERE user_id=#{userId} AND dedup_key=#{dedupKey}")
    Optional<NotificationRecords.Notification> findByDedupKey(@Param("userId") long userId,
                                                             @Param("dedupKey") String dedupKey);

    @Select("SELECT " + COLUMNS + " FROM business.user_notification WHERE id=#{id}")
    Optional<NotificationRecords.Notification> findById(@Param("id") long id);

    @Select("SELECT " + COLUMNS + " FROM business.user_notification"
            + " WHERE id=#{id} AND user_id=#{userId}")
    Optional<NotificationRecords.Notification> findByIdForUser(@Param("id") long id,
                                                              @Param("userId") long userId);

    String INBOX_FILTER = """
             WHERE user_id=#{userId}
             <if test="category != null"> AND category=#{category}</if>
             <if test="unreadOnly"> AND read_at IS NULL</if>
            """;

    @Select("<script>SELECT " + COLUMNS + " FROM business.user_notification" + INBOX_FILTER
            + " ORDER BY created_at DESC, id DESC LIMIT #{size} OFFSET #{offset}</script>")
    List<NotificationRecords.Notification> findByUser(@Param("userId") long userId,
                                                      @Param("category") String category,
                                                      @Param("unreadOnly") boolean unreadOnly,
                                                      @Param("offset") long offset,
                                                      @Param("size") int size);

    @Select("<script>SELECT count(*) FROM business.user_notification" + INBOX_FILTER + "</script>")
    long countByUser(@Param("userId") long userId, @Param("category") String category,
                     @Param("unreadOnly") boolean unreadOnly);

    @Select("SELECT count(*) FROM business.user_notification"
            + " WHERE user_id=#{userId} AND read_at IS NULL")
    long countUnread(@Param("userId") long userId);

    /**
     * Marks one notification read. The {@code read_at IS NULL} predicate makes this a
     * compare-and-set: a second attempt updates zero rows, so the original reading time survives
     * and the operation is idempotent rather than merely repeatable.
     */
    @Update("UPDATE business.user_notification SET read_at=#{now}"
            + " WHERE id=#{id} AND user_id=#{userId} AND read_at IS NULL")
    int markRead(@Param("id") long id, @Param("userId") long userId,
                 @Param("now") OffsetDateTime now);

    @Update("UPDATE business.user_notification SET read_at=#{now}"
            + " WHERE user_id=#{userId} AND read_at IS NULL")
    int markAllRead(@Param("userId") long userId, @Param("now") OffsetDateTime now);
}
