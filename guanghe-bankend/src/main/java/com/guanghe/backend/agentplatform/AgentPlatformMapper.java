package com.guanghe.backend.agentplatform;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface AgentPlatformMapper {
    @Select("SELECT id FROM business.business_space WHERE user_id=#{userId} AND storage_kind='PERSONAL_CREATIVE'")
    Optional<Long> personalCreativeStorage(@Param("userId") long userId);

    @Insert("""
            INSERT INTO business.business_space(id,user_id,name,name_normalized,brand_name,brand_profile,
                project_status,storage_kind,version,created_at,updated_at)
            VALUES(#{id},#{userId},'个人创作',#{normalized},NULL,'{}'::jsonb,'ACTIVE','PERSONAL_CREATIVE',1,#{now},#{now})
            ON CONFLICT(user_id) WHERE storage_kind='PERSONAL_CREATIVE' DO NOTHING
            """)
    int insertPersonalCreativeStorage(@Param("id") long id,@Param("userId") long userId,
            @Param("normalized") String normalized,@Param("now") OffsetDateTime now);

    String DEFINITION = "agent_key AS agentKey,version,name,description,capabilities::text AS capabilitiesJson,"
            + "adapter_key AS adapterKey,visibility,status";
    String INSTALLATION = "id,user_id AS userId,agent_key AS agentKey,agent_version AS agentVersion,"
            + "enabled,version,created_at AS createdAt,updated_at AS updatedAt,deleted_at AS deletedAt";
    String PROJECT = "id,user_id AS userId,name,name_normalized AS nameNormalized,project_status AS status,"
            + "version,created_at AS createdAt,updated_at AS updatedAt";
    String BINDING = "conversation_id AS conversationId,user_id AS userId,installation_id AS installationId,"
            + "agent_key AS agentKey,agent_version AS agentVersion,agent_name AS name,project_id AS projectId,"
            + "capabilities::text AS capabilitiesJson,created_at AS createdAt";

    @Select("SELECT " + DEFINITION + " FROM business.agent_definition_version ORDER BY agent_key,published_at DESC,version DESC")
    List<AgentPlatformRecords.Definition> definitions();

    @Select("SELECT " + DEFINITION + " FROM business.agent_definition_version"
            + " WHERE agent_key=#{key} AND version=#{version}")
    Optional<AgentPlatformRecords.Definition> definition(@Param("key") String key, @Param("version") String version);

    @Select("SELECT " + DEFINITION + " FROM business.agent_definition_version"
            + " WHERE agent_key=#{key} AND version=#{version} FOR SHARE")
    Optional<AgentPlatformRecords.Definition> lockDefinition(@Param("key") String key, @Param("version") String version);

    @Select("SELECT " + INSTALLATION + " FROM business.agent_installation"
            + " WHERE user_id=#{userId} AND deleted_at IS NULL ORDER BY created_at,id")
    List<AgentPlatformRecords.Installation> installations(@Param("userId") long userId);

    @Select("SELECT " + INSTALLATION + " FROM business.agent_installation"
            + " WHERE user_id=#{userId} AND agent_key=#{key} AND deleted_at IS NULL")
    Optional<AgentPlatformRecords.Installation> activeInstallation(@Param("userId") long userId, @Param("key") String key);

    @Select("SELECT " + INSTALLATION + " FROM business.agent_installation WHERE user_id=#{userId} AND id=#{id}")
    Optional<AgentPlatformRecords.Installation> installation(@Param("userId") long userId, @Param("id") long id);

    @Select("SELECT " + INSTALLATION + " FROM business.agent_installation WHERE user_id=#{userId} AND id=#{id} FOR UPDATE")
    Optional<AgentPlatformRecords.Installation> lockInstallation(@Param("userId") long userId, @Param("id") long id);

    @Select("SELECT " + INSTALLATION + " FROM business.agent_installation WHERE user_id=#{userId} AND id=#{id} FOR SHARE")
    Optional<AgentPlatformRecords.Installation> shareInstallation(@Param("userId") long userId, @Param("id") long id);

    @Insert("""
            INSERT INTO business.agent_installation(id,user_id,agent_key,agent_version,enabled,version,created_at,updated_at)
            VALUES(#{r.id},#{r.userId},#{r.agentKey},#{r.agentVersion},#{r.enabled},#{r.version},#{r.createdAt},#{r.updatedAt})
            ON CONFLICT (user_id,agent_key) WHERE deleted_at IS NULL DO NOTHING
            """)
    int insertInstallation(@Param("r") AgentPlatformRecords.Installation value);

    @Update("""
            UPDATE business.agent_installation SET enabled=#{enabled},version=version+1,updated_at=#{now}
            WHERE id=#{id} AND user_id=#{userId} AND version=#{version} AND deleted_at IS NULL
            """)
    int updateInstallation(@Param("id") long id, @Param("userId") long userId, @Param("version") int version,
                           @Param("enabled") boolean enabled, @Param("now") OffsetDateTime now);

    @Update("""
            UPDATE business.agent_installation SET enabled=false,deleted_at=#{now},updated_at=#{now},version=version+1
            WHERE id=#{id} AND user_id=#{userId} AND deleted_at IS NULL
            """)
    int tombstoneInstallation(@Param("id") long id, @Param("userId") long userId, @Param("now") OffsetDateTime now);

    @Select("SELECT " + PROJECT + " FROM business.business_space WHERE user_id=#{userId} AND storage_kind='PROJECT' ORDER BY created_at DESC,id DESC")
    List<AgentPlatformRecords.Project> projects(@Param("userId") long userId);

    @Select("SELECT " + PROJECT + " FROM business.business_space WHERE user_id=#{userId} AND id=#{id} AND storage_kind='PROJECT' FOR SHARE")
    Optional<AgentPlatformRecords.Project> lockProject(@Param("userId") long userId, @Param("id") long id);

    @Select("SELECT " + PROJECT + " FROM business.business_space WHERE user_id=#{userId} AND id=#{id} AND storage_kind='PROJECT'")
    Optional<AgentPlatformRecords.Project> project(@Param("userId") long userId, @Param("id") long id);

    @Select("SELECT " + PROJECT + " FROM business.business_space WHERE user_id=#{userId} AND id=#{id} AND storage_kind='PROJECT' FOR UPDATE")
    Optional<AgentPlatformRecords.Project> lockProjectForUpdate(@Param("userId") long userId, @Param("id") long id);

    @Update("""
            UPDATE business.business_space SET name=#{name},name_normalized=#{normalized},project_status=#{status},
                version=version+1,updated_at=#{now} WHERE id=#{id} AND user_id=#{userId} AND version=#{version}
            """)
    int updateProject(@Param("userId") long userId, @Param("id") long id, @Param("version") int version,
            @Param("name") String name, @Param("normalized") String normalized, @Param("status") String status,
            @Param("now") OffsetDateTime now);

    @Insert("""
            INSERT INTO business.business_space(id,user_id,name,name_normalized,brand_name,brand_profile,project_status,
                version,created_at,updated_at)
            VALUES(#{r.id},#{r.userId},#{r.name},#{r.nameNormalized},NULL,'{}'::jsonb,#{r.status},#{r.version},
                #{r.createdAt},#{r.updatedAt})
            """)
    int insertProject(@Param("r") AgentPlatformRecords.Project value);

    @Select("SELECT id FROM business.conversation WHERE id=#{conversationId} AND user_id=#{userId} FOR UPDATE")
    Optional<Long> lockConversation(@Param("userId") long userId, @Param("conversationId") long conversationId);

    @Select("SELECT EXISTS(SELECT 1 FROM business.conversation WHERE id=#{conversationId} AND user_id=#{userId})")
    boolean ownsConversation(@Param("userId") long userId, @Param("conversationId") long conversationId);

    @Select("SELECT " + BINDING + " FROM business.conversation_agent_binding"
            + " WHERE user_id=#{userId} AND conversation_id=#{conversationId}")
    Optional<AgentPlatformRecords.Binding> binding(@Param("userId") long userId, @Param("conversationId") long conversationId);

    @Insert("""
            INSERT INTO business.conversation_agent_binding(conversation_id,user_id,installation_id,agent_key,agent_version,
                agent_name,project_id,capabilities,created_at)
            VALUES(#{r.conversationId},#{r.userId},#{r.installationId},#{r.agentKey},#{r.agentVersion},#{r.name},
                #{r.projectId},CAST(#{r.capabilitiesJson} AS jsonb),#{r.createdAt})
            """)
    int insertBinding(@Param("r") AgentPlatformRecords.Binding value);
}
