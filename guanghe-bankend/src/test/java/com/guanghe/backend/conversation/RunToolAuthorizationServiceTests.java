package com.guanghe.backend.conversation;

import com.guanghe.backend.agentplatform.AgentPlatformDtos.AgentIdentity;
import com.guanghe.backend.agentplatform.AgentPlatformService;
import com.guanghe.backend.agentplatform.AgentRuntimeCatalog;
import com.guanghe.backend.auth.account.AccountIdentity;
import com.guanghe.backend.auth.account.AccountMapper;
import com.guanghe.backend.auth.account.AccountRole;
import com.guanghe.backend.auth.account.AccountStatus;
import com.guanghe.backend.common.api.BusinessException;
import com.guanghe.backend.common.api.ErrorCode;
import com.guanghe.backend.conversation.context.InternalRunContextDtos.RunContext;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Exercises the common public boundary with the real frozen/current catalogue policy. */
class RunToolAuthorizationServiceTests {
    private static final long USER = 41, CONVERSATION = 51, SOURCE = 61;
    private static final UUID RUN = UUID.fromString("1cc48fbb-478d-4ba4-8a0b-848637265a7e");
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-10-11T14:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW.toInstant(), ZoneOffset.UTC);
    private final ConversationMapper conversations = mock(ConversationMapper.class);
    private final AccountMapper accounts = mock(AccountMapper.class);
    private final AgentPlatformService platform = mock(AgentPlatformService.class);
    private final RunContext context = mock(RunContext.class);
    private final AgentRuntimeCatalog catalogue = new AgentRuntimeCatalog();
    private RunToolAuthorizationService service;
    private AgentIdentity frozen;

    @BeforeEach void setUp() {
        service = new RunToolAuthorizationService(conversations, accounts, Optional.of(platform), catalogue);
        var run = run("RUNNING", null, NOW.plusHours(1), NOW.plusMinutes(5));
        when(conversations.findRun(RUN)).thenReturn(Optional.of(run));
        when(conversations.lockAdmissionAccount(USER, 1)).thenReturn(USER);
        when(conversations.lockOwnedRun(RUN, USER, CONVERSATION)).thenReturn(Optional.of(run));
        when(accounts.findIdentityById(USER)).thenReturn(Optional.of(new AccountIdentity(USER, "test", null, 1,
                AccountRole.USER, AccountStatus.ACTIVE, "test", null)));
        frozen = catalogue.freeze(new AgentIdentity("71", "geo", "1.0.0", "GEO", "21",
                List.of("chat", "resources.read", "geo.read")));
        when(context.agentIdentity()).thenReturn(frozen);
        when(platform.assertTaskConfirmationAllowed(USER, CONVERSATION)).thenReturn(frozen);
    }

    @Test void currentAuthorityIsReadAfterAccountAndRunLocksWithoutRestoringRevokedGrants() {
        when(platform.assertTaskConfirmationAllowed(USER, CONVERSATION)).thenReturn(new AgentIdentity(
                "71", "geo", "1.0.0", "GEO", "21", List.of("chat", "resources.read")));
        var result = service.authorize(RUN, context, "context.read", CLOCK);
        assertThat(result.account().id()).isEqualTo(USER);
        assertThat(result.run().id()).isEqualTo(RUN);
        assertThat(result.agent().capabilities()).containsExactly("chat", "resources.read");
        assertThat(result.agent().executionRelease()).isEqualTo(frozen.executionRelease());
        var order = inOrder(accounts, conversations, platform);
        order.verify(conversations).findRun(RUN);
        order.verify(accounts).findIdentityById(USER);
        order.verify(conversations).lockAdmissionAccount(USER, 1);
        order.verify(conversations).lockOwnedRun(RUN, USER, CONVERSATION);
        order.verify(platform).assertTaskConfirmationAllowed(USER, CONVERSATION);
    }

    @ParameterizedTest
    @ValueSource(strings = {"STOP_REQUESTED", "COMPLETED", "FAILED", "STOPPED"})
    void lockResultOverridesAnEarlierActiveRun(String status) {
        when(conversations.lockOwnedRun(RUN, USER, CONVERSATION))
                .thenReturn(Optional.of(run(status, null, NOW.plusHours(1), NOW.plusMinutes(5))));
        assertCode(() -> service.authorize(RUN, context, "context.read", CLOCK), ErrorCode.RESOURCE_NOT_FOUND);
        verifyNoInteractions(platform);
    }

    @Test void stopFlagRejectsEvenWhenLockedStatusStillRunning() {
        when(conversations.lockOwnedRun(RUN, USER, CONVERSATION))
                .thenReturn(Optional.of(run("RUNNING", NOW, NOW.plusHours(1), NOW.plusMinutes(5))));
        assertCode(() -> service.authorize(RUN, context, "context.read", CLOCK), ErrorCode.RESOURCE_NOT_FOUND);
        verifyNoInteractions(platform);
    }

    @Test void timeIsReadAfterWaitingForTheRunLock() {
        Clock clock = mock(Clock.class);
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        when(clock.instant()).thenReturn(NOW.toInstant());
        when(conversations.lockOwnedRun(RUN, USER, CONVERSATION)).thenAnswer(invocation -> {
            when(clock.instant()).thenReturn(NOW.plusMinutes(5).toInstant());
            return Optional.of(run("RUNNING", null, NOW.plusHours(1), NOW.plusMinutes(5)));
        });
        assertCode(() -> service.authorize(RUN, context, "context.read", clock), ErrorCode.RESOURCE_NOT_FOUND);
        verifyNoInteractions(platform);
    }

    @Test void accountRevokedBetweenInitialReadAndLockNeverReachesRunOrInstallation() {
        when(conversations.lockAdmissionAccount(USER, 1)).thenReturn(null);
        assertCode(() -> service.authorize(RUN, context, "context.read", CLOCK), ErrorCode.RESOURCE_NOT_FOUND);
        verify(conversations, never()).lockOwnedRun(any(), anyLong(), anyLong());
        verifyNoInteractions(platform);
    }

    @Test void waitingForInstallationAuthorizationDoesNotExtendTheRunDeadline() {
        Clock clock = mock(Clock.class);
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        when(clock.instant()).thenReturn(NOW.toInstant());
        when(platform.assertTaskConfirmationAllowed(USER, CONVERSATION)).thenAnswer(invocation -> {
            when(clock.instant()).thenReturn(NOW.plusMinutes(5).toInstant());
            return frozen;
        });
        assertCode(() -> service.authorize(RUN, context, "context.read", clock), ErrorCode.RESOURCE_NOT_FOUND);
        verify(platform).assertTaskConfirmationAllowed(USER, CONVERSATION);
    }

    @Test void frozenReadOnlyAuthorityCannotUseNewCurrentWriteGrant() {
        when(context.agentIdentity()).thenReturn(new AgentIdentity("71", "geo", "1.0.0", "GEO", "21",
                List.of("chat", "resources.read"), frozen.executionRelease(), frozen.catalogHash()));
        assertCode(() -> service.authorize(RUN, context, "memory.upsert", CLOCK), ErrorCode.FORBIDDEN);
    }

    @Test void absentAgentPlatformFailsClosedWithoutDisablingLegacyResourceProjectionBean() {
        var disabled = new RunToolAuthorizationService(conversations, accounts, Optional.empty(), catalogue);
        assertCode(() -> disabled.authorize(RUN, context, "context.resources.read", CLOCK), ErrorCode.RESOURCE_NOT_FOUND);
    }

    private AgentRunRecord run(String status, OffsetDateTime stop, OffsetDateTime expires, OffsetDateTime deadline) {
        return new AgentRunRecord(RUN, CONVERSATION, USER, SOURCE, null, 21L, null, "[]", status, 1,
                "{}", "ref", expires, null, 0, "{}", null, null, stop, NOW, null, NOW, NOW, deadline);
    }

    private void assertCode(Runnable action, ErrorCode code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,
                failure -> assertThat(failure.errorCode()).isEqualTo(code));
    }
}
