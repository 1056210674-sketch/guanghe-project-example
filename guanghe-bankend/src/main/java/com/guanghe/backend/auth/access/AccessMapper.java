package com.guanghe.backend.auth.access;

import com.guanghe.backend.auth.account.AccountRecord;
import com.guanghe.backend.task.TaskRecords;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
interface AccessMapper {
    String ACCOUNT="id,username,username_normalized AS usernameNormalized,phone_e164 AS phoneE164,"
            +"password_hash AS passwordHash,password_algorithm AS passwordAlgorithm,token_version AS tokenVersion,"
            +"role,status,display_name AS displayName,email,credential_state AS credentialState,"
            +"created_at AS createdAt";
    @Insert("INSERT INTO business.account(id,username,username_normalized,phone_e164,password_hash,password_algorithm,"
            +"token_version,role,status,display_name,credential_state,created_at,updated_at) VALUES(#{a.id},#{a.username},"
            +"#{a.usernameNormalized},#{a.phoneE164},#{a.passwordHash},#{a.passwordAlgorithm},#{a.tokenVersion},"
            +"#{a.role},#{a.status},#{a.displayName},#{a.credentialState},#{now},#{now})")
    int insertAccount(@Param("a") AccountRecord account,@Param("now") OffsetDateTime now);
    @Select("SELECT "+ACCOUNT+" FROM business.account WHERE id=#{id} FOR UPDATE")
    Optional<AccountRecord> lockAccount(@Param("id") long id);
    @Select("SELECT "+ACCOUNT+" FROM business.account WHERE id=#{id}") Optional<AccountRecord> findAccount(@Param("id") long id);
    @Select("<script>SELECT "+ACCOUNT+" FROM business.account WHERE role='USER' <if test='search!=null'>AND "
            +"(username ILIKE CONCAT('%',#{search},'%') OR phone_e164 LIKE CONCAT('%',#{search},'%') OR display_name ILIKE CONCAT('%',#{search},'%'))</if> "
            +"ORDER BY created_at DESC,id DESC LIMIT #{size} OFFSET #{offset}</script>")
    List<AccountRecord> findUsers(@Param("search") String search,@Param("offset") long offset,@Param("size") int size);
    @Select("<script>SELECT count(*) FROM business.account WHERE role='USER' <if test='search!=null'>AND "
            +"(username ILIKE CONCAT('%',#{search},'%') OR phone_e164 LIKE CONCAT('%',#{search},'%') OR display_name ILIKE CONCAT('%',#{search},'%'))</if></script>")
    long countUsers(@Param("search") String search);
    @Update("UPDATE business.account SET password_hash=#{hash},password_algorithm='BCRYPT_V1',credential_state='MUST_CHANGE_PASSWORD',"
            +"token_version=token_version+1,updated_at=#{now} WHERE id=#{id} AND role='USER'")
    int resetPassword(@Param("id") long id,@Param("hash") String hash,@Param("now") OffsetDateTime now);
    @Update("UPDATE business.account SET status=#{status},token_version=token_version+1,updated_at=#{now} "
            +"WHERE id=#{id} AND role='USER' AND status<>#{status}")
    int setStatus(@Param("id") long id,@Param("status") String status,@Param("now") OffsetDateTime now);
    @Update("UPDATE business.account SET password_hash=#{hash},password_algorithm='BCRYPT_V1',credential_state='ACTIVE',"
            +"token_version=token_version+1,updated_at=#{now} WHERE id=#{id} AND role='USER' AND status='ACTIVE' "
            +"AND credential_state='MUST_CHANGE_PASSWORD' AND token_version=#{version}")
    int completeActivation(@Param("id") long id,@Param("version") int version,@Param("hash") String hash,@Param("now") OffsetDateTime now);
    @Update("UPDATE business.account_activation SET status='INVALIDATED' WHERE user_id=#{userId} AND status='AVAILABLE'")
    int invalidateActivations(@Param("userId") long userId);
    @Insert("INSERT INTO business.account_activation(id,user_id,token_hash,token_version,status,expires_at,created_at) "
            +"VALUES(#{a.id},#{a.userId},#{a.tokenHash},#{a.tokenVersion},'AVAILABLE',#{a.expiresAt},#{a.createdAt})")
    int insertActivation(@Param("a") AccessRecords.Activation activation);
    @Select("SELECT id,user_id AS userId,token_hash AS tokenHash,token_version AS tokenVersion,status,expires_at AS expiresAt,"
            +"consumed_at AS consumedAt,created_at AS createdAt FROM business.account_activation "
            +"WHERE token_hash=#{hash} FOR UPDATE")
    Optional<AccessRecords.Activation> lockActivation(@Param("hash") String hash);
    @Update("UPDATE business.account_activation SET status='CONSUMED',consumed_at=#{now} WHERE id=#{id} AND status='AVAILABLE'")
    int consumeActivation(@Param("id") long id,@Param("now") OffsetDateTime now);
    @Insert("INSERT INTO business.account_consent(id,user_id,consent_type,document_version,accepted_at,source_ip_hash,created_at) "
            +"VALUES(#{id},#{userId},#{type},#{version},#{now},#{ipHash},#{now}) ON CONFLICT DO NOTHING")
    int insertConsent(@Param("id") long id,@Param("userId") long userId,@Param("type") String type,
                      @Param("version") String version,@Param("ipHash") String ipHash,@Param("now") OffsetDateTime now);
    @Insert("INSERT INTO business.account_security_audit(id,actor_user_id,target_user_id,event_type,details,created_at) "
            +"VALUES(#{id},#{actor},#{target},#{event},CAST(#{details} AS jsonb),#{now})")
    int insertAudit(@Param("id") long id,@Param("actor") long actor,@Param("target") long target,
                    @Param("event") String event,@Param("details") String details,@Param("now") OffsetDateTime now);
    @Insert("INSERT INTO business.point_account(user_id,available_points,frozen_points,version,created_at,updated_at) "
            +"VALUES(#{userId},#{points},0,1,#{now},#{now})")
    int insertPointAccount(@Param("userId") long userId,@Param("points") int points,@Param("now") OffsetDateTime now);
    @Insert("INSERT INTO business.point_ledger(id,user_id,available_delta,frozen_delta,available_after,frozen_after,"
            +"entry_type,business_type,business_id,idempotency_key,operator_id,reason,created_at) VALUES(#{id},#{userId},"
            +"#{points},0,#{points},0,'ADMIN_INITIAL_GRANT','ACCOUNT_PROVISIONING',#{userId},#{key},#{actor},"
            +"'管理员开户初始积分',#{now})")
    int insertGrantLedger(@Param("id") long id,@Param("userId") long userId,@Param("points") int points,
                          @Param("key") String key,@Param("actor") long actor,@Param("now") OffsetDateTime now);
}
