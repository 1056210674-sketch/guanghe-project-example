package com.guanghe.backend.knowledge;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface KnowledgeMapper {
    String MEMORY = "id,user_id AS userId,project_id AS projectId,scope,kind,memory_key AS key,label,value,version,"
            + "source_type AS sourceType,source_message_id AS sourceMessageId,source_conversation_id AS sourceConversationId,"
            + "created_at AS createdAt,updated_at AS updatedAt,deleted_at AS deletedAt,history_cutoff_at AS historyCutoffAt";
    @Select("SELECT " + MEMORY + " FROM business.agent_memory_entry WHERE user_id=#{userId} AND project_id IS NOT DISTINCT FROM #{projectId} ORDER BY memory_key LIMIT 101")
    List<MemoryDtos.Stored> memories(@Param("userId") long userId,@Param("projectId") Long projectId);
    @Select("SELECT " + MEMORY + " FROM business.agent_memory_entry WHERE user_id=#{userId} AND id=#{id} FOR UPDATE")
    Optional<MemoryDtos.Stored> lockMemory(@Param("userId") long userId,@Param("id") long id);
    @Select("SELECT " + MEMORY + " FROM business.agent_memory_entry WHERE user_id=#{userId} AND project_id IS NOT DISTINCT FROM #{projectId} AND memory_key=#{key} FOR UPDATE")
    Optional<MemoryDtos.Stored> memoryByKey(@Param("userId") long userId,@Param("projectId") Long projectId,@Param("key") String key);
    @Insert("""
        INSERT INTO business.agent_memory_entry(id,user_id,project_id,scope,kind,memory_key,label,value,version,
            source_type,source_message_id,source_conversation_id,created_at,updated_at,deleted_at,history_cutoff_at)
        VALUES(#{r.id},#{r.userId},#{r.projectId},#{r.scope},#{r.kind},#{r.key},#{r.label},#{r.value},#{r.version},
            #{r.sourceType},#{r.sourceMessageId},#{r.sourceConversationId},#{r.createdAt},#{r.updatedAt},#{r.deletedAt},#{r.historyCutoffAt})
        """)
    int insertMemory(@Param("r") MemoryDtos.Stored value);
    @Update("""
        UPDATE business.agent_memory_entry SET label=#{r.label},value=#{r.value},version=#{r.version},source_type=#{r.sourceType},
            source_message_id=#{r.sourceMessageId},source_conversation_id=#{r.sourceConversationId},updated_at=#{r.updatedAt},
            deleted_at=#{r.deletedAt},history_cutoff_at=#{r.historyCutoffAt}
        WHERE id=#{r.id} AND user_id=#{r.userId} AND version=#{expectedVersion}
        """)
    int updateMemory(@Param("r") MemoryDtos.Stored value,@Param("expectedVersion") int expectedVersion);
    @Select("SELECT EXISTS(SELECT 1 FROM business.knowledge_document WHERE user_id=#{userId} AND id::text=#{documentId} AND conversation_memory_excluded=true)")
    boolean legacyConversationDocument(@Param("userId") long userId,@Param("documentId") String documentId);
    String DOCUMENT = "id,user_id AS userId,project_id AS projectId,name,source_type AS sourceType,status,"
            + "current_version AS currentVersion,failure_code AS failureCode,created_at AS createdAt,"
            + "updated_at AS updatedAt,deleted_at AS deletedAt";
    String PROJECTION = "d.id,d.user_id AS userId,d.project_id AS projectId,d.name,d.source_type AS sourceType,d.status,"
            + "d.current_version AS currentVersion,d.failure_code AS failureCode,d.created_at AS createdAt,"
            + "d.updated_at AS updatedAt,COALESCE(v.sha256,'') AS sha256,COALESCE(v.content_type,'text/plain') AS contentType,"
            + "COALESCE(v.size_bytes,0) AS sizeBytes,v.source_url AS sourceUrl,COALESCE(v.paragraphs,'[]'::jsonb)::text AS paragraphsJson,"
            + "v.page_count AS pageCount";
    String PREFERENCE = "id,user_id AS userId,project_id AS projectId,content,content_hash AS contentHash,"
            + "source_type AS sourceType,source_message_id AS sourceMessageId,version,created_at AS createdAt,"
            + "updated_at AS updatedAt,deleted_at AS deletedAt";

    @Select("SELECT project_status FROM business.business_space WHERE id=#{projectId} AND user_id=#{userId} AND storage_kind='PROJECT' FOR UPDATE")
    Optional<String> lockProjectForWrite(@Param("userId") long userId, @Param("projectId") long projectId);

    @Select("SELECT id FROM business.account WHERE id=#{userId} AND role='USER' AND status='ACTIVE' FOR UPDATE")
    Optional<Long> lockAccountForPreferenceWrite(@Param("userId") long userId);

    @Select("SELECT count(*) FROM business.knowledge_document WHERE user_id=#{userId} AND project_id=#{projectId} AND deleted_at IS NULL")
    long documentCount(@Param("userId") long userId, @Param("projectId") long projectId);

    @Select("SELECT count(*) FROM business.knowledge_document WHERE user_id=#{userId} AND project_id=#{projectId} AND status='READY' AND deleted_at IS NULL")
    long readyDocumentCount(@Param("userId") long userId, @Param("projectId") long projectId);

    @Select("<script>SELECT " + PROJECTION + " FROM business.knowledge_document d JOIN business.knowledge_document_version v"
            + " ON v.document_id=d.id AND v.version=d.current_version WHERE d.user_id=#{userId} AND d.project_id=#{projectId}"
            + " AND d.status='READY' AND d.deleted_at IS NULL AND NOT d.conversation_memory_excluded ORDER BY "
            + "<if test='terms != null and !terms.isEmpty()'>(0 "
            + "<foreach collection='terms' item='term'>+ CASE WHEN position(#{term} in lower(d.name)) &gt; 0 THEN 3 ELSE 0 END "
            + "+ CASE WHEN position(#{term} in lower(v.paragraphs::text)) &gt; 0 THEN 1 ELSE 0 END </foreach>) DESC,</if>"
            + "d.updated_at DESC,d.id DESC LIMIT #{size}</script>")
    List<KnowledgeRecords.DocumentProjection> contextDocuments(@Param("userId") long userId,
            @Param("projectId") long projectId, @Param("terms") List<String> terms, @Param("size") int size);

    @Select("SELECT " + PROJECTION + " FROM business.knowledge_document d LEFT JOIN business.knowledge_document_version v"
            + " ON v.document_id=d.id AND v.version=d.current_version WHERE d.user_id=#{userId} AND d.project_id=#{projectId}"
            + " AND d.deleted_at IS NULL ORDER BY d.updated_at DESC,d.id DESC LIMIT #{size} OFFSET #{offset}")
    List<KnowledgeRecords.DocumentProjection> documentPage(@Param("userId") long userId,
            @Param("projectId") long projectId, @Param("offset") long offset, @Param("size") int size);

    @Select("SELECT " + PROJECTION + " FROM business.knowledge_document d LEFT JOIN business.knowledge_document_version v"
            + " ON v.document_id=d.id AND v.version=d.current_version WHERE d.user_id=#{userId}"
            + " AND d.project_id=#{projectId} AND d.id=#{id} AND d.deleted_at IS NULL")
    Optional<KnowledgeRecords.DocumentProjection> document(@Param("userId") long userId,
            @Param("projectId") long projectId, @Param("id") long id);

    @Select("SELECT " + DOCUMENT + " FROM business.knowledge_document WHERE id=#{id} AND user_id=#{userId}"
            + " AND project_id=#{projectId} AND deleted_at IS NULL FOR UPDATE")
    Optional<KnowledgeRecords.Document> lockDocument(@Param("userId") long userId,
            @Param("projectId") long projectId, @Param("id") long id);

    @Insert("""
            INSERT INTO business.knowledge_document(id,user_id,project_id,name,source_type,status,current_version,
                failure_code,created_at,updated_at) VALUES(#{r.id},#{r.userId},#{r.projectId},#{r.name},#{r.sourceType},
                #{r.status},#{r.currentVersion},#{r.failureCode},#{r.createdAt},#{r.updatedAt})
            """)
    int insertDocument(@Param("r") KnowledgeRecords.Document record);

    @Insert("""
            INSERT INTO business.knowledge_document_version(document_id,user_id,project_id,version,sha256,content_type,
                size_bytes,source_url,object_key,paragraphs,page_count,created_at)
            VALUES(#{r.documentId},#{r.userId},#{r.projectId},#{r.version},#{r.sha256},#{r.contentType},#{r.sizeBytes},
                #{r.sourceUrl},#{r.objectKey},CAST(#{r.paragraphsJson} AS jsonb),#{r.pageCount},#{r.createdAt})
            """)
    int insertVersion(@Param("r") KnowledgeRecords.DocumentVersion record);

    @Update("""
            UPDATE business.knowledge_document SET name=#{r.name},source_type=#{r.sourceType},status=#{r.status},
                current_version=#{r.currentVersion},failure_code=#{r.failureCode},updated_at=#{r.updatedAt},deleted_at=#{r.deletedAt}
            WHERE id=#{r.id} AND user_id=#{r.userId} AND project_id=#{r.projectId} AND current_version=#{expectedVersion}
                AND deleted_at IS NULL
            """)
    int updateDocument(@Param("r") KnowledgeRecords.Document record, @Param("expectedVersion") int expectedVersion);

    @Select("SELECT " + DOCUMENT + " FROM business.knowledge_document WHERE status IN ('UPLOADING','PARSING')"
            + " AND updated_at < #{before} ORDER BY updated_at,id LIMIT 20")
    List<KnowledgeRecords.Document> staleUploads(@Param("before") OffsetDateTime before);

    @Select("SELECT object_key FROM business.knowledge_document_version WHERE document_id=#{id} AND user_id=#{userId}"
            + " AND project_id=#{projectId} AND object_key IS NOT NULL ORDER BY version")
    List<String> documentObjectKeys(@Param("userId") long userId, @Param("projectId") long projectId, @Param("id") long id);

    @Update("UPDATE business.knowledge_document_version SET paragraphs='[]'::jsonb,object_key=NULL,source_url=NULL"
            + " WHERE document_id=#{id} AND user_id=#{userId} AND project_id=#{projectId}")
    int redactDocumentVersions(@Param("userId") long userId, @Param("projectId") long projectId, @Param("id") long id);

    @Select("""
            SELECT count(*) FROM business.agent_memory_preference WHERE user_id=#{userId} AND deleted_at IS NULL
                AND project_id IS NOT DISTINCT FROM #{projectId}
            """)
    long preferenceCount(@Param("userId") long userId, @Param("projectId") Long projectId);

    @Select("SELECT " + PREFERENCE + " FROM business.agent_memory_preference WHERE user_id=#{userId} AND deleted_at IS NULL"
            + " AND project_id IS NOT DISTINCT FROM #{projectId} ORDER BY updated_at DESC,id DESC")
    List<KnowledgeRecords.Preference> preferences(@Param("userId") long userId, @Param("projectId") Long projectId);

    @Select("SELECT " + PREFERENCE + " FROM business.agent_memory_preference WHERE user_id=#{userId} AND id=#{id}"
            + " AND deleted_at IS NULL FOR UPDATE")
    Optional<KnowledgeRecords.Preference> lockPreference(@Param("userId") long userId, @Param("id") long id);

    @Select("SELECT " + PREFERENCE + " FROM business.agent_memory_preference WHERE user_id=#{userId}"
            + " AND project_id IS NOT DISTINCT FROM #{projectId} AND content_hash=#{contentHash} AND deleted_at IS NULL")
    Optional<KnowledgeRecords.Preference> duplicatePreference(@Param("userId") long userId,
            @Param("projectId") Long projectId, @Param("contentHash") String contentHash);

    @Select("SELECT " + PREFERENCE + " FROM business.agent_memory_preference WHERE user_id=#{userId}"
            + " AND source_message_id=#{messageId} AND content_hash=#{contentHash}")
    Optional<KnowledgeRecords.Preference> preferenceFromMessage(@Param("userId") long userId,
            @Param("messageId") long messageId, @Param("contentHash") String contentHash);

    @Insert("""
            INSERT INTO business.agent_memory_preference(id,user_id,project_id,content,content_hash,source_type,
                source_message_id,version,created_at,updated_at) VALUES(#{r.id},#{r.userId},#{r.projectId},#{r.content},
                #{r.contentHash},#{r.sourceType},#{r.sourceMessageId},#{r.version},#{r.createdAt},#{r.updatedAt})
            """)
    int insertPreference(@Param("r") KnowledgeRecords.Preference record);

    @Update("""
            UPDATE business.agent_memory_preference SET content=#{content},content_hash=#{hash},source_type='USER_INPUT',
                source_message_id=NULL,version=version+1,updated_at=#{now}
            WHERE id=#{id} AND user_id=#{userId} AND version=#{version} AND deleted_at IS NULL
            """)
    int updatePreference(@Param("userId") long userId, @Param("id") long id, @Param("version") int version,
            @Param("content") String content, @Param("hash") String hash, @Param("now") OffsetDateTime now);

    @Update("UPDATE business.agent_memory_preference SET content='已删除',deleted_at=#{now},updated_at=#{now},version=version+1"
            + " WHERE id=#{id} AND user_id=#{userId} AND version=#{version} AND deleted_at IS NULL")
    int deletePreference(@Param("userId") long userId, @Param("id") long id,
            @Param("version") int version, @Param("now") OffsetDateTime now);

    @Select("SELECT content->>'text' FROM business.conversation_message WHERE id=#{messageId} AND user_id=#{userId}"
            + " AND role='USER' AND status='COMPLETED'")
    Optional<String> originalUserText(@Param("userId") long userId, @Param("messageId") long messageId);

    @Select("""
            SELECT
              (SELECT count(*) FROM business.article WHERE user_id=#{userId} AND space_id=#{projectId}
                  AND length(btrim(body_text))>0) AS writtenArticleCount,
              (SELECT count(DISTINCT article_id) FROM business.media_submission WHERE user_id=#{userId}
                  AND space_id=#{projectId} AND status='PUBLISHED') AS publishedArticleCount,
              (SELECT count(*) FROM business.media_submission WHERE user_id=#{userId}
                  AND space_id=#{projectId} AND status='PUBLISHED') AS publishedPlacementCount
            """)
    KnowledgeRecords.Counts businessHistory(@Param("userId") long userId, @Param("projectId") long projectId);
}
