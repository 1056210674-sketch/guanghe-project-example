package com.guanghe.backend.adminplatform;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.guanghe.backend.auth.account.AccountRole;
import com.guanghe.backend.auth.account.AccountStatus;
import com.guanghe.backend.auth.security.AuthenticatedAccount;
import com.guanghe.backend.billing.OperationalAuditService;
import com.guanghe.backend.common.api.BusinessException;
import com.guanghe.backend.common.api.ErrorCode;
import com.guanghe.backend.geo.GeoPlanService;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminPlatformServiceTests {
    static final OffsetDateTime NOW = OffsetDateTime.parse("2026-10-07T00:00:00Z");
    static final String HASH = "a".repeat(64);
    @Mock AdminPlatformMapper mapper;
    @Mock ObjectProvider<GeoPlanService> plansProvider;
    @Mock ObjectProvider<OperationalAuditService> auditProvider;
    @Mock GeoPlanService plans;
    @Mock OperationalAuditService audits;
    ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    AdminPlatformService service;

    @BeforeEach void setup() { service = new AdminPlatformService(mapper, plansProvider, auditProvider, json); }

    @Test void everyManagementOperationRequiresRealActiveAdministrator() {
        var user = account(AccountRole.USER, AccountStatus.ACTIVE);
        var disabled = account(AccountRole.ADMIN, AccountStatus.DISABLED);
        assertCode(() -> service.definitions(user), ErrorCode.FORBIDDEN);
        assertCode(() -> service.documents(user, null, null, 1, 20), ErrorCode.FORBIDDEN);
        assertCode(() -> service.preferences(disabled, null, null, 1, 20), ErrorCode.FORBIDDEN);
        assertCode(() -> service.pause(user, 91, new AdminPlatformDtos.PausePlanRequest(3, "人工维护")), ErrorCode.FORBIDDEN);
        assertCode(() -> service.resume(user, 91, new AdminPlatformDtos.ResumePlanRequest(3)), ErrorCode.FORBIDDEN);
        verifyNoInteractions(mapper, plansProvider, auditProvider, plans, audits);
    }

    @Test void incompleteDefinitionGuardRejectedBeforeReadOrWrite() {
        assertCode(() -> service.updateDefinition(admin(), "geo", "1.0.0",
                new AdminPlatformDtos.UpdateDefinitionRequest(null, "PUBLIC", HASH, "DISABLED", null)), ErrorCode.VALIDATION_FAILED);
        assertCode(() -> service.updateDefinition(admin(), "geo", "1.0.0",
                new AdminPlatformDtos.UpdateDefinitionRequest("PUBLISHED", "PUBLIC", HASH, null, null)), ErrorCode.VALIDATION_FAILED);
        verifyNoInteractions(mapper, auditProvider);
    }

    @Test void definitionGovernanceMutatesOnlyStateAndVisibilityAndAuditsFrozenHash() throws Exception {
        when(auditProvider.getIfAvailable()).thenReturn(audits);
        when(mapper.lockDefinition("geo", "1.0.0")).thenReturn(Optional.of(definition("geo", "geo", "1.0.0", "PUBLISHED")));
        when(mapper.updateDefinition("geo", "1.0.0", HASH, "PUBLISHED", "PUBLIC", "DISABLED", "INTERNAL")).thenReturn(1);
        var result = service.updateDefinition(admin(), "geo", "1.0.0",
                new AdminPlatformDtos.UpdateDefinitionRequest("PUBLISHED", "PUBLIC", HASH, "DISABLED", "INTERNAL"));
        assertThat(result.definitionHash()).isEqualTo(HASH);
        assertThat(result.version()).isEqualTo("1.0.0");
        assertThat(result.status()).isEqualTo("DISABLED");
        assertThat(result.visibility()).isEqualTo("INTERNAL");
        var details = ArgumentCaptor.forClass(String.class);
        verify(audits).actorUser(eq("ADMIN"), eq(81L), eq("AGENT_DEFINITION"), isNull(),
                eq("AGENT_DEFINITION_GOVERNANCE_CHANGED"), eq("SUCCESS"), details.capture());
        assertThat(json.readTree(details.getValue()).path("definitionHash").textValue()).isEqualTo(HASH);
        assertThat(details.getValue()).doesNotContain("capabilities", "description", "token", "sourceUrl");
    }

    @Test void staleDefinitionSnapshotCannotChangeCatalog() {
        when(auditProvider.getIfAvailable()).thenReturn(audits);
        when(mapper.lockDefinition("geo", "1.0.0")).thenReturn(Optional.of(definition("geo", "geo", "1.0.0", "DISABLED")));
        assertCode(() -> service.updateDefinition(admin(), "geo", "1.0.0",
                new AdminPlatformDtos.UpdateDefinitionRequest("PUBLISHED", "PUBLIC", HASH, "WITHDRAWN", null)), ErrorCode.TASK_STATE_CONFLICT);
        verify(mapper, never()).updateDefinition(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
        verifyNoInteractions(audits);
    }

    @Test void unknownOrMismatchedStaticAdaptersCannotBePublished() {
        when(auditProvider.getIfAvailable()).thenReturn(audits);
        when(mapper.lockDefinition("external", "1.0.0")).thenReturn(Optional.of(definition("external", "external", "1.0.0", "DISABLED")));
        assertCode(() -> service.updateDefinition(admin(), "external", "1.0.0",
                new AdminPlatformDtos.UpdateDefinitionRequest("DISABLED", "PUBLIC", HASH, "PUBLISHED", null)), ErrorCode.FORBIDDEN);
        when(mapper.lockDefinition("geo", "2.0.0")).thenReturn(Optional.of(definition("geo", "geo", "2.0.0", "DISABLED")));
        assertCode(() -> service.updateDefinition(admin(), "geo", "2.0.0",
                new AdminPlatformDtos.UpdateDefinitionRequest("DISABLED", "PUBLIC", HASH, "PUBLISHED", null)), ErrorCode.FORBIDDEN);
        verifyNoInteractions(audits);
    }

    @Test void absentAuditCapabilityPreventsAnyBusinessMutation() {
        assertCode(() -> service.updateDefinition(admin(), "geo", "1.0.0",
                new AdminPlatformDtos.UpdateDefinitionRequest("PUBLISHED", "PUBLIC", HASH, "DISABLED", null)), ErrorCode.PROVIDER_NOT_CONFIGURED);
        assertCode(() -> service.pause(admin(), 91, new AdminPlatformDtos.PausePlanRequest(3, "维护")), ErrorCode.PROVIDER_NOT_CONFIGURED);
        verifyNoInteractions(mapper, plansProvider, plans);
    }

    @Test void planPauseUsesExactOwnerAwareDedicatedSeamAndRecordsNoTaskCancellation() throws Exception {
        when(auditProvider.getIfAvailable()).thenReturn(audits);
        when(plansProvider.getIfAvailable()).thenReturn(plans);
        when(mapper.plan(91)).thenReturn(Optional.of(plan("ACTIVE", 3)), Optional.of(plan("PAUSED", 4)));
        var result = service.pause(admin(), 91, new AdminPlatformDtos.PausePlanRequest(3, " 平台维护 "));
        verify(plans).adminPause(81, 91, 3, "平台维护");
        assertThat(result.status()).isEqualTo("PAUSED");
        assertThat(result.userId()).isEqualTo("41");
        var details = ArgumentCaptor.forClass(String.class);
        verify(audits).actorUser(eq("ADMIN"), eq(81L), eq("GEO_PLAN"), eq(91L), eq("GEO_PLAN_ADMIN_PAUSED"), eq("SUCCESS"), details.capture());
        assertThat(json.readTree(details.getValue()).path("inFlightTasksCancelled").asBoolean()).isFalse();
        assertThat(json.readTree(details.getValue()).path("reason").textValue()).isEqualTo("平台维护");
    }

    @Test void invalidPauseReasonNeverReachesTaskOrAuditCode() {
        assertCode(() -> service.pause(admin(), 91, new AdminPlatformDtos.PausePlanRequest(3, "秘密\n换行")), ErrorCode.VALIDATION_FAILED);
        assertCode(() -> service.pause(admin(), 91, new AdminPlatformDtos.PausePlanRequest(3, "x".repeat(201))), ErrorCode.VALIDATION_FAILED);
        verifyNoInteractions(mapper, plansProvider, auditProvider);
    }

    @Test void resumeCannotBypassOriginalAuthorizationFailureAndDoesNotRecordSuccess() {
        when(auditProvider.getIfAvailable()).thenReturn(audits);
        when(plansProvider.getIfAvailable()).thenReturn(plans);
        when(mapper.plan(91)).thenReturn(Optional.of(plan("PAUSED", 3)));
        when(plans.adminResume(81, 91, 3)).thenThrow(new BusinessException(ErrorCode.TASK_CONFIRMATION_MISMATCH));
        assertCode(() -> service.resume(admin(), 91, new AdminPlatformDtos.ResumePlanRequest(3)), ErrorCode.TASK_CONFIRMATION_MISMATCH);
        verifyNoInteractions(audits);
    }

    @Test void resumeRecordsOriginalOwnerWithoutBackfillingPausedSlots() throws Exception {
        when(auditProvider.getIfAvailable()).thenReturn(audits);
        when(plansProvider.getIfAvailable()).thenReturn(plans);
        when(mapper.plan(91)).thenReturn(Optional.of(plan("PAUSED", 3)), Optional.of(plan("ACTIVE", 4)));
        var result = service.resume(admin(), 91, new AdminPlatformDtos.ResumePlanRequest(3));
        verify(plans).adminResume(81, 91, 3);
        assertThat(result.version()).isEqualTo(4);
        var details = ArgumentCaptor.forClass(String.class);
        verify(audits).actorUser(eq("ADMIN"), eq(81L), eq("GEO_PLAN"), eq(91L), eq("GEO_PLAN_ADMIN_RESUMED"), eq("SUCCESS"), details.capture());
        assertThat(json.readTree(details.getValue()).path("ownerUserId").textValue()).isEqualTo("41");
        assertThat(json.readTree(details.getValue()).path("pausedSlotsBackfilled").asBoolean()).isFalse();
    }

    @Test void metadataDtosDoNotHavePrivateContentFields() {
        var forbidden = List.of("name", "content", "text", "paragraphs", "sourceUrl", "objectKey", "sourceMessageId", "inputSnapshot", "prompt");
        for (var type : List.of(AdminPlatformDtos.KnowledgeDocumentMetadata.class, AdminPlatformDtos.MemoryPreferenceMetadata.class,
                AdminPlatformDtos.PlanView.class, AdminPlatformDtos.PlanSlotView.class)) {
            assertThat(List.of(type.getRecordComponents()).stream().map(component -> component.getName()).toList()).doesNotContainAnyElementsOf(forbidden);
        }
    }

    @Test void metadataReadsUseBoundedFiltersAndNoKnowledgeServiceContentAccess() {
        when(mapper.documents(41L, 51L, 20, 20)).thenReturn(List.of());
        when(mapper.documentCount(41L, 51L)).thenReturn(23L);
        var result = service.documents(admin(), 41L, 51L, 2, 20);
        assertThat(result.total()).isEqualTo(23);
        assertThat(result.items()).isEmpty();
        assertCode(() -> service.documents(admin(), -1L, null, 1, 20), ErrorCode.VALIDATION_FAILED);
        assertCode(() -> service.preferences(admin(), null, null, 1, 101), ErrorCode.VALIDATION_FAILED);
        assertCode(() -> service.plans(admin(), null, null, "WHATEVER", 1, 20), ErrorCode.VALIDATION_FAILED);
        verifyNoInteractions(auditProvider, plansProvider, audits, plans);
    }

    @Test void immutableDefinitionUnknownRequestFieldsAreRejectedByDeserializer() {
        assertThatThrownBy(() -> json.readValue("{\"expectedStatus\":\"PUBLISHED\",\"expectedVisibility\":\"PUBLIC\",\"definitionHash\":\""
                + HASH + "\",\"status\":\"DISABLED\",\"capabilities\":[\"publish\"]}", AdminPlatformDtos.UpdateDefinitionRequest.class))
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
    }

    private AdminPlatformDtos.AgentDefinitionView definition(String key, String adapter, String version, String status) {
        return new AdminPlatformDtos.AgentDefinitionView(key, version, "智能体", adapter, "PUBLIC", status, HASH, NOW, 2);
    }
    private AdminPlatformDtos.PlanView plan(String status, int version) {
        return new AdminPlatformDtos.PlanView("91", "41", "51", "71", status, "HOURLY", 6, 10, "1",
                null, null, "PAUSED".equals(status) ? "ADMIN_PAUSED" : null, version, NOW, NOW);
    }
    private AuthenticatedAccount account(AccountRole role, AccountStatus status) {
        return new AuthenticatedAccount(81, "admin", role, 1, status, null, "admin", null);
    }
    private AuthenticatedAccount admin() { return account(AccountRole.ADMIN, AccountStatus.ACTIVE); }
    private void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, ErrorCode code) {
        assertThatThrownBy(action).isInstanceOfSatisfying(BusinessException.class, value -> assertThat(value.errorCode()).isEqualTo(code));
    }
}
