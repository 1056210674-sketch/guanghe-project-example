package com.guanghe.backend.conversation.context;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Select;
import com.guanghe.backend.conversation.ConversationMapper;
import com.guanghe.backend.conversation.ConversationMessageRecord;
import java.time.OffsetDateTime;
import java.util.List;

@Mapper
public interface ConversationCompactionMapper {
    @Select("<script>SELECT " + ConversationMapper.MESSAGE_COLUMNS
            + " FROM business.conversation_message m WHERE m.user_id=#{userId} AND m.conversation_id=#{conversationId}"
            + " AND m.sequence_no &gt; #{afterSequence} AND m.sequence_no &lt; #{beforeSequence}"
            + " AND m.status IN ('COMPLETED','FAILED','STOPPED')"
            + " <if test='cutoff != null'>AND m.created_at &gt;= #{cutoff}</if>"
            + " ORDER BY m.sequence_no ASC LIMIT 256</script>")
    List<ConversationMessageRecord> sourceMessages(@Param("userId") long userId,
            @Param("conversationId") long conversationId, @Param("afterSequence") long afterSequence,
            @Param("beforeSequence") long beforeSequence, @Param("cutoff") OffsetDateTime cutoff);

    @Update("""
            UPDATE business.conversation
               SET summary=CAST(#{summary} AS jsonb), summary_version=summary_version+1
             WHERE id=#{conversationId} AND user_id=#{userId} AND summary_version=#{expectedVersion}
            """)
    int checkpoint(@Param("conversationId") long conversationId, @Param("userId") long userId,
                   @Param("expectedVersion") int expectedVersion, @Param("summary") String summary);
}
