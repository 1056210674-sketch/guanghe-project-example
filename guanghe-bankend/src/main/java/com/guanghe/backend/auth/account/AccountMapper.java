package com.guanghe.backend.auth.account;

import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface AccountMapper {

    String ACCOUNT_COLUMNS = """
            id, username, username_normalized AS usernameNormalized,
            phone_e164 AS phoneE164, password_hash AS passwordHash,
            password_algorithm AS passwordAlgorithm, token_version AS tokenVersion,
            role, status, display_name AS displayName, email, credential_state AS credentialState,
            created_at AS createdAt
            """;

    @Select("SELECT " + ACCOUNT_COLUMNS
            + " FROM business.account WHERE username_normalized = #{usernameNormalized}")
    Optional<AccountRecord> findByUsernameNormalized(@Param("usernameNormalized") String usernameNormalized);

    @Select("""
            SELECT id, username, phone_e164 AS phoneE164,
                   token_version AS tokenVersion, role, status,
                   display_name AS displayName, email, credential_state AS credentialState
              FROM business.account
             WHERE id = #{id}
            """)
    Optional<AccountIdentity> findIdentityById(@Param("id") long id);

    @Update("""
            UPDATE business.account
               SET token_version = token_version + 1,
                   updated_at = CURRENT_TIMESTAMP
             WHERE id = #{id}
               AND token_version = #{expectedTokenVersion}
               AND status = 'ACTIVE'
            """)
    int incrementTokenVersion(
            @Param("id") long id,
            @Param("expectedTokenVersion") int expectedTokenVersion
    );

    @Update("UPDATE business.account SET password_hash=#{hash},password_algorithm='BCRYPT_V1',updated_at=CURRENT_TIMESTAMP "
            + "WHERE id=#{id} AND password_algorithm='MD5_FIXED_SALT_V1'")
    int upgradeLegacyPassword(@Param("id") long id, @Param("hash") String hash);
}
