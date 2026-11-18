package com.guanghe.backend.agentplatform;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.guanghe.backend.auth.account.AccountRole;
import com.guanghe.backend.auth.account.AccountStatus;
import com.guanghe.backend.auth.security.AuthenticatedAccount;
import com.guanghe.backend.catalog.CatalogNameNormalizer;
import com.guanghe.backend.common.api.BusinessException;
import com.guanghe.backend.common.api.ErrorCode;
import com.guanghe.backend.common.id.SnowflakeIdGenerator;
import com.guanghe.backend.common.id.SnowflakeProperties;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AgentPlatformServiceTests {
    static final OffsetDateTime NOW = OffsetDateTime.parse("2026-10-06T00:00:00Z");
    @Mock AgentPlatformMapper mapper;
    AgentPlatformProperties properties;
    AgentPlatformService service;

    @BeforeEach void setup() {
        properties = new AgentPlatformProperties();
        service = new AgentPlatformService(mapper, properties,
                new SnowflakeIdGenerator(new SnowflakeProperties(1,1)), new CatalogNameNormalizer(), new ObjectMapper());
    }

    @Test void projectCanBeRenamedAndArchivedWithExactVersionAndSameIdentity() {
        var current = new AgentPlatformRecords.Project(71, 41, "小行星", "小行星", "ACTIVE", 3, NOW, NOW);
        when(mapper.lockProjectForUpdate(41, 71)).thenReturn(Optional.of(current));
        when(mapper.updateProject(eq(41L), eq(71L), eq(3), eq("小行星广州店"), eq("小行星广州店"), eq("ARCHIVED"), any())).thenReturn(1);
        var result = service.updateProject(user(41), 71, new AgentPlatformDtos.UpdateProjectRequest(3, "小行星广州店", "ARCHIVED"));
        assertThat(result.id()).isEqualTo("71");
        assertThat(result.version()).isEqualTo(4);
        assertThat(result.status()).isEqualTo("ARCHIVED");
        verify(mapper, never()).insertProject(any());
    }

    @Test void fourthRegisteredAgentWithDifferentVersionReusesExistingAdapterWithoutPlatformBranches() throws Exception {
        service.setRuntimeCatalog(AgentRuntimeCatalogTests.readOnlyCatalog());
        var definition=new AgentPlatformRecords.Definition("client-reader","2.1.0","Reader","",
                "[\"chat\",\"resources.read\",\"memory.read\"]","geo","PUBLIC","PUBLISHED");
        when(mapper.activeInstallation(41,"client-reader")).thenReturn(Optional.of(
                new AgentPlatformRecords.Installation(52,41,"client-reader","2.1.0",true,1,NOW,NOW,null)));
        when(mapper.definition("client-reader","2.1.0")).thenReturn(Optional.of(definition));
        assertThat(service.canUseInstalledAgent(41,"client-reader",null)).isTrue();
        when(mapper.lockConversation(41,61)).thenReturn(Optional.of(61L));
        when(mapper.shareInstallation(41,52)).thenReturn(Optional.of(
                new AgentPlatformRecords.Installation(52,41,"client-reader","2.1.0",true,1,NOW,NOW,null)));
        when(mapper.lockDefinition("client-reader","2.1.0")).thenReturn(Optional.of(definition));
        when(mapper.insertBinding(any())).thenReturn(1);
        assertThat(service.bindConversation(41,61,52L,null).capabilities()).containsExactly("chat","resources.read","memory.read");
        verify(mapper).insertBinding(argThat(value->value.agentKey().equals("client-reader")&&value.agentVersion().equals("2.1.0")));
    }
    @Test void projectMutationRejectsStaleVersionUnknownStatusAndCrossUserIdentity() {
        when(mapper.lockProjectForUpdate(41, 71)).thenReturn(Optional.of(new AgentPlatformRecords.Project(71, 41, "x", "x", "ACTIVE", 3, NOW, NOW)));
        assertCode(() -> service.updateProject(user(41), 71, new AgentPlatformDtos.UpdateProjectRequest(2, "y", null)), ErrorCode.RESOURCE_VERSION_CONFLICT);
        assertCode(() -> service.updateProject(user(41), 71, new AgentPlatformDtos.UpdateProjectRequest(3, null, "DELETE")), ErrorCode.VALIDATION_FAILED);
        assertCode(() -> service.updateProject(user(42), 71, new AgentPlatformDtos.UpdateProjectRequest(3, "y", null)), ErrorCode.RESOURCE_NOT_FOUND);
        verify(mapper, never()).updateProject(anyLong(), anyLong(), anyInt(), anyString(), anyString(), anyString(), any());
    }
    @Test void scheduledCheckNeverAutoInstallsAndRechecksDefinitionAndProject() {
        assertThat(service.canUseInstalledAgent(41, "geo", 71L)).isFalse();
        when(mapper.activeInstallation(41, "geo")).thenReturn(Optional.of(installation(true, null)));
        when(mapper.definition("geo", "1.0.0")).thenReturn(Optional.of(definition("geo", "PUBLISHED")));
        when(mapper.project(41, 71)).thenReturn(Optional.of(new AgentPlatformRecords.Project(71, 41, "x", "x", "ACTIVE", 1, NOW, NOW)));
        assertThat(service.canUseInstalledAgent(41, "geo", 71L)).isTrue();
        when(mapper.project(41, 71)).thenReturn(Optional.of(new AgentPlatformRecords.Project(71, 41, "x", "x", "ARCHIVED", 2, NOW, NOW)));
        assertThat(service.canUseInstalledAgent(41, "geo", 71L)).isFalse();
        when(mapper.definition("geo", "1.0.0")).thenReturn(Optional.of(definition("geo", "WITHDRAWN")));
        assertThat(service.canUseInstalledAgent(41, "geo", null)).isFalse();
        verify(mapper, never()).insertInstallation(any());
    }

    @Test void internalCatalogueRequiresBothFlagAndExplicitUserAllowlist() {
        when(mapper.definitions()).thenReturn(List.of(definition("geo","PUBLISHED"),definition("summary","PUBLISHED")));
        assertThat(service.catalogue(user(41))).extracting(AgentPlatformDtos.AgentDefinitionView::agentKey).containsExactly("geo");
        properties.setInternalAgentsEnabled(true);
        assertThat(service.catalogue(user(41))).hasSize(1);
        properties.setInternalAgentUserIds(Set.of(41L));
        assertThat(service.catalogue(user(41))).hasSize(2);
        assertThat(service.catalogue(user(42))).hasSize(1);
        properties.setInternalAgentsEnabled(false);
        assertThat(service.catalogue(user(41))).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PUBLISHED", "WITHDRAWN", "DISABLED"})
    void catalogueKeepsExactInstalledVersionStatusAndCapabilitiesAfterANewRelease(String installedStatus) {
        var latest = new AgentPlatformRecords.Definition("geo", "1.1.0", "新版本", "",
                "[\"chat\",\"resources.read\",\"article.generate\"]", "geo", "PUBLIC", "PUBLISHED");
        var old = definition("geo", installedStatus);
        when(mapper.definitions()).thenReturn(List.of(latest, old));
        when(mapper.installations(41)).thenReturn(List.of(installation(true, null)));

        var result = service.catalogue(user(41));
        assertThat(result).extracting(AgentPlatformDtos.AgentDefinitionView::version).containsExactly("1.1.0", "1.0.0");
        assertThat(result.getFirst().installedInstallationId()).isNull();
        assertThat(result.getFirst().status()).isEqualTo("PUBLISHED");
        var exact = result.stream().filter(item -> item.agentKey().equals("geo") && item.version().equals("1.0.0"))
                .findFirst().orElseThrow();
        assertThat(exact.installedInstallationId()).isEqualTo("51");
        assertThat(exact.status()).isEqualTo(installedStatus);
        // The registered 1.0.0 compatibility grant maps geo.read -> web.search;
        // the newer version's article/resources capabilities must not leak in.
        assertThat(exact.capabilities()).containsExactly("chat", "geo.read", "web.search");
        assertThat(exact.capabilities()).doesNotContain("resources.read", "article.generate");
        verify(mapper, never()).insertInstallation(any());
        verify(mapper, never()).updateInstallation(anyLong(), anyLong(), anyInt(), anyBoolean(), any());
    }

    @Test void catalogueWithoutAnInstallationDoesNotExposeTheVersionHistory() {
        var latest = new AgentPlatformRecords.Definition("geo", "1.1.0", "新版本", "",
                "[\"chat\",\"geo.read\"]", "geo", "PUBLIC", "PUBLISHED");
        when(mapper.definitions()).thenReturn(List.of(latest, definition("geo", "PUBLISHED")));
        when(mapper.installations(41)).thenReturn(List.of(installation(true, null)));
        assertThat(service.catalogue(user(41))).hasSize(2);
        assertThat(service.catalogue(user(42)))
                .singleElement().satisfies(item -> {
                    assertThat(item.version()).isEqualTo("1.1.0");
                    assertThat(item.installedInstallationId()).isNull();
                });
    }

    @Test void installedHistoricalInternalDefinitionStillRequiresVisibilityPermission() {
        properties.setInternalAgentsEnabled(true);
        properties.setInternalAgentUserIds(Set.of(41L));
        var latest = new AgentPlatformRecords.Definition("summary", "1.1.0", "新版本", "",
                "[\"chat\",\"text.summarize\"]", "summary", "PUBLIC", "PUBLISHED");
        when(mapper.definitions()).thenReturn(List.of(latest, definition("summary", "PUBLISHED")));
        when(mapper.installations(41)).thenReturn(List.of(new AgentPlatformRecords.Installation(
                52, 41, "summary", "1.0.0", true, 1, NOW, NOW, null)));
        assertThat(service.catalogue(user(41))).hasSize(2);
        properties.setInternalAgentsEnabled(false);
        assertThat(service.catalogue(user(41)))
                .singleElement().satisfies(item -> {
                    assertThat(item.version()).isEqualTo("1.1.0");
                    assertThat(item.installedInstallationId()).isNull();
                });
    }

    @Test void hiddenAgentCannotBeInstalledByGuessingItsKey() {
        properties.setInternalAgentsEnabled(true);
        when(mapper.definitions()).thenReturn(List.of(definition("summary","PUBLISHED")));
        assertCode(() -> service.addInstallation(user(41),new AgentPlatformDtos.AddInstallationRequest("summary")),ErrorCode.RESOURCE_NOT_FOUND);
        verify(mapper,never()).insertInstallation(any());
    }

    @Test void addingExistingDisabledInstallationNeverReenablesOrRewritesIt() {
        var installation = installation(false,null);
        when(mapper.activeInstallation(41,"geo")).thenReturn(Optional.of(installation));
        when(mapper.definition("geo","1.0.0")).thenReturn(Optional.of(definition("geo","PUBLISHED")));
        assertThat(service.addInstallation(user(41),new AgentPlatformDtos.AddInstallationRequest("geo")).enabled()).isFalse();
        verify(mapper,never()).insertInstallation(any());
        verify(mapper,never()).updateInstallation(anyLong(),anyLong(),anyInt(),anyBoolean(),any());
    }

    @Test void concurrentAddReturnsWinningInstallationWithoutDuplicate() {
        when(mapper.activeInstallation(41,"geo")).thenReturn(Optional.empty(),Optional.of(installation(true,null)));
        when(mapper.definitions()).thenReturn(List.of(definition("geo","PUBLISHED")));
        when(mapper.lockDefinition("geo","1.0.0")).thenReturn(Optional.of(definition("geo","PUBLISHED")));
        when(mapper.definition("geo","1.0.0")).thenReturn(Optional.of(definition("geo","PUBLISHED")));
        when(mapper.insertInstallation(any())).thenReturn(0);
        assertThat(service.addInstallation(user(41),new AgentPlatformDtos.AddInstallationRequest("geo")).id()).isEqualTo("51");
        verify(mapper).insertInstallation(any());
    }

    @Test void unsupportedVersionOrMismatchedAdapterFailsBeforeInstallation() {
        var base = definition("geo","PUBLISHED");
        for (var candidate : List.of(new AgentPlatformRecords.Definition("geo","2.0.0",base.name(),"",base.capabilitiesJson(),"geo","PUBLIC","PUBLISHED"),
                new AgentPlatformRecords.Definition("geo","1.0.0",base.name(),"",base.capabilitiesJson(),"summary","PUBLIC","PUBLISHED"))) {
            when(mapper.definitions()).thenReturn(List.of(candidate));
            when(mapper.lockDefinition(candidate.agentKey(),candidate.version())).thenReturn(Optional.of(candidate));
            assertCode(() -> service.addInstallation(user(41),new AgentPlatformDtos.AddInstallationRequest("geo")),ErrorCode.PROVIDER_NOT_CONFIGURED);
        }
        verify(mapper,never()).insertInstallation(any());
    }

    @Test void legacyIdentityIsPureReadAndNeverInfersSelectedSpace() {
        when(mapper.ownsConversation(41,61)).thenReturn(true);
        when(mapper.definition("geo","1.0.0")).thenReturn(Optional.of(definition("geo","DISABLED")));
        var identity=service.identityOf(41,61);
        assertThat(identity.installationId()).isNull();
        assertThat(identity.projectId()).isNull();
        assertThat(identity.agentKey()).isEqualTo("geo");
        verify(mapper,never()).insertInstallation(any());
        verify(mapper,never()).insertBinding(any());
        verify(mapper,never()).lockConversation(anyLong(),anyLong());
    }

    @Test void frozenHistoryDoesNotQueryInstallationOrDefinitionAfterRemoval() {
        when(mapper.ownsConversation(41,61)).thenReturn(true);
        when(mapper.binding(41,61)).thenReturn(Optional.of(binding(71L)));
        assertThat(service.identityOf(41,61).projectId()).isEqualTo("71");
        verify(mapper,never()).definition(anyString(),anyString());
        verify(mapper,never()).lockInstallation(anyLong(),anyLong());
    }

    @Test void explicitBindingCannotChangeInstallationOrProject() {
        when(mapper.lockConversation(41,61)).thenReturn(Optional.of(61L));
        when(mapper.binding(41,61)).thenReturn(Optional.of(binding(71L)));
        assertCode(() -> service.bindConversation(41,61,52L,71L),ErrorCode.RESOURCE_VERSION_CONFLICT);
        assertCode(() -> service.bindConversation(41,61,51L,72L),ErrorCode.RESOURCE_VERSION_CONFLICT);
        assertThat(service.bindConversation(41,61,null,null).projectId()).isEqualTo("71");
        verify(mapper,never()).insertBinding(any());
    }

    @Test void newBindingCapturesDefinitionWithNoImplicitProject() {
        when(mapper.lockConversation(41,61)).thenReturn(Optional.of(61L));
        when(mapper.shareInstallation(41,51)).thenReturn(Optional.of(installation(true,null)));
        when(mapper.lockDefinition("geo","1.0.0")).thenReturn(Optional.of(definition("geo","PUBLISHED")));
        when(mapper.insertBinding(any())).thenReturn(1);
        assertThat(service.bindConversation(41,61,51L,null).projectId()).isNull();
        verify(mapper).insertBinding(argThat(value -> value.userId()==41 && value.installationId()==51
                && value.projectId()==null && value.capabilitiesJson().equals("[\"chat\",\"geo.read\"]")));
        verify(mapper,never()).lockProject(anyLong(),anyLong());
    }

    @Test void foreignConversationOrInstallationIsNeverBound() {
        assertCode(() -> service.bindConversation(41,62,51L,null),ErrorCode.RESOURCE_NOT_FOUND);
        when(mapper.lockConversation(41,61)).thenReturn(Optional.of(61L));
        assertCode(() -> service.bindConversation(41,61,52L,null),ErrorCode.RESOURCE_NOT_FOUND);
        verify(mapper,never()).insertBinding(any());
    }

    @Test void removedDisabledOrSafetyDisabledIdentityCannotStartNewRun() {
        when(mapper.lockConversation(41,61)).thenReturn(Optional.of(61L));
        when(mapper.binding(41,61)).thenReturn(Optional.of(binding(null)));
        when(mapper.shareInstallation(41,51)).thenReturn(Optional.of(installation(false,NOW)),
                Optional.of(installation(false,null)),Optional.of(installation(true,null)));
        assertCode(() -> service.assertRunAllowed(41,61),ErrorCode.FORBIDDEN);
        assertCode(() -> service.assertRunAllowed(41,61),ErrorCode.FORBIDDEN);
        when(mapper.lockDefinition("geo","1.0.0")).thenReturn(Optional.of(definition("geo","DISABLED")));
        assertCode(() -> service.assertRunAllowed(41,61),ErrorCode.FORBIDDEN);
    }

    @Test void archivedOrForeignProjectCannotAuthorizeNewRun() {
        when(mapper.lockConversation(41,61)).thenReturn(Optional.of(61L));
        when(mapper.binding(41,61)).thenReturn(Optional.of(binding(71L)));
        when(mapper.shareInstallation(41,51)).thenReturn(Optional.of(installation(true,null)));
        when(mapper.lockDefinition("geo","1.0.0")).thenReturn(Optional.of(definition("geo","PUBLISHED")));
        assertCode(() -> service.assertRunAllowed(41,61),ErrorCode.RESOURCE_NOT_FOUND);
        when(mapper.lockProject(41,71)).thenReturn(Optional.of(new AgentPlatformRecords.Project(71,41,"客户","客户","ARCHIVED",1,NOW,NOW)));
        assertCode(() -> service.assertRunAllowed(41,61),ErrorCode.RESOURCE_IN_USE);
    }

    @Test void updateRequiresVersionAndDeleteIsIdempotentTombstone() {
        when(mapper.lockInstallation(41,51)).thenReturn(Optional.of(installation(true,null)));
        assertCode(() -> service.updateInstallation(user(41),51,new AgentPlatformDtos.UpdateInstallationRequest(2,false)),ErrorCode.RESOURCE_VERSION_CONFLICT);
        when(mapper.tombstoneInstallation(eq(51L),eq(41L),any())).thenReturn(1);
        service.removeInstallation(user(41),51);
        when(mapper.lockInstallation(41,51)).thenReturn(Optional.of(installation(false,NOW)));
        service.removeInstallation(user(41),51);
        verify(mapper,times(1)).tombstoneInstallation(eq(51L),eq(41L),any());
    }

    @Test void projectCreationNormalizesNameWithoutBrandAndEnforcesLength() {
        when(mapper.insertProject(any())).thenReturn(1);
        var project=service.createProject(user(41),new AgentPlatformDtos.CreateProjectRequest("  客户 A  "));
        assertThat(project.name()).isEqualTo("客户 A");
        assertThat(project.status()).isEqualTo("ACTIVE");
        verify(mapper).insertProject(argThat(value -> value.userId()==41 && value.nameNormalized().equals("客户 a")));
        assertCode(() -> service.createProject(user(41),new AgentPlatformDtos.CreateProjectRequest("x".repeat(101))),ErrorCode.VALIDATION_FAILED);
    }

    @Test void legacyConfirmationWithoutInstallationChecksDefinitionWithoutMigratingOrLockingConversation() {
        when(mapper.ownsConversation(41,61)).thenReturn(true);
        when(mapper.lockDefinition("geo","1.0.0")).thenReturn(Optional.of(definition("geo","PUBLISHED")));
        var identity=service.assertTaskConfirmationAllowed(41,61);
        assertThat(identity.installationId()).isNull(); assertThat(identity.projectId()).isNull();
        verify(mapper,never()).lockConversation(anyLong(),anyLong());
        verify(mapper,never()).insertInstallation(any()); verify(mapper,never()).insertBinding(any());
        when(mapper.lockDefinition("geo","1.0.0")).thenReturn(Optional.of(definition("geo","DISABLED")));
        assertCode(() -> service.assertTaskConfirmationAllowed(41,61),ErrorCode.FORBIDDEN);
    }

    @Test void legacyConfirmationHonorsExistingDisabledInstallationWithoutCreatingIdentity() {
        when(mapper.ownsConversation(41,61)).thenReturn(true);
        when(mapper.activeInstallation(41,"geo")).thenReturn(Optional.of(installation(false,null)));
        when(mapper.shareInstallation(41,51)).thenReturn(Optional.of(installation(false,null)));
        assertCode(() -> service.assertTaskConfirmationAllowed(41,61),ErrorCode.FORBIDDEN);
        verify(mapper,never()).lockConversation(anyLong(),anyLong());
        verify(mapper,never()).insertInstallation(any()); verify(mapper,never()).insertBinding(any());
    }

    @Test void boundConfirmationUsesFrozenIdentityAndRejectsDeletedOrMismatchedDefinition() {
        when(mapper.ownsConversation(41,61)).thenReturn(true);
        when(mapper.binding(41,61)).thenReturn(Optional.of(binding(null)));
        when(mapper.shareInstallation(41,51)).thenReturn(Optional.of(installation(false,NOW)));
        assertCode(() -> service.assertTaskConfirmationAllowed(41,61),ErrorCode.FORBIDDEN);
        when(mapper.shareInstallation(41,51)).thenReturn(Optional.of(installation(true,null)));
        when(mapper.lockDefinition("geo","1.0.0")).thenReturn(Optional.of(definition("geo","PUBLISHED")));
        assertThat(service.assertTaskConfirmationAllowed(41,61).installationId()).isEqualTo("51");
        when(mapper.binding(41,61)).thenReturn(Optional.of(new AgentPlatformRecords.Binding(61,41,51,
                "geo","1.0.0","changed",null,"[\"chat\",\"geo.read\"]",NOW)));
        assertCode(() -> service.assertTaskConfirmationAllowed(41,61),ErrorCode.PROVIDER_NOT_CONFIGURED);
        verify(mapper,never()).lockConversation(anyLong(),anyLong());
        verify(mapper,never()).insertBinding(any());
    }

    @Test void boundConfirmationChecksProjectWithoutIntroducingProjectOrConversationLocks() {
        when(mapper.ownsConversation(41,61)).thenReturn(true);
        when(mapper.binding(41,61)).thenReturn(Optional.of(binding(71L)));
        when(mapper.shareInstallation(41,51)).thenReturn(Optional.of(installation(true,null)));
        when(mapper.lockDefinition("geo","1.0.0")).thenReturn(Optional.of(definition("geo","PUBLISHED")));
        when(mapper.project(41,71)).thenReturn(Optional.of(new AgentPlatformRecords.Project(71,41,"client","client","ARCHIVED",1,NOW,NOW)));
        assertCode(() -> service.assertTaskConfirmationAllowed(41,61),ErrorCode.RESOURCE_IN_USE);
        verify(mapper,never()).lockProject(anyLong(),anyLong());
        verify(mapper,never()).lockConversation(anyLong(),anyLong());
    }

    @Test void contextReadUsesOnlyUnlockedReadsAndHonorsInstallationDisable() {
        when(mapper.ownsConversation(41,61)).thenReturn(true);
        when(mapper.binding(41,61)).thenReturn(Optional.of(binding(null)));
        when(mapper.installation(41,51)).thenReturn(Optional.of(installation(true,null)));
        when(mapper.definition("geo","1.0.0")).thenReturn(Optional.of(definition("geo","PUBLISHED")));
        assertThat(service.assertContextReadAllowed(41,61,false).installationId()).isEqualTo("51");
        when(mapper.installation(41,51)).thenReturn(Optional.of(installation(false,null)));
        assertCode(()->service.assertContextReadAllowed(41,61,false),ErrorCode.FORBIDDEN);
        assertCode(()->service.assertContextReadAllowed(41,61,true),ErrorCode.PROVIDER_NOT_CONFIGURED);
        verify(mapper,never()).shareInstallation(anyLong(),anyLong());
        verify(mapper,never()).lockDefinition(anyString(),anyString());
        verify(mapper,never()).lockConversation(anyLong(),anyLong());
    }

    static AgentPlatformRecords.Definition definition(String key,String status) {
        return new AgentPlatformRecords.Definition(key,"1.0.0",key+" agent","",key.equals("geo")?"[\"chat\",\"geo.read\"]":"[\"chat\",\"text.summarize\"]",key,key.equals("geo")?"PUBLIC":"INTERNAL",status);
    }
    static AgentPlatformRecords.Installation installation(boolean enabled,OffsetDateTime deleted) {
        return new AgentPlatformRecords.Installation(51,41,"geo","1.0.0",enabled,1,NOW,NOW,deleted);
    }
    static AgentPlatformRecords.Binding binding(Long project) {
        return new AgentPlatformRecords.Binding(61,41,51,"geo","1.0.0","geo agent",project,"[\"chat\",\"geo.read\"]",NOW);
    }
    static AuthenticatedAccount user(long id) { return new AuthenticatedAccount(id,"user",AccountRole.USER,1,AccountStatus.ACTIVE,null,null,null); }
    static void assertCode(Runnable work,ErrorCode code) {
        assertThatThrownBy(work::run).isInstanceOfSatisfying(BusinessException.class,e -> assertThat(e.errorCode()).isEqualTo(code));
    }
}
