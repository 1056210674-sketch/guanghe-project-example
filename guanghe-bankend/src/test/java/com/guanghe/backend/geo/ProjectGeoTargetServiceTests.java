package com.guanghe.backend.geo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.guanghe.backend.agentplatform.AgentPlatformMapper;
import com.guanghe.backend.agentplatform.AgentPlatformRecords;
import com.guanghe.backend.auth.account.AccountRole;
import com.guanghe.backend.auth.account.AccountStatus;
import com.guanghe.backend.auth.security.AuthenticatedAccount;
import com.guanghe.backend.catalog.AliasValue;
import com.guanghe.backend.catalog.CatalogNameNormalizer;
import com.guanghe.backend.catalog.space.BusinessSpaceMapper;
import com.guanghe.backend.catalog.space.BusinessSpaceRecord;
import com.guanghe.backend.common.api.BusinessException;
import com.guanghe.backend.common.api.ErrorCode;
import com.guanghe.backend.common.id.SnowflakeIdGenerator;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ProjectGeoTargetServiceTests {
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-10-07T00:00:00Z");
    private final AgentPlatformMapper projects = mock(AgentPlatformMapper.class);
    private final BusinessSpaceMapper spaces = mock(BusinessSpaceMapper.class);
    private final SnowflakeIdGenerator ids = mock(SnowflakeIdGenerator.class);
    private final ProjectGeoTargetService service = new ProjectGeoTargetService(projects, spaces,
            new CatalogNameNormalizer(), ids);

    @Test void missingIdentityIsReadableButNeverInferredFromProjectName() {
        arrange("ACTIVE", null, List.of());
        var result = service.get(user(41), 71);
        assertThat(result.projectId()).isEqualTo("71");
        assertThat(result.version()).isEqualTo(3);
        assertThat(result.name()).isNull();
        assertThat(result.configured()).isFalse();
        assertThat(result.aliases()).isEmpty();
        verify(spaces, never()).updateGeoTarget(anyLong(), anyLong(), anyInt(), anyString(), any());
    }

    @Test void explicitNameAndAliasesUpdateOnlyPublicIdentityWithOneProjectVersion() {
        arrange("ACTIVE", null, List.of());
        when(spaces.updateGeoTarget(eq(71L), eq(41L), eq(3), eq("小行星咖啡店"), any())).thenReturn(1);
        when(ids.nextId()).thenReturn(91L);
        when(spaces.insertAlias(eq(91L), eq(41L), eq(71L), any(), any())).thenReturn(1);
        var result = service.update(user(41), 71, request(3, " 小行星咖啡店 ", List.of("小行星")));
        assertThat(result.name()).isEqualTo("小行星咖啡店");
        assertThat(result.aliases()).containsExactly("小行星");
        assertThat(result.version()).isEqualTo(4);
        assertThat(result.configured()).isTrue();
        verify(spaces).deleteAliases(71, 41);
        verify(spaces).insertAlias(eq(91L), eq(41L), eq(71L), eq(new AliasValue("小行星", "小行星")), any());
        verify(spaces, never()).update(any(), anyInt());
        verify(projects, never()).updateProject(anyLong(), anyLong(), anyInt(), anyString(), anyString(), anyString(), any());
    }

    @Test void unchangedIdentityIsANoopAndExplicitEmptyAliasesCanReplaceOldAliases() {
        arrange("ACTIVE", "小行星咖啡店", List.of());
        assertThat(service.update(user(41), 71, request(3, "小行星咖啡店", List.of())).version()).isEqualTo(3);
        verify(spaces, never()).deleteAliases(anyLong(), anyLong());
        arrange("ACTIVE", "小行星咖啡店", List.of(new AliasValue("小行星", "小行星")));
        when(spaces.updateGeoTarget(eq(71L), eq(41L), eq(3), eq("小行星咖啡店"), any())).thenReturn(1);
        assertThat(service.update(user(41), 71, request(3, "小行星咖啡店", List.of())).aliases()).isEmpty();
        verify(spaces).deleteAliases(71, 41);
    }

    @Test void archivedProjectAllowsReadButRejectsAnyNewPublicIdentity() {
        arrange("ARCHIVED", "小行星咖啡店", List.of());
        assertThat(service.get(user(41), 71).name()).isEqualTo("小行星咖啡店");
        assertCode(() -> service.update(user(41), 71, request(3, "小行星咖啡店", List.of())), ErrorCode.RESOURCE_IN_USE);
        verify(spaces, never()).updateGeoTarget(anyLong(), anyLong(), anyInt(), anyString(), any());
    }

    @Test void staleVersionAndConcurrentUpdateCannotReplaceAliases() {
        arrange("ACTIVE", null, List.of());
        assertCode(() -> service.update(user(41), 71, request(2, "小行星咖啡店", List.of())), ErrorCode.RESOURCE_VERSION_CONFLICT);
        assertCode(() -> service.update(user(41), 71, request(3, "小行星咖啡店", List.of())), ErrorCode.RESOURCE_VERSION_CONFLICT);
        verify(spaces, never()).deleteAliases(anyLong(), anyLong());
    }

    @Test void crossUserReadsAndUpdatesReturnOwnedNotFoundWithoutTouchingAliases() {
        assertCode(() -> service.get(user(42), 71), ErrorCode.RESOURCE_NOT_FOUND);
        assertCode(() -> service.update(user(42), 71, request(3, "小行星咖啡店", List.of())), ErrorCode.RESOURCE_NOT_FOUND);
        verifyNoInteractions(spaces);
    }

    @Test void adminCannotReadOrWriteUserPrivateSettings() {
        var admin = new AuthenticatedAccount(1, "admin", AccountRole.ADMIN, 1, AccountStatus.ACTIVE, null, null, null);
        assertCode(() -> service.get(admin, 71), ErrorCode.FORBIDDEN);
        assertCode(() -> service.update(admin, 71, request(3, "小行星咖啡店", List.of())), ErrorCode.FORBIDDEN);
        verifyNoInteractions(projects, spaces);
    }

    @Test void genericEmptyTooLongAndNormalizedDuplicateNamesAreRejectedBeforeDatabaseWrites() {
        for (String name : List.of("咖啡", "品牌", "a", " ", "x".repeat(101)))
            assertCode(() -> service.update(user(41), 71, request(3, name, List.of())), ErrorCode.VALIDATION_FAILED);
        for (var aliases : List.of(List.of("Asteroid", "Ａsteroid"), List.of("小行星咖啡店"), List.of("咖啡"),
                List.of("a"), java.util.Collections.nCopies(51, "小行星")))
            assertCode(() -> service.update(user(41), 71, request(3, "小行星咖啡店", aliases)), ErrorCode.VALIDATION_FAILED);
        assertCode(() -> service.update(user(41), 71, request(3, null, List.of())), ErrorCode.VALIDATION_FAILED);
        assertCode(() -> service.update(user(41), 71, request(3, "小行星咖啡店", null)), ErrorCode.VALIDATION_FAILED);
        verifyNoInteractions(projects, spaces);
    }

    @Test void payloadCannotOverrideOwnerRoleBrandProfileOrProjectDisplayName() {
        var json = new ObjectMapper();
        for (String field : List.of("userId", "role", "brandProfile", "projectName", "projectId"))
            assertThatThrownBy(() -> json.readValue("{\"version\":3,\"name\":\"小行星咖啡店\",\"aliases\":[],\""
                    + field + "\":\"injected\"}", ProjectGeoTargetDtos.UpdateRequest.class)).isInstanceOf(Exception.class);
    }

    @Test void targetSqlPreservesExistingProfileAndCatalogOwnershipWithoutMigration() throws Exception {
        var method = BusinessSpaceMapper.class.getMethod("updateGeoTarget", long.class, long.class, int.class,
                String.class, OffsetDateTime.class);
        String sql = String.join(" ", method.getAnnotation(org.apache.ibatis.annotations.Update.class).value());
        assertThat(sql).contains("SET brand_name=#{name}", "version=version+1", "user_id=#{userId}",
                "version=#{version}", "project_status='ACTIVE'");
        assertThat(sql).doesNotContain("brand_profile=", "name_normalized=", "logo_asset_id=", "DELETE", "INSERT");
    }

    private void arrange(String status, String name, List<AliasValue> aliases) {
        var project = new AgentPlatformRecords.Project(71, 41, "私密项目名", "私密项目名", status, 3, NOW, NOW);
        when(projects.lockProject(41, 71)).thenReturn(Optional.of(project));
        when(projects.lockProjectForUpdate(41, 71)).thenReturn(Optional.of(project));
        when(spaces.findProjectByIdAndUserId(71, 41)).thenReturn(Optional.of(new BusinessSpaceRecord(71, 41,
                project.name(), project.nameNormalized(), name, "{\"description\":\"existing facts\"}", 81L,
                3, NOW, NOW, 2, 1)));
        when(spaces.findAliases(71, 41)).thenReturn(aliases);
    }
    private static ProjectGeoTargetDtos.UpdateRequest request(int version, String name, List<String> aliases) {
        return new ProjectGeoTargetDtos.UpdateRequest(version, name, aliases);
    }
    private static AuthenticatedAccount user(long id) {
        return new AuthenticatedAccount(id, "fixture", AccountRole.USER, 1, AccountStatus.ACTIVE, null, null, null);
    }
    private static void assertCode(Runnable action, ErrorCode code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.errorCode()).isEqualTo(code));
    }
}
