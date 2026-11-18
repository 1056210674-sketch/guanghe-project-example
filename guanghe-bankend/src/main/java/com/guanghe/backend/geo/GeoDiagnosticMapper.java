package com.guanghe.backend.geo;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.apache.ibatis.annotations.*;

@Mapper
public interface GeoDiagnosticMapper {
    record Row(long id,long userId,long spaceId,long conversationId,UUID requestId,int version,int step,
               String dataJson,String quoteJson,OffsetDateTime createdAt,OffsetDateTime updatedAt) { }
    record CardRow(long id,long diagnosticId,long userId,long conversationId,int step,long messageId,
                   String snapshotJson,OffsetDateTime createdAt) { }
    record Job(long id,long diagnosticId,long userId,long conversationId,UUID requestId,String phase,
               String inputHash,String inputJson,String status,String errorCode,UUID claimToken,
               OffsetDateTime leaseExpiresAt,OffsetDateTime createdAt,OffsetDateTime updatedAt,int requestVersion) { }
    String COLS="id,user_id AS userId,space_id AS spaceId,conversation_id AS conversationId,request_id AS requestId,"
        +"version,step,data::text AS dataJson,quote::text AS quoteJson,created_at AS createdAt,updated_at AS updatedAt";
    String JOB="id,diagnostic_id AS diagnosticId,user_id AS userId,conversation_id AS conversationId,request_id AS requestId,"
        +"phase,input_hash AS inputHash,input::text AS inputJson,status,error_code AS errorCode,claim_token AS claimToken,"
        +"lease_expires_at AS leaseExpiresAt,created_at AS createdAt,updated_at AS updatedAt,request_version AS requestVersion";
    @Select("SELECT value FROM business.agent_memory_entry WHERE user_id=#{user} AND project_id=#{project} "
        +"AND scope='PROJECT' AND kind='FACT' AND memory_key='geo.target_regions' AND deleted_at IS NULL LIMIT 1")
    Optional<String> projectRegion(@Param("user") long user,@Param("project") long project);
    @Select("SELECT CASE WHEN count(DISTINCT region)=1 THEN min(region) END FROM business.geo_topic "
        +"WHERE user_id=#{user} AND space_id=#{project} AND region IS NOT NULL AND length(btrim(region))>0")
    Optional<String> topicRegion(@Param("user") long user,@Param("project") long project);
    @Update("UPDATE business.conversation SET title=#{title},version=version+1,updated_at=#{now} "
        +"WHERE user_id=#{user} AND id=#{conversation} AND title IN ('新聊天','新会话')")
    int defaultTitle(@Param("user") long user,@Param("conversation") long conversation,@Param("title") String title,@Param("now") OffsetDateTime now);
    @Select("SELECT "+COLS+" FROM business.geo_diagnostic_preparation WHERE user_id=#{user} AND id=#{id}")
    Optional<Row> find(@Param("user") long user,@Param("id") long id);
    @Select("SELECT "+COLS+" FROM business.geo_diagnostic_preparation WHERE user_id=#{user} AND id=#{id} FOR UPDATE")
    Optional<Row> lock(@Param("user") long user,@Param("id") long id);
    @Select("SELECT "+COLS+" FROM business.geo_diagnostic_preparation WHERE user_id=#{user} AND request_id=#{request}")
    Optional<Row> request(@Param("user") long user,@Param("request") UUID request);
    @Insert("INSERT INTO business.geo_diagnostic_preparation(id,user_id,space_id,conversation_id,request_id,version,step,data,created_at,updated_at)"
        +" VALUES(#{r.id},#{r.userId},#{r.spaceId},#{r.conversationId},#{r.requestId},#{r.version},#{r.step},CAST(#{r.dataJson} AS jsonb),#{r.createdAt},#{r.updatedAt})")
    int insert(@Param("r") Row row);
    @Update("UPDATE business.geo_diagnostic_preparation SET data=CAST(#{data} AS jsonb),quote=CAST(#{quote} AS jsonb),step=#{step},version=version+1,updated_at=#{now}"
        +" WHERE user_id=#{user} AND id=#{id} AND version=#{version}")
    int save(@Param("user") long user,@Param("id") long id,@Param("version") int version,@Param("step") int step,
             @Param("data") String data,@Param("quote") String quote,@Param("now") OffsetDateTime now);
    @Select("<script>SELECT "+COLS+" FROM business.geo_diagnostic_preparation WHERE user_id=#{user}"
        +"<if test='conversation != null'> AND conversation_id=#{conversation}</if><if test='project != null'> AND space_id=#{project}</if>"
        +" ORDER BY created_at DESC,id DESC LIMIT #{size} OFFSET #{offset}</script>")
    List<Row> list(@Param("user") long user,@Param("conversation") Long conversation,@Param("project") Long project,
                   @Param("size") int size,@Param("offset") long offset);
    @Select("<script>SELECT count(*) FROM business.geo_diagnostic_preparation WHERE user_id=#{user}"
        +"<if test='conversation != null'> AND conversation_id=#{conversation}</if><if test='project != null'> AND space_id=#{project}</if></script>")
    long count(@Param("user") long user,@Param("conversation") Long conversation,@Param("project") Long project);
    @Select("<script>SELECT "+COLS+" FROM business.geo_diagnostic_preparation p WHERE user_id=#{user}"
        +" AND EXISTS(SELECT 1 FROM business.geo_diagnostic_delivery d WHERE d.diagnostic_id=p.id AND d.user_id=p.user_id)"
        +"<if test='conversation != null'> AND conversation_id=#{conversation}</if><if test='project != null'> AND space_id=#{project}</if>"
        +" ORDER BY created_at DESC,id DESC LIMIT #{size} OFFSET #{offset}</script>")
    List<Row> listSubmitted(@Param("user") long user,@Param("conversation") Long conversation,@Param("project") Long project,@Param("size") int size,@Param("offset") long offset);
    @Select("<script>SELECT count(*) FROM business.geo_diagnostic_preparation p WHERE user_id=#{user}"
        +" AND EXISTS(SELECT 1 FROM business.geo_diagnostic_delivery d WHERE d.diagnostic_id=p.id AND d.user_id=p.user_id)"
        +"<if test='conversation != null'> AND conversation_id=#{conversation}</if><if test='project != null'> AND space_id=#{project}</if></script>")
    long countSubmitted(@Param("user") long user,@Param("conversation") Long conversation,@Param("project") Long project);
    @Select("SELECT id,diagnostic_id AS diagnosticId,user_id AS userId,conversation_id AS conversationId,step,message_id AS messageId,"
        +"snapshot::text AS snapshotJson,created_at AS createdAt FROM business.geo_diagnostic_card WHERE diagnostic_id=#{id} ORDER BY id LIMIT 128")
    List<CardRow> cards(@Param("id") long id);
    @Insert("INSERT INTO business.geo_diagnostic_card(id,diagnostic_id,user_id,conversation_id,step,message_id,snapshot,created_at)"
        +" VALUES(#{r.id},#{r.diagnosticId},#{r.userId},#{r.conversationId},#{r.step},#{r.messageId},CAST(#{r.snapshotJson} AS jsonb),#{r.createdAt})")
    int card(@Param("r") CardRow row);
    @Select("SELECT "+JOB+" FROM business.geo_diagnostic_generation WHERE diagnostic_id=#{id} ORDER BY id DESC LIMIT 1")
    Optional<Job> latest(@Param("id") long id);
    @Select("SELECT "+JOB+" FROM business.geo_diagnostic_generation WHERE user_id=#{user} AND request_id=#{request}")
    Optional<Job> jobRequest(@Param("user") long user,@Param("request") UUID request);
    @Select("SELECT count(*) FROM business.geo_diagnostic_generation WHERE diagnostic_id=#{id}")
    long generationCount(@Param("id") long id);
    @Select("SELECT count(*) FROM business.geo_diagnostic_generation WHERE user_id=#{user} AND status IN ('QUEUED','RUNNING')")
    long active(@Param("user") long user);
    @Insert("INSERT INTO business.geo_diagnostic_generation(id,diagnostic_id,user_id,conversation_id,request_id,phase,input_hash,input,status,created_at,updated_at,request_version)"
        +" VALUES(#{r.id},#{r.diagnosticId},#{r.userId},#{r.conversationId},#{r.requestId},#{r.phase},#{r.inputHash},CAST(#{r.inputJson} AS jsonb),'QUEUED',#{r.createdAt},#{r.updatedAt},#{r.requestVersion})")
    int job(@Param("r") Job row);
    @Update("UPDATE business.geo_diagnostic_generation SET status='SUPERSEDED',claim_token=NULL,lease_expires_at=NULL,updated_at=#{now}"
        +" WHERE diagnostic_id=#{id} AND status IN ('QUEUED','RUNNING')")
    int supersede(@Param("id") long id,@Param("now") OffsetDateTime now);
    @Select("SELECT "+JOB+" FROM business.geo_diagnostic_generation WHERE status='QUEUED' ORDER BY created_at,id FOR UPDATE SKIP LOCKED LIMIT 1")
    Optional<Job> next();
    @Update("UPDATE business.geo_diagnostic_generation SET status='RUNNING',claim_token=#{token},lease_expires_at=#{expires},updated_at=#{now} WHERE id=#{id} AND status='QUEUED'")
    int claim(@Param("id") long id,@Param("token") UUID token,@Param("expires") OffsetDateTime expires,@Param("now") OffsetDateTime now);
    @Select("SELECT "+JOB+" FROM business.geo_diagnostic_generation WHERE id=#{id} FOR UPDATE")
    Optional<Job> lockJob(@Param("id") long id);
    @Update("UPDATE business.geo_diagnostic_generation SET status=#{status},error_code=#{error},usage=CAST(#{usage} AS jsonb),claim_token=NULL,lease_expires_at=NULL,updated_at=#{now}"
        +" WHERE id=#{id} AND status='RUNNING' AND claim_token=#{token} AND lease_expires_at>#{now}")
    int finish(@Param("id") long id,@Param("token") UUID token,@Param("status") String status,@Param("error") String error,
               @Param("usage") String usage,@Param("now") OffsetDateTime now);
    @Update("UPDATE business.geo_diagnostic_generation SET status='FAILED',error_code='PREPARATION_TIMEOUT',claim_token=NULL,lease_expires_at=NULL,updated_at=#{now}"
        +" WHERE status='RUNNING' AND lease_expires_at<=#{now}")
    int expire(@Param("now") OffsetDateTime now);
}
