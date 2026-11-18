package com.guanghe.backend.catalog.competitor;

import com.guanghe.backend.auth.account.AccountRole;
import com.guanghe.backend.auth.account.AccountStatus;
import com.guanghe.backend.auth.security.AuthenticatedAccount;
import com.guanghe.backend.catalog.AliasValue;
import com.guanghe.backend.catalog.CatalogNameNormalizer;
import com.guanghe.backend.catalog.space.BusinessSpaceRecord;
import com.guanghe.backend.catalog.space.BusinessSpaceService;
import com.guanghe.backend.common.api.BusinessException;
import com.guanghe.backend.common.api.ErrorCode;
import com.guanghe.backend.common.id.SnowflakeIdGenerator;
import com.guanghe.backend.common.id.SnowflakeProperties;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CompetitorServiceTests {

    @Mock
    private CompetitorMapper mapper;
    @Mock
    private BusinessSpaceService spaceService;
    private CompetitorService service;

    @BeforeEach
    void setUp() {
        service = new CompetitorService(
                mapper, spaceService,
                new SnowflakeIdGenerator(new SnowflakeProperties(3, 1)),
                new CatalogNameNormalizer()
        );
    }

    @Test
    void userCreatesReadsUpdatesAndDeletesCompetitor() {
        when(spaceService.requireProjectSpace(101, 21)).thenReturn(space(21, 101));
        when(mapper.insert(any())).thenReturn(1);
        CompetitorDtos.CreateRequest request = new CompetitorDtos.CreateRequest(
                "  对手 A  ", CompetitorType.DIRECT, " 主要竞品 ", null
        );

        CompetitorDtos.Response created = service.create(user(101), 21, request);
        ArgumentCaptor<CompetitorRecord> captured = ArgumentCaptor.forClass(CompetitorRecord.class);
        verify(mapper).insert(captured.capture());
        assertThat(captured.getValue().userId()).isEqualTo(101);
        assertThat(created.status()).isEqualTo(CompetitorStatus.ACTIVE);
        assertThat(created.id()).containsOnlyDigits();

        CompetitorRecord current = competitor(41, 101, 21, 2);
        when(mapper.findByIdAndUserId(41, 101)).thenReturn(Optional.of(current));
        when(mapper.findAliases(41, 101, 21)).thenReturn(List.of());
        assertThat(service.get(user(101), 41).spaceId()).isEqualTo("21");

        CompetitorDtos.UpdateRequest update = new CompetitorDtos.UpdateRequest();
        update.setVersion(2);
        update.setStatus(CompetitorStatus.INACTIVE);
        when(mapper.update(any(), org.mockito.ArgumentMatchers.eq(2))).thenReturn(1);
        assertThat(service.update(user(101), 41, update).status()).isEqualTo(CompetitorStatus.INACTIVE);

        when(mapper.delete(41, 101)).thenReturn(1);
        service.delete(user(101), 41);
        verify(mapper).delete(41, 101);
    }

    @Test
    void aliasesReplaceAsNormalizedSetAndIncrementVersion() {
        CompetitorRecord current = competitor(41, 101, 21, 2);
        when(mapper.findByIdAndUserId(41, 101)).thenReturn(Optional.of(current));
        when(mapper.incrementVersion(
                org.mockito.ArgumentMatchers.eq(41L), org.mockito.ArgumentMatchers.eq(101L),
                org.mockito.ArgumentMatchers.eq(21L), org.mockito.ArgumentMatchers.eq(2), any()
        )).thenReturn(1);

        CompetitorDtos.Response response = service.replaceAliases(
                user(101), 41, new CompetitorDtos.ReplaceAliasesRequest(2, List.of(" 对手乙 ", "alpha"))
        );

        assertThat(response.aliases()).containsExactly("alpha", "对手乙");
        assertThat(response.version()).isEqualTo(3);
        verify(mapper).deleteAliases(41, 101, 21);
        verify(mapper, org.mockito.Mockito.times(2))
                .insertAlias(anyLong(), org.mockito.ArgumentMatchers.eq(101L),
                        org.mockito.ArgumentMatchers.eq(21L), org.mockito.ArgumentMatchers.eq(41L),
                        any(AliasValue.class), any());
    }

    @Test
    void duplicateNameAliasAndStaleVersionReturnExpectedConflicts() {
        when(spaceService.requireProjectSpace(101, 21)).thenReturn(space(21, 101));
        when(mapper.nameExists(101, 21, "competitor", 0)).thenReturn(true);
        assertCode(() -> service.create(user(101), 21, new CompetitorDtos.CreateRequest(
                " COMPETITOR ", CompetitorType.INDIRECT, null, CompetitorStatus.ACTIVE
        )), ErrorCode.RESOURCE_NAME_CONFLICT);

        CompetitorRecord current = competitor(41, 101, 21, 2);
        when(mapper.findByIdAndUserId(41, 101)).thenReturn(Optional.of(current));
        CompetitorDtos.UpdateRequest stale = new CompetitorDtos.UpdateRequest();
        stale.setVersion(1);
        stale.setName("new");
        assertCode(() -> service.update(user(101), 41, stale), ErrorCode.RESOURCE_VERSION_CONFLICT);

        when(mapper.aliasExists(101, 21, "taken", 41)).thenReturn(true);
        assertCode(() -> service.replaceAliases(
                user(101), 41, new CompetitorDtos.ReplaceAliasesRequest(2, List.of("TAKEN"))
        ), ErrorCode.RESOURCE_ALIAS_CONFLICT);
    }

    @Test
    void competitorCannotUseAnotherUsersSpaceOrResource() {
        doThrow(new BusinessException(ErrorCode.RESOURCE_NOT_FOUND))
                .when(spaceService).requireProjectSpace(101, 99);
        assertCode(() -> service.create(user(101), 99, new CompetitorDtos.CreateRequest(
                "competitor", CompetitorType.SUBSTITUTE, null, null
        )), ErrorCode.RESOURCE_NOT_FOUND);

        when(mapper.findByIdAndUserId(41, 101)).thenReturn(Optional.empty());
        CompetitorDtos.UpdateRequest update = new CompetitorDtos.UpdateRequest();
        update.setVersion(1);
        update.setName("new");
        assertCode(() -> service.get(user(101), 41), ErrorCode.RESOURCE_NOT_FOUND);
        assertCode(() -> service.update(user(101), 41, update), ErrorCode.RESOURCE_NOT_FOUND);
        assertCode(() -> service.delete(user(101), 41), ErrorCode.RESOURCE_NOT_FOUND);
        verify(mapper, never()).insert(any());
    }

    @Test
    void adminCannotInvokeCompetitorService() {
        AuthenticatedAccount admin = new AuthenticatedAccount(
                1, "admin", AccountRole.ADMIN, 1, AccountStatus.ACTIVE, null, null, null
        );
        assertCode(() -> service.list(admin, 21, 1, 20), ErrorCode.FORBIDDEN);
    }

    private CompetitorRecord competitor(long id, long userId, long spaceId, int version) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return new CompetitorRecord(
                id, userId, spaceId, "Competitor", "competitor", CompetitorType.DIRECT,
                null, CompetitorStatus.ACTIVE, version, now, now, 0
        );
    }

    private BusinessSpaceRecord space(long id, long userId) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return new BusinessSpaceRecord(id, userId, "Space", "space", "Brand", "{}",
                null, 1, now, now, 0, 0);
    }

    private AuthenticatedAccount user(long id) {
        return new AuthenticatedAccount(id, "user", AccountRole.USER, 1,
                AccountStatus.ACTIVE, null, null, null);
    }

    private void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, ErrorCode code) {
        assertThatThrownBy(action)
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> assertThat(((BusinessException) exception).errorCode()).isEqualTo(code));
    }
}
